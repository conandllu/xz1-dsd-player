// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.data

import android.content.Context
import com.dsd.player.decoder.AudioSource
import com.dsd.player.decoder.SourceFactory

/** 全局播放状态（Service 写，Activity 读；StateFlow） */
object PlaybackState {
    data class Ui(
        val status: String = "idle",
        val trackName: String = "",
        val trackFmt: String = "",
        val usbLabel: String = "",
        val usbCaps: String = "",
        val connected: Boolean = false,
        val supportsDsdNative: Boolean = false,
        val outputMode: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val playing: Boolean = false,
        val error: String = "",
    )
}

/** Service <-> Activity 控制总线（进程内简单实现；后续可换 MediaSession 命令） */
object PlayerBus {
    @Volatile var status: String = "idle"
    @Volatile var connected: Boolean = false
    @Volatile var capsLine: String = ""
    /** 人类可读的一行摘要，如 "XD05 Bal · 独占 · 32bit · DSD Native" */
    @Volatile var usbSummary: String = ""
    @Volatile var usbLabel: String = ""
    @Volatile var supportsDsdNative: Boolean = false
    @Volatile var outputMode: String = ""
    @Volatile var trackName: String = ""
    @Volatile var trackFmt: String = ""
    /** 源标称位深与**实际输出**位深（0=未知）；用于"源 24bit / 输出 16bit"的诚实显示 */
    @Volatile var sourceBits: Int = 0
    @Volatile var outputBits: Int = 0
    /** 当前曲目的来源路径（本地绝对路径 / qobuz:<id>），用于回查封面与高亮 */
    @Volatile var trackPath: String = ""
    /** 当前曲目的封面：http(s) 直链（在线/WebDAV）、file:// (内嵌图缓存) 或空（本地按 albumId 取） */
    @Volatile var trackCover: String = ""
    /** 取封面时需要的请求头（WebDAV 的 Basic 认证）；其它来源为空 */
    @Volatile var trackCoverHeaders: Map<String, String> = emptyMap()
    /** 播放模式（UI 与 Service 同步；Service 以此决定队列末尾行为） */
    @Volatile var repeatMode: RepeatMode = RepeatMode.ORDER
    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0
    @Volatile var playing: Boolean = false
    @Volatile var errorMessage: String = ""
    @Volatile var statusTick: Long = 0
    /** 用户手动选定的输出模式："dop"/"native"/null=自动 */
    @Volatile var forcedMode: String? = null
    @Volatile var queueIndex: Int = -1
    @Volatile var queueSize: Int = 0

    /**
     * 位深降级的诚实提示（源 24bit 但平台解码器只给 16bit 时非空）。
     * 空串表示"位深一致/未知"，UI 不要显示。
     */
    fun bitDepthNote(): String {
        val ob = outputBits
        if (ob <= 0 || ob >= 32) return ""
        val sb = sourceBits
        if (sb <= ob) return ""
        return "源 ${sb}bit → 输出 ${ob}bit（平台解码上限）"
    }

    var service: android.content.ComponentName? = null

    fun bindCommand(ctx: Context, action: String, uri: android.net.Uri? = null,
                    path: String? = null, mode: String? = null, posMs: Long? = null,
                    paused: Boolean? = null) {
        val i = android.content.Intent(action).setComponent(
            android.content.ComponentName(ctx, com.dsd.player.playback.PlaybackService::class.java))
        if (uri != null) i.putExtra(EXTRA_URI, uri)
        if (path != null) i.putExtra(EXTRA_PATH, path)
        if (mode != null) i.putExtra(EXTRA_MODE, mode)
        if (posMs != null) i.putExtra(EXTRA_POS, posMs)
        if (paused != null) i.putExtra(EXTRA_PAUSED, paused)
        ctx.startForegroundService(i)
    }

    /** 跳到队列第 index 首（服务实例不可用时走 Intent） */
    fun jumpQueue(ctx: Context, index: Int) {
        val i = android.content.Intent(ACTION_JUMP).setComponent(
            android.content.ComponentName(ctx, com.dsd.player.playback.PlaybackService::class.java))
        i.putExtra(EXTRA_QUEUE_INDEX, index)
        ctx.startForegroundService(i)
    }

    /** 在线流播：把直链当"路径"塞进现有链路（SourceFactory 会路由到 HttpFlacSource） */
    fun playOnline(ctx: Context, url: String, title: String) {
        val i = android.content.Intent(ACTION_PLAY_URL).setComponent(
            android.content.ComponentName(ctx, com.dsd.player.playback.PlaybackService::class.java))
        i.putExtra(EXTRA_PATH, url)
        i.putExtra(EXTRA_TITLE, title)
        ctx.startForegroundService(i)
    }

