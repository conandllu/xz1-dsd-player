// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.usb

/** 播放格式模式 */
enum class OutputMode { PCM, DOP, DSD_NATIVE }

data class StreamConfig(
    val mode: OutputMode,
    /** PCM/DoP：USB 侧采样率 Hz；DSD native：DSD 位率 Hz */
    val usbRate: Int,
    /** 每 USB 帧字节：PCM24=8, DoP=8, DSD native=2 */
    val frameBytes: Int,
    /** 音频源采样率（PCM=Hz；DSD=位率），用于 UI */
    val sourceRate: Int,
    /** 源是否 DSD（决定生产者走哪条路） */
    val sourceIsDsd: Boolean,
    /** DSF 位序反转需求（DSD native 时有效） */
    val dsdLsbFirst: Boolean = false,
)

object AudioFormatRouter {
    /** UAC2 常见采样率（含 DSD 位率）——不在此列表且 DAC 无速率表时应拒播 */
    private val KNOWN = setOf(
        44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000,
        2822400, 5644800, 11289600, 22579200,
    )

    fun isKnownRate(hz: Int): Boolean = hz in KNOWN
    /** DoP 载波率：DS64→176400 ... */
    fun dopCarrier(dsdRateHz: Int): Int? = when (dsdRateHz) {
        2822400 -> 176400
        5644800 -> 352800
        11289600 -> 705600
        22579200 -> 1411200
        else -> if (dsdRateHz % 44100 == 0) dsdRateHz / 16 else null
    }

    fun dsdModeName(dsdRateHz: Int): String = when (dsdRateHz) {
        2822400 -> "DSD64"
        5644800 -> "DSD128"
        11289600 -> "DSD256"
        22579200 -> "DSD512"
        else -> "DSD${(dsdRateHz / 44100).coerceAtLeast(1)}"
    }
}
