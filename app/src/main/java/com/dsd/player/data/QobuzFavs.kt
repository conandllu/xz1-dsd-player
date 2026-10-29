// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Qobuz 歌曲收藏（**本机收藏**）。
 *
 * 为什么不用 Qobuz 的写接口：实测（2026-10-26）`favorite/create` 对当前 app_id 一律返回
 * `404 No result matching given argument`（GET/POST、JSON/表单、track_id/item_id、带签名/不带签名
 * 全部试过），而读接口 `favorite/getUserFavorites` 是 200 —— 说明这个 app_id 没有写的权限。
 * 所以收藏落在本机：只存"足够展示与排队播放"的元数据，真正播放时仍按 track id 现取直链。
 */
object QobuzFavs {
    private const val FILE = "qobuz_favs"
    private const val KEY = "tracks"

    fun all(ctx: Context): List<OnlineTrack> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id")
                if (id <= 0) null else OnlineTrack(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    durationSec = o.optInt("dur"),
                    bitDepth = o.optInt("bits"),
                    sampleRate = o.optInt("rate"),
                    trackNo = o.optInt("no"),
                    imageUrl = o.optString("img"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun ids(ctx: Context): Set<Long> = all(ctx).map { it.id }.toSet()

    fun isFav(ctx: Context, id: Long): Boolean = all(ctx).any { it.id == id }

    /** 切换收藏；返回 true 表示现在已收藏 */
    fun toggle(ctx: Context, t: OnlineTrack): Boolean {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == t.id }
        val added: Boolean
        if (idx >= 0) {
            list.removeAt(idx)
            added = false
        } else {
            list.add(0, t)          // 新收藏放最前（"最近收藏"更符合直觉）
            added = true
        }
        save(ctx, list)
        return added
    }

    fun save(ctx: Context, list: List<OnlineTrack>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("dur", t.durationSec)
                put("bits", t.bitDepth)
                put("rate", t.sampleRate)
                put("no", t.trackNo)
                put("img", t.imageUrl)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun count(ctx: Context): Int = all(ctx).size

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
