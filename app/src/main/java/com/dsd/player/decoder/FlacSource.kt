// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * PCM 统一输出格式：24-bit-in-32bit-LE，立体声交织（L32 R32 ...）。
 * 这样 DoP 打包 / UAC2 32-bit PCM 帧都直接可用。
 */
object PcmUtils {
    const val OUT_BYTES_PER_FRAME = 8 // 2ch × 4B

    /** 16-bit → 24-in-32（<<16） */
    fun upsample16(src: ByteArray, srcOff: Int, n: Int, dst: ByteArray, dstOff: Int) {
        var s = srcOff; var d = dstOff
        val end = srcOff + n
        while (s < end) {
            val v = (src[s].toInt() and 0xFF) or (src[s + 1].toInt() shl 8)
            val v32 = (v.toShort().toInt() shl 16) and -256
            dst[d] = (v32 and 0xFF).toByte()
            dst[d + 1] = ((v32 shr 8) and 0xFF).toByte()
            dst[d + 2] = ((v32 shr 16) and 0xFF).toByte()
            dst[d + 3] = ((v32 shr 24) and 0xFF).toByte()
            s += 2; d += 4
        }
    }

    /** 24-bit LE → 32-bit LE（<<8） */
    fun upsample24(src: ByteArray, srcOff: Int, n: Int, dst: ByteArray, dstOff: Int) {
        var s = srcOff; var d = dstOff
        val end = srcOff + n
        while (s < end) {
            val v = (src[s].toInt() and 0xFF) or
                ((src[s + 1].toInt() and 0xFF) shl 8) or
                ((src[s + 2].toInt() and 0xFF) shl 16)
            val v32 = (v shl 8)
            dst[d] = (v32 and 0xFF).toByte()
            dst[d + 1] = ((v32 shr 8) and 0xFF).toByte()
            dst[d + 2] = ((v32 shr 16) and 0xFF).toByte()
            dst[d + 3] = ((v32 shr 24) and 0xFF).toByte()
            s += 3; d += 4
        }
    }

    /** 32-bit 直通（复制），src/dst 都是 32-bit LE 交织 */
    fun copy32(src: ByteArray, srcOff: Int, n: Int, dst: ByteArray, dstOff: Int) {
        System.arraycopy(src, srcOff, dst, dstOff, n)
    }
}

/** 我们自己解析出来的 FLAC 头部信息（不依赖 MediaExtractor 的 mime）。 */
data class FlacHeader(
    val rate: Int,
    val channels: Int,
    val bits: Int,
    val totalSamples: Long,
    val firstFrameOffset: Long, // 音频帧起点（跳过全部 metadata block）
    val streamInfo: ByteArray,  // 34 字节 STREAMINFO（用作 MediaCodec csd-0）
)

/**
 * 提取器到底给的是"压缩帧"还是"已解码 PCM"——真机实测两种都有（mime 可能是 audio/raw），
 * 所以开工前嗅一下首样本：FLAC 帧头是 0xFF 后跟 0xF8..0xFB（sync + reserved + blocking）。
 */
enum class FlacSupply { CODEC, RAW_PCM }

/**
 * FLAC 解码：容器头由我们自己解析（"fLaC" 魔数 + STREAMINFO），
 * 解码用平台 MediaCodec。**不再用 MediaExtractor 报告的 mime 判断格式** ——
 * 真机上 FLAC 轨道可能被报成 audio/raw（见 docs/DEV-NOTES.md）。
 */
