// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 自研的 FLAC **帧边界解析**（不做解码，只切帧）。
 *
 * 为什么需要它：这台机器（模拟器 API 33 与真机 A14 都实测过）的 FLAC 提取器会**先自解码**
 * 再把 PCM 交给应用（`mime=audio/raw`、无 `csd-0`、只给 16bit）。24bit 母带因此被砍成 16bit，
 * 对"无损播放器"是硬伤。要拿到真正的 24bit，只能自己把压缩帧切出来喂 MediaCodec。
 *
 * FLAC 帧没有长度字段，所以只能按同步字扫描 + 头 CRC-8 校验 + 样本号衔接校验来定界
 * （压缩载荷里出现"能过 CRC-8 且样本号自洽"的假同步的概率可以忽略）。
 */
interface RandomSource : Closeable {
    val size: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
}

/** 本地文件 */
class FileRandomSource(path: String) : RandomSource {
    private val raf = RandomAccessFile(path, "r")
    override val size: Long = raf.length()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        // 注意：参数名 size 会遮蔽属性 size，剩余长度必须用 this.size 算（否则每次都少读 position 字节）
        if (position >= this.size) return -1
        val want = minOf(size.toLong(), this.size - position).toInt()
        if (want <= 0) return -1
        raf.seek(position)
        // RandomAccessFile.read 可能短读（实测 27660 B 只要 27618 B），必须自己补齐
        var got = 0
        while (got < want) {
            val n = raf.read(buffer, offset + got, want - got)
            if (n <= 0) break
            got += n
        }
        return if (got == 0) -1 else got
    }
    override fun close() = raf.close()
}

/** 读满指定长度；各 readAt 实现都可能短读 */
fun RandomSource.readFully(position: Long, length: Int): ByteArray? {
    val out = ByteArray(length)
    var got = 0
    while (got < length) {
        val n = readAt(position + got, out, got, length - got)
        if (n <= 0) return null
        got += n
    }
    return out
}

/** HTTP Range（Qobuz 直链） */
class HttpRandomSource(url: String, headers: Map<String, String> = emptyMap()) : RandomSource {
    private val ds = HttpMediaDataSource(url, headers = headers)
    override val size: Long get() = ds.getSize()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        ds.readAt(position, buffer, offset, size)
    override fun close() = ds.close()
}

/** 把已有的 HttpMediaDataSource 包成 RandomSource（复用它的块缓存，不再多开一条连接） */
class DsRandomSource(private val ds: HttpMediaDataSource, private val closeUnderlying: Boolean = false) : RandomSource {
    override val size: Long get() = ds.getSize()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        ds.readAt(position, buffer, offset, size)
    override fun close() { if (closeUnderlying) runCatching { ds.close() } }
}

data class FlacInfo(
    val rate: Int,
    val channels: Int,
    val bits: Int,
    val totalSamples: Long,
    val minBlock: Int,
    val maxBlock: Int,
    val streamInfo: ByteArray,
    val firstFrameOffset: Long,
)

data class FlacFrame(
    val offset: Long,
    val length: Int,
    val blockSize: Int,
    val sample: Long,
    val variableBlocking: Boolean,
)

/**
 * 扫描式 FLAC 帧读取器。
 *
 * 用法：
 * ```
 * FlacFrameReader(src).use { r ->
 *     val info = r.info
 *     var f = r.firstFrame()
 *     while (f != null) { ...; f = r.nextFrame(f) }
 * }
 * ```
 */
class FlacFrameReader(private val src: RandomSource) : Closeable {

    companion object {
        private const val TAG = "FlacFrames"
        private const val WIN = 512 * 1024
        internal var DEBUG = false
    }

    lateinit var info: FlacInfo
        private set

    private val win = ByteArray(WIN)
    private var winBase = 0L
    private var winLen = 0

    init {
        info = parseStreamInfo()
    }

    // ── 窗口读 ────────────────────────────────────────────────────────
    private fun byteAt(pos: Long): Int {
        if (pos < 0 || pos >= src.size) return -1
        if (pos < winBase || pos >= winBase + winLen) {
            val n = src.readAt(pos, win, 0, win.size)
            if (n <= 0) return -1
            winBase = pos
            winLen = n
        }
        return win[(pos - winBase).toInt()].toInt() and 0xFF
    }

