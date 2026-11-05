// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/**
 * 网络 FLAC（Qobuz 直链 / 任意 http(s) FLAC）→ 24-in-32 交织，走现有 USB 独占链路。
 *
 * 与本地 FlacSource 的区别：数据源换成 [HttpMediaDataSource]（Range 随机读），
 * 所以 seek/拖动进度条也成立；解码仍然用平台 MediaCodec 的 FLAC 解码器。
 */
class HttpFlacSource(
    private val context: Context,
    private val url: String,
    /** WebDAV 需要 Basic 认证头；Qobuz 直链为空 */
    private val headers: Map<String, String> = emptyMap(),
) : AudioSource {

    companion object { private const val TAG = "HttpFlac" }

    private var dataSource: HttpMediaDataSource? = null
    private var extractor: MediaExtractor? = null
    private var format: MediaFormat? = null
    private var mime: String = MediaFormat.MIMETYPE_AUDIO_FLAC

    /** 非 null = 标准 FLAC 流，走"自研切帧 + float 解码"，**完全不碰 MediaExtractor** */
    private var flacInfo: FlacInfo? = null

    /** 已打开的字节流（需要显式关闭） */
    private var floatBs: ByteSource? = null

    override val info: AudioInfo

    init {
        try {
            val ds = HttpMediaDataSource(url, headers = headers)
            dataSource = ds
            val head = ByteArray(4)
            val n = runCatching { ds.readAt(0, head, 0, 4) }.getOrDefault(-1)
            val magic = if (n == 4) String(head, Charsets.US_ASCII) else "?"
            if (magic == "fLaC") {
                // 标准 FLAC：只读 STREAMINFO 就够（位深/声道/采样率/总样本都在里面）。
                // 以前这里用 MediaExtractor 探测，会让平台 FLACDecoder 对着"不完整的探测数据"
                // 报一串 `no streaminfo metadata block`，还会白解一遍（真机日志噪音来源）。
                val fi = FlacFrameReader(DsRandomSource(ds)).use { it.info }
                flacInfo = fi
                info = AudioInfo(
                    container = "FLAC(stream)", sampleRate = fi.rate, channels = fi.channels,
                    bitsPerSample = fi.bits, isDsd = false, dsdRateHz = 0, dsdLsbFirst = false,
                    durationUs = if (fi.rate > 0) fi.totalSamples * 1_000_000L / fi.rate else 0L,
                )
                Log.i(TAG, "FLAC 流（自研切帧，跳过 MediaExtractor）：${fi.rate}Hz ${fi.channels}ch " +
                    "${fi.bits}bit dur=${info.durationUs / 1000}ms size=${ds.getSize() / 1024}KB")
            } else {
                info = initExtractor(ds, magic)
            }
        } catch (e: Throwable) {
            runCatching { extractor?.release() }
            runCatching { dataSource?.close() }
            throw IllegalStateException("网络 FLAC 打开失败：${e.message}", e)
        }
    }

    /**
     * 非 FLAC（m4a/aac 等）或魔数异常时的老路径：MediaExtractor 探轨道 + MediaCodec 解码。
     * 标准 FLAC 流不会走到这里；自研路径失败时也会回退到这里（见 [openPcm]）。
     */
    private fun initExtractor(ds: HttpMediaDataSource, magic: String): AudioInfo {
        val ex = MediaExtractor()
        ex.setDataSource(ds)
        var pick = -1
        var pickFmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!m.startsWith("audio/")) continue
            pick = i; pickFmt = f; break
        }
        if (pick < 0 || pickFmt == null) error("流里没有音频轨道（不是 FLAC？）")
        ex.selectTrack(pick)
        extractor = ex
        format = pickFmt
        mime = pickFmt.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_FLAC
        val rate = if (pickFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            pickFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val ch = if (pickFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            pickFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        // FLAC 的位深在 STREAMINFO 里，MediaFormat 一般不给 → 默认 16，解码器输出会纠正
        val bits = if (pickFmt.containsKey("bits-per-sample"))
            pickFmt.getInteger("bits-per-sample") else 16
        val durUs = if (pickFmt.containsKey(MediaFormat.KEY_DURATION))
            pickFmt.getLong(MediaFormat.KEY_DURATION) else 0L
        Log.i(TAG, "头部魔数=$magic → 走 MediaExtractor：mime=$mime ${rate}Hz ${ch}ch ${bits}bit " +
            "dur=${durUs / 1000}ms size=${ds.getSize() / 1024}KB csd0=${pickFmt.containsKey("csd-0")}")
        return AudioInfo(
            container = "FLAC(stream)", sampleRate = rate, channels = ch,
            bitsPerSample = bits, isDsd = false, dsdRateHz = 0, dsdLsbFirst = false,
            durationUs = durUs,
        )
    }

    override fun openPcm(): ByteSource {
        val ds = dataSource
        // 标准 FLAC：**任何位深**都走自研切帧 + float 解码
        //  - 24bit：唯一能拿到真 24bit 的办法（平台 extractor 会降到 16bit）
        //  - 16bit：float 解码后缩放到 24-in-32，低 8 位为 0，与以前 upsample16 的结果一致
        if (ds != null && flacInfo != null) {
            runCatching { floatBs?.close() }
            floatBs = null
            runCatching { openFlac(ds) }.onSuccess { return it }
                .onFailure { Log.w(TAG, "自研 FLAC 路径失败，回退 MediaExtractor：${it.message}") }
        }
        return try {
            if (extractor == null && ds != null) initExtractor(ds, "fallback")   // 只为建立 extractor
            StreamByteSource(extractor ?: error("流已关闭"), format!!, info, mime)
        } catch (t: Throwable) {
            throw IllegalStateException("网络 FLAC 打开失败：${t.message}", t)
        }
    }

    /** 自研切帧 → 平台解码器（要求 float 输出）→ 24-in-32 */
    private fun openFlac(ds: HttpMediaDataSource): ByteSource {
        val rs = DsRandomSource(ds)
        val r = FlacFrameReader(rs)
        val bs = FlacFloatByteSource(r, DsRandomSource(ds), TAG)
        floatBs = bs
        Log.i(TAG, "自研 FLAC 解码：源 ${r.info.bits}bit ${r.info.rate}Hz ${r.info.channels}ch " +
            "总样本 ${r.info.totalSamples}（float 输出 → 24-in-32）")
        return bs
    }

    override fun openDsd(): ByteSource = throw IllegalStateException("PCM 源无 DSD 流")

    override fun close() {
        runCatching { floatBs?.close() }
        floatBs = null
        runCatching { extractor?.release() }
        runCatching { dataSource?.close() }
        extractor = null
        dataSource = null
    }
}