class FlacSource(private val context: Context, uri: Uri) : AudioSource {
    companion object {
        private const val TAG = "FlacSource"
        private const val KEY_PCM_ENCODING = "pcm-encoding"
        /** 读 FLAC 头；不是 FLAC 返回 null */
        fun parseHeader(path: String): FlacHeader? {
            return try {
                RandomAccessFile(path, "r").use { f -> parseHeader(f) }
            } catch (e: Exception) {
                Log.w(TAG, "parseHeader($path) 失败: ${e.message}")
                null
            }
        }

        fun parseHeader(f: RandomAccessFile): FlacHeader? {
            val magic = ByteArray(4)
            if (f.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "fLaC") return null
            var pos = 4L
            var streamInfo: ByteArray? = null
            val len = f.length()
            while (pos + 4 <= len) {
                f.seek(pos)
                val h = ByteArray(4)
                if (f.read(h) != 4) break
                val last = (h[0].toInt() and 0x80) != 0
                val type = h[0].toInt() and 0x7F
                val blen = ((h[1].toInt() and 0xFF) shl 16) or
                    ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                pos += 4
                if (type == 0) { // STREAMINFO
                    if (blen < 34) return null
                    val si = ByteArray(34)
                    f.seek(pos)
                    if (f.read(si) != 34) return null
                    streamInfo = si
                }
                pos += blen
                if (last) break
            }
            val si = streamInfo ?: return null
            val rate = ((si[10].toInt() and 0xFF) shl 12) or
                ((si[11].toInt() and 0xFF) shl 4) or
                ((si[12].toInt() and 0xFF) ushr 4)
            val channels = (((si[12].toInt() and 0xFF) ushr 1) and 0x07) + 1
            val bits = ((((si[12].toInt() and 0xFF) and 0x01) shl 4) or
                ((si[13].toInt() and 0xFF) ushr 4)) + 1
            val totalSamples = (((si[13].toLong() and 0x0F) shl 32) or
                ((si[14].toLong() and 0xFF) shl 24) or
                ((si[15].toLong() and 0xFF) shl 16) or
                ((si[16].toLong() and 0xFF) shl 8) or
                (si[17].toLong() and 0xFF))
            if (rate <= 0 || channels <= 0) return null
            return FlacHeader(rate, channels, bits.coerceIn(4, 32), totalSamples, pos, si)
        }
    }

    private var extractor: MediaExtractor? = null
    private var supply = FlacSupply.CODEC
    private var selPcmEncoding = 0
    private var header: FlacHeader? = null
    private var filePath: String = ""
    override lateinit var info: AudioInfo
        private set

    /** 官方 ctor 语义：uri 必须能给出可读文件路径 */
    init {
        val p = uri.path ?: throw IllegalArgumentException("需要文件路径 URI：$uri")
        val real = File(p)
        if (!real.canRead()) throw IllegalArgumentException("文件不可读：$p")
        val path = real.absolutePath
        val h = parseHeader(path)
            ?: throw IllegalArgumentException("不是 FLAC 文件（缺少 fLaC/STREAMINFO 头）：$p")
        header = h
        filePath = path
        val durUs = if (h.totalSamples > 0) h.totalSamples * 1_000_000 / h.rate else -1L
        info = AudioInfo("FLAC", h.rate, h.channels, h.bits, false, 0, false, durUs)
        // 提取器只用来供帧 + 定位；拿不到也不影响播放（走文件直喂）
        extractor = tryOpenExtractor(path)
        supply = decideSupply(extractor)
    }

