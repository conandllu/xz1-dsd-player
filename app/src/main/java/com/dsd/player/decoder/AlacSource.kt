// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.RandomAccessFile

/**
 * MP4/M4A 容器（**Apple ALAC 无损** / AAC 有损）播放。
 *
 * 用途：用户自己拥有（iTunes Store 的 iTunes Plus 是**无 DRM** 的 ALAC，或自己 CD 抓轨转 ALAC）
 * 的文件，放进手机后与 FLAC/WAV 一样走现有 USB 独占链路，输出 24-bit-in-32bit。
 *
 * ⚠ 不能用来播放 Apple Music 流媒体：那些流用 FairPlay 加密，解密在系统 FairPlay 模块内
 * 完成，第三方拿不到 PCM，也不存在合法 API（MusicKit / Apple Music API 只给元数据）。
 *
 * 实现要点：
 * - 容器/解码都交给 MediaExtractor + MediaCodec（ALAC 的 magic cookie 在 csd-0）；
 * - 解码器输出位深以 `outputFormat` 的 `pcm-encoding` 为准（2=16bit / 21=24in32 / 22=32bit /
 *   4=float），统一转成 24-in-32；
 * - seek = extractor.seekTo(CLOSEST_SYNC) + codec.flush，再按"目标帧 − 同步帧"丢样本。
 */
class AlacSource(private val context: Context, uri: Uri) : AudioSource {

