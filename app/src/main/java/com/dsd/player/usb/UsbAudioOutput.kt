// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.dsd.player.decoder.AudioSource

/**
 * USB 输出管理器：独占 DAC、按格式开流、启动 native ISO 泵。
 * 独占语义：openDevice → probe（native libusb 解析描述符）→ openStream
 * （claim + detach 内核驱动）→ startPump。关闭时 attach_kernel_driver 归还。
 */
class UsbAudioOutput(private val onError: (String) -> Unit) {

    companion object { private const val TAG = "UsbAudioOutput" }

    var connected = false; private set
    var capsLine = ""; private set
    var usbSummary = ""; private set
    var supportsDsdNative = false; private set
    var supportsDoP = true; private set
    var label = ""; private set
    var currentConfig: StreamConfig? = null; private set

    private var conn: UsbDeviceConnection? = null
    private var device: android.hardware.usb.UsbDevice? = null
    private val claimed = ArrayList<android.hardware.usb.UsbInterface>()
    /** 流是否已开（避免一次播放里重复 claim / 反复开关泵） */
    @Volatile private var streamOpen = false


    fun attach(dev: DacInfo, connection: UsbDeviceConnection) {
        if (connected && conn != null) {
            // 重复 attach（例如 Activity 重建）会把旧 connection 泄漏 → claim 报 BUSY
            Log.i(TAG, "attach: 已连接，忽略重复请求并关闭新连接")
            runCatching { connection.close() }
            return
        }
        NativeBridge.ensureLoaded()
        val caps = NativeBridge.nativeProbe(connection.fileDescriptor, 0)
        if (caps == null) {
            onError("设备探测失败：${NativeBridge.nativeLastError() ?: "?"}")
            connection.close()
            return
        }
        conn = connection
        device = dev.device
        label = dev.label()
        capsLine = caps
        supportsDsdNative = NativeBridge.nativeSupportsDsdNative()
        connected = true
        Log.i(TAG, "attached $label :: $caps, DSD-native=$supportsDsdNative")
        // 面向用户的一行摘要（描述符细节留给"高级信息"）
        val bits = when {
            caps.contains("24bit") -> "24bit"
            caps.contains("32bit") -> "32bit"
            else -> ""
        }
        usbSummary = buildList {
            add(label)
            add("独占")
            if (bits.isNotBlank()) add(bits)
            add(if (supportsDsdNative) "DSD Native" else "DoP")
        }.joinToString(" · ")
    }

    fun detach() {
        stopStream()
        runCatching { conn?.close() }
        conn = null
        device = null
        connected = false
        currentConfig = null
    }