    private fun tryOpenExtractor(path: String): MediaExtractor? {
        val ex = MediaExtractor()
        return try {
            RandomAccessFile(path, "r").use { raf ->
                ex.setDataSource(raf.fd, 0, raf.length())
            }
            if (ex.trackCount > 0) {
                var pick = 0
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME)
                    Log.i(TAG, "extractor track[$i] mime=$mime rate=${f.intOr(MediaFormat.KEY_SAMPLE_RATE)} " +
                        "ch=${f.intOr(MediaFormat.KEY_CHANNEL_COUNT)} csd0=${f.containsKey("csd-0")} " +
                        "pcmEnc=${f.intOr(KEY_PCM_ENCODING)} full=$f")
                    if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) { pick = i; break }
                }
                ex.selectTrack(pick)
                runCatching {
                    val pf = ex.getTrackFormat(pick)
                    if (pf.containsKey("pcm-encoding")) selPcmEncoding = pf.getInteger("pcm-encoding")
                }
                ex
            } else {
                Log.w(TAG, "extractor trackCount=0，改为直接喂文件字节")
                ex.release(); null
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractor 打开失败，改为直接喂文件字节: ${e.message}")
            runCatching { ex.release() }
            null
        }
    }

    /** 已打开的「精确 24bit」字节流 */
    private var floatBs: ByteSource? = null

    override fun openPcm(): ByteSource {
        // 24bit 本地 FLAC：平台 extractor 只给 16bit，自己切帧 + float 解码拿真 24bit
        val hdr = header
        if (hdr != null && hdr.bits > 16 && filePath.isNotBlank()) {
            runCatching { floatBs?.close() }
            floatBs = null
            runCatching { openFloat() }.onSuccess { return it }
                .onFailure { Log.w(TAG, "精确 24bit 路径失败，回退平台解码：${it.message}") }
        }
        return FlacByteSource(extractor, header!!, filePath, info, supply, selPcmEncoding)
    }

    private fun openFloat(): ByteSource {
        val rs = FileRandomSource(filePath)
        val r = FlacFrameReader(rs)
        require(r.info.bits > 16) { "源位深 ${r.info.bits}bit，不需要精确路径" }
        val bs = FlacFloatByteSource(r, rs, TAG)
        floatBs = bs
        Log.i(TAG, "精确 24bit 解码（本地）：${r.info.bits}bit ${r.info.rate}Hz " +
            "总样本 ${r.info.totalSamples}")
        return bs
    }

    /**
     * 决定供给方式。**先相信提取器给的格式**：
     *  - mime=audio/raw 或带 pcm-encoding → 样本已是 PCM，直接 RAW_PCM（不要再喂解码器）
     *  - mime=audio/flac                   → CODEC
     *  - 拿不到 mime 时才嗅探首样本，且嗅探失败一律兜底 RAW_PCM
     *    （宁可放过压缩帧，也不要把 PCM 喂进 FLAC 解码器——那会直接崩管线）
     */
    private fun decideSupply(ex: MediaExtractor?): FlacSupply {
        if (ex == null) return FlacSupply.CODEC   // 无提取器：文件直喂，必然是压缩帧
        val mime = runCatching { ex.getTrackFormat(0).getString(MediaFormat.KEY_MIME) }.getOrNull()
        val pcmEnc = selPcmEncoding
        if (mime == MediaFormat.MIMETYPE_AUDIO_RAW || (pcmEnc != 0 && mime != MediaFormat.MIMETYPE_AUDIO_FLAC)) {
            Log.i(TAG, "supply: 提取器报 mime=$mime pcmEnc=$pcmEnc → RAW_PCM（已是 PCM，跳过解码器）")
            return FlacSupply.RAW_PCM
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) {
            Log.i(TAG, "supply: mime=$mime → CODEC")
            return FlacSupply.CODEC
        }
        // mime 不明确：嗅一下首样本
        return try {
            val probe = ByteArray(4096)
            val n = ex.readSampleData(ByteBuffer.wrap(probe), 0)   // 不推进游标，无需复位
            if (n < 4) return FlacSupply.RAW_PCM
            val b0 = probe[0].toInt() and 0xFF
            val b1 = probe[1].toInt() and 0xFF
            val looksFlacFrame = b0 == 0xFF && (b1 and 0xFE) == 0xF8
            Log.i(TAG, "supply sniff: n=$n b0=0x${b0.toString(16)} b1=0x${b1.toString(16)} " +
                "→ ${if (looksFlacFrame) "CODEC" else "RAW_PCM"}")
            if (looksFlacFrame) FlacSupply.CODEC else FlacSupply.RAW_PCM
        } catch (e: Throwable) {
            Log.w(TAG, "嗅探失败（mime=$mime），兜底 RAW_PCM", e)   // 打完整栈
            FlacSupply.RAW_PCM
        }
    }

    override fun openDsd() = throw IllegalStateException("PCM 源无 DSD 流")

    override fun close() {
        runCatching { floatBs?.close() }
        floatBs = null
        runCatching { extractor?.release() }
        extractor = null
    }

    private fun MediaFormat.intOr(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(-1) else -1
}

/**
 * FLAC → 24-in-32 PCM。
 * 两条供给路径：
 *  1) MediaExtractor 可用：readSampleData 取压缩帧（以文件信息为准，不信 mime）
 *  2) 不可用：直接按字节读文件（MediaCodec 的 FLAC 解码器内部自行成帧）
 */
