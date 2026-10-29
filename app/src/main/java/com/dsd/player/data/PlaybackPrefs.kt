// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context

/**
 * 播放/集成相关的持久设置。
 *
 * `handoff`：**让出 USB 独占**。打开后，暂停（以及停止）时除了把接口 alt 归 0 之外，
 * 还会主动 release 接口，使内核 snd-usb-audio 有机会重新绑定 —— 这样官方 Apple Music
 * 等外部播放器就能把 XD05 Bal 当普通 USB 声卡用。
 * companion 分支默认开启（values/bools.xml 里按 flavor 给默认值）。
 */
object PlaybackPrefs {
    private const val FILE = "playback_prefs"
    private const val KEY_HANDOFF = "usb_handoff"
    private const val KEY_REPEAT = "repeat_mode"

    fun handoff(ctx: Context): Boolean = prefs(ctx).getBoolean(
        KEY_HANDOFF, ctx.resources.getBoolean(com.dsd.player.R.bool.handoff_default))

    fun setHandoff(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HANDOFF, on).apply()
    }

    /** 播放模式（顺序 / 列表循环 / 单曲循环）；重启后保持 */
    fun repeat(ctx: Context): RepeatMode =
        RepeatMode.of(prefs(ctx).getString(KEY_REPEAT, RepeatMode.ORDER.name))

    fun setRepeat(ctx: Context, mode: RepeatMode) {
        prefs(ctx).edit().putString(KEY_REPEAT, mode.name).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
