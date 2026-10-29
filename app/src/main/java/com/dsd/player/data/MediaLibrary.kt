// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

data class MediaItem(val name: String, val uri: Uri, val path: String?, val isDir: Boolean, val size: Long)

/**
 * 音频来源：优先 MediaStore 音频库；辅以直接目录浏览（/sdcard/Music 等）。
 * DSD/USB 独占要求文件路径（native 读），SAF-only 的文件会被复制到 app 缓存。
 */
object MediaLibrary {

    fun queryAudio(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Audio.Media.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idI)
                    val data = c.getString(dI)
                    // 显示名可能为空（部分扫描器），退化到路径文件名，否则扩展名过滤会误杀
                    val name = (c.getString(nI) ?: "").ifBlank {
                        data?.substringAfterLast('/') ?: ""
                    }
                    val size = c.getLong(sI)
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    if (com.dsd.player.decoder.SourceFactory.isAudio(name)) {
                        out.add(MediaItem(name, uri, data, false, size))
                    }
                }
            }
        }
        return out
    }

    fun browseDir(dir: File): List<MediaItem> {
        val files = dir.listFiles() ?: return emptyList()
        val list = files.filter { it.isDirectory || com.dsd.player.decoder.SourceFactory.isAudio(it.name) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { MediaItem(it.name, Uri.fromFile(it), it.absolutePath, it.isDirectory, it.length()) }
            .toMutableList()
        // 上级目录入口
        val parent = dir.parentFile
        if (parent != null && parent.canRead()) {
            list.add(0, MediaItem("..", Uri.fromFile(parent), parent.absolutePath, true, 0))
        }
        return list
    }

    fun defaultDirs(context: Context): List<File> =
        listOf(
            File(android.os.Environment.getExternalStorageDirectory(), "Music"),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0"),
        ).distinct().filter { it.exists() }

    /** 保证拿到真实文件路径（content:// 无路径时复制到缓存）。 */
    fun ensurePath(context: Context, item: MediaItem): String? {
        item.path?.let { if (File(it).canRead()) return it }
        return runCatching {
            val input = context.contentResolver.openInputStream(item.uri) ?: return null
            val tmp = File(context.cacheDir, "audio_" + item.name.hashCode() +
                "." + item.name.substringAfterLast('.', "bin"))
            if (!tmp.exists() || tmp.length() == 0L) {
                input.use { f -> tmp.outputStream().use { o -> f.copyTo(o, 128 * 1024) } }
            } else runCatching { input.close() }
            tmp.absolutePath
        }.getOrNull()
    }
}
