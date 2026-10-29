// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * FLAC → **真 24bit** 的字节流（24-in-32 交织）。
 *
 * ## 为什么需要它
 * AOSP 的 FLAC extractor 把轨道暴露成 `audio/raw` 时，会用平台解码器把 24bit 素材**降成
 * 16bit**（日志 `pcmBytes=2 extractor报=24bit ⚠被平台降位成 16bit`），DAC 面板显 24bit
 * 但低 8 位恒 0。绕过的办法（见 docs/DEV-NOTES.md）：
 * 自己喂 `c2.android.flac.decoder`，configure 时显式要
 * `KEY_PCM_ENCODING = ENCODING_PCM_FLOAT`，解码器就真的输出 32bit float，再缩放成 24-in-32。
 *
 * ## 三条实测硬约束
 *  1. **必须先喂 42 字节合成头**（`"fLaC"` + STREAMINFO 元数据块，last 标志置位）：只喂帧数据
 *     （哪怕逐帧完整、带 csd-0）解码器一个字节都不吐（libFLAC 需要流头）。
 *  2. 喂进去的字节**不必帧对齐**：解码器自己成帧（但把多帧拼成"一个"超大缓冲会
 *     `buffer is inaccessible`，所以按 256 KB 分块）。
 *  3. 解码器没给 float 时要按它实际报的位深转换，绝不能把 16bit 当 float（⇒ 2 倍速噪声）。
 *
 * ## 为什么有独立解码线程（第一百零二轮真机反馈）
 * 第一版是"生产者线程里串行：扫帧 → 读源 → 喂解码 → 取输出"。真机 96k/24bit 实测只有
 * **0.64× 实时**（underrun=39821）。模拟器分段计时显示**扫帧占 50%**
 * （3307ms 里 1640ms），而 1 MB 环形缓冲只够 1.3 s —— 抖动直接变成断音。现在：
 *  - **按 256 KB 顺序块喂**，不再逐帧扫（省掉一半 CPU，也省掉每帧一次读源调用）；
 *  - 解码 + float→24in32 放在**独立解码线程**，预读 [PREBUFFER]（96k/24bit ≈8 s）环形缓冲，
 *    播放线程只做 memcpy —— 网络/解码抖动被缓冲吸收；
 *  - seek 用"代次 + 应答"交给解码线程执行（flush → 重喂合成头 → 从估算位置继续），
 *    帧边界只在 seek 时找一次。
 */
