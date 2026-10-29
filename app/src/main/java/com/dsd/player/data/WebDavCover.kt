// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import java.io.File

/**
 * WebDAV 曲目的封面解析（流播用）。
 *
 * 顺序：
 *  1. **同目录封面文件**：`cover / folder / front / album / artwork / AlbumArt` + jpg/jpeg/png/webp，
 *     文件名不区分大小写；目录列表结果按目录缓存（LRU），滚动/连播不会重复 PROPFIND；
 *  2. **文件内嵌封面**：`MediaMetadataRetriever`（支持带请求头的 http(s)）取 `embeddedPicture`，
 *     落盘到 cache 后以 `file://` 交给 Coil（避免把字节数组塞进 PlayerBus）；
 *  3. 都没有 → 返回 null，界面回落到统一的"音符"占位（即音乐播放器图标）。
 */
object WebDavCover {
    private const val TAG = "WebDavCover"
    private val COVER_NAMES = listOf(
        "cover", "folder", "front", "album", "artwork", "albumart", "albumartsmall", "thumb",
    )
    private val COVER_EXTS = listOf("jpg", "jpeg", "png", "webp")

    /** 目录 → 封面文件名（"" 表示确认没有，避免反复查） */
    private val dirCache = LruCache<String, String>(64)

    /** 目录 → 解析好的封面 (url, headers)；值为 NONE 表示"查过、没有封面" */
    private val NONE = Any()
    private val dirCovers = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val coverExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dav-dir-cover").apply { isDaemon = true }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 同步读缓存（**不要**在 UI 线程调 [coverForDir]，那会做网络请求） */
    @Suppress("UNCHECKED_CAST")
    fun cachedDirCover(dir: String): Pair<String, Map<String, String>>? =
        dirCovers[dir] as? Pair<String, Map<String, String>>

    fun dirCoverAttempted(dir: String): Boolean = dirCovers.containsKey(dir)

    /**
     * 后台批量解析目录封面（UI 线程只读缓存，避免 NetworkOnMainThreadException）。
     * 期间会给每个目录写入缓存，完成后回调一次。
     */
    fun prefetchDirs(ctx: Context, dirs: Collection<String>, onDone: () -> Unit = {}) {
        prefetchDirsWithSamples(ctx, dirs.associateWith { null }, onDone)
    }

    /**
     * 同上，但额外给每个目录一个"样本曲目"。目录里没有 cover 文件时，
     * 用该曲目的**内嵌封面**当专辑封面 —— 这样网格与播放页看到的图一致
     * （第九十四轮反馈：无 cover.jpg 的目录网格会串图/留旧图）。
     */
    fun prefetchDirsWithSamples(
        ctx: Context,
        dirToSample: Map<String, String?>,
        onDone: () -> Unit = {},
    ) {
        val todo = dirToSample.filterKeys { !dirCovers.containsKey(it) }
        if (todo.isEmpty()) return
        coverExec.execute {
            todo.forEach { (d, sample) ->
                var r: Pair<String, Map<String, String>>? =
                    runCatching { coverForDir(ctx, d) }.getOrNull()
                if (r == null && !sample.isNullOrBlank()) {
                    // 退回该目录第一首的内嵌封面
                    r = runCatching {
                        val (url, headers) = WebDavResolver.resolve(sample)
                        val file = embeddedCover(ctx, url, headers) ?: return@runCatching null
                        file to emptyMap<String, String>()
                    }.getOrNull()
                }
                dirCovers[d] = r ?: NONE
            }
            mainHandler.post(onDone)
        }
    }

    /**
     * 解析某个 `webdav:<远程路径>` 条目的封面。
     * 返回 (url, headers)：url 可能是 http(s) 直链（带 Basic 认证头）或 `file://`（缓存的内嵌图）。
     */
    fun ensureCover(ctx: Context, handle: String): Pair<String, Map<String, String>>? {
        if (!WebDavResolver.isDav(handle)) return null
        val remote = WebDavResolver.remotePath(handle)
        val dir = remote.substringBeforeLast('/', "")
        val client = WebDavClient(DavConfig.load(ctx))
        val auth = runCatching { WebDavResolver.resolve(handle).second }.getOrDefault(emptyMap())

        // 1) 同目录封面文件
        val name = coverNameInDir(client, dir)
        if (name.isNotBlank()) {
            val path = if (dir.isEmpty()) name else "$dir/$name"
            return client.fileUrl(path) to auth
        }
        // 2) 内嵌封面
        val (url, headers) = runCatching { WebDavResolver.resolve(handle) }.getOrNull() ?: return null
        return embeddedCover(ctx, url, headers)?.let { it to emptyMap() }
    }

    /** 目录级封面（WebDAV 专辑/艺术家分组行用）：返回 (url, headers) 或 null */
    fun coverForDir(ctx: Context, dir: String): Pair<String, Map<String, String>>? {
        val cfg = DavConfig.load(ctx)
        if (!cfg.configured) return null
        val client = WebDavClient(cfg)
        val name = coverNameInDir(client, dir) ?: return null
        if (name.isBlank()) return null
        val path = if (dir.isBlank()) name else "$dir/$name"
        val headers = if (cfg.user.isNotBlank())
            mapOf("Authorization" to okhttp3.Credentials.basic(cfg.user, cfg.password, Charsets.UTF_8))
        else emptyMap()
        return client.fileUrl(path) to headers
    }

    private fun coverNameInDir(client: WebDavClient, dir: String): String {
        dirCache.get(dir)?.let { return it }
        val name = runCatching {
            client.list(dir).asSequence()
                .filter { !it.isDir }
                .map { it.name }
                .firstOrNull { n ->
                    val base = n.substringBeforeLast('.').lowercase()
                    val ext = n.substringAfterLast('.', "").lowercase()
                    ext in COVER_EXTS && base in COVER_NAMES
                }.orEmpty()
        }.onFailure { Log.w(TAG, "列目录失败（$dir）：${it.message}") }.getOrDefault("")
        dirCache.put(dir, name)
        Log.i(TAG, "目录「$dir」封面：${name.ifBlank { "无（将尝试内嵌图）" }}")
        return name
    }

    /** 内嵌封面 → cache 文件（file:// 路径）；没有返回 null */
    private fun embeddedCover(ctx: Context, url: String, headers: Map<String, String>): String? {
        val mmr = MediaMetadataRetriever()
        return try {
            if (headers.isEmpty()) mmr.setDataSource(url) else mmr.setDataSource(url, headers)
            val pic = mmr.embeddedPicture ?: return null
            val f = File(ctx.cacheDir, "davcover_" + url.hashCode().toUInt().toString(16) + ".img")
            if (!f.exists() || f.length() != pic.size.toLong()) f.writeBytes(pic)
            Log.i(TAG, "内嵌封面：${pic.size} 字节 → ${f.name}")
            "file://${f.absolutePath}"
        } catch (e: Throwable) {
            Log.w(TAG, "内嵌封面读取失败：${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}
