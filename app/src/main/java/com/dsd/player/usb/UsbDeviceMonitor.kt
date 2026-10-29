// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/** DAC 能力摘要（Java 侧粗筛 + native probe 细化） */
data class DacInfo(
    val device: UsbDevice,
    val name: String,
    val vid: Int,
    val pid: Int,
    val hasAudioStreaming: Boolean,
    val nativeCaps: String = "",
) {
    fun label(): String =
        "%s (%04x:%04x)".format(if (name.isNotBlank()) name else "USB Audio", vid, pid)
}

/** USB DAC 插拔监听 + 权限申请。权限授予后回调 onDevice(DacInfo, connection)。 */
class UsbDeviceMonitor(
    private val context: Context,
    private val onDevice: (DacInfo, android.hardware.usb.UsbDeviceConnection) -> Unit,
    private val onDetached: () -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "UsbDeviceMonitor"
        const val ACTION_USB_PERMISSION = "com.dsd.player.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var lastDevice: UsbDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                        if (dev == null || lastDevice?.deviceName == dev.deviceName) {
                            lastDevice = null
                            onDetached()
                        }
                    } else if (dev != null) {
                        tryOpen(dev, interactive = false)
                    }
                }
                ACTION_USB_PERMISSION -> {
                    @Suppress("UNCHECKED_CAST")
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                        openNow(dev)
                    } else {
                        onError("USB 权限被拒绝")
                    }
                }
            }
        }
    }

    fun register() {
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, f, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, f)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** 主动扫描已连接的音频类设备（app 启动时调用） */
    fun scanExisting(): DacInfo? {
        if (usbManager.deviceList.isEmpty()) Log.w(TAG, "UsbManager.getDeviceList() empty (OTG off?)")
        for (d in usbManager.deviceList.values) {
            if (hasAudio(d) && !usbManager.hasPermission(d)) Log.i(TAG, "audio device ${d.vendorId.toString(16)}:${d.productId.toString(16)} needs permission")
            if (hasAudio(d)) {
                tryOpen(d, interactive = true)
                return describe(d)
            }
        }
        return null
    }

    fun describe(d: UsbDevice): DacInfo =
        DacInfo(d, safeName(d), d.vendorId, d.productId, hasAudio(d))

    private fun safeName(d: UsbDevice): String =
        runCatching { d.productName ?: "" }.getOrDefault("")

    /** 是否含可播放的音频接口：class=Audio 且有等时 OUT 端点。
     *  不限定 subclass —— UAC1 的 AudioStreaming 是 0x01，UAC2 才是 0x02，
     *  只认 0x02 会把 UAC1 DAC（不少老机型/小尾巴）直接过滤掉。 */
    private fun hasAudio(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) return true
            }
        }
        return false
    }

    private fun tryOpen(dev: UsbDevice, interactive: Boolean) {
        if (usbManager.hasPermission(dev)) {
            openNow(dev)
        } else if (interactive) {
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(dev, pi)
        }
    }

    private fun openNow(dev: UsbDevice) {
        val conn = usbManager.openDevice(dev) ?: run {
            onError("openDevice 失败"); return
        }
        lastDevice = dev
        Log.i(TAG, "DAC connected: ${describe(dev).label()}")
        onDevice(describe(dev), conn)
    }
}
