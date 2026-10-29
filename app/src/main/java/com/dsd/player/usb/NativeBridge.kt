// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.usb

import android.util.Log

/** Kotlin <-> native (libusb/UAC2/ISO 泵) 桥。所有 USB 音频操作走这里。 */
object NativeBridge {
    private const val TAG = "NativeBridge"
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            runCatching { System.loadLibrary("dsdplayer") }
                .onFailure { Log.e(TAG, "loadLibrary failed", it) }
            loaded = runCatching { true }.getOrDefault(false)
        }
    }

    external fun nativeLastError(): String?

    /** 探测设备能力；失败返回 null（原因看 lastError） */
    external fun nativeProbe(fd: Int, deviceAddress: Int): String?
    external fun nativeSupportsDsdNative(): Boolean

    /** mode: 0=PCM 1=DSD native 2=DoP；rate=Hz（DSD 传位率） */
    external fun nativeOpenStream(mode: Int, rate: Int): Boolean
    external fun nativeCloseStream()
    external fun nativeGetRate(): Int
    /** 本次 open 实际使用的每帧字节数（channels × subslot） */
    external fun nativeGetFrameBytes(): Int

    /** mode: 0=PCM 1=DSD native 2=DoP */
    external fun nativeStartPump(rate: Int, frameBytes: Int, mode: Int): Boolean
    external fun nativeStopPump()
    external fun nativeSetMute(mute: Boolean)
    external fun nativePumpRunning(): Boolean

    external fun nativeRingReset()
    /** 丢弃未播放数据（seek 专用） */
    external fun nativeRingDiscard()
    /** seek 后冲刷：淡出 → 丢弃 → 淡入 */
    external fun nativeSeekFlush()
    /** 内置 ALAC 解码器（AOSP 没有 audio/alac，返回 0 表示初始化失败） */
    external fun nativeAlacOpen(cookie: ByteArray): Long
    /** 解码一帧 ALAC → 交织 PCM（16/24/32bit，由 cookie 的 bitDepth 决定），返回字节数 */
    external fun nativeAlacDecode(handle: Long, packet: ByteArray, packetLen: Int,
                                  out: ByteArray, outCap: Int): Int
    /** [frameLength, bitDepth, channels, sampleRate] */
    external fun nativeAlacInfo(handle: Long): IntArray
    external fun nativeAlacClose(handle: Long)
    /** 阻塞版：返回时缓冲已丢弃（不含淡入）；返回 false 表示等淡出超时（已强制丢弃） */
    external fun nativeSeekFlushBlocking(timeoutMs: Int): Boolean
    external fun nativeRingFree(): Int
    external fun nativeRingUsed(): Int
    external fun nativeRingWrite(data: ByteArray, off: Int, len: Int): Int

    /** [submitted, bytes, underruns, errors] */
    external fun nativeStats(): LongArray

    // DSD 容器
    external fun nativeDsdOpen(path: String): Boolean
    external fun nativeDsdOpenFd(fd: Int, offset: Long): Boolean
    external fun nativeDsdClose()
    external fun nativeDsdTotalBytes(): Long
    external fun nativeDsdRate(): Int
    external fun nativeDsdLsbFirst(): Boolean
    external fun nativeDsdRead(pos: Long, dst: ByteArray, off: Int, len: Int): Int
}