    companion object {
        private const val TAG = "AlacSource"
        /** ALAC 在 Android 里的 MIME 是 "audio/alac"（MediaFormat 没有公开常量） */
        private const val MIME_ALAC = "audio/alac"
        private val CONTAINERS = setOf("m4a", "mp4", "alac", "aac", "m4b")

        fun supports(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in CONTAINERS

        /**
         * 平台是否有 ALAC 解码器。AOSP **原生不带** ALAC 解码（`c2.android.alac.decoder`
         * 在多数镜像里不存在，模拟器实测 NAME_NOT_FOUND），只有部分 OEM ROM 才有。
         * 没有时我们给出明确提示，而不是抛一个看不懂的 NAME_NOT_FOUND。
         */
        fun hasPlatformDecoder(): Boolean = runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MIME_ALAC, true) }
            }
        }.getOrDefault(false)

        /**
         * 从 CSD-0（MP4 的 'alac' atom 或裸 24 字节配置）读 (channels, bitDepth)。
         * ALACSpecificConfig 是**大端**：frameLength(4) compatibleVersion(1) bitDepth(1)
         * pb(1) mb(1) kb(1) numChannels(1) maxRun(2) maxFrameBytes(4) avgBitRate(4) sampleRate(4)
         */
        private fun readCookie(f: MediaFormat): ByteArray? = runCatching {
            val bb = f.getByteBuffer("csd-0") ?: return null
            bb.rewind()
            val b = ByteArray(bb.remaining())
            bb.get(b)
            Log.i(TAG, "ALAC csd-0: ${b.size} 字节")
            b
        }.getOrNull()

        private fun parseAlacCookie(b: ByteArray?): Pair<Int, Int>? = runCatching {
            if (b == null || b.size < 24) return null
            val off = if (b.size >= 36 && String(b, 4, 4, Charsets.US_ASCII) == "alac") 12 else 0
            if (b.size < off + 24) return null
            val bitDepth = b[off + 5].toInt() and 0xFF
            val channels = b[off + 9].toInt() and 0xFF
            Log.i(TAG, "ALAC cookie: bitDepth=$bitDepth channels=$channels")
            channels to bitDepth
        }.getOrNull()

        /** MP4 家族：第 4 字节起是 "ftyp" */
        fun looksLikeMp4(path: String): Boolean = runCatching {
            RandomAccessFile(path, "r").use { f ->
                val head = ByteArray(12)
                f.seek(0)
                f.read(head) == 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
            }
        }.getOrDefault(false)
    }

    private var extractor: MediaExtractor? = null
    /** CSD-0 原始字节（**只在 init 读一次**）：MediaFormat.getByteBuffer 可能返回同一个
     *  ByteBuffer 实例，读一次后 position 就到末尾，再 get 会得到 0 字节
     *  → native 报「cookie too short (0)」（已在真机/模拟器上踩到） */
    private var cookieBytes: ByteArray? = null
    private var trackIndex = -1
    private var mime: String = MediaFormat.MIMETYPE_AUDIO_AAC
    private var format: MediaFormat? = null

    override val info: AudioInfo

    init {
        val path = uri.path ?: error("ALAC 需要文件路径")
        val ex = MediaExtractor()
        try {
            RandomAccessFile(path, "r").use { raf -> ex.setDataSource(raf.fd, 0, raf.length()) }
            var pick = -1
            var pickFmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("audio/")) continue
                pick = i; pickFmt = f
                if (m == MIME_ALAC) break   // 优先 ALAC
            }
            if (pick < 0 || pickFmt == null) error("M4A 里没有音频轨道")
            ex.selectTrack(pick)
            extractor = ex
            trackIndex = pick
            format = pickFmt
            mime = pickFmt.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_AAC
            if (mime == MIME_ALAC) cookieBytes = readCookie(pickFmt)
            val cfg = if (mime == MIME_ALAC) parseAlacCookie(cookieBytes) else null
            val rate = pickFmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val ch = cfg?.first ?: pickFmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            // ALAC 的真实位深/声道在 magic cookie（csd-0）里，pcm-encoding 只说明容器；
            // 不读 cookie 的话 24bit 素材会被显示成 16bit。
            val bits = cfg?.second ?: when (pickFmt.intOr(KEY_PCM_ENCODING, 0)) {
                2 -> 16
                21, 22 -> 24
                4 -> 32
                else -> 16
            }
            val durUs = if (pickFmt.containsKey(MediaFormat.KEY_DURATION))
                pickFmt.getLong(MediaFormat.KEY_DURATION) else 0L
            Log.i(TAG, "平台 ALAC 解码器：${if (hasPlatformDecoder()) "有" else "没有"}")
            info = AudioInfo(
                container = if (mime == MIME_ALAC) "ALAC" else "M4A/AAC",
                sampleRate = rate,
                channels = ch,
                bitsPerSample = bits,
                isDsd = false,
                dsdRateHz = 0,
                dsdLsbFirst = false,
                durationUs = durUs,
            )
            Log.i(TAG, "打开 ${path.substringAfterLast('/')}: mime=$mime ${rate}Hz ${ch}ch " +
                "${bits}bit dur=${durUs / 1000}ms csd0=${pickFmt.containsKey("csd-0")}")
        } catch (e: Throwable) {
            runCatching { ex.release() }
            throw IllegalStateException("不是可播放的 M4A/ALAC：${e.message}", e)
        }
    }

    override fun openPcm(): ByteSource {
        val ex = extractor ?: error("提取器已关闭")
        val f = format!!
        if (mime == MIME_ALAC) {
            if (hasPlatformDecoder()) {
                Log.i(TAG, "用平台 ALAC 解码器（MediaCodec）")
                return Mp4ByteSource(ex, f, mime, info)
            }
            // AOSP 没有 audio/alac：用内置 Apple ALACDecoder（Apache-2.0）自己解
            val bytes = cookieBytes ?: error("ALAC 缺少 csd-0（magic cookie）")
            Log.i(TAG, "平台无 ALAC 解码器 → 使用内置解码器（cookie ${bytes.size}B）")
            return AlacNativeByteSource(ex, bytes, info)
        }
        return Mp4ByteSource(ex, f, mime, info)
    }

    override fun openDsd(): ByteSource = throw IllegalStateException("PCM 源无 DSD 流")

    override fun close() {
        runCatching { extractor?.release() }
        extractor = null
    }
}

private const val KEY_PCM_ENCODING = "pcm-encoding"

