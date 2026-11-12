// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.playback

import android.util.Log
import com.dsd.player.decoder.AudioSource
import com.dsd.player.decoder.ByteSource
import com.dsd.player.usb.NativeBridge
import com.dsd.player.usb.OutputMode
import com.dsd.player.usb.StreamConfig
import com.dsd.player.usb.UsbAudioOutput
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 生产者线程：从 ByteSource 取音频字节写入 native 环形缓冲。
 *  PCM      ：直通（ByteSource 已输出 24-in-32）。
 *  DSD native：直通交织 DSD 字节（每帧 2 字节 = L,R 各 1 字节）。
 *  DoP      ：见 packDoP()。
 *
 * 背压完全由环形缓冲提供（写不进就等），不在这里改时钟——
 * 播放中途重开流会让 alt setting 复位、必然出爆音，已移除该逻辑。
 */
class Producer(
    private val usb: UsbAudioOutput,
    private val onProgress: (pos: Long, total: Long) -> Unit,
    private val onEnded: () -> Unit,
    private val onUnderrunStorm: () -> Unit,
) {
    companion object {
        private const val TAG = "Producer"
        /** 必须与 native 的 kRingBytes 一致（app/src/main/cpp/native/usb_stream.cpp，现 8 MB） */
        const val RING_CAPACITY = 8 * 1024 * 1024
    }

    enum class DsdDataMode { NONE, NATIVE, DOP }

    @Volatile var paused = false
    @Volatile var dsdDataMode: DsdDataMode = DsdDataMode.NONE
    @Volatile var dsdLsbFirst = false

    private var thread: Thread? = null
    private var src: ByteSource? = null
    private var cfg: StreamConfig? = null
    private val running = AtomicBoolean(false)
    private var pos = 0L           // 源字节位置：PCM=24in32字节；DSD=DSD字节
    private var dopMarkerEven = true
    private var underrunSeen = 0

    // 每 10 秒报一次"供数速率 / underrun 增量 / 环形水位"（真机联调看是否供得动）
    private var statsAt = 0L
    private var statsPos = 0L
    private var statsUnderrun = 0L
    private var statsRingLow = Int.MAX_VALUE
    private var statsReadNs = 0L
    private var statsReads = 0
    private var srcBlockMs = 0L
    /** 环见底（<256 KB）的窗口计数：直接对应"会不会听到空洞" */
    private var statsRingEmpty = 0
    private var packBuf = ByteArray(128 * 1024)
    /** 源总字节数（进度用，-1 = 未知） */
    private var lengthBytes = -1L
    /** 本次流开始时"泵累计送出字节"的基准（位置 = posBase + 当前值 − 基准） */
    private var usbSentBase = 0L
    /** 本次流起点（seek 后会变成 seek 目标） */
    private var posBase = 0L
    /** 对外位置（单调不回退） */
    private var lastPlayed = 0L

    fun start(source: AudioSource, config: StreamConfig, byteSource: ByteSource, fromPos: Long) {
        stop()
        src = byteSource
        cfg = config
        pos = fromPos
        paused = false
        underrunSeen = 0
        dopMarkerEven = true
        dopLogged = false
        reachedEof = false
        dsdDataMode = when {
            !source.info.isDsd -> DsdDataMode.NONE
            config.mode == OutputMode.DSD_NATIVE -> DsdDataMode.NATIVE
            else -> DsdDataMode.DOP
        }
        lengthBytes = -1L
        // 位置记账的新基准：泵"累计送出字节"从此刻开始算（换流时 native 计数会归零）
        usbSentBase = runCatching { usb.stats()[1] }.getOrDefault(0L)
        posBase = fromPos
        lastPlayed = fromPos
        // ★ 换曲/重开时必须把遥测基线一起归位：否则 `pos - statsPos` 会算出
        //   "送出 -46144KB"（= 上一首累计 46 MB，而新曲 pos≈0）——真机第一百四十二轮仍复现。
        statsPos = fromPos
        statsAt = 0L
        statsRingLow = Int.MAX_VALUE
        statsRingEmpty = 0
        statsReads = 0
        statsReadNs = 0
        dsdLsbFirst = config.dsdLsbFirst
        subslot = (config.frameBytes / source.info.channels.coerceAtLeast(1)).coerceIn(2, 4)
        running.set(true)
        thread = Thread({ loop() }, "Producer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        runCatching { thread?.join(600) }
        thread = null
        runCatching { src?.close() }
        src = null
    }

    /** 请求 seek：由生产者线程在循环边界执行（避免与环形缓冲写入竞争） */
    fun seek(toBytePos: Long): Boolean {
        if (src == null) return false
        pendingSeek.set(toBytePos)
        return true
    }

    fun positionBytes(): Long = pos

    /** 此刻"真正已送出"的字节数（读块可能领先，但 ring 里未播的部分会被扣掉）——切模式/恢复用 */
    fun played(): Long = playedBytes()

    /**
     * 已播放字节 = **泵实际交给 USB 的字节数（单调递增）**。
     *
     * 以前是 `pos - ringUsed()`：环满时 producer 写多少、泵就读多少，两者同步增长，
     * 差值会**长期停住**，于是 UI 位置冻结、watchdog 判定"卡住"并把好流杀掉
     * （真机第一百四十七轮：环指针日志证明 `rd` 每秒 +352,440 B 在正常播放，`pos` 却停在 1.6 MB）。
     * 换成泵侧计数后：只要音频在往 DAC 送，位置就一定前进；与环形缓冲水位无关。
     */
    private fun playedBytes(): Long {
        // 基准 + 本次流"泵送出字节"的增量（单调递增，不依赖 ringUsed，也不受 EOF 后 pos 停住影响）
        val sent = runCatching { usb.stats()[1] - usbSentBase }.getOrDefault(0L)
        val delta = when (dsdDataMode) {
            DsdDataMode.DOP -> sent / 2      // DoP：1 个 DSD 字节 = 2 个 PCM 字节
            else -> sent
        }
        val now = posBase + delta
        // 单调保护：绝不让对外位置回退（以前 `coerceIn(0, pos)` 会在 EOF 后把位置钉死，
        // 于是 watchdog 一直以为"pos 未动"，每 5 秒刷一条误报 —— 真机第一百四十九轮）
        if (now > lastPlayed) lastPlayed = now
        return lastPlayed
    }

    fun isRunning(): Boolean = running.get()

    /** 是否处于"解码已到底、正在把环里剩的音频播完"的阶段（此时位置前进得很慢是正常的） */
    @Volatile private var draining = false
    fun isDraining(): Boolean = draining

    /**
     * 曲末提前淡出：等缓冲降到约 200 ms 就淡出，别等泵把环吃空。
     *
     * 泵在静音态不读环形缓冲，所以这里只是"把目标增益降为 0"（10 ms 斜坡），
     * 剩下的几百毫秒音频会在随后切歌的 seek_flush 里被丢弃 —— 换来的是切歌处
     * **不再出现 `欠载` 行**。
     *
     * 为什么从 50 ms 加到 200 ms：真机第一百二十八轮切歌时出现
     * `欠载：URB 需 352 B，只取到 240 B（差 112 B），环里剩 0 B` ——
     * 50 ms 的提前量刚好是"泵最后两个 URB 的量"，而这段判断 + 调用 native 静音
     * 本身就有几十毫秒延迟（日志里从环见底到"开始淡出"隔了 61 ms），于是泵先饿死。
     * 200 ms（8 MB 环的 2.5%）在听感上仍是"曲尾自然收尾"，但足够盖住这部分延迟。
     */
    private fun fadeOutBeforeEnd() {
        val c = cfg ?: return
        val bps = c.usbRate.toLong() * c.frameBytes
        if (bps <= 0) return
        val threshold = (bps * 200 / 1000).toInt().coerceAtLeast(8192)
        val enter = usb.ringUsed()
        // ★ 预算是"环里还能播多久 + 2 s"，不能是固定 3 s。
        //   真机第一百三十九轮：解码器读到 EOF 时环里还压着 7155 KB（≈23.7 s @44.1k），
        //   而这里固定等 3 s 就超时 → 直接 setMute(true)，把最后 24 秒整段静音丢掉，
        //   用户听到的就是"每首歌结尾少 24 秒 + 卡住 3 秒 + 从头重播"。
        val budgetMs = if (enter <= threshold) 0L else enter * 1000L / bps + 2000L
        val deadline = System.currentTimeMillis() + budgetMs
        var lastProg = 0L
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (paused) return                       // 用户暂停了：保持静音状态即可
            if (pendingSeek.get() >= 0) return       // 有新的 seek（切歌）：这次淡出作废
            if (usb.ringUsed() <= threshold) break
            // 这段最长要等"环里还能播多久"（8 MB ≈ 24 s @44.1k），期间不更新进度的话
            // UI 位置会冻住二十多秒 —— 真机第一百三十九轮就被误判成"死锁"。
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastProg >= 200) {
                lastProg = nowMs
                onProgress(playedBytes(), lengthBytes)
            }
            try { Thread.sleep(5) } catch (ie: InterruptedException) { return }
        }
        val used = usb.ringUsed()
        if (used > threshold) {
            // 泵没在消费环（暂停/被卡住/被别的线程动过）：**不要**闷掉剩余音频，
            // 交给 drainRing 处理（它有 2 s 无进展逃生），宁可尾部少一点淡出也不能吞掉整段。
            Log.w(TAG, "曲末淡出跳过：环里仍有 ${used / 1024}KB（预算 ${budgetMs}ms 用完，" +
                "泵未消费）")
            return
        }
        if (running.get() && !paused) {
            runCatching { usb.pauseOutput() }        // nativeSetMute(true) → 10ms 淡出
            if (com.dsd.player.BuildConfig.DEBUG) {
                Log.i(TAG, "曲末提前淡出（ring ${used / 1024}KB，阈值 ${threshold / 1024}KB，" +
                    "进入时 ${enter / 1024}KB，等了 ${System.currentTimeMillis() + budgetMs - deadline}ms）")
            }
        }
    }

    /**
     * 等环形缓冲排空（最多等「缓冲时长 × 1.5 + 1s」，约 5.4s @44.1k）。
     * 期间仍受 running 控制：用户按停止会立刻退出。
     */
    private fun drainRing() {
        val ringBytes = RING_CAPACITY.toLong()
        val bytesPerSec = cfg?.let { it.usbRate.toLong() * it.frameBytes } ?: 0L
        val budgetMs = if (bytesPerSec > 0)
            (ringBytes * 1000 / bytesPerSec * 1.5).toLong() + 1000 else 6000L
        val t0 = System.currentTimeMillis()
        var lastUsed = usb.ringUsed()
        val enter = lastUsed
        var lastLog = 0L
        var lastProg = 0L
        var stalledSince = 0L
        while (running.get() && System.currentTimeMillis() - t0 < budgetMs) {
            val used = usb.ringUsed()
            if (used <= 0) break
            val now = System.currentTimeMillis()
            if (used != lastUsed) { lastUsed = used; stalledSince = 0 }
            else if (stalledSince == 0L) stalledSince = now
            // 排空（曲末）阶段泵仍在送数据：必须同步更新对外位置，否则会冻在曲末值上，
            // watchdog 就会周期性报"泵在送数据但 pos 未动"（真机第一百五十一轮，每 60 秒一条）。
            if (now - lastProg >= 200) {
                lastProg = now
                onProgress(playedBytes(), lengthBytes)
            }
            // 每 500ms 打一次水位，便于判断"泵是否真的在消费"
            if (now - lastLog > 500) {
                lastLog = now
                val st = usb.stats()
                Log.i(TAG, "drain: 剩余水位 $used（起 $enter），已用 ${now - t0}ms，" +
                    "xfer=${st[0]} bytes=${st[1]} underrun=${st[2]} err=${st[3]}")
            }
            // 水位 1 秒不动 = 泵没在消费 → 报出来；**连续 2 秒不动就放弃 drain**。
            // 真机第一百三十八轮就是死在这里：列表循环重播时两次并发 seek 把泵带成
            // "只发静音、不读环"的中间态，drain 等环清空永远等不到 → 卡住 55 秒。
            // 环里的数据此时注定要被丢弃（马上要重播/切歌），直接退出比挂死强。
            if (stalledSince != 0L && now - stalledSince > 1000) {
                val st = usb.stats()
                if (now - stalledSince > 2000) {
                    Log.w(TAG, "drain: 水位 2s 未变化（泵不再消费）→ 放弃等待，丢弃剩余 " +
                        "${used / 1024}KB（xfer=${st[0]} bytes=${st[1]}）")
                    runCatching { usb.ringDiscard() }
                    break
                }
                Log.w(TAG, "drain: 水位 1s 未变化（pump 可能已停）：ringUsed=$used " +
                    "xfer=${st[0]} bytes=${st[1]} underrun=${st[2]} err=${st[3]}")
            }
            try { Thread.sleep(20) } catch (ie: InterruptedException) { break }
        }
        Log.i(TAG, "drain: 用时 ${System.currentTimeMillis() - t0}ms，剩余水位 ${usb.ringUsed()}" +
            "（进入排空时 $enter 字节）")
    }

    /** 每 10 秒一行：供给速率对照需求、underrun 增量、环形水位最低点 */
    private fun logSupplyRate() {
        if (!com.dsd.player.BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        val ring = usb.ringUsed()
        if (statsAt == 0L) {
            statsAt = now; statsPos = pos; statsUnderrun = usb.stats()[2].toLong()
            statsRingLow = ring
            return
        }
        if (ring < statsRingLow) statsRingLow = ring
        if (ring < 256 * 1024) statsRingEmpty++
        val ms = now - statsAt
        if (ms < 5_000) return
        val st = usb.stats()
        val dBytes = pos - statsPos
        val need = cfg?.let { it.usbRate.toLong() * it.frameBytes } ?: 0L
        val rate = dBytes * 1000 / ms
        // 注意量纲：dBytes 是字节、ms 是毫秒、need 是"字节/秒" → 必须 ×1000/ms 才是 B/s
        val ratio = if (need > 0) dBytes.toDouble() * 1000 / ms / need.toDouble() else 0.0
        Log.i(TAG, "供数：${ms / 1000}s 送出 ${dBytes / 1024}KB = ${rate / 1024}KB/s" +
            "（需求 ${need / 1024}KB/s，${String.format("%.2f", ratio)}×实时）；" +
            "underrun=${st[2]}（+${st[2] - statsUnderrun}）" +
            (if (st.size > 4) " 起播欠载=${st[4]}" else "") +
            "ring 最低 ${statsRingLow / 1024}KB / 当前 ${ring / 1024}KB" +
            "读源阻塞 ${srcBlockMs}ms / ${statsReads} 次" +
            (if (statsRingLow < RING_CAPACITY / 4) " ⚠ 环低水位（<25%）" else " 正常") +
            (if (st[2] - statsUnderrun > 0) " ⚠ 有欠载（见底 ${statsRingEmpty} 次）" else ""))
        statsAt = now; statsPos = pos; statsUnderrun = st[2].toLong(); statsRingLow = ring
        statsRingEmpty = 0
        statsReadNs = 0; statsReads = 0
    }

    private fun loop() {
        // 一次读大一点：96k/24bit 时 64KB 只有 85ms，一秒钟要醒 12 次，每次都可能把环
        // 拖到"差一个 URB"；256KB（≈340ms @96k/24bit）把唤醒次数降到 3 次/秒。
        val buf = ByteArray(256 * 1024)
        try {
            while (running.get()) {
                if (paused) { Thread.sleep(20); continue }
                val seekTo = pendingSeek.getAndSet(-1)
                if (seekTo >= 0) {
                    val s0 = src
                    if (s0 != null && s0.seek(seekTo)) {
                        pos = seekTo
                        reachedEof = false
                        underrunSeen = 0
                        // 遥测基线一起回退，否则 `pos - statsPos` 会出现巨大负数
                        // （真机第一百三十九轮：`供数：6s 送出 -46144KB = -7026KB/s`）
                        statsPos = seekTo
                        statsAt = System.currentTimeMillis()
                        statsRingLow = Int.MAX_VALUE
                        // 位置基准也跟着 seek 走（否则位置会从旧基准继续累加）
                        runCatching { usb.stats()[1] }.onSuccess {
                            usbSentBase = it; posBase = seekTo; lastPlayed = seekTo
                        }
                        // 丢掉环形缓冲里"旧位置"的数据（阻塞到丢弃完成），再显式淡入：
                        // 丢弃后 native 保持静音，必须这里解除，否则会一直静音
                        usb.seekFlush()
                        if (!paused) usb.resumeOutput()
                        Log.i(TAG, "seek → $seekTo（已冲刷环形缓冲）")
                        onProgress(pos, s0.lengthBytes)
                    }
                    continue
                }
                val s = src ?: break
                val tRead = System.nanoTime()
                val n = s.read(buf, 0, buf.size)
                statsReadNs += System.nanoTime() - tRead
                statsReads++
                srcBlockMs = statsReadNs / 1_000_000
                if (n < 0) { Log.e(TAG, "read error"); break }
                if (n == 0) { pos = s.lengthBytes; reachedEof = true; break }
                val data: ByteArray
                val dlen: Int
                when (dsdDataMode) {
                    DsdDataMode.NONE -> {
                        // PCM：源是 24-in-32（4B/样本），USB 侧按协商到的 subslot 收窄
                        val p = packPcm(buf, n, subslot)
                        data = p.first; dlen = p.second
                    }
                    DsdDataMode.NATIVE -> {
                        // DSD native：UAC2 的 subslot 可能是 1B（直通）或 4B（DSD_U32 容器）
                        val p = packDsdNative(buf, n, subslot)
                        data = p.first; dlen = p.second
                    }
                    DsdDataMode.DOP -> {
                        val p = packDoP(buf, n)
                        data = p.first; dlen = p.second
                    }
                }
                var written = 0
                while (running.get() && written < dlen) {
                    val w = NativeBridge.nativeRingWrite(data, written, dlen - written)
                    if (w <= 0) {
                        if (!running.get()) break
                        Thread.sleep(2)
                        val st = usb.stats()
                        if (st[2] > lastUnderrun) {
                            lastUnderrun = st[2]
                            underrunSeen++
                            if (underrunSeen > 30) {
                                Log.e(TAG, "欠载过多（$underrunSeen），停止")
                                onUnderrunStorm()
                                return
                            }
                        }
                        continue
                    }
                    underrunSeen = 0            // 写进数据就说明恢复正常
                    written += w
                    // DoP：4 个 DSD 字节 → 8 个 PCM 字节，故 w/2
                    pos += if (dsdDataMode == DsdDataMode.DOP) w / 2 else w
                }
                lengthBytes = s.lengthBytes
                // 进度必须按"已经送给 DAC"的量算：预填进环形缓冲的部分还没播
                onProgress(playedBytes(), s.lengthBytes)
                logSupplyRate()
            }
            // 读完文件的瞬间环形缓冲里还压着最多 8MB（≈23.8 s @44.1k/16bit）没送出去，
            // 直接收尾会把这段音乐整段丢掉——必须等水位归零再结束。
            // 但在归零**之前**要先主动淡出：真机第一百二十四轮实测，切歌瞬间总有 1 条
            // `欠载：…环里剩 0 B`——时序是"泵先把环吃空 → 欠载 → 才淡出"，反了。
            if (running.get() && reachedEof) {
                draining = true
                fadeOutBeforeEnd()
                drainRing()
                draining = false
            }
            if (running.get()) onEnded()
        } catch (ie: InterruptedException) {
            // stop() 打断，正常退出
        } catch (e: Exception) {
            Log.e(TAG, "生产者线程异常", e)
        } finally {
            running.set(false)
        }
    }

    private var lastUnderrun = -1L
    private var dopLogged = false
    private var reachedEof = false
    /** 待执行的 seek（生产者线程消费；-1 = 无） */
    private val pendingSeek = java.util.concurrent.atomic.AtomicLong(-1)
    /** 每声道子槽字节数（2/3/4），由 native 协商结果决定 */
    private var subslot = 4

    private fun hex(b: ByteArray, off: Int, n: Int): String =
        (off until off + n).joinToString("") { "%02x".format(b[it]) }

    /** 小端存储的 32-bit 字，按 MSB 优先打印 */
    private fun hexMsb(b: ByteArray, off: Int, n: Int): String =
        (off + n - 1 downTo off).joinToString("") { "%02x".format(b[it]) }

    private var pcmBuf = ByteArray(0)
    private var dsdBuf = ByteArray(0)

    /**
     * DSD native 打包：
     *   subslot 1 → 直通（帧 = L1B + R1B）
     *   subslot 4 → 每声道 4 个 DSD 字节塞进一个 32-bit 字（DSD_U32），帧 = 8B
     *   subslot 2 → 每声道 2 个字节
     * 无论哪种，输入输出字节数都是 1:1（只是重新分组），所以 pos 记账不变。
     */
    private fun packDsdNative(dsd: ByteArray, n: Int, subslot: Int): Pair<ByteArray, Int> {
        if (subslot <= 1) return dsd to n
        val perCh = subslot
        val frameBytes = perCh * 2              // L + R
        val frames = n / frameBytes
        if (frames <= 0) return dsd to 0
        val out = frames * frameBytes
        if (dsdBuf.size < out) dsdBuf = ByteArray(out)
        var j = 0
        var i = 0
        while (i + frameBytes <= n) {
            // 源是交织 L,R；取出连续的 perCh 个 L 字节与 perCh 个 R 字节
            for (c in 0 until perCh) dsdBuf[j + c] = dsd[i + 2 * c]
            for (c in 0 until perCh) dsdBuf[j + perCh + c] = dsd[i + 2 * c + 1]
            i += frameBytes
            j += frameBytes
        }
        return dsdBuf to j
    }

    /**
     * PCM 24-in-32（每样本 4B，数据在高 24 位）→ USB 侧 subslot 字节数：
     *   subslot 4 → 原样；3 → 丢最低字节（取高 3 字节）；2 → 取最高 2 字节（16bit）
     * 之前 Java 侧硬编码 frameBytes=8，若 DAC 的 alt 其实是 24-bit（3B/声道），
     * 送 8 字节帧会整帧错位 → DAC 直接静音。
     */
    private fun packPcm(src: ByteArray, n: Int, subslot: Int): Pair<ByteArray, Int> {
        if (subslot == 4) return src to n
        val out = (n / 4) * subslot
        if (pcmBuf.size < out) pcmBuf = ByteArray(out)
        var si = 0; var di = 0
        if (subslot == 3) {
            while (si + 3 < n) {
                pcmBuf[di] = src[si + 1]; pcmBuf[di + 1] = src[si + 2]; pcmBuf[di + 2] = src[si + 3]
                si += 4; di += 3
            }
        } else {
            while (si + 3 < n) {
                pcmBuf[di] = src[si + 2]; pcmBuf[di + 1] = src[si + 3]
                si += 4; di += 2
            }
        }
        return pcmBuf to di
    }

    /**
     * DoP 打包（dCS DoP 规范）：
     *  每个声道的一个 PCM 样本携带 **2 个 DSD 字节**（16 bit 载荷）+ 8 bit marker，
     *  即 24-bit 有效位：bit23..16 = 第 1 个 DSD 字节，bit15..8 = 第 2 个 DSD 字节，
     *  bit7..0 = marker。marker 在**最低字节**，左右声道互为 0x05/0xFA 且逐帧交替。
     *  USB 侧按 32-bit 左对齐（24-bit 有效数据在 bit31..8），所以整字左移 8。
     *
     *  输入：dsd = 交织 DSD 字节 L,R,L,R...（每 4 字节 = 2 帧 × 2 声道）
     *  输出：8 字节 PCM（L 帧 4B + R 帧 4B），即 4 DSD 字节 → 8 PCM 字节
     */
    private fun packDoP(dsd: ByteArray, n: Int): Pair<ByteArray, Int> {
        val frames = n / 4                     // 每 4 个 DSD 字节产生 1 个 L+R 样本对
        val per = if (subslot >= 4) 4 else 3   // DoP 至少需要 24 bit
        val pairBytes = per * 2
        if (packBuf.size < frames * pairBytes) packBuf = ByteArray(frames * pairBytes)
        var j = 0
        var i = 0
        while (i + 3 < n) {
            val ml = if (dopMarkerEven) 0x05 else 0xFA
            val mr = if (dopMarkerEven) 0xFA else 0x05
            // DoP 字布局（XD05 Bal 实测：DAC 只看最高字节判 DoP）：
            //   bit31..24 = marker，bit23..8 = 16-bit DSD 载荷，bit7..0 = 0
            // 之前把 marker 放在 bit23..16，DAC 认不出 DoP → 当 176.4k PCM 播 → 刺耳噪
            val wl = (ml shl 24) or ((dsd[i].toInt() and 0xFF) shl 16) or
                ((dsd[i + 2].toInt() and 0xFF) shl 8)
            val wr = (mr shl 24) or ((dsd[i + 1].toInt() and 0xFF) shl 16) or
                ((dsd[i + 3].toInt() and 0xFF) shl 8)
            if (per == 4) {
                // 32-bit 子槽：高位对齐，最低字节恒 0
                packBuf[j] = (wl and 0xFF).toByte()
                packBuf[j + 1] = ((wl shr 8) and 0xFF).toByte()
                packBuf[j + 2] = ((wl shr 16) and 0xFF).toByte()
                packBuf[j + 3] = ((wl shr 24) and 0xFF).toByte()
                packBuf[j + 4] = (wr and 0xFF).toByte()
                packBuf[j + 5] = ((wr shr 8) and 0xFF).toByte()
                packBuf[j + 6] = ((wr shr 16) and 0xFF).toByte()
                packBuf[j + 7] = ((wr shr 24) and 0xFF).toByte()
            } else {
                // 24-bit 子槽：marker 在最高字节
                packBuf[j] = ((wl shr 8) and 0xFF).toByte()
                packBuf[j + 1] = ((wl shr 16) and 0xFF).toByte()
                packBuf[j + 2] = ((wl shr 24) and 0xFF).toByte()
                packBuf[j + 3] = ((wr shr 8) and 0xFF).toByte()
                packBuf[j + 4] = ((wr shr 16) and 0xFF).toByte()
                packBuf[j + 5] = ((wr shr 24) and 0xFF).toByte()
            }
            j += pairBytes
            i += 4
            dopMarkerEven = !dopMarkerEven
        }
        // 首帧诊断：确认左右声道都有数据、marker 位置正确（真机排查「只出一边」时看这行）
        if (!dopLogged && j >= 16) {
            dopLogged = true
            // 按 MSB 优先打印（与 DoP 规范的读法一致）：期望 L=05xxxx00 / R=faxxxx00 交替
            Log.i(TAG, "DoP first frames(MSB-first): L=${hexMsb(packBuf, 0, 4)} R=${hexMsb(packBuf, 4, 4)} " +
                "L2=${hexMsb(packBuf, 8, 4)} R2=${hexMsb(packBuf, 12, 4)} " +
                "src L0=0x${(dsd[0].toInt() and 0xFF).toString(16)} R0=0x${(dsd[1].toInt() and 0xFF).toString(16)}" +
                " L1=0x${(dsd[2].toInt() and 0xFF).toString(16)} R1=0x${(dsd[3].toInt() and 0xFF).toString(16)}")
        }
        return packBuf to j
    }
}
