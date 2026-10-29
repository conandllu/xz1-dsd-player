// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context

/**
 * 在线曲目的"队列条目 → 播放直链"解析器。
 *
 * 队列里放的是 `qobuz:<trackId>`（不能提前取流：直链有时效且每首都要签名），
 * 真正需要用某一首时才在**开流线程**里换取流地址（阻塞调用，绝不能在主线程做）。
 */
object QobuzResolver {
    const val PREFIX = "qobuz:"

    @Volatile private var appCtx: Context? = null
    @Volatile private var client: QobuzClient? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun isQobuz(path: String): Boolean = path.startsWith(PREFIX)

    fun trackId(path: String): Long? = path.removePrefix(PREFIX).toLongOrNull()

    /** 换取流地址（阻塞；调用方必须在工作线程） */
    fun resolve(path: String): String {
        val id = trackId(path) ?: error("不是在线曲目：$path")
        val ctx = appCtx ?: error("QobuzResolver 未初始化")
        val c = client ?: QobuzClient(ctx).also { client = it }
        return c.bestStreamUrl(id)
    }
}
