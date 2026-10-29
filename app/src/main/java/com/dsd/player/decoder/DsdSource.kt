// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.content.Context
import android.net.Uri
import com.dsd.player.usb.NativeBridge
import java.io.RandomAccessFile

/**
 * DSF / DFF：容器解析与数据读取走 native（dsd_native.cpp，含 DSF 位反转与
 * 半文件交织还原）。Kotlin 侧只管路径与 fd 定位。
 */
class DsdStreamSource(context: Context, uri: Uri) : AudioSource {
    override lateinit var info: AudioInfo
        private set
    private var cachedPath: String = ""
    private var usingNative = false

    private var initPath: String? = null

    init {
        NativeBridge.ensureLoaded()
        val path = uri.path?.takeIf { it.contains('.') }
            ?: throw IllegalArgumentException("需要文件路径 URI（原生 DAC 播放不支持 SAF URI）：$uri")
        openPath(path)
    }

    override fun bindPath(path: String) {
        cachedPath = path
        if (!::info.isInitialized) runCatching { openPath(path) }
    }

    fun openPath(path: String) {
        // 先用 native 探测
        if (NativeBridge.nativeDsdOpen(path)) {
            val rate = NativeBridge.nativeDsdRate()
            val lsb = NativeBridge.nativeDsdLsbFirst()
            val bytes = NativeBridge.nativeDsdTotalBytes()
            info = AudioInfo(
                if (path.endsWith(".dff", true)) "DFF" else "DSF",
                0, 2, 1, true, rate, lsb,
                bytes * 4_000_000 / rate.coerceAtLeast(1)
            )
            cachedPath = path
            usingNative = true
            NativeBridge.nativeDsdClose() // openFor 时才重开
            return
        }
        val err = NativeBridge.nativeLastError() ?: "未知"
        throw IllegalArgumentException("DSD 解析失败：$err")
    }

    /** content:// 需要 fd+offset 时：先复制到缓存再走路径（README 说明） */
    override fun openPcm(): ByteSource = throw IllegalStateException("DSD 源请用 openDsd()")

    override fun openDsd(): ByteSource {
        // 立即打开 native 容器（长度/进度需要），DsdByteSource 复用此打开态
        if (cachedPath.isNotEmpty() && NativeBridge.nativeDsdOpen(cachedPath)) {
            return DsdByteSource(cachedPath, opened = true)
        }
        return DsdByteSource(cachedPath)
    }



    override fun close() {}

    private inner class DsdByteSource(
        private val path: String,
        private var opened: Boolean = false,
    ) : ByteSource {
        private var current = 0L

        override val lengthBytes: Long get() = NativeBridge.nativeDsdTotalBytes()

        override fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (!opened && !NativeBridge.nativeDsdOpen(path)) return -1
            opened = true
            val n = NativeBridge.nativeDsdRead(current, dst, off, len)
            if (n > 0) current += n
            return n
        }

        override fun seek(bytePos: Long): Boolean {
            current = bytePos
            return true // dsd_read 带 pos 参数
        }
        override fun close() {
            if (opened) NativeBridge.nativeDsdClose()
            opened = false
        }
    }
}

/** 便捷工厂：按扩展名/容器判断创建 AudioSource。content:// SAF URI 不支持 DSD native（需路径）。 */
object SourceFactory {
    /** 在线音源（Qobuz 直链等）：path 形如 https://… */
    fun isUrl(path: String?): Boolean =
        path != null && (path.startsWith("http://") || path.startsWith("https://"))

    fun create(context: Context, uri: Uri, pathHint: String? = null): AudioSource {
        if (com.dsd.player.data.WebDavResolver.isDav(pathHint ?: "")) {
            // WebDAV 流播：条目是 webdav:<远程路径>，这里才拼直链 + Basic 认证头
            val (url, headers) = com.dsd.player.data.WebDavResolver.resolve(pathHint!!)
            android.util.Log.i("SourceFactory", "WebDAV 流播：${url.take(80)}（认证头 ${headers.size} 个）")
            return HttpFlacSource(context, url, headers)
        }
        if (isUrl(pathHint)) {
            // 在线 FLAC/MP3：走 Range 随机读的数据源，解码与本地一致
            return HttpFlacSource(context, pathHint!!)
        }
        // MediaStore 的 content uri path 不是文件路径——优先用调用方给的真实路径
        val path = pathHint?.takeIf { it.isNotBlank() } ?: uri.path
        val lname = (path ?: uri.toString()).lowercase()
        val sniffUri = if (path != null) android.net.Uri.fromFile(java.io.File(path)) else uri
        val src: AudioSource = when {
            lname.endsWith(".dsf") || lname.endsWith(".dff") -> DsdStreamSource(context, sniffUri)
            lname.endsWith(".flac") -> FlacSource(context, sniffUri)
            lname.endsWith(".wav") || lname.endsWith(".aiff") || lname.endsWith(".aif") ||
                lname.endsWith(".wave") -> WavSource(context, sniffUri)
            // Apple ALAC / AAC：.m4a/.mp4/.aac/.alac（本地文件，不是 Apple Music 流）
            lname.endsWith(".m4a") || lname.endsWith(".m4b") || lname.endsWith(".alac") ||
                lname.endsWith(".mp4") || lname.endsWith(".aac") -> AlacSource(context, sniffUri)
            else -> {
                // 按内容嗅探（用文件 URI 保证构造器能直接 open）
                runCatching { FlacSource(context, sniffUri) }
                    .getOrElse { runCatching { WavSource(context, sniffUri) }
                        .getOrElse { runCatching {
                            if (path != null && AlacSource.looksLikeMp4(path)) AlacSource(context, sniffUri)
                            else error("not mp4")
                        }.getOrElse { DsdStreamSource(context, sniffUri) } } }
            }
        }
        if (path != null && runCatching { java.io.File(path).canRead() }.getOrDefault(false))
            src.bindPath(path)
        return src
    }

    val AUDIO_EXTS = setOf("flac", "wav", "wave", "aiff", "aif", "dsf", "dff",
        "m4a", "m4b", "alac", "mp4", "aac")
    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS
}