private class FlacByteSource(
    private val ex: MediaExtractor?,
    private val hdr: FlacHeader,
    private val path: String,
    private val info: AudioInfo,
    private val supply: FlacSupply = FlacSupply.CODEC,
    selPcmEnc: Int = 0,
) : ByteSource {

    companion object {
        private const val TAG = "FlacByteSource"
        private const val OUT_BYTES_PER_FRAME = PcmUtils.OUT_BYTES_PER_FRAME
    }

    override val lengthBytes: Long
        get() {
            val d = info.durationUs
            return if (d > 0) d * info.sampleRate * OUT_BYTES_PER_FRAME / 1_000_000 else -1
        }

    /** 有效位深：本地 FLAC 走提取器 RAW_PCM 时由实测每样本字节数决定（24bit 素材常被降成 16） */
    override val outputBits: Int
        get() = when {
            supply == FlacSupply.RAW_PCM && pcmBytes > 0 -> pcmBytes * 8
            supply == FlacSupply.CODEC -> 32
            else -> 0
        }

    private var codec: MediaCodec? = null
    private var pcmEncoding: Int = 0
    private val obi = MediaCodec.BufferInfo()
    private val outBuf = ByteArray(256 * 1024)   // 输出（24-in-32）暂存
    private var outPending = 0
    private var outPos = 0
    private var eof = false
    private var inputDone = false
    private var raf: RandomAccessFile? = null
    /** RAW_PCM 时每样本字节数（0=未知，需要校准） */
    private var pcmBytes = 0
    private val holdBuf = ByteArray(192 * 1024)
    private var holdLen = 0
    private val inChunk = ByteArray(64 * 1024)
    private val convBuf = ByteArray(192 * 1024)   // 解码器原始 PCM 暂存（避免每次分配）

    init {
        pcmEncoding = selPcmEnc   // audio/raw 轨道由提取器告的 pcm-encoding 决定位深
    }

    private fun ensureCodec(): MediaCodec {
        codec?.let { return it }
        val fmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC, hdr.rate, hdr.channels)
        fmt.setInteger("bits-per-sample", hdr.bits)
        // csd-0 = STREAMINFO（34 字节）：解码器必需，自己解析的不依赖提取器
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(hdr.streamInfo))
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        try {
            c.configure(fmt, null, null, 0)
        } catch (e: Exception) {
            runCatching { c.release() }
            throw IllegalStateException("FLAC 解码器配置失败：${e.message}", e)
        }
        c.start()
        codec = c
        Log.i(TAG, "decoder started: ${hdr.rate}Hz ${hdr.channels}ch ${hdr.bits}bit " +
            "total=${hdr.totalSamples} src=${if (ex != null) "extractor" else "file"}")
        return c
    }

    /**
     * 提取器给的是**已解码 PCM**（mime 常被报成 audio/raw，不给 pcm-encoding）：
     * 这种时候长度是唯一线索，而 18432 B 既可能是 4608 帧 16bit 也可能是 2304 帧 32bit。
     * 判据只能靠 extractor 的时间戳：读一帧、看 Δt 与「帧数 × 1e6 / 采样率」哪个位深吻合。
     * 猜错的表现是 2 倍速噪声（旧代码按 hdr.bits>16 硬判 3 字节，24bit 文件正好踩中）。
     */
    private fun detectPcmBytes(): Int {
        val mapped = when (pcmEncoding) {
            3 -> 1                       // ENCODING_PCM_8BIT
            2 -> 2                       // ENCODING_PCM_16BIT
            21 -> 3                      // ENCODING_PCM_24BIT
            22, 4 -> 4                   // ENCODING_PCM_32BIT / FLOAT
            else -> 0
        }
        if (mapped != 0) return mapped
        val e = ex ?: return if (hdr.bits > 16) 3 else 2
        val t1 = e.sampleTime
        val n2 = e.readSampleData(ByteBuffer.wrap(holdBuf), 0)
        if (n2 <= 0) return if (hdr.bits > 16) 3 else 2
        holdLen = n2
        val dUs = e.sampleTime - t1
        e.advance()
        if (dUs > 0 && hdr.rate > 0) {
            var best = 0
            var bestErr = Long.MAX_VALUE
            for (bps in intArrayOf(2, 3, 4)) {
                if (n2 % (hdr.channels * bps) != 0) continue
                val frames = n2 / hdr.channels / bps
                val err = kotlin.math.abs(frames * 1_000_000L / hdr.rate - dUs)
                if (err < bestErr) { bestErr = err; best = bps }
            }
            if (best > 0) {
                Log.i(TAG, "RAW_PCM 校准每样本字节数=$best（帧 $n2 B，Δt=$dUs µs @ ${hdr.rate}Hz，" +
                    "文件标称 ${hdr.bits}bit 有效 ${best * 8}bit" +
                    (if (best == 2 && hdr.bits > 16) " ⚠平台提取器已降位（低 ${hdr.bits - 16} 位恒 0）" else "") + "）")
                return best
            }
        }
        return if (hdr.bits > 16) 3 else 2
    }

    private fun readRawPcm(dst: ByteArray, off: Int, len: Int): Int {
        val framesWanted = len / OUT_BYTES_PER_FRAME
        if (framesWanted <= 0) return 0
        if (pcmBytes == 0) pcmBytes = detectPcmBytes()
        val src: ByteArray
        val n: Int
        if (holdLen > 0) {
            src = holdBuf; n = holdLen; holdLen = 0
        } else {
            val e = ex ?: return -1
            n = e.readSampleData(ByteBuffer.wrap(convBuf), 0)
            if (n <= 0) return 0
            e.advance()
            src = convBuf
        }
        val bytesPerSample = pcmBytes
        val srcN = minOf(n, src.size)
        // 只取本次 dst 能容纳的整帧；**剩下的必须存起来下次用**，否则每个提取器块都会
        // 丢掉尾部（真机/SELFTEST 用 16KB 读时 1920ms 的 24bit FLAC 只解出 853ms）
        val wantSamples = framesWanted * hdr.channels
        val haveSamples = srcN / bytesPerSample
        val samples = (minOf(haveSamples, wantSamples) / hdr.channels) * hdr.channels
        if (samples <= 0) return 0
        val leftover = srcN - samples * bytesPerSample
        if (leftover > 0) {
            System.arraycopy(src, samples * bytesPerSample, holdBuf, 0, leftover)
            holdLen = leftover
        }
        return when (bytesPerSample) {
            1 -> {
                // 8bit 无符号 → 24-in-32
                var si = 0
                var d = off
                for (i in 0 until samples) {
                    val s8 = ((src[si++].toInt() and 0xFF) - 128) shl 16
                    dst[d] = (s8 and 0xFF).toByte()
                    dst[d + 1] = ((s8 shr 8) and 0xFF).toByte()
                    dst[d + 2] = ((s8 shr 16) and 0xFF).toByte()
                    dst[d + 3] = ((s8 shr 24) and 0xFF).toByte()
                    d += 4
                }
                samples * 4
            }
            2 -> { PcmUtils.upsample16(src, 0, samples * 2, dst, off); samples * 4 }
            3 -> { PcmUtils.upsample24(src, 0, samples * 3, dst, off); samples * 4 }
            else -> {
                if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                    floatTo24(src, samples, dst, off)
                } else {
                    System.arraycopy(src, 0, dst, off, samples * 4)
                }
                samples * 4
            }
        }
    }

    /** float[-1,1] → 24-bit 左对齐（bit31..8） */
    private fun floatTo24(src: ByteArray, samples: Int, dst: ByteArray, off: Int) {
        val bb = ByteBuffer.wrap(src).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var d = off
        for (i in 0 until samples) {
            val f = bb.getFloat(i * 4)
            var v = (f * 8388607f).toInt()
            if (v > 8388607) v = 8388607 else if (v < -8388608) v = -8388608
            val v32 = v shl 8
            dst[d] = (v32 and 0xFF).toByte()
            dst[d + 1] = ((v32 shr 8) and 0xFF).toByte()
            dst[d + 2] = ((v32 shr 16) and 0xFF).toByte()
            dst[d + 3] = ((v32 shr 24) and 0xFF).toByte()
            d += 4
        }
    }

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (supply == FlacSupply.RAW_PCM) {
            return try { readRawPcm(dst, off, len) } catch (e: Exception) {
                Log.e(TAG, "readRawPcm 失败: ${e.message}"); -1
            }
        }
        var written = 0
        try {
            val c = ensureCodec()
            while (written < len) {
                if (outPos >= outPending) {
                    if (eof) break
                    if (!decodeMore(c)) break
                }
                val n = minOf(len - written, outPending - outPos)
                System.arraycopy(outBuf, outPos, dst, off + written, n)
                outPos += n
                written += n
            }
        } catch (e: Exception) {
            Log.e(TAG, "read 失败: ${e.message}", e)
            return if (written > 0) written else -1
        }
        return written
    }

    /**
     * 喂输入 + 取输出。返回 false **仅**表示流真的结束了。
     * 注意：上层把 read()==0 当作 EOF，所以这里不能因"暂时没数据"就返回 false。
     */
    private fun decodeMore(c: MediaCodec): Boolean {
        var stalls = 0
        while (true) {
            if (eof && outPos >= outPending) return false
            if (!inputDone) {
                val si = c.dequeueInputBuffer(10000)
                if (si >= 0) {
                    val ib = c.getInputBuffer(si)!!
                    ib.clear()
                    val cap = ib.remaining()
                    if (ex != null) {
                        val n = ex.readSampleData(ib, 0)
                        if (n > 0) {
                            c.queueInputBuffer(si, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        } else {
                            c.queueInputBuffer(si, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    } else {
                        val r = raf ?: RandomAccessFile(path, "r").also {
                            it.seek(hdr.firstFrameOffset); raf = it
                        }
                        val want = minOf(cap, inChunk.size)
                        val got = r.read(inChunk, 0, want)
                        if (got > 0) {
                            ib.put(inChunk, 0, got)
                            c.queueInputBuffer(si, 0, got, 0, 0)
                        } else {
                            c.queueInputBuffer(si, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
            }
            val oi = c.dequeueOutputBuffer(obi, 10000)
            if (oi >= 0) {
                stalls = 0
                val size = obi.size
                val isEos = (obi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val isCfg = (obi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (size > 0 && !isCfg) {
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(obi.offset)
                    ob.limit(obi.offset + size)
                    var consumed = 0
                    val srcBps = if (hdr.bits > 16) 3 else 2
                    outPos = 0
                    outPending = 0
                    while (consumed < size) {
                        val room = outBuf.size - outPending
                        val samples = minOf((size - consumed) / srcBps, room / 4)
                        if (samples <= 0) break
                        val srcN = samples * srcBps
                        ob.get(convBuf, 0, srcN)
                        if (srcBps == 3) PcmUtils.upsample24(convBuf, 0, srcN, outBuf, outPending)
                        else PcmUtils.upsample16(convBuf, 0, srcN, outBuf, outPending)
                        outPending += samples * 4
                        consumed += srcN
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (isEos) eof = true
                    if (outPending > 0) return true
                    continue
                }
                c.releaseOutputBuffer(oi, false)
                if (isEos) eof = true
                continue
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = c.outputFormat
                Log.i(TAG, "output format: ${of.getString(MediaFormat.KEY_MIME)}")
                continue
            } else { // TRY_AGAIN
                if (++stalls > 500) {
                    Log.e(TAG, "解码器长时间无输出（inputDone=$inputDone），按流结束处理")
                    eof = true
                    return false
                }
            }
        }
    }

    override fun seek(bytePos: Long): Boolean {
        return try {
            outPos = 0; outPending = 0
            holdLen = 0
            eof = false; inputDone = false
            val c = codec
            if (c != null) c.flush()      // 清掉管线里的旧 PCM
            if (ex != null && supply == FlacSupply.RAW_PCM) {
                val tUs = (bytePos / OUT_BYTES_PER_FRAME) * 1_000_000 / info.sampleRate.coerceAtLeast(1)
                ex.seekTo(tUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } else if (ex != null) {
                val frame = bytePos / OUT_BYTES_PER_FRAME
                val tUs = frame * 1_000_000 / info.sampleRate.coerceAtLeast(1)
                ex.seekTo(tUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // 丢弃到目标帧（提取器只保证落到同步点之前）
                var skip = frame - (ex.sampleTime * info.sampleRate / 1_000_000).coerceAtLeast(0)
                if (skip > 0) {
                    val tmp = ByteArray(OUT_BYTES_PER_FRAME)
                    var guard = 0
                    while (skip > 0 && guard++ < 2_000_000) {
                        val n = read(tmp, 0, OUT_BYTES_PER_FRAME)
                        if (n <= 0) break
                        skip--
                    }
                }
            } else {
                raf?.close(); raf = null   // 下次从 firstFrameOffset 重新开
                // 直喂模式无法精确 seek：重解到目标位置
                val target = bytePos
                var got = 0L
                if (target > 0) {
                    val tmp = ByteArray(64 * 1024)
                    var guard = 0
                    while (got < target && guard++ < 200_000) {
                        val n = read(tmp, 0, minOf(tmp.size.toLong(), target - got).toInt())
                        if (n <= 0) break
                        got += n
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "seek($bytePos) 失败: ${e.message}")
            false
        }
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { raf?.close() }
        raf = null
    }
}
