// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.content.Context

/** 解码器输出的统一字节流：PCM 为 24-bit-in-32bit-LE 交织立体声；
 *  DSD 为交织的 DSD 字节（L,R,L,R）。 */
interface ByteSource {
    /** 填 dst 中最多 len 字节，返回实际字节数；0 = 文件结束；<0 错误 */
    fun read(dst: ByteArray, off: Int, len: Int): Int
    /** 定位到位置（PCM 按字节；DSD 按 DSD 字节） */
    fun seek(bytePos: Long): Boolean
    val lengthBytes: Long
    /** 实际送给 USB 的**有效位深**（16/24/32；0=未知）。源标称 24bit 但平台解码器只给
     *  16bit 时这里会是 16，UI 用它诚实地显示"源 24bit / 输出 16bit"。 */
    val outputBits: Int get() = 0
    fun close()
}

/** 可播放音频源（文件或 URI） */
interface AudioSource {
    val info: AudioInfo
    /** 打开为字节流。dsdModeTarget：DSD 源需要输出 DSD 字节流时调用。 */
    fun openPcm(): ByteSource
    /** 若 isDsd：打开为 DSD 字节流（交织 DSD 字节）；PCM 源抛异常 */
    fun openDsd(): ByteSource
    /** DSD 源绑定文件路径（native 读需要）；PCM 源忽略 */
    fun bindPath(path: String) {}
    fun close()
}

data class AudioInfo(
    val container: String,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isDsd: Boolean,
    val dsdRateHz: Int,
    val dsdLsbFirst: Boolean,
    val durationUs: Long,
) {
    fun fmt(): String = if (isDsd) {
        "${com.dsd.player.usb.AudioFormatRouter.dsdModeName(dsdRateHz)}"
    } else {
        "$sampleRate Hz / ${bitsPerSample}-bit"
    }
}
