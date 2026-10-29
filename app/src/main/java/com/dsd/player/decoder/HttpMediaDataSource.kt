// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.media.MediaDataSource
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 给 MediaExtractor 用的 **HTTP Range 随机读**数据源。
 *
 * MediaExtractor 解析 FLAC 头（STREAMINFO）与 seek 都会跳到任意偏移，
 * 所以不能只给一个顺序流。这里按 512 KB 块做缓存（LRU，上限约 24 MB），
 * 缺块时用 `Range: bytes=a-b` 补齐；单块请求失败会抛 IOException 让上层报错。
 */
class HttpMediaDataSource(
    private val url: String,
    private val ok: OkHttpClient = defaultClient(),
    /** 额外请求头（WebDAV 需要 Authorization: Basic …；Qobuz 直链为空） */
    private val headers: Map<String, String> = emptyMap(),
) : MediaDataSource() {

    companion object {
        private const val TAG = "HttpDataSource"
        // 真机 96k/24bit 实测（第一百零二轮）：按块读的 RTT 开销会直接吃掉供数，
        // 块从 512KB → 1MB、一次补 2 块（2MB），请求数减半
        private const val BLOCK = 1024 * 1024
        private const val MAX_BLOCKS = 24        // ≈24 MB
        private const val PREFETCH = 2           // 一次补 2 块（2 MB）；太大容易被 429
        private const val RETRY = 3

        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // CDN（Qobuz 的 akamaized）常解析出 IPv6，而很多网络/模拟器没有可用 IPv6 →
            // okhttp 只试第一个地址就会整条播放失败。这里把 IPv4 排到前面。
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val all = okhttp3.Dns.SYSTEM.lookup(hostname)
                    val sorted = all.sortedBy { it is java.net.Inet6Address }
                    Log.i(TAG, "DNS $hostname → ${sorted.joinToString { it.hostAddress ?: "?" }}")
                    return sorted
                }
            })
            .build()
    }

    private var totalSize: Long = -1
    private val cache = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) =
            size > MAX_BLOCKS
    }

    init {
        // 用一次 Range:0-0 拿到总长度（Content-Range: bytes 0-0/123456）
        totalSize = probeSize()
        if (totalSize <= 0) throw IOException("无法确定音频总长度：$url")
        Log.i(TAG, "流大小 ${totalSize / 1024}KB：${url.take(80)}…")
    }

    private fun probeSize(): Long {
        var last: Exception? = null
        for (attempt in 0 until 2) {
            try {
                return probeSizeOnce()
            } catch (e: Exception) {
                last = e
                // 打类名：NetworkOnMainThreadException 这类 message 为 null 的异常，
                // 只打 message 会写成"探测失败：null"，白白丢掉全部线索
                Log.w(TAG, "探测失败（第 ${attempt + 1} 次）：${e.javaClass.simpleName}: ${e.message}")
                Thread.sleep(300)
            }
        }
        throw java.io.IOException(
            "探测音频长度失败：${last?.javaClass?.simpleName}: ${last?.message}", last)
    }

    private fun probeSizeOnce(): Long {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-0")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        ok.newCall(req).execute().use { r ->
            val cr = r.header("Content-Range")            // bytes 0-0/1234
            val cl = r.header("Content-Length")
            Log.i(TAG, "探测：code=${r.code} Content-Range=$cr Content-Length=$cl " +
                "Accept-Ranges=${r.header("Accept-Ranges")}")
            cr?.substringAfterLast('/')?.toLongOrNull()?.let { if (it > 0) return it }
            // 服务器忽略 Range（200 + 全长）：也能直接用总长
            cl?.toLongOrNull()?.let { if (r.code == 200 && it > 0) return it }
            return -1
        }
    }

    private fun blockOf(pos: Long): Long = pos / BLOCK

    private fun loadBlock(startBlock: Long): ByteArray {
        cache[startBlock]?.let { return it }
        val from = startBlock * BLOCK
        val to = minOf(from + BLOCK * PREFETCH - 1, totalSize - 1)
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=$from-$to")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val body = fetchWithRetry(req, from, to)
        // body 可能覆盖多块，按块切开缓存
        var off = 0
        var b = startBlock
        while (off < body.size) {
            val n = minOf(BLOCK, body.size - off)
            cache[b] = body.copyOfRange(off, off + n)
            off += n
            b++
        }
        return cache[startBlock] ?: throw IOException("Range 返回长度不足")
    }

    /** 429/5xx 之类限流要退避重试，否则 MediaExtractor 的 seek（会连发多个 Range）容易失败 */
    private fun fetchWithRetry(req: Request, from: Long, to: Long): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until RETRY) {
            try {
                ok.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body?.bytes() ?: throw IOException("空响应")
                    val code = r.code
                    if (code != 429 && code < 500) throw IOException("HTTP $code（Range $from-$to）")
                    last = IOException("HTTP $code（Range $from-$to，第 ${attempt + 1} 次）")
                }
            } catch (e: IOException) {
                last = e
            }
            Thread.sleep(250L * (attempt + 1))
        }
        throw last ?: IOException("Range $from-$to 失败")
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= totalSize) return -1
        val want = minOf(size.toLong(), totalSize - position).toInt()
        if (want <= 0) return -1
        var copied = 0
        while (copied < want) {
            val pos = position + copied
            val blk = blockOf(pos)
            val data = loadBlock(blk)
            val inBlock = (pos % BLOCK).toInt()
            val n = minOf(want - copied, data.size - inBlock)
            if (n <= 0) break
            System.arraycopy(data, inBlock, buffer, offset + copied, n)
            copied += n
        }
        return if (copied == 0) -1 else copied
    }

    override fun getSize(): Long = totalSize

    override fun close() {
        cache.clear()
    }
}
