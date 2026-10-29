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
    companion object { private const val TAG = "Producer" }

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
    private var packBuf = ByteArray(128 * 1024)

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

    /** 已播放字节 = 已读出字节 − 环形缓冲里尚未送出的字节（含 DoP 的 4:1 换算） */
    private fun playedBytes(): Long {
        val used = usb.ringUsed().toLong()
        val pending = when (dsdDataMode) {
            DsdDataMode.DOP -> used / 2      // DoP：1 个 DSD 字节 = 2 个 PCM 字节
            else -> used
        }
        return (pos - pending).coerceAtLeast(0)
    }

    fun isRunning(): Boolean = running.get()

    /**
     * 等环形缓冲排空（最多等「缓冲时长 × 1.5 + 1s」，约 5.4s @44.1k）。
     * 期间仍受 running 控制：用户按停止会立刻退出。
     */
    private fun drainRing() {
        // 必须与 native 的 kRingBytes 一致（usb_stream.cpp，现 2MB）
        val ringBytes = 2L * 1024 * 1024
        val bytesPerSec = cfg?.let { it.usbRate.toLong() * it.frameBytes } ?: 0L
        val budgetMs = if (bytesPerSec > 0)
            (ringBytes * 1000 / bytesPerSec * 1.5).toLong() + 1000 else 6000L
        val t0 = System.currentTimeMillis()
        var lastUsed = usb.ringUsed()
        val enter = lastUsed
        var lastLog = 0L
        var stalledSince = 0L
        while (running.get() && System.currentTimeMillis() - t0 < budgetMs) {
            val used = usb.ringUsed()
            if (used <= 0) break
            val now = System.currentTimeMillis()
            if (used != lastUsed) { lastUsed = used; stalledSince = 0 }
            else if (stalledSince == 0L) stalledSince = now
            // 每 500ms 打一次水位，便于判断"泵是否真的在消费"
            if (now - lastLog > 500) {
                lastLog = now
                val st = usb.stats()
                Log.i(TAG, "drain: 剩余水位 $used（起 $enter），已用 ${now - t0}ms，" +
                    "xfer=${st[0]} bytes=${st[1]} underrun=${st[2]} err=${st[3]}")
            }
            // 水位 1 秒不动 = 泵没在消费，直接报出来（真机排查用）
            if (stalledSince != 0L && now - stalledSince > 1000) {
                val st = usb.stats()
                Log.w(TAG, "drain: 水位 1s 未变化（pump 可能已停）：ringUsed=$used " +
                    "xfer=${st[0]} bytes=${st[1]} underrun=${st[2]} err=${st[3]}")
                stalledSince = now          // 再等 1 秒才重复报
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
        val ms = now - statsAt
        if (ms < 10_000) return
        val st = usb.stats()
        val dBytes = pos - statsPos
        val need = cfg?.let { it.usbRate.toLong() * it.frameBytes } ?: 0L
        val rate = dBytes * 1000 / ms
        val ratio = if (need > 0) dBytes.toDouble() / ms / need.toDouble() else 0.0
        Log.i(TAG, "供数：${ms / 1000}s 送出 ${dBytes / 1024}KB = ${rate / 1024}KB/s" +
            "（需求 ${need / 1024}KB/s，${String.format("%.2f", ratio)}×实时）；" +
            "underrun=${st[2]}（+${st[2] - statsUnderrun}）ring 最低 ${statsRingLow / 1024}KB" +
            (if (ratio < 0.98) " ⚠ 供数不足" else ""))
        statsAt = now; statsPos = pos; statsUnderrun = st[2].toLong(); statsRingLow = ring
    }

    private fun loop() {
        val buf = ByteArray(64 * 1024)
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
                val n = s.read(buf, 0, buf.size)
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
                // 进度必须按"已经送给 DAC"的量算：预填进环形缓冲的部分还没播
                onProgress(playedBytes(), s.lengthBytes)
                logSupplyRate()
            }
            // 读完文件的瞬间环形缓冲里还压着最多 2MB（≈5.9 s @44.1k/16bit）没送出去，
            // 直接收尾会把这段音乐整段丢掉——必须等水位归零再结束。
            if (running.get() && reachedEof) drainRing()
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