    private fun readBytes(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len) {
            val v = byteAt(pos + i)
            if (v < 0) return out.copyOf(i)
            out[i] = v.toByte()
        }
        return out
    }

    // ── STREAMINFO + 元数据块 ─────────────────────────────────────────
    private fun parseStreamInfo(): FlacInfo {
        val magic = readBytes(0, 4)
        require(magic.size == 4 && String(magic, Charsets.US_ASCII) == "fLaC") { "不是 FLAC（缺 fLaC 魔数）" }
        var p = 4L
        var si: ByteArray? = null
        while (p < src.size) {
            val b0 = byteAt(p)
            val last = (b0 and 0x80) != 0
            val type = b0 and 0x7F
            val len = (byteAt(p + 1) shl 16) or (byteAt(p + 2) shl 8) or byteAt(p + 3)
            if (type == 0 && len >= 34) si = readBytes(p + 4, 34)
            p += 4 + len
            if (last) break
        }
        val s = si ?: error("缺少 STREAMINFO")
        val minBlock = ((s[0].toInt() and 0xFF) shl 8) or (s[1].toInt() and 0xFF)
        val maxBlock = ((s[2].toInt() and 0xFF) shl 8) or (s[3].toInt() and 0xFF)
        // 8..12 = 20bit 采样率 + 3bit 通道数-1 + 5bit 位深-1
        val rate = ((s[10].toInt() and 0xFF) shl 12) or
            ((s[11].toInt() and 0xFF) shl 4) or ((s[12].toInt() and 0xFF) shr 4)
        // 注意位域跨界：通道数-1 是 3bit，跨 byte12 低位；位深-1 是 5bit，跨 byte12 的 bit0 与 byte13 高 4bit
        val ch = (((s[12].toInt() and 0xFF) shr 1) and 0x07) + 1
        val bits = ((((s[12].toInt() and 0x01) shl 4) or ((s[13].toInt() and 0xFF) shr 4)) and 0x1F) + 1
        var total = (s[13].toLong() and 0x0F)
        for (i in 14..17) total = (total shl 8) or (s[i].toLong() and 0xFF)
        return FlacInfo(
            rate = rate, channels = ch, bits = bits, totalSamples = total,
            minBlock = minBlock, maxBlock = maxBlock,
            streamInfo = s, firstFrameOffset = p,
        )
    }

    // ── 帧头 ──────────────────────────────────────────────────────────
    private fun crc8(data: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) {
            c = c xor (data[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    private fun blocksizeOf(code: Int, p: Long): Int = when {
        code == 1 -> 192
        code in 2..5 -> 576 shl (code - 2)
        code == 6 -> (byteAt(p) + 1)
        code == 7 -> (((byteAt(p) shl 8) or byteAt(p + 1)) + 1)
        code in 8..15 -> 256 shl (code - 8)
        else -> -1
    }

    /** 解析指定偏移处的帧头；不是合法帧头则返回 null */
    private fun parseHeader(off: Long, expectedSample: Long = -1): FlacFrame? {
        val b0 = byteAt(off)
        if (DEBUG) Log.d(TAG, "parseHeader@$off b=${readBytes(off, 8).joinToString("") { "%02x".format(it) }}")
        if (b0 != 0xFF) return null
        val b1 = byteAt(off + 1)
        if ((b1 and 0xFC) != 0xF8) return null
        if (b1 and 0x02 != 0) return null                    // 保留位必须为 0
        val variable = (b1 and 0x01) != 0
        val b2 = byteAt(off + 2)
        val bsCode = b2 shr 4
        val srCode = b2 and 0x0F
        if (bsCode == 0 || srCode == 0x0F) return null
        val b3 = byteAt(off + 3)
        val chAssign = b3 shr 4
        val bpsCode = (b3 shr 1) and 0x07
        if (chAssign > 10 || bpsCode == 3 || bpsCode == 7 || (b3 and 0x01) != 0) return null

        // UTF-8 变长编码的帧号/样本号
        val u0 = byteAt(off + 4)
        if (u0 < 0) return null
        var numBytes = 1
        var num: Long = when {
            u0 and 0x80 == 0 -> u0.toLong()
            u0 and 0xE0 == 0xC0 -> { numBytes = 2; (u0 and 0x1F).toLong() }
            u0 and 0xF0 == 0xE0 -> { numBytes = 3; (u0 and 0x0F).toLong() }
            u0 and 0xF8 == 0xF0 -> { numBytes = 4; (u0 and 0x07).toLong() }
            u0 and 0xFC == 0xF8 -> { numBytes = 5; (u0 and 0x03).toLong() }
            u0 and 0xFE == 0xFC -> { numBytes = 6; (u0 and 0x01).toLong() }
            u0 == 0xFE -> { numBytes = 7; 0L }
            else -> return null
        }
        var p = off + 5
        for (i in 1 until numBytes) {
            val nb = byteAt(p++)
            if ((nb and 0xC0) != 0x80) return null
            num = (num shl 6) or (nb and 0x3F).toLong()
        }

        val bs = blocksizeOf(bsCode, p)
        if (bs <= 0) return null
        if (bsCode == 6) p += 1
        if (bsCode == 7) p += 2
        when (srCode) {
            12 -> p += 1
            13, 14 -> p += 2
        }
        val headerLen = (p - off).toInt()
        if (headerLen <= 0 || headerLen > 16) return null
        val hdr = readBytes(off, headerLen + 1)
        if (hdr.size != headerLen + 1) return null
        if (crc8(hdr, headerLen) != (hdr[headerLen].toInt() and 0xFF)) return null

        val sample = if (variable) num else num * bs
        if (expectedSample >= 0 && sample != expectedSample) return null
        return FlacFrame(off, 0, bs, sample, variable)
    }

    fun firstFrame(): FlacFrame? {
        DEBUG = true
        val f = parseHeader(info.firstFrameOffset)
        DEBUG = false
        Log.i(TAG, "firstFrame@${info.firstFrameOffset} → ${f?.let { "block=${it.blockSize} sample=${it.sample}" } ?: "null"}")
        return f
    }

    /** 从给定帧往后找下一帧（定界靠"样本号 = 当前样本 + 当前块长"）；找不到返回 null（当前是最后一帧） */
    fun nextFrame(cur: FlacFrame): FlacFrame? {
        val expect = cur.sample + cur.blockSize
        val limit = minOf(src.size, cur.offset + 2L * info.maxFrameBound) + 64
        var p = cur.offset + 6
        while (p < limit) {
            if (byteAt(p) == 0xFF) {
                val f = parseHeader(p, expect)
                if (f != null) return f
            }
            p++
        }
        return null
    }

    /**
     * 在 [estimate] 附近找"样本号 ≤ targetSample 且最大"的帧（seek 用）。
     *
     * 不能从文件头线性扫：272 MB 的 WebDAV 文件线性扫要整份读一遍。
     * 估计偏移用"样本比例 × 数据区大小"，然后在 ±[span] 字节内按帧头特征搜；
     * 单帧 ≤ maxFrameBound，span 取 4 个上界足以保证必然命中一个帧头。
     */
    fun frameAtOrBefore(estimate: Long, targetSample: Long, spanBytes: Int = 0): FlacFrame? {
        val span = if (spanBytes > 0) spanBytes.toLong()
                   else (info.maxFrameBound * 4).coerceIn(64L * 1024, 4L * 1024 * 1024)
        val from = maxOf(info.firstFrameOffset, estimate - span)
        val to = minOf(src.size - 1, estimate + span)
        var best: FlacFrame? = null
        var p = from
        while (p < to) {
            if (byteAt(p) == 0xFF) {
                val f = parseHeader(p)
                if (f != null && f.sample <= targetSample && (best == null || f.sample > best!!.sample)) best = f
            }
            p++
        }
        return best
    }

    /** 帧的字节长度（给"从任意帧起喂解码器"用）：到下一帧头，或到文件尾 */
    fun lengthOf(frame: FlacFrame): Int {
        val nxt = nextFrame(frame)
        return frameLength(frame, nxt)
    }

    /** 估计某个样本对应的文件偏移（固定/变化块长都够用：按样本比例线性插值） */
    fun offsetForSample(sample: Long): Long {
        val total = info.totalSamples
        val dataStart = info.firstFrameOffset
        val dataLen = (src.size - dataStart).coerceAtLeast(1)
        if (total <= 0) return dataStart
        val frac = (sample.toDouble() / total).coerceIn(0.0, 1.0)
        return dataStart + (frac * dataLen).toLong()
    }

    /** 当前帧的字节长度：到下一帧头，或到文件尾 */
    fun frameLength(cur: FlacFrame, next: FlacFrame?): Int =
        ((next?.offset ?: src.size) - cur.offset).toInt()

    override fun close() = src.close()
}

/** STREAMINFO 里没给 maxFrame 时用的上界（FLAC 单帧上限取块长 × 通道 × 4B + 头部） */
private val FlacInfo.maxFrameBound: Long
    get() = (maxBlock.toLong() * channels * 4 + 64)