    /**
     * Java 侧强制 claim 音频接口。
     * `claimInterface(itf, force=true)` 走 USBDEVFS_DISCONNECT_CLAIM，
     * 这是普通应用（无 root）把 snd-usb-audio 解绑下来的唯一可靠方式；
     * 只依赖 libusb 的 detach_kernel_driver 在部分 ROM/SELinux 策略下会被拒绝。
     */
    private fun claimAudioInterfaces(): Boolean {
        val c = conn ?: return false
        val d = device ?: return false
        if (claimed.isNotEmpty()) {
            Log.i(TAG, "claim: 已持有 ${claimed.size} 个接口，跳过重复 claim")
            return true
        }
        claimed.clear()
        var any = false
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass != android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) continue
            if (claimed.any { it.id == itf.id }) continue      // 同一接口只记一次
            val ok = runCatching { c.claimInterface(itf, true) }.getOrElse { e ->
                Log.w(TAG, "claimInterface(if=${itf.id}) 异常: ${e.message}")
                false
            }
            Log.i(TAG, "claim if=${itf.id} sub=${itf.interfaceSubclass} alt=${itf.alternateSetting} force=true -> $ok")
            if (ok && claimed.none { it.id == itf.id }) { claimed.add(itf); any = true }
        }
        if (!any) Log.w(TAG, "没有任何音频接口被 claim（独占可能失败）")
        return any
    }

    /**
     * 关流并**把 alt setting 归 0**再释放接口。
     * 不归零的话流式端点仍然激活，DAC 会持续取空数据 → 不播放也有「哒哒哒」。
     * 幂等：可重复调用。
     */
    @Synchronized
    fun stopStream() {
        NativeBridge.nativeStopPump()
        NativeBridge.nativeCloseStream()
        releaseAudioInterfaces()
        streamOpen = false
    }

    /**
     * **让出 USB 独占**：停流（alt→0）→ release 接口 → 关闭连接。
     *
     * 只 release 接口不够：Java 侧是用 `claimInterface(force=true)`（USBDEVFS_DISCONNECT_CLAIM）
     * 把内核 snd-usb-audio 踢开的，只有**关闭 fd** 才会让它重新绑定。
     * 关掉连接后系统/官方 Apple Music 才能正常把 DAC 当声卡用；我们要播时再重新申请权限。
     *
     * @return 之前是否真的占着 DAC（用于日志/状态显示）
     */
    @Synchronized
    fun releaseExclusive(): Boolean {
        val held = streamOpen || claimed.isNotEmpty() || conn != null
        runCatching { stopStream() }
        runCatching { conn?.close() }
        conn = null
        connected = false
        streamOpen = false
        claimed.clear()
        currentConfig = null
        if (held) Log.i(TAG, "让出 USB：已停流、release 接口并关闭连接（内核驱动可重新绑定）")
        return held
    }

    private fun releaseAudioInterfaces() {
        val c = conn ?: return
        for (itf in claimed) {
            runCatching { c.setInterface(itf) }        // alt → 0
            runCatching { c.releaseInterface(itf) }
            Log.i(TAG, "release if=${itf.id} (alt→0)")
        }
        claimed.clear()
    }

    /** 按输出模式为 source 建立流。返回实际生效的 StreamConfig。
     * @param forceMode null = 自动选最优 */
    @Synchronized
    fun openFor(source: AudioSource, forceMode: OutputMode?,
                allowReuse: Boolean = true): StreamConfig {
        val c = conn ?: error("未连接 DAC")
        val cfg = decide(source, forceMode)
        // 切歌时若速率/模式没变，**复用现有流**：
        // 不 close/release/重开，避免"切歌把流拆了之后没人拉起来"（真机死锁）
        val cur = currentConfig
        if (allowReuse && streamOpen && cur != null &&
            cur.usbRate == cfg.usbRate && cur.mode == cfg.mode) {
            // 复用流：淡出 → **阻塞到丢弃完成** → （调用方随后淡入）。
            // 这里必须用阻塞版：非阻塞版只置标志，调用方紧接着 ensureUnmuted() 会把淡出状态
            // 直接盖掉 → 缓冲没丢 → 旧歌继续响 ≈2.95 s（第三十三/三十四/四十六/六十轮同一条）。
            seekFlush()
            Log.i(TAG, "复用现有流（切歌）：${cfg.mode} ${cfg.usbRate}Hz，已冲刷缓冲")
            return cur
        }
        // DAC 照抄回显却可能根本不支持（如 32k）→ 直接拒播，避免"能放但音高全错"
        if (!AudioFormatRouter.isKnownRate(cfg.usbRate)) {
            error("采样率 ${cfg.usbRate} Hz 不在 DAC 常见支持列表内，已拒绝播放" +
                "（避免音高错误；如确认设备支持请反馈）")
        }
        val modeInt = when (cfg.mode) { OutputMode.PCM -> 0; OutputMode.DSD_NATIVE -> 1; OutputMode.DOP -> 2 }
        // 换曲/换模式：只停泵 + 关 native 句柄，**保留 Java 侧 claim**，
        // 避免一次播放里反复 claim/release（真机日志里 5 次 claim 就是这么来的）。
        // alt 会在下面重新设置，所以这里不强制归零。
        NativeBridge.nativeStopPump()
        NativeBridge.nativeCloseStream()
        // 关键顺序：先把内核 snd-usb-audio 解绑（Java force claim），再交给 libusb 切 alt/设时钟
        claimAudioInterfaces()
        if (!NativeBridge.nativeOpenStream(modeInt, cfg.usbRate)) {
            // 回退链：native → DoP → PCM44.1k 静音
            val fb = if (cfg.mode == OutputMode.DSD_NATIVE && cfg.sourceIsDsd) {
                val carrier = AudioFormatRouter.dopCarrier(cfg.sourceRate)
                if (carrier != null) StreamConfig(OutputMode.DOP, carrier, 8, cfg.sourceRate, true)
                else null
            } else null
            if (fb != null && NativeBridge.nativeOpenStream(2, fb.usbRate)) {
                Log.w(TAG, "openStream 回退：${cfg.mode} -> ${fb.mode}")
                val nfb = negotiate(fb)
                runCatching { startPumpChecked(nfb) }.onFailure { stopStream(); throw it }
                currentConfig = nfb
                streamOpen = true
                return nfb
            }
            stopStream()   // 失败必须归还接口，否则 alt 留在流式档位
            error("打开输出流失败：${NativeBridge.nativeLastError() ?: "?"}")
        }
        val ncfg = negotiate(cfg)
        runCatching { startPumpChecked(ncfg) }.onFailure { stopStream(); throw it }
        currentConfig = ncfg
        streamOpen = true
        // 读回 DAC 实际采样率：SET_CUR 若未生效，这里会与请求值不一致（真机排查关键）
        val actual: Int = runCatching { NativeBridge.nativeGetRate() }.getOrDefault(-1)
        if (actual > 0) {
            if (actual == ncfg.usbRate) {
                Log.i(TAG, "DAC 采样率确认: ${actual}Hz ✓")
                capsLine = capsLine.substringBefore(" · 实际") + " · 实际 ${actual}Hz"
            } else {
                Log.w(TAG, "DAC 采样率不符: 请求 ${ncfg.usbRate}Hz，读回 ${actual}Hz")
                capsLine = capsLine.substringBefore(" · 实际") + " · ⚠ 实际 ${actual}Hz(请求 ${ncfg.usbRate})"
                onError("采样率设置未生效：请求 ${ncfg.usbRate}Hz，DAC 读回 ${actual}Hz")
            }
        }
        Log.i(TAG, "stream open: ${ncfg.mode} ${ncfg.usbRate}Hz frame=${ncfg.frameBytes}B " +
            "(subslot=${ncfg.frameBytes / 2}B/ch)")
        return ncfg
    }

    /** 暂停：泵改送静音并清空缓冲（不再"把剩下的播完"） */
    fun pauseOutput() {
        runCatching { NativeBridge.nativeSetMute(true) }
    }

    /** 恢复：清掉残留数据后继续 */
    fun resumeOutput() {
        // 不要 ringReset：buffer 里是"尚未播放"的音频，保留才能从原位置继续
        runCatching { NativeBridge.nativeSetMute(false) }
    }

    /** 确保未被静音（切歌/重开后强制回到增益 1，避免"点播放走淡出"的状态错位） */
    fun ensureUnmuted() {
        runCatching { NativeBridge.nativeSetMute(false) }
    }

    /**
     * seek / 切歌后冲刷环形缓冲（旧位置数据必须丢弃，否则会先把旧声音播完 ≈3 s）。
     *
     * **阻塞到丢弃完成再返回**：native 的淡出→丢弃是跨微帧序列，如果调用方紧接着
     * 调 `ensureUnmuted()`（淡入），淡入会把淡出状态直接盖掉 → 缓冲没丢、旧歌继续放；
     * 真机日志里 `seek：开始淡出` 之后 1 ms 出现 `开始淡入` 就是这个坑。
     */
    fun seekFlush(timeoutMs: Int = 200) {
        val t0 = System.nanoTime()
        val ok = runCatching { NativeBridge.nativeSeekFlushBlocking(timeoutMs) }.getOrDefault(true)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (!ok) Log.w(TAG, "seekFlush：等待淡出超时 ${ms}ms（已强制丢弃）")
        else if (ms > 30) Log.i(TAG, "seekFlush：丢弃完成用了 ${ms}ms")
    }

    /** 用 native 协商出的每帧字节数（channels × subslot）覆盖硬编码值 */
    private fun negotiate(cfg: StreamConfig): StreamConfig {
        val fb = runCatching { NativeBridge.nativeGetFrameBytes() }.getOrDefault(0)
        if (fb > 0 && fb != cfg.frameBytes) {
            Log.i(TAG, "帧长协商: ${cfg.frameBytes}B → ${fb}B（按 DAC 的 subslot）")
            return cfg.copy(frameBytes = fb)
        }
        return cfg
    }

    private fun startPumpChecked(cfg: StreamConfig) {
        NativeBridge.nativeRingReset()
        val mode = when (cfg.mode) {
            OutputMode.DSD_NATIVE -> 1
            OutputMode.DOP -> 2
            else -> 0
        }
        if (!NativeBridge.nativeStartPump(cfg.usbRate, cfg.frameBytes, mode)) {
            error("启动 ISO 泵失败：${NativeBridge.nativeLastError() ?: "?"}")
        }
    }

    fun decide(source: AudioSource, forceMode: OutputMode?): StreamConfig {
        val info = source.info
        if (info.isDsd) {
            val mode = forceMode ?: when {
                supportsDsdNative -> OutputMode.DSD_NATIVE
                supportsDoP && AudioFormatRouter.dopCarrier(info.dsdRateHz) != null -> OutputMode.DOP
                else -> OutputMode.PCM // 兜底：DSD→PCM 转码在生产者里做（需采样器）——当前用 DoP 强行走
            }
            return when (mode) {
                OutputMode.DSD_NATIVE -> StreamConfig(mode, info.dsdRateHz, 2, info.dsdRateHz, true,
                    dsdLsbFirst = info.dsdLsbFirst)
                OutputMode.DOP -> StreamConfig(mode, AudioFormatRouter.dopCarrier(info.dsdRateHz)
                    ?: error("不支持的 DSD 位率"), 8, info.dsdRateHz, true)
                OutputMode.PCM -> StreamConfig(OutputMode.DOP,
                    AudioFormatRouter.dopCarrier(info.dsdRateHz) ?: error("不支持的 DSD 位率"),
                    8, info.dsdRateHz, true)
            }
        }
        // PCM 源：始终按原率走 24-in-32 PCM；DOP/NATIVE 对 PCM 无意义
        return StreamConfig(OutputMode.PCM, info.sampleRate, 8, info.sampleRate, false)
    }

    fun stats(): LongArray = NativeBridge.nativeStats()
    fun ringUsed(): Int = NativeBridge.nativeRingUsed()
    fun ringFree(): Int = NativeBridge.nativeRingFree()

}