private fun MediaFormat.intOr(key: String, def: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

/**
 * MP4 音频 → 24-in-32 交织字节流。
 */
private class Mp4ByteSource(
    private val ex: MediaExtractor,
    private val format: MediaFormat,
    private val mime: String,
    private val info: AudioInfo,
) : ByteSource {

    companion object {
        private const val TAG = "Mp4ByteSource"
        private const val FRAME_BYTES = PcmUtils.OUT_BYTES_PER_FRAME    // 8 = 2ch × 4B
    }

    private var codec: MediaCodec? = null
    private val bufInfo = MediaCodec.BufferInfo()
    private val raw = ByteArray(256 * 1024)
    private val out = ByteArray(768 * 1024)     // 512KB 输入可膨胀成 4 倍，留足
    private var outPos = 0
    private var outEnd = 0
    private var inputDone = false
    private var outputDone = false
    private var started = false

    /** 解码器输出位深：0=未知（**按 16bit** 兜底），收到 INFO_OUTPUT_FORMAT_CHANGED 后确定 */
    private var enc = 0

    /** 位深自检：产出字节数必须与解码器 pts 成正比（比例≈2 就是 16bit 被当 32bit） */
    private val encCheck = CodecPcm.SelfCheck(TAG, info.sampleRate, FRAME_BYTES)

    /** seek 后要丢弃的字节数（对齐到帧） */
    private var skipBytes = 0L

    private val ch get() = info.channels.coerceAtLeast(1)

    override val lengthBytes: Long
        get() = if (info.durationUs > 0)
            info.durationUs * info.sampleRate * FRAME_BYTES / 1_000_000 else -1

    private fun ensureCodec() {
        if (started) return
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, null, null, 0)
        c.start()
        codec = c
        started = true
    }

    /** 把解码器输出转换后放进 out[]；返回是否产生了数据 */
    private fun pumpOnce(): Boolean {
        ensureCodec()
        val c = codec ?: return false
        // 先喂输入
        if (!inputDone) {
            val inIdx = c.dequeueInputBuffer(0)
            if (inIdx >= 0) {
                val ib = c.getInputBuffer(inIdx)
                if (ib == null) return false
                val sz = ex.readSampleData(ib, 0)
                if (sz < 0) {
                    c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    c.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                    ex.advance()
                }
            }
        }
        val idx = c.dequeueOutputBuffer(bufInfo, 8_000)
        when {
            idx >= 0 -> {
                val buf = c.getOutputBuffer(idx)
                var produced = 0
                if (buf != null && bufInfo.size > 0) {
                    val n = minOf(bufInfo.size, raw.size)
                    buf.position(bufInfo.offset)
                    buf.limit(bufInfo.offset + n)
                    buf.get(raw, 0, n)
                    produced = convert(raw, n)
                    encCheck.feed(produced, bufInfo.presentationTimeUs)
                }
                c.releaseOutputBuffer(idx, false)
                if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                if (produced > 0) {
                    outPos = 0
                    outEnd = produced
                    applySeekSkip()
                    if (outEnd > 0) return true
                }
                return false
            }
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                enc = CodecPcm.read(c.outputFormat)
                Log.i(TAG, "解码输出格式 pcm-encoding=${CodecPcm.describe(enc)}" +
                    "（${info.sampleRate}Hz ${ch}ch）")
                if (enc == 0) Log.w(TAG, "解码器未报位深 → 按 16bit 处理（MediaCodec 规范默认）；" +
                    "outputFormat=${c.outputFormat}")
                encCheck.onFormat(enc)
                return false
            }
            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                if (inputDone) outputDone = true
                return false
            }
            else -> return false
        }
    }

    /** seek 后丢弃，直到跳过 skipBytes */
    private fun applySeekSkip() {
        if (skipBytes <= 0) return
        val n = minOf(skipBytes, (outEnd - outPos).toLong()).toInt()
        outPos += n
        skipBytes -= n
        if (outPos >= outEnd) { outPos = 0; outEnd = 0 }
    }

    /**
     * 解码器 PCM → 24-in-32 交织，返回写入 out[] 的字节数。
     *
     * 位深只认 [CodecPcm]：`pcm-encoding` / `android._config-pcm-encoding`，读不到按
     * 16bit。**绝不能靠"能不能被 ch*4 整除"猜**——16bit 数据块通常也是 8 的倍数，
     * 猜成 32bit 就会写出一倍多余的字节，表现为 2 倍速（第九十七轮 m4a 事故根因）。
     */
    private fun convert(src: ByteArray, n: Int): Int {
        when (CodecPcm.bytesPerSample(enc)) {
            3 -> {                      // 24bit packed（少见）→ 左移 8
                val samples = n / 3
                PcmUtils.upsample24(src, 0, samples * 3, out, 0)
                return (samples / ch) * FRAME_BYTES
            }
            4 -> return convert32(src, n)
            else -> {                   // 16bit → s16 shl 16（左对齐进 32bit 槽）
                val samples = n / 2
                PcmUtils.upsample16(src, 0, samples * 2, out, 0)
                return (samples / ch) * FRAME_BYTES
            }
        }
    }

    /** 32bit：整数直通、浮点按 [-1,1] 归一化转整型 */
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
            return (d / FRAME_BYTES) * FRAME_BYTES
        }
        // 32bit 整数（含 24-in-32）：直通
        val frames = n / 4 / ch
        val total = frames * FRAME_BYTES
        System.arraycopy(src, 0, out, 0, total)
        return total
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        var guard = 0
        while (outPos >= outEnd) {
            if (outputDone) return 0
            if (!pumpOnce() && outputDone) return 0
            if (++guard > 4000) return 0      // 保险：解码器卡住时不要死循环
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
        codec?.let { c ->
            runCatching { c.flush() }
        }
        inputDone = false
        outputDone = false
        outPos = 0; outEnd = 0
        Log.i(TAG, "seek → ${frame} 帧（同步点 ${syncFrames} 帧，需丢弃 ${skipBytes / FRAME_BYTES} 帧）")
        true
    }.getOrDefault(false)

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { ex.release() }
    }
}