/**
 * FLAC 流 → 24-in-32。
 *
 * 供给方式必须**闻一下首样本**：AOSP 的 FLAC extractor 在部分版本/镜像上把轨道报成
 * `audio/raw` 且不给 csd-0，此时 `readSampleData` 拿到的可能已经是**解码后的 PCM**
 * （真机上就踩过，见 docs/DEV-NOTES.md）。判据：FLAC 帧头是 0xFF + 0xF8..0xFB。
 *  - 是压缩帧 → MediaCodec FLAC 解码器（格式自己建，带/不带 csd-0 都行）
 *  - 已是 PCM → 直接按 16bit 提升到 24-in-32
 */
private class StreamByteSource(
    private val ex: MediaExtractor,
    private val format: MediaFormat,
    private val info: AudioInfo,
    /** 提取器报的轨道 mime（audio/flac、audio/raw、audio/mpeg…） */
    private val mime: String,
) : ByteSource {

    companion object {
        private const val TAG = "StreamByteSource"
        private const val FRAME_BYTES = PcmUtils.OUT_BYTES_PER_FRAME
    }

    private enum class Supply { UNKNOWN, CODEC, RAW_PCM }

    private var supply = Supply.UNKNOWN
    private var codec: MediaCodec? = null
    private val bufInfo = MediaCodec.BufferInfo()
    private val raw = ByteArray(256 * 1024)
    private val out = ByteArray(768 * 1024)
    private var outPos = 0
    private var outEnd = 0
    private var inputDone = false
    private var outputDone = false
    private var enc = 0
    /** 位深自检：产出字节 / 解码器 pts 的比例≈2 就是 16bit 被当 32bit（2 倍速） */
    private val encCheck = CodecPcm.SelfCheck(TAG, info.sampleRate, FRAME_BYTES)
    /** RAW_PCM 时每样本字节数（2/3/4）。extractor 可能已经替我们把 FLAC 解成 PCM，
     *  这时只能靠"帧长 ÷ 通道数 ÷ 时长"反推，猜错会变成 2 倍速噪声。 */
    private var pcmBytes = 0
    private val hold = ByteArray(192 * 1024)
    private var holdLen = 0
    private var skipBytes = 0L
    private var eof = false

    private val ch get() = info.channels.coerceAtLeast(1)

    override val lengthBytes: Long
        get() = if (info.durationUs > 0)
            info.durationUs * info.sampleRate * FRAME_BYTES / 1_000_000 else -1

    /** 有效位深：RAW_PCM 由实测的每样本字节数决定；CODEC 由解码器报的位深决定 */
    override val outputBits: Int
        get() = when {
            supply == Supply.RAW_PCM && pcmBytes > 0 -> pcmBytes * 8
            supply == Supply.CODEC && enc != 0 -> CodecPcm.bytesPerSample(enc) * 8
            else -> 0
        }

    private fun ensureCodec() {
        codec?.let { return }
        if (mime != MediaFormat.MIMETYPE_AUDIO_FLAC) {
            // MP3/AAC 等：提取器给的 format 里已有 csd-0 与正确的 mime，直接用
            val ext = format
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(ext, null, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "解码器就绪（通用）：$mime ${info.sampleRate}Hz ${info.channels}ch")
            return
        }
        val f = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC, info.sampleRate, info.channels)
        f.setInteger("bits-per-sample", info.bitsPerSample)
        format.getByteBuffer("csd-0")?.let { csd ->
            csd.rewind()
            val copy = ByteArray(csd.remaining())
            csd.get(copy)
            f.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(copy))
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        c.configure(f, null, null, 0)
        c.start()
        codec = c
        Log.i(TAG, "FLAC 解码器就绪 ${info.sampleRate}Hz ${info.channels}ch " +
            "csd0=${f.containsKey("csd-0")}（extractor mime=${format.getString(MediaFormat.KEY_MIME)}）")
    }

    /** 读一个样本到 raw[]，返回长度（<0 = EOF） */
    private fun readSample(): Int = ex.readSampleData(java.nio.ByteBuffer.wrap(raw), 0)

    /**
     * 判定供给方式（只做一次）。
     *
     * WebDAV 流里可能是 MP3/WAV/AAC 等，不能只看 FLAC 同步字：
     *  - mime=audio/raw → 提取器已解码成 PCM（走 RAW_PCM，位深靠时间戳校准）
     *  - mime=audio/flac 或嗅到 FLAC 帧 → CODEC（自己建 MediaFormat）
     *  - 其它 audio 前缀的压缩格式（mpeg / aac / mp4a-latm / opus…）→ CODEC（直接用提取器的 format）
     */
    private fun decide(firstLen: Int) {
        val b0 = raw[0].toInt() and 0xFF
        val b1 = if (firstLen > 1) raw[1].toInt() and 0xFF else 0
        val looksFlacFrame = b0 == 0xFF && (b1 and 0xFE) == 0xF8
        val mimeIsRaw = mime == MediaFormat.MIMETYPE_AUDIO_RAW
        if (!mimeIsRaw && !looksFlacFrame && mime.startsWith("audio/")) {
            // 压缩音频但不是 FLAC（MP3/AAC…）：仍然走解码器
            supply = Supply.CODEC
            Log.i(TAG, "供给方式=CODEC（mime=$mime）")
            ensureCodec()
            return
        }
        supply = if (looksFlacFrame) Supply.CODEC else Supply.RAW_PCM
        if (supply == Supply.CODEC) {
            Log.i(TAG, "供给方式=CODEC（首样本 $firstLen B 是 FLAC 帧 0x${b0.toString(16)}${b1.toString(16)}）")
            ensureCodec()
        } else {
            pcmBytes = detectPcmBytes()
            val downgraded = pcmBytes == 2 && info.bitsPerSample > 16
            Log.i(TAG, "供给方式=RAW_PCM（首样本 $firstLen B，b0=0x${b0.toString(16)} b1=0x${b1.toString(16)}）" +
                " pcmBytes=$pcmBytes extractor报=${info.bitsPerSample}bit 有效=${pcmBytes * 8}bit" +
                (if (downgraded) " ⚠被平台降位成 16bit（低 ${info.bitsPerSample - 16} 位恒 0，" +
                    "DAC 面板显 24bit 但内容是 16bit）" else ""))
        }
    }

    /**
     * 猜 RAW_PCM 的每样本字节数。
     *
     * 只知道长度是不够的：18432 B 既可能是 4608 帧 16bit，也可能是 2304 帧 32bit。
     * 可靠的判据是 extractor 给的时间戳 —— 读第二帧，用 Δt 与
     * 帧数 × 1e6 / 采样率 比对，误差最小的就是真实位深。
     */
    private fun detectPcmBytes(): Int {
        val declared = if (format.containsKey("pcm-encoding"))
            runCatching { format.getInteger("pcm-encoding") }.getOrDefault(0) else 0
        when (declared) {
            2 -> return 2
            21, 22, 4 -> return if (declared == 4) 4 else if (declared == 21) 3 else 4
        }
        val t1 = ex.sampleTime
        val n2 = ex.readSampleData(java.nio.ByteBuffer.wrap(hold), 0)
        if (n2 > 0) {
            holdLen = n2
            val dUs = ex.sampleTime - t1
            ex.advance()
            if (dUs > 0 && info.sampleRate > 0) {
                var best = 0
                var bestErr = Long.MAX_VALUE
                for (bps in intArrayOf(2, 3, 4)) {
                    if (n2 % (ch * bps) != 0) continue
                    val frames = n2 / ch / bps
                    val err = kotlin.math.abs(frames * 1_000_000L / info.sampleRate - dUs)
                    if (err < bestErr) { bestErr = err; best = bps }
                }
                if (best > 0) {
                    Log.i(TAG, "校准每样本字节数=$best（帧 $n2 B，Δt=$dUs µs @ ${info.sampleRate}Hz）")
                    return best
                }
            }
        }
        // 兜底：优先 16bit（extractor 自解码最常见），能整除 4 字节也不改判
        return 2
    }

    /** 取下一段 24-in-32 数据放进 out[]，返回字节数（0 表示暂时没数据，-1 EOF） */
    private fun pump(): Int {
        if (eof) return -1
        if (supply == Supply.UNKNOWN) {
            val n = readSample()
            if (n < 0) { eof = true; return -1 }
            if (n == 0) { ex.advance(); return 0 }
            decide(n)
            if (supply == Supply.RAW_PCM) {
                val produced = convertPcm(raw, n)
                ex.advance()
                return produced
            }
            // CODEC：把刚读到的这一帧送进解码器
            feedCurrent(n)
        } else if (supply == Supply.RAW_PCM) {
            if (holdLen > 0) {
                val n = holdLen
                holdLen = 0
                return convertPcm(hold, n)
            }
            val n = readSample()
            if (n < 0) { eof = true; return -1 }
            if (n == 0) { ex.advance(); return 0 }
            val produced = convertPcm(raw, n)
            ex.advance()
            return produced
        } else {
            feedNext()
        }
        return drainDecoder()
    }

    private fun feedCurrent(sampleLen: Int) {
        val c = codec ?: return
        val inIdx = c.dequeueInputBuffer(0)
        if (inIdx < 0) return
        val ib = c.getInputBuffer(inIdx) ?: return
        ib.clear()
        ib.put(raw, 0, sampleLen)
        c.queueInputBuffer(inIdx, 0, sampleLen, ex.sampleTime, 0)
        ex.advance()
    }

    private fun feedNext() {
        val c = codec ?: return
        if (inputDone) return
        val inIdx = c.dequeueInputBuffer(0)
        if (inIdx < 0) return
        val ib = c.getInputBuffer(inIdx)
        if (ib == null) return
        val sz = ex.readSampleData(ib, 0)
        if (sz < 0) {
            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
            return
        }
        c.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
        ex.advance()
    }

    private fun drainDecoder(): Int {
        val c = codec ?: return 0
        when (val idx = c.dequeueOutputBuffer(bufInfo, 8_000)) {
            in 0..Int.MAX_VALUE -> {
                val buf = c.getOutputBuffer(idx)
                var produced = 0
                if (buf != null && bufInfo.size > 0) {
                    val n = minOf(bufInfo.size, raw.size)
                    buf.position(bufInfo.offset)
                    buf.limit(bufInfo.offset + n)
                    buf.get(raw, 0, n)
                    produced = convert(raw, n)
                    encCheck.feed(produced, bufInfo.presentationTimeUs)
                    if (produced > 0) applySeekSkip()
                }
                c.releaseOutputBuffer(idx, false)
                if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eof = true
                return produced
            }
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // codec2 的位深只写在 android._config-pcm-encoding（第九十七轮真机现象：
                // 只读 pcm-encoding 得到 0 → 16bit 被当 32bit → 2 倍速外星人声）
                enc = CodecPcm.read(c.outputFormat)
                Log.i(TAG, "解码输出 encoding=${CodecPcm.describe(enc)} → 转 24in32（每帧 ${FRAME_BYTES}B）")
                if (enc == 0) Log.w(TAG, "解码器未报位深 → 按 16bit 处理（MediaCodec 规范默认）；" +
                    "outputFormat=${c.outputFormat}")
                return 0
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                if (inputDone) eof = true
                return 0
            }
            else -> return 0
        }
    }

    private fun applySeekSkip() {
        if (skipBytes <= 0) return
        val n = minOf(skipBytes, outEnd.toLong()).toInt()
        outPos += n
        skipBytes -= n
        if (outPos >= outEnd) { outPos = 0; outEnd = 0 }
    }

    /** RAW_PCM：按实测的每样本字节数补到 24-in-32 */
    private fun convertPcm(src: ByteArray, n: Int): Int {
        when (pcmBytes) {
            3 -> {
                val samples = n / 3
                PcmUtils.upsample24(src, 0, samples * 3, out, 0)
                outPos = 0; outEnd = (samples / ch) * FRAME_BYTES
            }
            4 -> {
                val frames = n / 4 / ch
                outPos = 0; outEnd = frames * FRAME_BYTES
                System.arraycopy(src, 0, out, 0, outEnd)
            }
            else -> {
                val samples = n / 2
                PcmUtils.upsample16(src, 0, samples * 2, out, 0)
                outPos = 0; outEnd = (samples / ch) * FRAME_BYTES
            }
        }
        return outEnd
    }

    /**
     * 解码器 PCM → 24-in-32 交织。
     *
     * 位深只认 [CodecPcm]（`pcm-encoding` / `android._config-pcm-encoding`），读不到按
     * 16bit。**不要再用"能否被 ch*4 整除"来猜**：16bit 块本身也常是 8 的倍数，猜成
     * 32bit 会多写一倍字节 → 2 倍速 + 音调升八度（m4a 事故，第九十七轮）。
     */
    private fun convert(src: ByteArray, n: Int): Int {
        when (CodecPcm.bytesPerSample(enc)) {
            3 -> {                              // 24bit packed → 左移 8
                val samples = n / 3
                PcmUtils.upsample24(src, 0, samples * 3, out, 0)
                outPos = 0
                outEnd = (samples / ch) * FRAME_BYTES
            }
            4 -> outEnd = convert32(src, n)
            else -> {                           // 16bit → s16 shl 16
                val samples = n / 2
                PcmUtils.upsample16(src, 0, samples * 2, out, 0)
                outPos = 0
                outEnd = (samples / ch) * FRAME_BYTES
            }
        }
        return outEnd
    }

    /** 32bit：整数直通、浮点归一化转整型 */
    private fun convert32(src: ByteArray, n: Int): Int {
        if (CodecPcm.isFloat(enc)) {
            var s = 0; var d = 0
            while (s + 4 <= n) {
                val bits = (src[s].toInt() and 0xFF) or
                    ((src[s + 1].toInt() and 0xFF) shl 8) or
                    ((src[s + 2].toInt() and 0xFF) shl 16) or
                    ((src[s + 3].toInt() and 0xFF) shl 24)
                val v = (Float.fromBits(bits).coerceIn(-1f, 1f) * 2147483520f).toInt()
                out[d] = (v and 0xFF).toByte()
                out[d + 1] = ((v shr 8) and 0xFF).toByte()
                out[d + 2] = ((v shr 16) and 0xFF).toByte()
                out[d + 3] = ((v shr 24) and 0xFF).toByte()
                s += 4; d += 4
            }
            outPos = 0
            return (d / FRAME_BYTES) * FRAME_BYTES
        }
        val frames = n / 4 / ch
        val total = frames * FRAME_BYTES
        System.arraycopy(src, 0, out, 0, total)
        outPos = 0
        return total
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        var guard = 0
        while (outPos >= outEnd) {
            if (eof) return 0
            val produced = pump()
            if (produced <= 0) {
                if (eof) return 0
                if (++guard > 6000) return 0
                continue
            }
            outPos = 0
            outEnd = produced
            if (outPos >= outEnd) { outPos = 0; outEnd = 0; continue }
        }
        val n = minOf(len, outEnd - outPos)
        System.arraycopy(out, outPos, dst, off, n)
        outPos += n
        if (outPos >= outEnd) { outPos = 0; outEnd = 0 }
        return n
    }

    override fun seek(bytePos: Long): Boolean = runCatching {
        val frame = bytePos / FRAME_BYTES
        val us = frame * 1_000_000 / info.sampleRate
        ex.seekTo(us, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val syncFrames = ex.sampleTime * info.sampleRate / 1_000_000
        skipBytes = ((frame - syncFrames).coerceAtLeast(0)) * FRAME_BYTES
        codec?.let { runCatching { it.flush() } }
        holdLen = 0
        inputDone = false
        eof = false
        outPos = 0; outEnd = 0
        true
    }.getOrDefault(false)

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { ex.release() }
    }
}
