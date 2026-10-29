// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.RandomAccessFile

/**
 * WAV (RIFF/WAVE, PCM 16/24/32-bit) 解析 + 直读，输出 24-in-32。
 * 不支持 IEEE float / mul-law（会给出明确错误）。
 */
class WavSource(context: Context, uri: Uri) : AudioSource {
    private var raf: RandomAccessFile? = null
    override lateinit var info: AudioInfo
        private set
    private var dataStart = 0L
    private var dataLen = 0L
    private var srcBytesPerFrame = 0
    private var bps = 16

    private var cachedPath0: String? = null

    init {
        // 用真实文件路径（文件 URI；MediaStore content uri 的 path 无效会被 takeIf 过滤）
        val p = uri.path?.takeIf { it.contains('.') }
            ?: throw IllegalArgumentException("需要文件路径 URI：$uri")
        openPath(p)
    }
    override fun bindPath(path: String) {
        cachedPath0 = path
        if (raf == null) runCatching { openPath(path) }
    }

    fun openPath(path: String) {
        val f = RandomAccessFile(path, "r")
        raf = f
        // RIFF 头是 "RIFF" + u32 长度 + "WAVE"（长度字段不能跳过！之前把长度当成 WAVE 比较，
        // 所以标准 44 字节头 WAV 全被判成「不是 WAV/AIFF 文件」）
        val magic0 = readStr(f, 4)
        if (magic0 == "FORM") {                 // AIFF：FORM + u32 + AIFF/AIFC
            f.seek(0)
            parseAiff(f)
            return
        }
        if (magic0 != "RIFF") {
            val headHex = runCatching {
                f.seek(0); val b = ByteArray(12); f.read(b)
                b.joinToString("") { "%02x".format(it) }
            }.getOrDefault("?")
            android.util.Log.w("WavSource", "不是 RIFF/FORM: path=$path head=$headHex")
            f.close(); raf = null
            throw IllegalArgumentException("不是 WAV/AIFF 文件（$path 前 12 字节=$headHex）")
        }
        readLe32(f)                              // RIFF 块长度
        val waveId = readStr(f, 4)
        if (waveId != "WAVE") {
            f.close(); raf = null
            throw IllegalArgumentException("RIFF 但不是 WAVE（$waveId）")
        }
        var fmtSeen = false
        var ch = 0; var rate = 0; var bits = 0; var audioFormat = 1
        var byteRate = 0
        var pos = 12L
        val len = f.length()
        while (pos + 8 <= len) {
            f.seek(pos)
            val id = readStr(f, 4)
            val sz = readLe32(f)
            if (id == "fmt ") {
                audioFormat = readLe16(f)
                ch = readLe16(f)
                rate = readLe32(f)
                byteRate = readLe32(f)
                f.skipBytes(2) // blockAlign
                bits = readLe16(f)
                fmtSeen = true
            } else if (id == "data") {
                if (!fmtSeen) { f.close(); raf = null; throw IllegalArgumentException("WAV 缺 fmt 块") }
                dataStart = pos + 8
                // size=0/-1(0xFFFFFFFF) 常见于流式写入的 WAV → 用文件剩余长度
                dataLen = if (sz <= 0) len - dataStart else sz.toLong().coerceAtMost(len - dataStart)
                break
            }
            if (sz <= 0) break          // 损坏或流式 chunk，无法继续遍历
            pos += 8 + sz + (sz and 1)
        }
        if (dataLen == 0L) { f.close(); raf = null; throw IllegalArgumentException("WAV 缺 data 块") }
        if (audioFormat != 1 && audioFormat != 0xFFFE) {
            f.close(); raf = null
            throw IllegalArgumentException("WAV 非 PCM（format=$audioFormat，浮点/压缩不支持）")
        }
        if (bits !in intArrayOf(16, 24, 32)) { f.close(); raf = null; throw IllegalArgumentException("WAV 位宽 $bits 不支持") }
        bps = bits
        srcBytesPerFrame = bits / 8 * ch
        val durUs = when {
            byteRate > 0 -> dataLen * 1_000_000 / byteRate
            rate > 0 && ch > 0 -> dataLen * 1_000_000 / (rate * ch * (bits / 8))
            else -> -1L
        }
        android.util.Log.i("WavSource", "WAV ok: ${rate}Hz ${ch}ch ${bits}bit " +
            "dataStart=$dataStart dataLen=$dataLen byteRate=$byteRate dur=${durUs / 1000}ms")
        info = AudioInfo("WAV", rate, ch, bits, false, 0, false, durUs)
    }