class FlacFloatByteSource(
    private val r: FlacFrameReader,
    private val src: RandomSource,
    private val tag: String = "FlacFloat",
) : ByteSource {

    companion object {
        private const val FRAME = PcmUtils.OUT_BYTES_PER_FRAME   // 8 = 2ch × 4B

        /** 每次从数据源读的压缩字节数（96k/24bit 约 4 s）。块内自己切帧后**按帧边界**
         *  喂解码器：实测非帧对齐的块会丢样本（喂整份只吐出 42%）。 */
        private const val READ_CHUNK = 2 * 1024 * 1024

        /** 预读缓冲：96k/24bit（750 KB/s）≈8 s；44.1k/16bit ≈20 s */
        private const val PREBUFFER = 6 * 1024 * 1024

        private const val OUT_CAP = 1024 * 1024
    }

    private val info = r.info
    private val ch = info.channels.coerceAtLeast(1)

    // ── 解码器（只在解码线程里碰）──────────────────────────────────────
    private var codec: MediaCodec? = null
    private val oi = MediaCodec.BufferInfo()
    private val raw = ByteArray(OUT_CAP)
    private val outTmp = ByteArray(OUT_CAP)
    /** 块缓冲：READ_CHUNK + 单帧上界（65535 样本 × 2ch × 4B ≈ 512KB） */
    private val chunkBuf = ByteArray(READ_CHUNK + 640 * 1024)
    private var blkLen = 0
    private var blkStart = 0L
    /** 下一个要喂的帧（帧边界由 FlacFrameReader 保证） */
    private var nextFrame: FlacFrame? = null
    private var feedLogged = 0
    private var frameIndex = 0L

    /** 解码器实际输出位深（0=未知；4=float → 真 24bit） */
    @Volatile private var outEnc = 0

    /** 下一个要喂的字节位置（任意偏移都行，解码器自己成帧） */
    private var nextReadPos = 0L
    private var headerSent = false
    private var inputDone = false

    /** 解码侧是否已到底（≠ 播放侧读完，播放侧还要看缓冲） */
    @Volatile private var eof = false

    /** seek 之后要丢掉的开头字节（对齐到帧） */
    private var skipBytes = 0L

    // ── 环形缓冲（解码线程写 / 播放线程读）────────────────────────────
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val ring = ByteArray(PREBUFFER)
    private var rPos = 0
    private var wPos = 0
    private var filled = 0

    @Volatile private var closed = false
    @Volatile private var thread: Thread? = null

    /** seek 请求：seekTarget >= 0 待处理；gen/ack 用来确认识别完成 */
    private var seekTarget = -1L
    private var seekGen = 0
    private var seekAck = 0
    private var seekResult = false

    /** 已经交给播放端的字节数 */
    @Volatile var produced = 0L
        private set

    override val lengthBytes: Long
        get() = if (info.totalSamples > 0) info.totalSamples * FRAME else -1

    /** 真正拿到的有效位深：float → 24（24bit 精度），否则 16（平台上限） */
    override val outputBits: Int
        get() = when {
            outEnc == AudioFormat.ENCODING_PCM_FLOAT -> 24
            outEnc != 0 -> 16
            else -> 0
        }

    // ── 吞吐诊断（真机排查"供数不足"用）───────────────────────────────
    /** 入队失败重试计数（重建解码器后重试，避免一次抖动就整曲崩） */
    private var feedRetry = 0

    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    /** 解码器失效：释放，让下一次 feed 重建并重喂合成头 */
    private fun onCodecBroken() {
        Log.w(tag, "解码器失效 → 重建（已喂到 $nextReadPos B）")
        releaseCodec()
        headerSent = false
        inputDone = false
        eof = false
    }

    private var statT0 = System.currentTimeMillis()
    private var statBytesAtT0 = 0L
    private var statChunks = 0
    private var statReadNs = 0L
    private var statQueueNs = 0L
    private var statDrainNs = 0L
    private var statConvNs = 0L
    private var statWaitNs = 0L

    private fun logThroughput() {
        if (!com.dsd.player.BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        val ms = now - statT0
        if (ms < 2000) return
        val bytes = produced - statBytesAtT0
        val need = info.rate.toLong() * ch * 4 / 1024          // 需求 KB/s（24-in-32）
        val ratio = if (need > 0) bytes.toDouble() / 1024 / (ms / 1000.0) / need else 0.0
        Log.i(tag, "吞吐：${ms}ms 产出 ${bytes / 1024}KB = ${bytes / 1024 * 1000 / ms}KB/s" +
            "（需求 ${need}KB/s，${String.format("%.2f", ratio)}×实时）；块 $statChunks，" +
            "读源 ${statReadNs / 1_000_000}ms 入队 ${statQueueNs / 1_000_000}ms " +
            "解码 ${statDrainNs / 1_000_000}ms 转换 ${statConvNs / 1_000_000}ms " +
            "等空间 ${statWaitNs / 1_000_000}ms 缓冲 ${filled / 1024}KB" +
            (if (ratio < 0.98) " ⚠ 供数不足" else ""))
        statT0 = now
        statBytesAtT0 = produced
        statChunks = 0
        statReadNs = 0; statQueueNs = 0; statDrainNs = 0; statConvNs = 0; statWaitNs = 0
    }

    // ── 解码线程 ──────────────────────────────────────────────────────
    private fun ensureWorker() {
        if (thread != null) return
        val t = Thread({ workLoop() }, "$tag-decode")
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY + 1
        thread = t
        t.start()
    }

    private fun workLoop() {
        try {
            while (true) {
                lock.withLock { if (closed) return }
                if (isSeekPending()) { runSeek(); continue }

                val n = decodeOnce()
                if (n > 0) { writeRing(n); continue }

                if (feedOne()) {
                    // 成功喂入：立刻回到解码，别再空转
                    continue
                }
                if (!inputDone) Thread.sleep(1)

                // 输入已尽：把解码器里剩下的尾部抽干
                if (inputDone) {
                    var guard = 0
                    while (guard++ < 200) {
                        val tail = decodeOnce()
                        if (tail > 0) { writeRing(tail); continue }
                        if (eof || closed) break
                        Thread.sleep(5)
                    }
                    lock.withLock { eof = true; cond.signalAll() }
                    logThroughput()
                    Log.i(tag, "解码线程收尾退出（缓冲还剩 ${filled / 1024}KB）")
                    return
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "解码线程异常：${t.javaClass.simpleName}: ${t.message}", t)
            lock.withLock { eof = true; cond.signalAll() }
        }
    }

    private fun isSeekPending(): Boolean = lock.withLock { seekTarget >= 0 }

    private fun runSeek() {
        val target: Long
        val gen: Int
        lock.withLock { target = seekTarget; gen = seekGen }
        val ok = try { doSeek(target) } catch (t: Throwable) {
            Log.w(tag, "seek 执行失败：${t.message}"); false
        }
        lock.withLock {
            seekResult = ok
            seekAck = gen
            seekTarget = -1
            cond.signalAll()
        }
    }

    private fun doSeek(targetSample: Long): Boolean {
        val est = r.offsetForSample(targetSample)
        val frame = if (targetSample <= 0) r.firstFrame() else r.frameAtOrBefore(est, targetSample)
        val from = frame ?: r.firstFrame()
        // 不用 flush()：实测 FLAC 解码器 flush 之后会把已解码过的帧再吐一遍
        // （seek 后输出翻倍，读端会读到整曲长度的数据）。直接重建解码器最干净。
        releaseCodec()
        headerSent = false
        inputDone = false
        eof = false
        outEnc = 0
        nextReadPos = from?.offset ?: info.firstFrameOffset
        nextFrame = from
        blkLen = 0
        frameIndex = 0
        feedLogged = 0
        skipBytes = if (from != null) (targetSample - from.sample).coerceAtLeast(0) * FRAME else 0
        Log.i(tag, "seek → 样本 $targetSample（估计偏移 $est，帧@${from?.offset} 样本${from?.sample}，" +
            "丢弃 ${skipBytes / FRAME} 帧）")
        lock.withLock { produced = targetSample * FRAME }
        return true
    }

    /** 抽一次解码输出到 outTmp，返回字节数（0 = 暂时没有） */
    private fun decodeOnce(): Int {
        val c = codec ?: runCatching { ensureCodec() }.getOrElse {
            Log.e(tag, "解码器创建失败：${it.message}"); eof = true; return 0
        }
        val t0 = System.nanoTime()
        try {
            var guard = 0
            while (guard++ < 64) {
                when (val idx = c.dequeueOutputBuffer(oi, 2_000)) {
                    in 0..Int.MAX_VALUE -> {
                        var n = 0
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && oi.size > 0) {
                            val sz = minOf(oi.size, raw.size)
                            buf.position(oi.offset)
                            buf.limit(oi.offset + sz)
                            buf.get(raw, 0, sz)
                            val tc = System.nanoTime()
                            n = convert(raw, sz, outTmp)
                            statConvNs += System.nanoTime() - tc
                        }
                        c.releaseOutputBuffer(idx, false)
                        if ((oi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eof = true
                        if (n > 0) return n
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        outEnc = CodecPcm.read(of)
                        Log.i(tag, "解码输出 encoding=${CodecPcm.describe(outEnc)}（期望 4=float）" +
                            "outputFormat=$of")
                        if (outEnc != AudioFormat.ENCODING_PCM_FLOAT) {
                            Log.w(tag, "⚠ 解码器没给 float（${CodecPcm.describe(outEnc)}）→ 按该位深转换，" +
                                "有效位深受平台限制（UI 显示“源 24bit → 输出 16bit”）")
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return 0
                    else -> return 0
                }
            }
            return 0
        } finally {
            statDrainNs += System.nanoTime() - t0
        }
    }

    /** 喂**一帧**压缩数据（或流头）；没有更多输入时返回 false */
    private fun feedOne(): Boolean {
        val c = codec ?: runCatching { ensureCodec() }.getOrElse { return false }
        if (!headerSent) {
            headerSent = true
            return queue(c, syntheticHeader(), 0, 42, 0)
        }
        if (inputDone) return false
        val cur = nextFrame ?: r.firstFrame()?.also { nextFrame = it }
        if (cur == null) { finishInput(c); return false }
        // 帧边界只扫一次（FlacFrameReader 用"样本号 = 上一帧样本 + 块长"定界，CRC8 校验，
        // 不会把帧内数据误判成帧头 —— 只按 CRC8 找会在压缩数据里出错帧）
        val nxt = r.nextFrame(cur)
        val len = if (nxt != null) (nxt.offset - cur.offset).toInt()
                  else (src.size - cur.offset).toInt()
        if (len <= 0 || len > chunkBuf.size) { finishInput(c); return false }
        if (!ensureBlock(cur.offset, len)) { finishInput(c); return false }
        val from = (cur.offset - blkStart).toInt()
        val pts = frameIndex * 20_000L
        feedLogged++
        if (queue(c, chunkBuf, from, len, pts)) {
            frameIndex++
            nextFrame = nxt
            if (nxt == null) finishInput(c)
            return true
        }
        // 入队失败：解码器可能失效 → 重建后从同一帧重试
        if (feedRetry++ < 3) { onCodecBroken(); return true }
        Log.e(tag, "入队连续失败 ${feedRetry} 次，跳过该帧")
        feedRetry = 0
        frameIndex++
        nextFrame = nxt
        if (nxt == null) finishInput(c)
        return true
    }

    /**
     * 保证 [offset, offset+need) 已在 chunkBuf 里（大块顺序读，避免每帧一次数据源调用）。
     * 块读完就换下一块，起点永远是帧边界。
     */
    private fun ensureBlock(offset: Long, need: Int): Boolean {
        if (offset >= blkStart && offset + need <= blkStart + blkLen) return true
        if (offset >= src.size) return false
        val want = minOf(chunkBuf.size.toLong(), src.size - offset).toInt()
        if (want < need) return false
        val tr = System.nanoTime()
        val got = src.readAt(offset, chunkBuf, 0, want)
        statReadNs += System.nanoTime() - tr
        if (got < need) {
            Log.w(tag, "读块不足：pos=$offset 需要 $need 实得 $got（size=${src.size}）")
            return false
        }
        blkStart = offset
        blkLen = got
        statChunks++
        return true
    }

    private fun queue(c: MediaCodec, data: ByteArray, from: Int, size: Int, ptsUs: Long): Boolean {
        val t0 = System.nanoTime()
        try {
            var off = from
            val end = from + size
            while (off < end) {
                val idx = c.dequeueInputBuffer(200_000)
                if (idx < 0) return false
                val ib = c.getInputBuffer(idx) ?: return false
                ib.clear()
                val take = minOf(ib.capacity(), end - off)
                if (take <= 0) return false
                ib.put(data, off, take)
                try {
                    c.queueInputBuffer(idx, 0, take, ptsUs + off, 0)
                } catch (t: Throwable) {
                    Log.e(tag, "queueInputBuffer 异常（idx=$idx take=$take cap=${ib.capacity()} " +
                        "off=$off end=$end）：${t.javaClass.simpleName}: ${t.message}")
                    return false
                }
                off += take
            }
            return true
        } finally {
            statQueueNs += System.nanoTime() - t0
        }
    }

    private fun finishInput(c: MediaCodec) {
        if (inputDone) return
        Log.i(tag, "输入到底：喂了 $feedLogged 帧（blkStart=$blkStart blkLen=$blkLen，" +
            "nextReadPos=$nextReadPos 文件 ${src.size} B）")
        inputDone = true
        val idx = c.dequeueInputBuffer(200_000)
        if (idx >= 0) c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    /** 合成 42 字节 fLaC + STREAMINFO（libFLAC 需要它才认流） */
    private fun syntheticHeader(): ByteArray {
        val h = ByteArray(42)
        "fLaC".toByteArray(Charsets.US_ASCII).copyInto(h, 0)
        h[4] = 0x80.toByte()          // last-metadata-block | type 0 (STREAMINFO)
        h[5] = 0; h[6] = 0; h[7] = 34
        info.streamInfo.copyInto(h, 8)
        return h
    }

    private fun ensureCodec(): MediaCodec {
        codec?.let { return it }
        val fmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC, info.rate, info.channels)
        fmt.setInteger("bits-per-sample", info.bits)
        fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(info.streamInfo))
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        c.configure(fmt, null, null, 0)
        c.start()
        codec = c
        Log.i(tag, "精确解码：请求 FLOAT 输出（源 ${info.bits}bit ${info.rate}Hz ${info.channels}ch，" +
            "预读 ${PREBUFFER / 1024 / 1024}MB，读块 ${READ_CHUNK / 1024}KB，帧对齐喂入）")
        return c
    }

    /** 按解码器实际位深转换到 24-in-32（float / 16bit / 24bit packed / 32bit int 兜底） */
    private fun convert(buf: ByteArray, n: Int, dst: ByteArray): Int = when {
        outEnc == AudioFormat.ENCODING_PCM_FLOAT -> convertFloat(buf, n, dst)
        CodecPcm.bytesPerSample(outEnc) == 2 -> {
            val samples = n / 2
            PcmUtils.upsample16(buf, 0, samples * 2, dst, 0)
            (samples / ch) * FRAME
        }
        CodecPcm.bytesPerSample(outEnc) == 3 -> {
            val samples = n / 3
            PcmUtils.upsample24(buf, 0, samples * 3, dst, 0)
            (samples / ch) * FRAME
        }
        else -> {
            val frames = n / 4 / ch
            val total = frames * FRAME
            System.arraycopy(buf, 0, dst, 0, total)
            total
        }
    }

    /** float32 → 24-in-32（左对齐 24bit）。手写位拼装，避开 ByteBuffer 逐样本开销 */
    private fun convertFloat(buf: ByteArray, n: Int, dst: ByteArray): Int {
        var s = 0
        var d = 0
        val limit = n - 3
        while (s < limit) {
            val bits = (buf[s].toInt() and 0xFF) or
                ((buf[s + 1].toInt() and 0xFF) shl 8) or
                ((buf[s + 2].toInt() and 0xFF) shl 16) or
                ((buf[s + 3].toInt() and 0xFF) shl 24)
            val f = Float.fromBits(bits)
            var x = (f * 8388607f).toInt()
            if (x > 8388607) x = 8388607 else if (x < -8388608) x = -8388608
            val v32 = x shl 8
            dst[d] = (v32 and 0xFF).toByte()
            dst[d + 1] = ((v32 shr 8) and 0xFF).toByte()
            dst[d + 2] = ((v32 shr 16) and 0xFF).toByte()
            dst[d + 3] = ((v32 shr 24) and 0xFF).toByte()
            s += 4; d += 4
        }
        return (d / FRAME) * FRAME
    }

    /** 把转换好的数据写进环形缓冲（满了就等，同时响应 seek / close） */
    private fun writeRing(n: Int) {
        var off = 0
        if (skipBytes > 0) {                       // seek 之后要丢掉的开头部分
            val k = minOf(skipBytes, n.toLong()).toInt()
            skipBytes -= k
            off += k
            if (off >= n) return
        }
        while (off < n) {
            var copied: Int
            lock.withLock {
                val tw = System.nanoTime()
                while (filled >= ring.size && !closed && seekTarget < 0) cond.await(100, TimeUnit.MILLISECONDS)
                statWaitNs += System.nanoTime() - tw
                if (closed || seekTarget >= 0) return
                val chunk = minOf(ring.size - filled, n - off, ring.size - wPos)
                System.arraycopy(outTmp, off, ring, wPos, chunk)
                wPos = (wPos + chunk) % ring.size
                filled += chunk
                produced += chunk
                copied = chunk
                cond.signalAll()
            }
            off += copied
            logThroughput()
        }
    }

    // ── ByteSource ───────────────────────────────────────────────────
    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        ensureWorker()
        lock.withLock {
            while (filled == 0) {
                if (closed) return 0
                val th = thread
                if (eof && (th == null || !th.isAlive)) return 0
                cond.await(200, TimeUnit.MILLISECONDS)
            }
            val n = minOf(len, filled)
            val first = minOf(n, ring.size - rPos)
            System.arraycopy(ring, rPos, dst, off, first)
            if (first < n) System.arraycopy(ring, 0, dst, off + first, n - first)
            rPos = (rPos + n) % ring.size
            filled -= n
            cond.signalAll()
            return n
        }
    }

    override fun seek(bytePos: Long): Boolean {
        ensureWorker()
        val target = bytePos / FRAME
        lock.withLock {
            seekTarget = target
            seekGen++
            val gen = seekGen
            seekResult = false
            cond.signalAll()
            val deadline = System.currentTimeMillis() + 4000
            while (seekAck < gen && !closed && System.currentTimeMillis() < deadline) cond.await(50, TimeUnit.MILLISECONDS)
            if (seekAck < gen) {
                Log.w(tag, "seek($bytePos) 超时（解码线程未应答）")
                return false
            }
            val before = filled
            rPos = 0; wPos = 0; filled = 0        // 丢掉缓冲里的旧位置数据
                Log.i(tag, "seek 完成：清缓冲 ${before / 1024}KB，丢弃 ${skipBytes / FRAME} 帧")
            cond.signalAll()
            return seekResult
        }
    }

    override fun close() {
        lock.withLock { closed = true; cond.signalAll() }
        thread?.let { t ->
            if (t !== Thread.currentThread()) runCatching { t.join(1500) }
        }
        thread = null
        releaseCodec()
        runCatching { r.close() }
        runCatching { src.close() }
    }
}
