// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.dsd.player.decoder.SourceFactory
import java.io.File

/** 一首曲目（MediaStore 与磁盘扫描合并后的统一模型） */
data class Track(
    val title: String,
    val uri: Uri,
    val path: String?,
    val album: String,
    val artist: String,
    val albumId: Long,
    val trackNo: Int,
    val year: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val ext: String,
    val folder: String,
    val genre: String = "",
    val dateAdded: Long = 0L,
) {
    /** MediaStore 封面 URI（albumId 为 0 时返回 null） */
    fun artUri(): android.net.Uri? =
        if (albumId > 0L) android.content.ContentUris.withAppendedId(
            android.net.Uri.parse("content://media/external/audio/albumart"), albumId) else null

    /** 播放条/列表副标题：格式 · 时长 · 艺术家 */
    /** 展示用专辑名：MediaStore 常把目录名（"艺术家 - 专辑"）直接当专辑 */
    fun displayAlbum(): String {
        val split = Library.splitFolderName(folder)
        return if (split != null && album.equals(folder.substringAfterLast('/'), ignoreCase = true))
            split.second else album
    }

    fun subtitle(): String = buildString {
        append(ext.uppercase())
        if (durationMs > 0) append(" · ").append(fmtDuration(durationMs))
        if (artist.isNotBlank() && artist != "<unknown>") append(" · ").append(artist)
        if (sizeBytes > 0) {
            append(" · ")
            append(if (sizeBytes >= 1048576) "%.1f MB".format(sizeBytes / 1048576.0)
                   else "%.0f KB".format(sizeBytes / 1024.0))
        }
    }

    companion object {
        fun fmtDuration(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
            else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/** 一个分组（专辑 / 艺术家 / 文件夹都复用） */
data class TrackGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>,
    val albumId: Long = 0,
) {
    val count: Int get() = tracks.size
    val totalMs: Long get() = tracks.sumOf { it.durationMs }
}

/**
 * 曲库：MediaStore + 磁盘扫描合并。
 * 磁盘扫描是必要的——DSF/DFF 不在 MediaStore 音频库里的情况很常见。
 */
object Library {

    private const val TAG = "Library"

    /** reload(context) 时记下的 application context，供 scanDisk 读「本地音乐目录」设置 */
    @Volatile private var androidContext: android.content.Context? = null

    /** 目录名 "Artist - Album" → (artist, album)；无该形态返回 null */
    fun splitFolderName(folder: String): Pair<String, String>? {
        val name = folder.substringAfterLast('/').ifBlank { return null }
        for (sep in listOf(" - ", " – ", " — ", "-")) {
            val i = name.indexOf(sep)
            if (i > 2 && i < name.length - sep.length - 1) {
                val a = name.substring(0, i).trim()
                val b = name.substring(i + sep.length).trim()
                if (a.isNotEmpty() && b.isNotEmpty()) return a to b
            }
        }
        return null
    }

    /** 文件名 "01 - 曲名" / "01. 曲名" → (音轨号, 曲名) */
    fun splitTrackName(raw: String): Pair<Int, String> {
        // 1~2 位数字 + 任意分隔（含空格，如 "01 Don't Know Why"）
        Regex("^\\s*(\\d{1,2})\\s*[.\\s_\\-)\\]]+\\s*(.+)$").find(raw)?.let { m ->
            val no = m.groupValues[1].toIntOrNull() ?: return@let
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) return no to rest
        }
        // 3 位数字必须是明确的分隔符（避免把 "2001 A Space Odyssey" 当成第 2001 轨）
        Regex("^\\s*(\\d{3})\\s*[.\\-_)]+\\s*(.+)$").find(raw)?.let { m ->
            val no = m.groupValues[1].toIntOrNull() ?: return@let
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) return no to rest
        }
        return 0 to raw
    }
    private val SCAN_EXTS = setOf("flac", "wav", "wave", "aiff", "aif", "dsf", "dff",
        "m4a", "m4b", "alac", "mp4", "aac")

    @Volatile private var cache: List<Track> = emptyList()

    /** 最近一次扫描的统计：MediaStore 条数 / 扫盘条数 / 扫盘是否被权限挡住 */
    data class ScanStats(val dbCount: Int, val diskCount: Int, val diskBlocked: Boolean)
    @Volatile var lastStats = ScanStats(0, 0, false)
        private set
    @Volatile private var loadedAt = 0L

    fun cached(): List<Track> = cache

    fun isReady(): Boolean = cache.isNotEmpty()

    /** 重新扫描（IO 线程调用） */
    fun reload(context: Context): List<Track> {
        androidContext = context.applicationContext
        val fromDb = queryMediaStore(context)
        val fromDisk = scanDisk()
        // 用路径去重：MediaStore 优先（带元数据），磁盘补漏（DSD 等）
        val diskByPath = HashMap<String, Track>()
        for (t in fromDisk) t.path?.let { diskByPath[it] = t }
        val map = LinkedHashMap<String, Track>()
        for (t in fromDb) {
            // MediaStore 对部分文件拿不到时长（如带 id3 块、无标签的 WAV）→ 用扫盘结果补
            val d = t.path?.let { diskByPath[it] }
            map[t.path ?: t.uri.toString()] =
                if (d != null && t.durationMs <= 0 && d.durationMs > 0) t.copy(durationMs = d.durationMs)
                else t
        }
        for (t in fromDisk) {
            val k = t.path ?: continue
            if (!map.containsKey(k)) map[k] = t
        }
        // 用户指定了「本地音乐目录」→ 只保留该目录下的歌（路径前缀匹配，注意补 / 防 MusicX 误匹配）
        scanRoot = ScanPrefs.localRoot(context)
        val merged = map.values.toMutableList()
        val list = (if (scanRoot.isBlank()) merged else {
            val pfx = if (scanRoot.endsWith("/")) scanRoot else "$scanRoot/"
            merged.filter { it.path?.startsWith(pfx) == true || it.path == scanRoot }
        }).sortedWith(compareBy({ it.title.lowercase() }, { it.path ?: "" }))
        // 扫盘被分区存储挡住时 listFiles 返回 null，这里明确记录（真机常见：重装后权限被重置）
        val diskBlocked = fromDisk.isEmpty() && audioRoots().any { root ->
            runCatching { root.listFiles() }.getOrNull() == null
        }
        lastStats = ScanStats(fromDb.size, fromDisk.size, diskBlocked)
        android.util.Log.i("Library",
            "扫描完成：MediaStore ${fromDb.size} 首，磁盘 ${fromDisk.size} 首，合并 ${list.size} 首，" +
            "扫盘受限=$diskBlocked")
        cache = list
        clearBrowseCache()
        loadedAt = System.currentTimeMillis()
        return list
    }

    // ── MediaStore ────────────────────────────────────────────────────
    private fun queryMediaStore(context: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Audio.Media.TITLE + " ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iGenre = c.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val data = c.getString(iData)
                    val name = (c.getString(iName) ?: "").ifBlank {
                        data?.substringAfterLast('/') ?: ""
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in SCAN_EXTS) continue
                    val id = c.getLong(iId)
                    val rawTitle = c.getString(iTitle)?.ifBlank { name } ?: name
                    val rawArtist = c.getString(iArtist) ?: ""
                    val untagged = rawArtist.isBlank() || rawArtist == "<unknown>"
                    val folderPath = data?.substringBeforeLast('/') ?: ""
                    val split = if (untagged) splitFolderName(folderPath) else null
                    val (noGuess, titleGuess) = if (untagged) splitTrackName(
                        rawTitle.removeSuffix(".$ext")) else (0 to rawTitle)
                    out.add(
                        Track(
                            title = titleGuess,
                            trackNo = if (untagged) noGuess else c.getInt(iTrack),
                            uri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            path = data,
                            album = (c.getString(iAlbum) ?: "").ifBlank { split?.second ?: "" },
                            artist = if (untagged) (split?.first ?: "") else rawArtist,
                            albumId = c.getLong(iAlbumId),
                            year = c.getInt(iYear),
                            durationMs = c.getLong(iDur),
                            sizeBytes = c.getLong(iSize),
                            ext = ext,
                            folder = data?.substringBeforeLast('/') ?: "",
                            genre = c.getString(iGenre) ?: "",
                            dateAdded = c.getLong(iAdded) * 1000L,
                        )
                    )
                }
            }
        }
        return out
    }

    // ── 磁盘扫描（补 DSD 等未被索引的格式） ────────────────────────────
    /** 用户指定的扫描根（设置里的「本地音乐目录」）；空 = 默认根 */
    @Volatile private var scanRoot: String = ""

    /** 扫描根为空时用的默认目录集合（目录浏览也用这个列表） */
    fun audioRoots(): List<File> = listOf(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath),
        File(Environment.getExternalStorageDirectory(), "Music"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath),
        File(Environment.getExternalStorageDirectory(), "Download"),
        File(Environment.getExternalStorageDirectory(), "音乐"),
        File("/storage/emulated/0"),
    ).distinct().filter { it.isDirectory && it.canRead() }

    /** M4A/ALAC 时长：MediaMetadataRetriever 直接读 mvhd（失败返回 0，不影响播放） */
    private fun probeMp4DurationMs(f: File): Long = runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrDefault(0L)

    /** 只读容器头估算 DSD 时长（DSF: fmt@56 采样率 + data 块长度；DFF: FS + DSD 块长度） */
    private fun probeDsdDurationMs(f: File): Long {
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { r ->
                val head = ByteArray(92)
                if (r.read(head) != 92) return 0L
                fun le32(o: Int): Long = ((head[o].toLong() and 0xFF) or
                    ((head[o + 1].toLong() and 0xFF) shl 8) or
                    ((head[o + 2].toLong() and 0xFF) shl 16) or
                    ((head[o + 3].toLong() and 0xFF) shl 24))
                fun be64(o: Int): Long {
                    var v = 0L
                    for (i in 0 until 8) v = (v shl 8) or (head[o + i].toLong() and 0xFF)
                    return v
                }
                val isDsf = String(head, 0, 4, Charsets.US_ASCII) == "DSD "
                val len = f.length()
                if (isDsf) {
                    val rate = le32(56)
                    if (rate <= 0) return 0L
                    // data 块在 fmt(28+52=80) 之后：80 处 'data' + 4..11 长度
                    r.seek(80)
                    val dh = ByteArray(12)
                    if (r.read(dh) != 12) return 0L
                    var csize = 0L
                    for (i in 0 until 8) csize = csize or ((dh[4 + i].toLong() and 0xFF) shl (8 * i))
                    val dataBytes = if (csize in 13..len) csize - 12 else len - 92
                    dataBytes * 4000 / rate
                } else {
                    // DFF：扫 FS 采样率与 DSD 块大小
                    var rate = 0L
                    var dataBytes = 0L
                    var pos = 16L
                    var guard = 0
                    while (pos + 12 <= len && guard++ < 32) {
                        r.seek(pos)
                        val ch = ByteArray(12)
                        if (r.read(ch) != 12) break
                        val id = String(ch, 0, 4, Charsets.US_ASCII)
                        var csize = 0L
                        for (i in 0 until 8) csize = csize or ((ch[4 + i].toLong() and 0xFF) shl (8 * (7 - i)))
                        if (id == "PROP") { pos += 12 + 4; continue }
                        if (id == "FS  ") { r.seek(pos + 12); val b = ByteArray(4); r.read(b)
                            rate = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
                                   ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
                            pos += 12 + csize + (csize and 1); continue }
                        if (id == "DSD ") { dataBytes = csize; break }
                        if (csize == 0L) { pos += 12; continue }
                        pos += 12 + csize + (csize and 1)
                    }
                    if (rate <= 0L || dataBytes <= 0L) 0L else dataBytes * 4000 / rate
                }
            }
        }.getOrDefault(0L)
    }

    /** 读 RIFF/AIFF 头算 PCM 时长（毫秒）；失败返回 0 */
    private fun probePcmDurationMs(f: File): Long {
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { r ->
                val len = f.length()
                val h = ByteArray(12)
                if (r.read(h) != 12) return 0L
                val magic = String(h, 0, 4, Charsets.US_ASCII)
                var pos: Long
                var byteRate = 0L
                var dataLen = 0L
                if (magic == "RIFF") {
                    pos = 12
                    while (pos + 8 <= len) {
                        r.seek(pos)
                        val c = ByteArray(8)
                        if (r.read(c) != 8) break
                        val id = String(c, 0, 4, Charsets.US_ASCII)
                        val sz = ((c[4].toLong() and 0xFF) or ((c[5].toLong() and 0xFF) shl 8) or
                            ((c[6].toLong() and 0xFF) shl 16) or ((c[7].toLong() and 0xFF) shl 24))
                        if (id == "fmt ") {
                            r.seek(pos + 8)
                            val fb = ByteArray(12)
                            if (r.read(fb) != 12) break
                            byteRate = ((fb[8].toLong() and 0xFF) or ((fb[9].toLong() and 0xFF) shl 8) or
                                ((fb[10].toLong() and 0xFF) shl 16) or ((fb[11].toLong() and 0xFF) shl 24))
                        } else if (id == "data") {
                            dataLen = if (sz <= 0) len - (pos + 8) else sz
                            break
                        }
                        if (sz <= 0) break
                        pos += 8 + sz + (sz and 1)
                    }
                } else if (magic == "FORM") {
                    // AIFF: 用 SSND 长度 / (rate*ch*bytes) —— 这里只取常见情况
                    pos = 12
                    var frames = 0L
                    var ssndLen = 0L
                    while (pos + 8 <= len) {
                        r.seek(pos)
                        val c = ByteArray(8)
                        if (r.read(c) != 8) break
                        val id = String(c, 0, 4, Charsets.US_ASCII)
                        val sz = ((c[4].toLong() and 0xFF) shl 24) or ((c[5].toLong() and 0xFF) shl 16) or
                            ((c[6].toLong() and 0xFF) shl 8) or (c[7].toLong() and 0xFF)
                        if (id == "COMM") {
                            r.seek(pos + 8)
                            val cb = ByteArray(8)
                            if (r.read(cb) == 8) frames = ((cb[2].toLong() and 0xFF) shl 24) or
                                ((cb[3].toLong() and 0xFF) shl 16) or ((cb[4].toLong() and 0xFF) shl 8) or
                                (cb[5].toLong() and 0xFF)
                        } else if (id == "SSND") { ssndLen = sz; break }
                        if (sz <= 0) break
                        pos += 8 + sz + (sz and 1)
                    }
                    if (ssndLen > 0 && frames > 0) {
                        // 时长用 COMM 的 frames / rate（rate 需 80-bit，粗略用 44100 兜底不可靠）→ 交给上层
                        return 0L
                    }
                }
                if (byteRate > 0 && dataLen > 0) dataLen * 1000 / byteRate else 0L
            }
        }.getOrDefault(0L)
    }

    private fun scanDisk(): List<Track> {
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        val visited = intArrayOf(0)          // 目录访问预算，防止扫全盘
        val userRoot = ScanPrefs.localRoot(androidContext ?: return emptyList())
        for (root in if (userRoot.isNotBlank()) listOf(File(userRoot)) else audioRoots()) {
            walk(root, 0, out, seen, visited, maxDepth = 6, maxDirs = 3000)
        }
        return out
    }

    private fun walk(dir: File, depth: Int, out: MutableList<Track>, seen: MutableSet<String>,
                     visited: IntArray, maxDepth: Int, maxDirs: Int) {
        if (depth > maxDepth || visited[0] >= maxDirs) return
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        val name = dir.name
        // 跳过明显不是音乐的目录
        if (name.equals("Android", true) || name.startsWith(".") ||
            name.equals("tencent", true) || name.equals("DCIM", true)) return
        visited[0]++
        for (f in files) {
            if (visited[0] >= maxDirs) return
            if (f.isDirectory) {
                walk(f, depth + 1, out, seen, visited, maxDepth, maxDirs)
            } else {
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (ext !in SCAN_EXTS) continue
                val p = f.absolutePath
                if (!seen.add(p)) continue
                if (out.size > 20_000) return
                val durMs = when (ext) {
                    "dsf", "dff" -> probeDsdDurationMs(f)
                    "wav", "wave" -> probePcmDurationMs(f)
                    // ALAC/M4A 的时长在 MP4 mvhd/mdhd 里，交给系统元数据解析（比自解析稳）
                    "m4a", "m4b", "alac", "mp4", "aac" -> probeMp4DurationMs(f)
                    else -> 0L
                }
                val folderPath = f.parent ?: ""
                val split = splitFolderName(folderPath)
                val (no, ttl) = splitTrackName(f.nameWithoutExtension)
                out.add(
                    Track(
                        title = ttl,
                        uri = Uri.fromFile(f),
                        path = p,
                        album = split?.second ?: (f.parentFile?.name ?: ""),
                        artist = split?.first ?: "",
                        albumId = 0,
                        trackNo = no,
                        year = 0,
                        durationMs = durMs,
                        sizeBytes = f.length(),
                        ext = ext,
                        folder = f.parent ?: "",
                    )
                )
            }
        }
    }

    // ── 分组 ──────────────────────────────────────────────────────────
    /** 专辑显示名：优先标签里的 album，空/等于目录名时尝试拆「艺术家 - 专辑」目录名 */
    fun albumNameOf(t: Track): String {
        val raw = t.album.ifBlank { folderName(t) }
        if (raw.isBlank()) return "未知专辑"
        val split = splitFolderName(t.folder)
        return if (split != null && raw.equals(folderName(t), ignoreCase = true)) split.second else raw
    }

    fun byAlbum(tracks: List<Track>): List<TrackGroup> {
        val map = LinkedHashMap<String, MutableList<Track>>()
        for (t in tracks) {
            val album = albumNameOf(t)
            // 只按**专辑名**分组：同名专辑不因艺术家不同而拆开（用户诉求），
            // 也不用 albumId —— MediaStore 与扫盘里同一张专辑的 id 不同，按 id 分会重复列出
            val key = "name:" + album.lowercase()
            map.getOrPut(key) { ArrayList() }.add(t)
        }
        return map.map { (key, list) ->
            val sorted = list.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
            val first = sorted.first()
            // MediaStore 常把未标注 WAV 的 album 设成目录名；若目录是 "艺术家 - 专辑" 形态，
            // 就取后半段作为专辑名（前半段交给艺术家列）
            val split = splitFolderName(first.folder)
            val albumName = run {
                val raw = first.album.ifBlank { folderName(first) }
                when {
                    raw.isBlank() -> split?.second ?: "未知专辑"
                    split != null && raw.equals(folderName(first), ignoreCase = true) -> split.second
                    else -> raw
                }
            }
            val artists = sorted.map { it.artist }
                .filter { it.isNotBlank() && it != "<unknown>" }
                .distinct()
            // 同名专辑可能横跨多位艺术家（合辑/合唱），>2 位时折叠成「多位艺术家」
            val artist = when {
                artists.isEmpty() -> split?.first ?: ""
                artists.size <= 2 -> artists.joinToString(", ")
                else -> "多位艺术家"
            }
            val year = sorted.firstOrNull { it.year > 0 }?.year ?: 0
            TrackGroup(
                key = key,
                title = albumName,
                subtitle = when {
                    artist.isNotBlank() -> if (year > 0) "$artist · $year" else artist
                    year > 0 -> "$year 年"
                    else -> ""          // 没有艺术家就不显示，避免满屏"无艺术家标签"
                },
                tracks = sorted,
                albumId = sorted.first().albumId,
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun byArtist(tracks: List<Track>): List<TrackGroup> {
        val map = LinkedHashMap<String, MutableList<Track>>()
        for (t in tracks) {
            val a = t.artist.ifBlank { null }
                ?: splitFolderName(t.folder)?.first
                ?: "未知艺术家"
            map.getOrPut(a) { ArrayList() }.add(t)
        }
        return map.map { (artist, list) ->
            // 艺术家分组本身不按专辑拆开；这里只统计"有多少张专辑"作为副标题
            val albums = list.map { albumNameOf(it) }.distinct().size
            TrackGroup(
                key = "artist:$artist",
                title = artist,
                subtitle = "$albums 张专辑",
                tracks = list.sortedWith(compareBy({ albumNameOf(it).lowercase() }, { it.trackNo })),
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun byFolder(tracks: List<Track>): List<TrackGroup> {
        val map = LinkedHashMap<String, MutableList<Track>>()
        for (t in tracks) {
            val folder = t.folder.ifBlank { "未知目录" }
            map.getOrPut(folder) { ArrayList() }.add(t)
        }
        return map.map { (folder, list) ->
            TrackGroup(
                key = "folder:$folder",
                title = folder.substringAfterLast('/').ifBlank { folder },
                subtitle = folder,
                tracks = list.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() })),
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun byGenre(tracks: List<Track>): List<TrackGroup> {
        val map = LinkedHashMap<String, MutableList<Track>>()
        for (t in tracks) {
            val g = t.genre.ifBlank { "未分类" }
            map.getOrPut(g) { ArrayList() }.add(t)
        }
        return map.map { (g, list) ->
            TrackGroup("genre:$g", g, "${list.size} 首", list.sortedBy { it.title.lowercase() })
        }.sortedBy { it.title.lowercase() }
    }

    fun byYear(tracks: List<Track>): List<TrackGroup> {
        val map = LinkedHashMap<Int, MutableList<Track>>()
        for (t in tracks) map.getOrPut(t.year) { ArrayList() }.add(t)
        return map.map { (y, list) ->
            TrackGroup("year:$y", if (y > 0) "$y 年" else "年份未知", "${list.size} 首",
                list.sortedBy { it.title.lowercase() })
        }.sortedByDescending { it.key.substringAfter(':').toIntOrNull() ?: 0 }
    }

    /** 排序键 */
    enum class SortKey { ALBUM, YEAR, ADDED, MODIFIED, TITLE }

    fun sortTracks(tracks: List<Track>, key: SortKey, desc: Boolean): List<Track> {
        val cmp: Comparator<Track> = when (key) {
            SortKey.ALBUM -> compareBy({ it.album.lowercase() }, { it.trackNo }, { it.title.lowercase() })
            SortKey.YEAR -> compareBy({ it.year }, { it.title.lowercase() })
            SortKey.ADDED -> compareBy { it.dateAdded }
            SortKey.MODIFIED -> compareBy { it.dateAdded }
            SortKey.TITLE -> compareBy { it.title.lowercase() }
        }
        val sorted = tracks.sortedWith(cmp)
        return if (desc) sorted.reversed() else sorted
    }

    fun sortGroups(groups: List<TrackGroup>, key: SortKey, desc: Boolean): List<TrackGroup> {
        val cmp: Comparator<TrackGroup> = when (key) {
            SortKey.YEAR -> compareBy { it.tracks.firstOrNull()?.year ?: 0 }
            SortKey.ADDED, SortKey.MODIFIED -> compareBy { it.tracks.maxOfOrNull { t -> t.dateAdded } ?: 0L }
            else -> compareBy { it.title.lowercase() }
        }
        val sorted = groups.sortedWith(cmp)
        return if (desc) sorted.reversed() else sorted
    }

    private fun folderName(t: Track): String =
        t.folder.substringAfterLast('/').ifBlank { "" }

    // ── 文件夹浏览 ────────────────────────────────────────────────────
    data class DirEntry(val dir: File, val audioCount: Int, val subdirCount: Int)

    private val browseCache = HashMap<String, List<DirEntry>>()

    fun clearBrowseCache() = browseCache.clear()

    /** 列子目录（带递归曲目数，结果缓存）与直属音频文件 */
    fun browse(dir: File, tracks: List<Track>): Pair<List<DirEntry>, List<Track>> {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList<DirEntry>() to emptyList()
        val subdirs = browseCache.getOrPut(dir.absolutePath) {
            val list = ArrayList<DirEntry>()
            for (f in files) {
                if (!f.isDirectory) continue
                if (f.name.startsWith(".")) continue
                val (dirs, audios) = countAudio(f, 0, 600)
                list.add(DirEntry(f, audios, dirs))
            }
            list.sortedBy { it.dir.name.lowercase() }
        }
        val prefix = dir.absolutePath
        val direct = tracks.filter { t ->
            val p = t.path ?: return@filter false
            p.substringBeforeLast('/') == prefix
        }.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
        return subdirs to direct
    }

    /** (子目录数, 音频文件数)，带预算防止扫太久 */
    private fun countAudio(dir: File, depth: Int, budget: Int): Pair<Int, Int> {
        if (depth > 4 || budget <= 0) return 0 to 0
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return 0 to 0
        var dirs = 0
        var audios = 0
        var left = budget
        for (f in files) {
            if (left <= 0) break
            if (f.isDirectory) {
                if (f.name.startsWith(".")) continue
                dirs++
                left--
                val (d, a) = countAudio(f, depth + 1, left)
                dirs += d; audios += a; left -= (d + a)
            } else {
                left--
                if (f.name.substringAfterLast('.', "").lowercase() in SCAN_EXTS) audios++
            }
        }
        return dirs to audios
    }
}
