// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Qobuz 在线音源（**非官方接口**）。
 *
 * ⚠ 说明与边界：
 * - 音频本身**没有 DRM**（Qobuz 流是普通 FLAC，最高 24bit/192k），所以能直接进我们的
 *   USB 独占链路做逐位输出；这里不存在破解技术保护措施的问题。
 * - 但用的是客户端私有接口（app_id/app_secret 从 web 播放器 bundle 里取），
 *   **违反 Qobuz 服务条款**，接口可能随时失效、账号可能被风控。做成可选功能，
 *   只流播、不批量下载。
 * - 凭据与 token 只存本机 SharedPreferences，不打印到日志。
 */
object QobuzPrefs {
    private const val FILE = "qobuz_prefs"
    private const val K_EMAIL = "email"
    private const val K_TOKEN = "uat"
    private const val K_TIER = "tier"
    private const val K_APP_ID = "app_id"
    private const val K_SECRET = "app_secret"
    private const val K_SECRETS = "app_secrets"
    private const val K_FORMAT = "format_id"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun email(ctx: Context) = p(ctx).getString(K_EMAIL, "") ?: ""
    fun token(ctx: Context) = p(ctx).getString(K_TOKEN, "") ?: ""
    fun tier(ctx: Context) = p(ctx).getString(K_TIER, "") ?: ""
    fun appId(ctx: Context) = p(ctx).getString(K_APP_ID, "") ?: ""
    fun appSecret(ctx: Context) = p(ctx).getString(K_SECRET, "") ?: ""
    /** 全部候选 secret（取流签名要逐个试，Qobuz 只认 seed 派生出来的那个） */
    fun appSecrets(ctx: Context): List<String> =
        (p(ctx).getString(K_SECRETS, "") ?: "").split(",").filter { it.isNotBlank() }
    /** 5=MP3 6=FLAC16/44.1 7=FLAC24/96 27=FLAC24/192 */
    fun formatId(ctx: Context, def: Int = 27) = p(ctx).getInt(K_FORMAT, def)
    fun loggedIn(ctx: Context) = token(ctx).isNotBlank()

    fun saveLogin(ctx: Context, email: String, token: String, tier: String) {
        p(ctx).edit().putString(K_EMAIL, email).putString(K_TOKEN, token)
            .putString(K_TIER, tier).apply()
    }

    fun saveCreds(ctx: Context, appId: String, secret: String) {
        p(ctx).edit().putString(K_APP_ID, appId).putString(K_SECRET, secret).apply()
    }

    fun saveSecrets(ctx: Context, secrets: List<String>, working: String = "") {
        val edit = p(ctx).edit().putString(K_SECRETS, secrets.joinToString(","))
        if (working.isNotBlank()) edit.putString(K_SECRET, working)
        edit.apply()
    }

    fun setFormat(ctx: Context, id: Int) {
        p(ctx).edit().putInt(K_FORMAT, id).apply()
    }

    fun logout(ctx: Context) {
        p(ctx).edit().remove(K_TOKEN).remove(K_TIER).remove(K_EMAIL).apply()
    }
}

/** 曲目（在线） */
data class OnlineTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val bitDepth: Int,
    val sampleRate: Int,
    val trackNo: Int = 0,
    /** 专辑封面（Qobuz 的 image.large/small） */
    val imageUrl: String = "",
)

data class OnlineAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val tracks: Int,
    val bitDepth: Int,
    val sampleRate: Int,
    val imageUrl: String = "",
    val releaseDate: String = "",
)

class QobuzException(msg: String, val code: Int = 0) : Exception(msg)

/**
 * Qobuz 客户端：登录 / 搜索 / 专辑 / 取流地址。
 * 所有请求都带 `X-App-Id`，登录后带 `X-User-Auth-Token`。
 */
class QobuzClient(private val ctx: Context) {