/**
 * 内置 Apple ALACDecoder 的字节流：MediaExtractor 只负责拆包（packet），
 * 解码交给 native（见 cpp/alac + cpp/native/alac_jni.cpp）。
 *
 * 输出位深由 cookie 里的 bitDepth 决定：16 → 提升到 24-in-32；24 → 左移 8 位；
 * 32 → 直通。所有路径最终都是 24-bit-in-32 交织，供 DoP/USB 复用。
 */
private class AlacNativeByteSource(
    private val ex: MediaExtractor,
    cookie: ByteArray,
    private val info: AudioInfo,
) : ByteSource {

    companion object {
        private const val TAG = "AlacNative"
        private const val FRAME_BYTES = PcmUtils.OUT_BYTES_PER_FRAME
    }

    private val handle: Long = com.dsd.player.usb.NativeBridge.nativeAlacOpen(cookie)
    private var bitDepth = 16
    private var frameLength = 4096
    private val ch get() = info.channels.coerceAtLeast(1)

    private val packet = ByteArray(512 * 1024)
    private val pcm = ByteArray(1024 * 1024)
    private val out = ByteArray(2048 * 1024)
    private var outPos = 0
    private var outEnd = 0
    private var eof = false
    private var skipBytes = 0L

    init {
        if (handle == 0L) throw IllegalStateException("ALAC 解码器初始化失败（cookie 异常）")
        val inf = com.dsd.player.usb.NativeBridge.nativeAlacInfo(handle)
        if (inf.size >= 2) {
            frameLength = inf[0]
            bitDepth = inf[1]
        }
        Log.i(TAG, "内置 ALAC 解码器：frameLength=$frameLength bitDepth=$bitDepth " +
            "ch=${inf.getOrNull(2)} rate=${inf.getOrNull(3)}")
    }

    override val lengthBytes: Long
        get() = if (info.durationUs > 0)
            info.durationUs * info.sampleRate * FRAME_BYTES / 1_000_000 else -1

    /** 解下一个 packet，返回写入 out 的字节数 */
    private fun decodeNext(): Int {
        val size = ex.readSampleData(java.nio.ByteBuffer.wrap(packet), 0)
        if (size < 0) { eof = true; return 0 }
        val n = com.dsd.player.usb.NativeBridge.nativeAlacDecode(handle, packet, size, pcm, pcm.size)
        ex.advance()
        if (n <= 0) { eof = true; return 0 }
        val written = when (bitDepth) {
            16 -> {
                val samples = n / 2
                PcmUtils.upsample16(pcm, 0, samples * 2, out, 0)
                (samples / ch) * FRAME_BYTES
            }
            24 -> {
                val samples = n / 3
                PcmUtils.upsample24(pcm, 0, samples * 3, out, 0)
                (samples / ch) * FRAME_BYTES
            }
            else -> {
                // 20bit（3 字节）也按 24bit 处理；32bit 直通
                if (n % 4 == 0) {
                    System.arraycopy(pcm, 0, out, 0, n)
                    n
                } else {
                    val samples = n / 3
                    PcmUtils.upsample24(pcm, 0, samples * 3, out, 0)
                    (samples / ch) * FRAME_BYTES
                }
            }
        }
        // seek 后丢弃
        if (skipBytes > 0) {
            val k = minOf(skipBytes, written.toLong()).toInt()
            skipBytes -= k
            return written - k
        }
        return written
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        var guard = 0
        while (outPos >= outEnd) {
            if (eof) return 0
            val w = decodeNext()
            if (w <= 0) { if (eof) return 0; if (++guard > 2000) return 0; continue }
            // decodeNext 可能因为 skip 返回 0 字节（此时 out 里的前 skip 段要丢掉）
            if (skipBytes > 0) { outPos = 0; outEnd = 0; continue }
            outPos = 0; outEnd = w
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
        outPos = 0; outEnd = 0; eof = false
        Log.i(TAG, "seek → $frame 帧（同步点 $syncFrames，丢弃 ${skipBytes / FRAME_BYTES} 帧）")
        true
    }.getOrDefault(false)

    override fun close() {
        if (handle != 0L) com.dsd.player.usb.NativeBridge.nativeAlacClose(handle)
        runCatching { ex.release() }
    }
}
