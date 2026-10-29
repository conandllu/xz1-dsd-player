// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 把 WebDAV 上的单个文件**下载到本地音乐目录**（`Music/WebDAV/<远程路径>`），
 * 这样它既能离线播放，也会被本地曲库扫描到。
 *
 * 与"流播"的区别：流播不落盘（第八十二轮），下载是明确的长按动作。
 */
object DavDownload {
    /** 下载根目录：`/storage/emulated/0/Music/WebDAV` */
    fun rootDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WebDAV")

    /**
     * 目标文件（保持远程子目录结构，方便在文件夹里找到）。
     *
     * 一个小坑：音乐目录设成服务器根 `/` 时，远程路径本身就以 `Music/` 开头
     * （`Music/[Hi-Res]专辑/01.flac`），直接拼会得到 `Music/WebDAV/Music/…` 的双层 Music。
     * 这里把首段是与本地音乐目录同名的 `Music` 去掉，最终统一成
     * `Music/WebDAV/[Hi-Res]专辑/01.flac`。
     */
    fun targetFile(ctx: Context, remotePath: String): File {
        val root = rootDir()
        var clean = remotePath.trim('/').split('/').filter { it.isNotBlank() }
        if (clean.size > 1 && clean.first().equals(Environment.DIRECTORY_MUSIC, ignoreCase = true)) {
            clean = clean.drop(1)
        }
        var dir = root
        clean.dropLast(1).forEach { dir = File(dir, it) }
        dir.mkdirs()
        return File(dir, clean.lastOrNull() ?: "download.bin")
    }

    /** 给用户看的相对路径（`Music/WebDAV/…`），用于提示"下载到哪了" */
    fun displayPath(ctx: Context, file: File): String {
        val abs = file.absolutePath
        val cut = abs.indexOf("/${Environment.DIRECTORY_MUSIC}/")
        return if (cut >= 0) abs.substring(cut + 1) else abs
    }

    /** 下载；已存在且大小一致则直接返回（幂等） */
    fun download(
        ctx: Context,
        remotePath: String,
        expectedSize: Long = 0,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val cfg = DavConfig.load(ctx)
        require(cfg.configured) { "未配置 WebDAV" }
        val dest = targetFile(ctx, remotePath)
        if (dest.exists() && dest.length() > 0 && (expectedSize <= 0 || dest.length() == expectedSize)) {
            return dest
        }
        WebDavClient(cfg).download(remotePath, dest, onProgress)
        return dest
    }
}
