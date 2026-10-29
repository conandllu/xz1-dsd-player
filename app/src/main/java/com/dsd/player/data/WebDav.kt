// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import android.util.Log
import android.util.Xml
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 一个 WebDAV 条目 */
data class DavEntry(
    val name: String,
    /** 服务器给的原始 href（已解码） */
    val href: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: String,
)

/** WebDAV 配置（持久化在 SharedPreferences） */
data class DavConfig(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    /** 自签证书 / 内网 NAS 常用 */
    val trustAll: Boolean = false,
) {
    val configured: Boolean get() = url.isNotBlank()

    companion object {
        private const val PREF = "webdav"
        fun load(ctx: Context): DavConfig {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return DavConfig(
                url = sp.getString("url", "") ?: "",
                user = sp.getString("user", "") ?: "",
                password = sp.getString("pass", "") ?: "",
                trustAll = sp.getBoolean("trust_all", false),
            )
        }

        fun save(ctx: Context, cfg: DavConfig) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("url", cfg.url.trim())
                .putString("user", cfg.user)
                .putString("pass", cfg.password)
                .putBoolean("trust_all", cfg.trustAll)
                .apply()
        }
    }
}

/**
 * 极简 WebDAV 客户端：PROPFIND 列目录 + GET 下载。
 * 用 okhttp（Coil 已带入依赖）：Android 的 HttpURLConnection 只允许
 * OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，**会直接拒绝 PROPFIND**。
 */
class WebDavClient(private val cfg: DavConfig) {

    companion object {
        private const val TAG = "WebDav"
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
</d:prop></d:propfind>"""
    }

    private val client: okhttp3.OkHttpClient by lazy {
        val b = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (cfg.trustAll) {
            runCatching {
                val tm = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                    override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val ssl = SSLContext.getInstance("TLS")
                ssl.init(null, tm, SecureRandom())
                b.sslSocketFactory(ssl.socketFactory, tm[0] as X509TrustManager)
                b.hostnameVerifier { _, _ -> true }
            }.onFailure { Log.w(TAG, "trustAll 设置失败: ${it.message}") }
        }
        b.build()
    }

    /** 某个远程文件的直链（流播用：交给 HttpFlacSource 走 Range 随机读） */
    fun fileUrl(path: String): String = urlFor(path)

    private fun urlFor(path: String): String {
        val base = cfg.url.trim().trimEnd('/')
        val rel = path.trim('/')
        if (rel.isEmpty()) return "$base/"
        return "$base/" + rel.split('/').joinToString("/") { encode(it) }
    }

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun requestBuilder(path: String): okhttp3.Request.Builder {
        val b = okhttp3.Request.Builder().url(urlFor(path))
        if (cfg.user.isNotBlank()) {
            b.header("Authorization",
                okhttp3.Credentials.basic(cfg.user, cfg.password, Charsets.UTF_8))
        }
        return b
    }

    /** 连接测试 */
    fun ping(): Boolean = runCatching { list("").isNotEmpty() }.getOrElse {
        Log.w(TAG, "ping 失败: ${it.message}")
        false
    }

    /** 列目录（Depth: 1），去掉自身条目 */
    fun list(path: String): List<DavEntry> {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = requestBuilder(path).method("PROPFIND", body).header("Depth", "1").build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 -> throw IllegalStateException("认证失败（401）：请检查用户名/密码")
                resp.code == 404 -> throw IllegalStateException("路径不存在（404）")
                resp.code !in 200..299 && resp.code != 207 ->
                    throw IllegalStateException("服务器返回 ${resp.code}")
            }
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("空响应")
            val raw = parseMultistatus(bytes.inputStream())
            val selfPath = normalize("/" + path.trim('/'))
            return raw.filter { normalize(it.href) != selfPath }
        }
    }

    private fun normalize(p: String): String {
        var s = p
        val idx = s.indexOf("://")
        if (idx >= 0) {
            val slash = s.indexOf('/', idx + 3)
            s = if (slash >= 0) s.substring(slash) else "/"
        }
        s = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        return s.trimEnd('/').ifEmpty { "/" }
    }

    private fun parseMultistatus(input: InputStream): List<DavEntry> {
        val out = ArrayList<DavEntry>()
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        var href: String? = null
        var displayName: String? = null
        var size = -1L
        var lastMod: String? = null
        var isDir = false
        var inResponse = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        inResponse = true
                        href = null; displayName = null; size = -1L; lastMod = null; isDir = false
                    }
                    "href" -> if (inResponse) href = parser.nextText().trim()
                    "displayname" -> if (inResponse) displayName = parser.nextText().trim()
                    "getcontentlength" -> if (inResponse) {
                        size = parser.nextText().trim().toLongOrNull() ?: -1L
                    }
                    "getlastmodified" -> if (inResponse) lastMod = parser.nextText().trim()
                    "collection" -> if (inResponse) isDir = true
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("response", true) && inResponse) {
                    inResponse = false
                    val h = href
                    if (h != null) {
                        val decoded = runCatching { URLDecoder.decode(h, "UTF-8") }.getOrDefault(h)
                        var name = displayName?.takeIf { it.isNotBlank() }
                            ?: decoded.trimEnd('/').substringAfterLast('/')
                        if (name.isBlank()) name = decoded
                        out.add(DavEntry(name, decoded, isDir, size, lastMod ?: ""))
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** 下载到 dest（先写 .part，完成再改名），回调 (已下载, 总大小) */
    fun download(path: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val req = requestBuilder(path).get().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IllegalStateException("认证失败（401）")
            if (resp.code !in 200..299) throw IllegalStateException("下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("空响应")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            val part = File(dest.absolutePath + ".part")
            body.byteStream().use { ins ->
                FileOutputStream(part).use { outs ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                        done += n
                        if (done - last > 512 * 1024) { last = done; onProgress(done, total) }
                    }
                    outs.fd.sync()
                    onProgress(done, total)
                }
            }
            if (part.length() <= 0) throw IllegalStateException("下载内容为空")
            dest.delete()
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
            Log.i(TAG, "下载完成 ${dest.name} ${dest.length()}B")
        }
    }
}

/** 远程文件的本地缓存（按 路径+大小 命名，命中即复用） */
object DavCache {
    fun cacheFile(ctx: Context, remotePath: String, size: Long): File {
        val dir = File(ctx.cacheDir, "webdav")
        dir.mkdirs()
        val safe = remotePath.hashCode().toUInt().toString(16)
        val name = remotePath.substringAfterLast('/').take(64)
        return File(dir, "${safe}_${size}_$name")
    }

    fun clear(ctx: Context) {
        File(ctx.cacheDir, "webdav").deleteRecursively()
    }
}