    /** 解码自检（不需要 DAC）：只打开解码器并读几块，供无 DAC 时排查 */
    fun selfTest(ctx: Context, path: String) {
        val i = android.content.Intent(ACTION_SELFTEST).setComponent(
            android.content.ComponentName(ctx, com.dsd.player.playback.PlaybackService::class.java))
        i.putExtra(EXTRA_PATH, path)
        ctx.startForegroundService(i)
    }

    /** 整队列播放（专辑/艺术家/文件夹/全部歌曲 → 连播） */
    fun playQueue(ctx: Context, paths: List<String>, titles: List<String>, startIndex: Int,
                  covers: List<String> = emptyList()) {
        if (paths.isEmpty()) return
        val i = android.content.Intent(ACTION_PLAY).setComponent(
            android.content.ComponentName(ctx, com.dsd.player.playback.PlaybackService::class.java))
        i.putStringArrayListExtra(EXTRA_QUEUE, ArrayList(paths))
        i.putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(titles))
        if (covers.isNotEmpty()) i.putStringArrayListExtra(EXTRA_QUEUE_COVERS, ArrayList(covers))
        i.putExtra(EXTRA_QUEUE_INDEX, startIndex.coerceIn(0, paths.size - 1))
        ctx.startForegroundService(i)
    }

    /** 在线专辑/曲目列表 → 队列播放（条目是 qobuz:<trackId>，播放时再换取流地址） */
    fun playOnlineQueue(ctx: Context, trackIds: List<Long>, titles: List<String>,
                        covers: List<String>, startIndex: Int) {
        playQueue(ctx, trackIds.map { "${QobuzResolver.PREFIX}$it" }, titles, startIndex, covers)
    }

    /** 调试：跳到离结尾 seconds 秒处（默认 5 秒） */
    fun seekNearEnd(ctx: Context, seconds: Int = 5) {
        val i = android.content.Intent(ACTION_SEEK_NEAR_END).setComponent(
            android.content.ComponentName(ctx, com.dsd.player.playback.PlaybackService::class.java))
        i.putExtra(EXTRA_POS, seconds.toLong())   // Service 侧按 Long 读，类型必须一致
        ctx.startForegroundService(i)
    }

    /** 循环切换播放模式并同步给 Service */
    fun cycleRepeat(ctx: Context) {
        repeatMode = repeatMode.next()
        PlaybackPrefs.setRepeat(ctx, repeatMode)
        bindCommand(ctx, ACTION_SET_REPEAT, mode = repeatMode.name)
    }

    const val ACTION_PLAY = "com.dsd.player.PLAY"
    /** 在线流播：path 是 http(s) 直链（Qobuz FLAC），title 用于界面显示 */
    const val ACTION_PLAY_URL = "com.dsd.player.PLAY_URL"
    const val ACTION_PAUSE = "com.dsd.player.PAUSE"
    /** 显式暂停/恢复（不是 toggle）：侧键、媒体键、线控用，避免"按了播放键却在暂停" */
    const val ACTION_SET_PAUSED = "com.dsd.player.SET_PAUSED"
    const val ACTION_STOP = "com.dsd.player.STOP"
    const val ACTION_SEEK = "com.dsd.player.SEEK"
    const val ACTION_SET_MODE = "com.dsd.player.SET_MODE"
    const val ACTION_CONNECT = "com.dsd.player.CONNECT"
    const val ACTION_SELFTEST = "com.dsd.player.SELFTEST"
    const val ACTION_JUMP = "com.dsd.player.JUMP"
    const val ACTION_NEXT = "com.dsd.player.NEXT"
    const val ACTION_SET_REPEAT = "com.dsd.player.SET_REPEAT"
    /** 调试用：跳到"离本曲结束 N 秒"（快速验证单曲循环/列表循环的曲末行为） */
    const val ACTION_SEEK_NEAR_END = "com.dsd.player.SEEK_NEAR_END"
    const val ACTION_PREV = "com.dsd.player.PREV"
    const val EXTRA_URI = "uri"
    const val EXTRA_PATH = "path"
    const val EXTRA_MODE = "mode"
    const val EXTRA_POS = "pos_ms"
    const val EXTRA_PAUSED = "paused"
    const val EXTRA_TITLE = "title"
    const val EXTRA_QUEUE = "queue_paths"
    const val EXTRA_QUEUE_TITLES = "queue_titles"
    const val EXTRA_QUEUE_INDEX = "queue_index"
    const val EXTRA_QUEUE_COVERS = "queue_covers"
}
