// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context

/**
 * 曲库范围设置：
 *  - [localRoot] 为空 = 按默认根目录（Music/Download/内部存储…）扫描；
 *    非空 = **只扫描该目录**（用户需求：选了路径就只收这个路径下的歌）。
 *  - [davMusicDir] 是 WebDAV 上的音乐目录（相对路径，空 = 服务器根），
 *    WebDAV 曲库只收这个目录下的文件。
 */
object ScanPrefs {
    private const val PREF = "scan_prefs"
    private const val KEY_LOCAL = "local_root"
    private const val KEY_DAV_DIR = "dav_music_dir"

    fun localRoot(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LOCAL, "").orEmpty()

    fun setLocalRoot(ctx: Context, path: String) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCAL, path.trim().trimEnd('/')).apply()
    }

    fun davMusicDir(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_DAV_DIR, "").orEmpty()

    fun setDavMusicDir(ctx: Context, dir: String) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_DAV_DIR, dir.trim().trim('/')).apply()
    }
}