    companion object {
        private const val TAG = "Qobuz"
        private const val BASE = "https://www.qobuz.com/api.json/0.2/"
        private const val PLAY = "https://play.qobuz.com"
        private val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .dns(object : okhttp3.Dns {
                    // IPv4 优先：部分网络/模拟器解析出 IPv6 却连不通，会导致"整片取流失败"
                    override fun lookup(hostname: String): List<java.net.InetAddress> =
                        okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { it is java.net.Inet6Address }
                })
                .build()
        }
    }

    private fun appId(): String = QobuzPrefs.appId(ctx)
    private fun secret(): String = QobuzPrefs.appSecret(ctx)
    private fun appSecrets(): List<String> = QobuzPrefs.appSecrets(ctx)
    private fun token(): String = QobuzPrefs.token(ctx)

    /**
     * 没配过 app_id/secret 时，从 web 播放器 bundle 里取（一次性，之后缓存）。
     *
     * ⚠ 实测（2026-10-25，player 8.3.0-b029）：bundle 里 `production.api.appSecret` 那个
     * 32 位十六进制串**不能**用于 `track/getFileUrl` 签名（会 400 Invalid Request Signature）。
     * 真正能用的是由 `initialSeed(...)` + 时区表里的 `info`/`extras` 拼出来后 base64 解码得到的
     * 三个 secret（london / abidjan / berlin），逐个试到 200 为止（实测 berlin 生效）。
     */
    fun ensureCreds() {
        if (appId().isNotBlank() && appSecrets().isNotEmpty()) return
        val login = get("$PLAY/login", withApiHeaders = false)
        val bundlePath = Regex("""/resources/[\d.]+-[a-z]\d{3}/bundle\.js""")
            .find(login)?.value ?: throw QobuzException("没找到 Qobuz web bundle（接口可能改了）")
        Log.i(TAG, "bundle: $bundlePath")
        val js = get(PLAY + bundlePath, withApiHeaders = false)
        val m = Regex("""production:\{api:\{appId:"(\d{9})",appSecret:"(\w{32})"""")
            .find(js) ?: throw QobuzException("bundle 里没找到 appId/appSecret（接口可能改了）")
        val (id, prodSecret) = m.destructured
        val candidates = ArrayList<String>()
        candidates.add(prodSecret)
        candidates.addAll(deriveSecrets(js))
        QobuzPrefs.saveCreds(ctx, id, prodSecret)
        QobuzPrefs.saveSecrets(ctx, candidates)
        Log.i(TAG, "已从 bundle 取得 app_id=${id}，候选 secret ${candidates.size} 个（不打印内容）")
    }

    private fun get(url: String, withApiHeaders: Boolean, withToken: Boolean = true): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (withApiHeaders) {
            b.header("X-App-Id", appId())
            b.header("Content-Type", "application/json;charset=UTF-8")
            if (withToken && token().isNotBlank()) b.header("X-User-Auth-Token", token())
        }
        var attempt = 0
        while (true) {
        try {
        return http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                // 401 = 凭据被拒（账号密码不对 / 被风控）；400 = app_id 失效；其余按原文报
                val hint = when (r.code) {
                    401 -> "邮箱或密码不正确（或被 Qobuz 风控/需要二次验证）"
                    400 -> "Qobuz 拒绝请求（参数或接口有变更）：${body.take(120)}"
                    403 -> "该账号无权访问此接口（可能需要付费档位）"
                    else -> "HTTP ${r.code}"
                }
                throw QobuzException("$hint：${body.take(160)}", r.code)
            }
            body
        }
        } catch (e: java.io.IOException) {
            // 模拟器/移动网络上 DNS 解析偶发失败（UnknownHostException）→ 退避重试
            attempt++
            if (attempt >= 3) throw QobuzException("网络错误（已重试 3 次）：${e.message}")
            Log.w(TAG, "请求失败（第 $attempt 次），重试：${e.message}")
            Thread.sleep(400L * attempt)
        }
        }
    }

    private fun api(endpoint: String, params: Map<String, String>): JSONObject {
        ensureCreds()
        val q = params.entries.joinToString("&") { (k, v) ->
            "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val body = get(BASE + endpoint + "?" + q, withApiHeaders = true)
        return JSONObject(body)
    }

    /** 登录：email + password → user_auth_token */
    fun login(email: String, password: String): String {
        ensureCreds()
        val q = "email=" + java.net.URLEncoder.encode(email, "UTF-8") +
            "&password=" + java.net.URLEncoder.encode(password, "UTF-8") +
            "&app_id=" + appId()
        val body = get(BASE + "user/login?" + q, withApiHeaders = true, withToken = false)
        val j = JSONObject(body)
        val uat = j.optString("user_auth_token")
        if (uat.isBlank()) throw QobuzException("登录失败：没有返回 token")
        val tier = j.optJSONObject("user")?.optJSONObject("credential")
            ?.optJSONObject("parameters")?.optString("short_label").orEmpty()
        QobuzPrefs.saveLogin(ctx, email, uat, tier)
        Log.i(TAG, "登录成功，档位=${tier.ifBlank { "未知" }}")
        return tier
    }

    /**
     * 用已拿到的 `user_auth_token` 直接登录（免密码）。
     * token 可从 web 播放器拿到：在 play.qobuz.com 登录后，
     * 开发者工具 Console 执行 `localStorage.getItem('user_auth_token')`。
     * 校验方式：用它请求一次 catalog/search，成功即视为有效。
     */
    fun loginWithToken(token: String): Boolean {
        ensureCreds()
        val t = token.trim()
        if (t.isBlank()) throw QobuzException("token 为空")
        // 先验证，再落盘（避免写入废 token）
        val q = "query=" + java.net.URLEncoder.encode("test", "UTF-8") + "&type=tracks&limit=1"
        val b = Request.Builder().url(BASE + "catalog/search?" + q)
            .header("User-Agent", UA).header("X-App-Id", appId())
            .header("X-User-Auth-Token", t).build()
        http.newCall(b).execute().use { r ->
            if (!r.isSuccessful) {
                throw QobuzException("token 无效（HTTP ${r.code}，可能已过期）", r.code)
            }
        }
        QobuzPrefs.saveLogin(ctx, "(token 登录)", t, "已授权")
        Log.i(TAG, "已用 token 登录（长度 ${t.length}，不打印内容）")
        return true
    }

    /** 搜索曲目 */
    fun searchTracks(query: String, limit: Int = 30): List<OnlineTrack> {
        val j = api("catalog/search", mapOf("query" to query, "type" to "tracks",
            "limit" to limit.toString()))
        val arr = j.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> parseTrack(arr.optJSONObject(i)) }
    }

    /** 搜索专辑 */
    fun searchAlbums(query: String, limit: Int = 20): List<OnlineAlbum> {
        val j = api("catalog/search", mapOf("query" to query, "type" to "albums",
            "limit" to limit.toString()))
        val arr = j.optJSONObject("albums")?.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            OnlineAlbum(
                id = a.optString("id"),
                title = a.optString("title"),
                artist = a.optJSONObject("artist")?.optString("name")
                    ?: a.optString("artist_name"),
                tracks = a.optInt("tracks_count"),
                bitDepth = a.optInt("maximum_bit_depth"),
                sampleRate = rateHz(a.optInt("maximum_sampling_rate")),
                imageUrl = imageOf(a),
                releaseDate = a.optString("release_date_original").ifBlank {
                    a.optString("released_at").let { ts ->
                        if (ts.toLongOrNull() != null)
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .format(java.util.Date(ts.toLong() * 1000)) else ""
                    }
                },
            )
        }
    }

    /**
     * 专辑曲目。
     *
     * ⚠ 2026-10 起 Qobuz 不再接受 `extra=tracks`（会 400：accepted values are
     * albumsFromSameArtist/focus/focusAll/track_ids），但 **不加 extra 也会返回
     * `tracks.items`**；专辑内曲目的 `album.image` 常常为空，这里用专辑自己的封面补上。
     */
    fun albumTracks(albumId: String): List<OnlineTrack> {
        val j = api("album/get", mapOf("album_id" to albumId))
        val arr = j.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        val albumImg = imageOf(j)
        return (0 until arr.length()).mapNotNull { i ->
            parseTrack(arr.optJSONObject(i))?.let { t ->
                if (t.imageUrl.isBlank() && albumImg.isNotBlank()) t.copy(imageUrl = albumImg) else t
            }
        }
    }

    /** 专辑详情（封面/发行日期/曲目数），打开专辑时用来画头部 */
    fun albumInfo(albumId: String): OnlineAlbum? {
        // 失败原因向上抛，别吞成 null（之前界面只会显示"拿不到专辑信息"，无法判断是网络还是接口）
        val j = try {
            api("album/get", mapOf("album_id" to albumId))
        } catch (e: Exception) {
            Log.w(TAG, "album/get 失败（$albumId）：${e.message}")
            throw e
        }
        return OnlineAlbum(
            id = albumId,
            title = j.optString("title"),
            artist = j.optJSONObject("artist")?.optString("name").orEmpty(),
            tracks = j.optInt("tracks_count", j.optInt("media_count")),
            bitDepth = j.optInt("maximum_bit_depth"),
            sampleRate = rateHz(j.optInt("maximum_sampling_rate")),
            imageUrl = imageOf(j),
            releaseDate = j.optString("release_date_original").ifBlank {
                j.optString("released_at").toLongOrNull()?.let { ts ->
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(ts * 1000))
                } ?: ""
            },
        )
    }

    /** Qobuz 的 `maximum_sampling_rate` 是 **kHz**（192 / 96 / 44.1），统一换算成 Hz */
    private fun rateHz(v: Int): Int = when {
        v <= 0 -> 0
        v < 1000 -> (v * 1000)
        else -> v
    }

    /** Qobuz 的封面：image.large / small / thumbnail */
    private fun imageOf(o: JSONObject?): String {
        val img = o?.optJSONObject("image") ?: return ""
        return img.optString("large").ifBlank {
            img.optString("small").ifBlank { img.optString("thumbnail") }
        }
    }

    private fun parseTrack(t: JSONObject?): OnlineTrack? {
        if (t == null) return null
        val id = t.optString("id").toLongOrNull() ?: return null
        return OnlineTrack(
            id = id,
            title = t.optString("title"),
            artist = t.optJSONObject("performer")?.optString("name")
                ?: t.optJSONObject("artist")?.optString("name")
                ?: t.optString("performer_name"),
            album = t.optJSONObject("album")?.optString("title") ?: t.optString("album_title"),
            durationSec = t.optInt("duration"),
            bitDepth = t.optInt("maximum_bit_depth"),
            sampleRate = rateHz(t.optInt("maximum_sampling_rate")),
            trackNo = t.optInt("track_number"),
            imageUrl = imageOf(t.optJSONObject("album")).ifBlank { imageOf(t) },
        )
    }

    /**
     * 取流地址（FLAC 直链）。
     * 签名 = md5("trackgetFileUrlformat_id{fmt}intentstreamtrack_id{id}{ts}{secret}")
     */
    fun streamUrl(trackId: Long, formatId: Int = QobuzPrefs.formatId(ctx)): JSONObject {
        ensureCreds()
        // 先用上次成功的 secret，不行再把候选全试一遍（并记住成功那个）
        val pref = secret()
        val cands = (listOf(pref) + appSecrets()).filter { it.isNotBlank() }.distinct()
        var last: Exception? = null
        for (sec in cands) {
            try {
                val j = signedFileUrl(trackId, formatId, sec)
                if (j.optString("url").isNotBlank()) {
                    if (sec != pref) {
                        QobuzPrefs.saveSecrets(ctx, cands, sec)
                        Log.i(TAG, "换用可用的 secret（${sec.length} 位）")
                    }
                    return j
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw QobuzException("取流签名失败：${last?.message ?: "全部候选 secret 都被拒"}")
    }

    /** 我的收藏（专辑） */
    fun favoriteAlbums(limit: Int = 50): List<OnlineAlbum> {
        val j = favorites("albums", limit)
        val arr = j.optJSONArray("albums")?.let { null } ?: j.optJSONArray("items")
        val src = arr ?: j.optJSONObject("albums")?.optJSONArray("items") ?: return emptyList()
        return (0 until src.length()).mapNotNull { i ->
            val a = src.optJSONObject(i) ?: return@mapNotNull null
            OnlineAlbum(
                id = a.optString("id"),
                title = a.optString("title"),
                artist = a.optJSONObject("artist")?.optString("name")
                    ?: a.optString("artist_name"),
                tracks = a.optInt("tracks_count"),
                bitDepth = a.optInt("maximum_bit_depth"),
                sampleRate = rateHz(a.optInt("maximum_sampling_rate")),
            )
        }
    }

    /** 我的收藏（曲目） */
    fun favoriteTracks(limit: Int = 50): List<OnlineTrack> {
        val j = favorites("tracks", limit)
        val src = j.optJSONObject("tracks")?.optJSONArray("items") ?: j.optJSONArray("items")
            ?: return emptyList()
        return (0 until src.length()).mapNotNull { i -> parseTrack(src.optJSONObject(i)) }
    }

    /** favorite/getUserFavorites 需要第二套签名（favorite…{ts}{secret}） */
    private fun favorites(type: String, limit: Int): JSONObject {
        ensureCreds()
        val ts = System.currentTimeMillis() / 1000
        val sig = md5("favoritegetUserFavorites${ts}${secret()}")
        return api("favorite/getUserFavorites", mapOf(
            "type" to type,
            "request_ts" to ts.toString(),
            "request_sig" to sig,
            "limit" to limit.toString(),
            "offset" to "0",
        ))
    }

    /**
     * 从 bundle 派生 secret：
     * `base64( (seed + info + extras) 去掉尾部 44 字符 )` → 32 位十六进制串。
     * 三个时区（london/abidjan/berlin）各一个，取流时逐个试。
     */
    private fun deriveSecrets(js: String): List<String> {
        val seeds = HashMap<String, String>()
        Regex("""initialSeed\("([\w=]+)",window\.utimezone\.([a-z]+)\)""").findAll(js)
            .forEach { seeds[it.groupValues[2]] = it.groupValues[1] }
        val infos = HashMap<String, Pair<String, String>>()
        Regex("""name:"[\w\-]+/([A-Za-z_\-]+)",info:"([\w=]+)",extras:"([\w=]+)"""")
            .findAll(js).forEach {
                infos[it.groupValues[1].lowercase()] = it.groupValues[2] to it.groupValues[3]
            }
        val out = ArrayList<String>()
        for ((tz, seed) in seeds) {
            val p = infos[tz] ?: continue
            val raw = (seed + p.first + p.second).dropLast(44)
            runCatching {
                out.add(String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT)))
            }.onFailure { Log.w(TAG, "派生 secret 失败（$tz）") }
        }
        Log.i(TAG, "派生 secret：${out.map { it.length }}（长度列表）")
        return out
    }

    /** 用某个 secret 给 getFileUrl 签名 */
    private fun signedFileUrl(trackId: Long, formatId: Int, sec: String): JSONObject {
        val ts = System.currentTimeMillis() / 1000
        val raw = "trackgetFileUrlformat_id${formatId}intentstreamtrack_id${trackId}${ts}${sec}"
        return api("track/getFileUrl", mapOf(
            "request_ts" to ts.toString(),
            "request_sig" to md5(raw),
            "track_id" to trackId.toString(),
            "format_id" to formatId.toString(),
            "intent" to "stream",
        ))
    }

    /** 按账号档位挑一个能用的格式：27(24/192) → 7(24/96) → 6(16/44.1) */
    fun bestStreamUrl(trackId: Long): String {
        val prefer = QobuzPrefs.formatId(ctx)
        val chain = (listOf(prefer) + listOf(27, 7, 6)).distinct()
        var last: Exception? = null
        for (f in chain) {
            try {
                val j = streamUrl(trackId, f)
                val url = j.optString("url")
                if (url.isNotBlank()) {
                    Log.i(TAG, "stream fmt=$f ${j.optInt("bit_depth")}bit/${j.optInt("sampling_rate")}Hz")
                    return url
                }
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "取流失败 fmt=$f：${e.message}")
            }
        }
        throw QobuzException("无法获取流地址：${last?.message ?: "账号档位可能不含无损"}")
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
