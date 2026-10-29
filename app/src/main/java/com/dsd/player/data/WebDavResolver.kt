// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import okhttp3.Credentials

/**
 * WebDAV 曲目的"队列条目 → 流播直链 + 认证头"解析器。
 *
 * 队列里只放 `webdav:<远程路径>`（**不放账号密码**，也不放带 userinfo 的 URL）：
 * 真正开流时才从 DavConfig 取配置拼出直链并生成 Basic 认证头，
 * 这样日志/Intent/状态里都不会出现凭据，换服务器地址也不用改队列。
 */
object WebDavResolver {
    const val PREFIX = "webdav:"

    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun isDav(path: String): Boolean = path.startsWith(PREFIX)

    fun handle(remotePath: String): String = PREFIX + remotePath.trim('/')

    fun remotePath(handle: String): String = handle.removePrefix(PREFIX)

    /** 直链 + 需要的请求头（Basic 认证）；调用方负责在**工作线程**里用 */
    fun resolve(handle: String): Pair<String, Map<String, String>> {
        val ctx = appCtx ?: error("WebDavResolver 未初始化")
        val cfg = DavConfig.load(ctx)
        require(cfg.configured) { "未配置 WebDAV（设置 → WebDAV）" }
        val url = WebDavClient(cfg).fileUrl(remotePath(handle))
        val headers = if (cfg.user.isNotBlank())
            mapOf("Authorization" to Credentials.basic(cfg.user, cfg.password, Charsets.UTF_8))
        else emptyMap()
        return url to headers
    }
}