    private fun parseAiff(f: RandomAccessFile) {
        // FORM AIFF / AIFC —— 读 COMM + SSND
        f.seek(0)
        val form = readStr(f, 4) // FORM
        readBe32(f) // size
        val aifc = readStr(f, 4)
        var pos = 12L
        var ch = 0; var rate = 0; var bits = 0; var comp = "NONE"
        var dataStart = 0L; var dataLen = 0L
        val len = f.length()
        while (pos + 8 <= len) {
            f.seek(pos)
            val id = readStr(f, 4)
            val sz = readBe32(f).toLong() and 0xFFFFFFFFL
            if (id == "COMM") {
                ch = readBe16(f)
                readBe32(f) // numSamples
                bits = readBe16(f)
                // sampleRate: 80-bit extended
                rate = readExt80Rate(f)
                if (aifc == "AIFC") comp = readStr(f, 4)
            } else if (id == "SSND") {
                readBe32(f); readBe32(f) // offset + blockSize
                dataStart = pos + 16
                dataLen = sz - 8
                break
            }
            pos += 8 + sz + (sz and 1)
        }
        if (dataLen == 0L) { f.close(); raf = null; throw IllegalArgumentException("AIFF 缺 SSND") }
        if (comp != "NONE" && comp != "not ") {
            f.close(); raf = null
            throw IllegalArgumentException("AIFF 压缩 $comp 不支持")
        }
        if (bits !in intArrayOf(16, 24, 32)) { f.close(); raf = null; throw IllegalArgumentException("AIFF 位宽 $bits 不支持") }
        this.dataStart = dataStart
        this.dataLen = dataLen
        bps = bits
        srcBytesPerFrame = bits / 8 * ch
        info = AudioInfo("AIFF", rate, ch, bits, false, 0, false,
            dataLen * 1_000_000 / (rate.coerceAtLeast(1) * ch * (bits / 8)))
    }

    private fun readStr(f: RandomAccessFile, n: Int): String {
        val b = ByteArray(n); f.readFully(b); return String(b, Charsets.ISO_8859_1)
    }
    private fun readLe16(f: RandomAccessFile): Int {
        val b = ByteArray(2); f.readFully(b); return (b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)
    }
    private fun readLe32(f: RandomAccessFile): Int {
        val b = ByteArray(4); f.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or (b[3].toInt() shl 24)
    }
    private fun readBe16(f: RandomAccessFile): Int {
        val b = ByteArray(2); f.readFully(b)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }
    private fun readBe32(f: RandomAccessFile): Int {
        val b = ByteArray(4); f.readFully(b)
        return (b[0].toInt() shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }
    /** 80-bit IEEE 754 extended（AIFF sampleRate） */
    private fun readExt80Rate(f: RandomAccessFile): Int {
        val b = ByteArray(10); f.readFully(b)
        val exp = ((b[0].toInt() and 0x7F) shl 8) or (b[1].toInt() and 0xFF)
        var mant = 0L
        for (i in 2 until 10) mant = (mant shl 8) or (b[i].toLong() and 0xFF)
        if (exp == 0x7FFF) return 0
        val e = exp - 16383 - 63
        val v = if (e >= 0) (mant shl e) else (mant ushr -e)
        return v.toInt()
    }

    override fun openPcm(): ByteSource = WavByteSource(raf!!, info, dataStart, dataLen, bps, srcBytesPerFrame)
    override fun openDsd() = throw IllegalStateException("PCM 源无 DSD 流")
    override fun close() { runCatching { raf?.close() } }
}

private class WavByteSource(
    private val f: RandomAccessFile,
    private val info: AudioInfo,
    private val start: Long,
    private val dataLen: Long,
    private val bps: Int,
    private val srcBpf: Int,
) : ByteSource {
    /** 输出的字节数（24-in-32 立体声 = 8B/帧），与 Producer 的 pos 单位一致 */
    override val lengthBytes: Long
        get() = if (srcBpf > 0) dataLen / srcBpf * PcmUtils.OUT_BYTES_PER_FRAME else dataLen

    private var pos = 0L                     // 源字节位置
    private val raw = ByteArray(64 * 1024)
    private val bigEndian = info.container == "AIFF"

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        val framesWanted = len / PcmUtils.OUT_BYTES_PER_FRAME
        if (framesWanted <= 0) return 0
        var wantSrc = framesWanted.toLong() * srcBpf
        val avail = dataLen - pos
        if (avail <= 0) return 0
        if (wantSrc > avail) wantSrc = avail
        val srcBytes = minOf(wantSrc, raw.size.toLong()).toInt()
        val frames = srcBytes / srcBpf
        if (frames <= 0) return 0
        val n = frames * srcBpf
        f.seek(start + pos)
        f.readFully(raw, 0, n)
        pos += n
        if (bigEndian) swapBe(raw, n, bps / 8)
        return when (bps) {
            16 -> { PcmUtils.upsample16(raw, 0, n, dst, off); frames * PcmUtils.OUT_BYTES_PER_FRAME }
            24 -> { PcmUtils.upsample24(raw, 0, n, dst, off); frames * PcmUtils.OUT_BYTES_PER_FRAME }
            else -> { // 32-bit（大端已转 LE）
                System.arraycopy(raw, 0, dst, off, n)
                n
            }
        }
    }

    /** 逐样本反转字节序（16/24/32-bit 通用） */
    private fun swapBe(b: ByteArray, n: Int, width: Int) {
        var i = 0
        while (i + width <= n) {
            var lo = i; var hi = i + width - 1
            while (lo < hi) {
                val t = b[lo]; b[lo] = b[hi]; b[hi] = t
                lo++; hi--
            }
            i += width
        }
    }

    override fun seek(bytePos: Long): Boolean {
        // 调用方给的是输出字节位置（8B/帧）→ 换算成源字节
        val frame = bytePos / PcmUtils.OUT_BYTES_PER_FRAME
        pos = (frame * srcBpf).coerceIn(0, dataLen)
        return true
    }
    override fun close() {} // f 归 AudioSource
}
