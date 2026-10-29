// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** WebDAV 曲库里的一首歌（远程路径 + 展示信息） */
data class DavTrack(
    val path: String,      // 相对服务器的路径，不含前导 /
    val name: String,      // 文件名
    val dir: String,       // 所在目录（相对路径）
    val size: Long,
) {
    /** 队列条目（不含账号密码） */
    val handle: String get() = WebDavResolver.handle(path)
    val title: String get() = name.substringBeforeLast('.')
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
}

/**
 * WebDAV 曲库：扫描「音乐目录」下的音频文件，结果缓存在内存 + SharedPreferences。
 *
 * 为什么不每次实时扫：音乐库要能秒开（也支持离线查看列表），真扫描放到后台；
 * 播放时用 `webdav:<路径>` 句柄，真正取流才连服务器。
 */
object DavLibrary {
    private const val TAG = "DavLibrary"
    private const val PREF = "dav_library"
    private const val KEY = "tracks"
    private const val MAX_FILES = 20000
    private const val MAX_DEPTH = 10
    private const val MAX_DIRS = 4000

    /** WebDAV 曲库只收这些音频扩展名（**不含 mp4**：服务器上的 mp4 多是视频，会淹掉曲库） */
    private val DAV_AUDIO_EXTS = setOf(
        "flac", "wav", "wave", "aiff", "aif", "dsf", "dff", "m4a", "m4b", "alac", "aac", "mp3", "ogg", "opus", "ape", "wv",
    )

    /** AppleDouble(`._x`)、隐藏文件、系统垃圾文件一律跳过 */
    private fun isJunk(name: String): Boolean =
        name.startsWith(".") || name.equals("Thumbs.db", true) ||
            name.equals("desktop.ini", true) || name.startsWith("~$")

    private fun isAudioName(name: String): Boolean =
        !isJunk(name) && name.substringAfterLast('.', "").lowercase() in DAV_AUDIO_EXTS

    @Volatile private var cache: List<DavTrack> = emptyList()
    @Volatile var lastScanAt: Long = 0L
        private set

    fun cached(): List<DavTrack> = cache

    /**
     * 从目录名解析「艺术家 / 专辑」，并清掉常见残留：
     * `[120919]EGOIST.-. Extra terrestrial…` → 艺术家 `EGOIST`、专辑 `Extra terrestrial…`
     * （去掉开头 `[编号]` 与 `.-.` 留下的 `.`）。只用于显示，不影响路径。
     */
    private fun clean(s: String): String = s.trim()
        .replace(Regex("""^\s*(\[[^\]]*\]\s*)+"""), "")   // 去掉开头的一个或多个 [tag]
        .trimStart('.', '-', '·', ' ', '_')
        .trimEnd('.', ' ', '-', '_')
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    /** 目录名（最后一级）；根目录返回 "/" */
    fun dirName(dir: String): String = dir.substringAfterLast('/').ifBlank { "/" }

    /** 专辑名：`Artist - Album` 取后半段；解析不到就用清洗后的目录名 */
    fun albumOf(dir: String): String {
        val name = dirName(dir)
        val pair = com.dsd.player.data.Library.splitFolderName(name)
        val album = if (pair != null) clean(pair.second) else clean(name)
        return album.ifBlank { name }
    }

    /** 艺术家：`Artist - Album` 取前半段；否则退回清洗后的目录名（根目录给"未知艺术家"） */
    fun artistOf(dir: String): String {
        val name = dirName(dir)
        val pair = com.dsd.player.data.Library.splitFolderName(name)
        val artist = if (pair != null) clean(pair.first) else ""
        if (artist.isNotBlank()) return artist
        val self = clean(name)
        return if (self.isBlank() || self == "/") "未知艺术家" else self
    }

    fun loadCached(ctx: Context): List<DavTrack> {
        if (cache.isNotEmpty()) return cache
        val raw = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        cache = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val p = o.optString("path")
                if (p.isBlank()) null else DavTrack(
                    path = p, name = o.optString("name"),
                    dir = o.optString("dir"), size = o.optLong("size"),
                )
            }
        }.getOrDefault(emptyList())
        return cache
    }

    /**
     * 全量扫描（IO 线程）：从「音乐目录」开始**广度优先**遍历，只收音频白名单。
     *
     * 之前用递归 + 全局 visited 预算，扫根目录时会在第一个大目录（一堆视频子目录）里
     * 把预算耗光，结果只剩那一个顶层目录的内容（第九十轮报的"778 首全是 mp4"）。
     * 现在改成显式队列 + 每目录单独计数日志，一眼能看出哪个目录收了多少。
     */
    fun scan(ctx: Context): List<DavTrack> {
        val cfg = DavConfig.load(ctx)
        if (!cfg.configured) { cache = emptyList(); return cache }
        val root = ScanPrefs.davMusicDir(ctx)
        val client = WebDavClient(cfg)
        val out = ArrayList<DavTrack>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(root to 0)
        var dirs = 0
        var skippedByCap = 0
        val perDir = ArrayList<Triple<String, Int, Int>>()   // dir, 文件数, 子目录数
        while (queue.isNotEmpty()) {
            if (dirs >= MAX_DIRS || out.size >= MAX_FILES) { skippedByCap = queue.size; break }
            val (dir, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) continue
            dirs++
            val entries = try {
                client.list(dir)
            } catch (e: Throwable) {
                Log.w(TAG, "列目录失败（${dir.ifBlank { "/" }}）：${e.message}")
                continue
            }
            var files = 0
            var subdirs = 0
            for (e in entries) {
                if (out.size >= MAX_FILES) break
                val path = if (dir.isBlank()) e.name else "$dir/${e.name}"
                if (e.isDir) {
                    if (isJunk(e.name)) continue
                    subdirs++
                    queue.add(path to depth + 1)
                } else if (isAudioName(e.name)) {
                    out.add(DavTrack(path, e.name, dir, e.size))
                    files++
                }
            }
            perDir.add(Triple(dir.ifBlank { "/" }, files, subdirs))
        }
        // 逐目录明细只在 debug 里打（995 首时会把 logcat 刷满）
        if (com.dsd.player.BuildConfig.DEBUG) {
            perDir.take(30).forEach { (d, f, s2) -> Log.i(TAG, "目录「$d」收集 $f 首，子目录 $s2 个") }
            if (perDir.size > 30) Log.i(TAG, "…（共 ${perDir.size} 个目录，其余略）")
        }
        if (skippedByCap > 0) Log.w(TAG, "⚠ 达到上限（目录 $MAX_DIRS / 文件 $MAX_FILES），"
            + "还有 $skippedByCap 个目录没扫：请把音乐目录指到更具体的子目录")
        cache = out.sortedWith(compareBy({ it.dir.lowercase() }, { it.name.lowercase() }))
        lastScanAt = System.currentTimeMillis()
        save(ctx)
        Log.i(TAG, "WebDAV 曲库扫描完成：目录「${root.ifBlank { "/" }}」，" +
            "扫 ${dirs} 个目录，${cache.size} 首（跳过隐藏/AppleDouble/mp4 等）")
        return cache
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        cache.forEach { t ->
            arr.put(JSONObject().apply {
                put("path", t.path); put("name", t.name); put("dir", t.dir); put("size", t.size)
            })
        }
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }
}
