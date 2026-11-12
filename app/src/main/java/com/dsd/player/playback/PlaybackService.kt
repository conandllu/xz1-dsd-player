// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.dsd.player.R
import com.dsd.player.data.MediaLibrary
import com.dsd.player.data.PlayerBus
import com.dsd.player.decoder.AudioSource
import com.dsd.player.decoder.SourceFactory
import com.dsd.player.ui.MainActivity
import com.dsd.player.usb.NativeBridge
import com.dsd.player.usb.OutputMode
import com.dsd.player.usb.UsbAudioOutput
import com.dsd.player.usb.UsbDeviceMonitor
import com.dsd.player.usb.DacInfo

/**
 * 前台播放服务：持有 DAC 独占句柄 + Producer。
 * Activity 通过 PlayerBus 发命令（startForegroundService + action）。
 */
class PlaybackService : LifecycleService() {

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        const val NOTIF_ID = 42
        /** 同进程内 UI 直接调用，避免每次按键走 startForegroundService 的 200ms 延迟 */
        @Volatile private var instance: PlaybackService? = null
        fun current(): PlaybackService? = instance
    }

    /** UI 直接调：队列快照（标题列表 + 当前索引） */
    fun queueSnapshot(): Pair<List<String>, Int> =
        queueTitles.ifEmpty { queue.map { java.io.File(it).name } } to queueIndex

    /** UI 直接调：跳到队列第 index 首 */
    fun jumpTo(index: Int) {
        if (index !in queue.indices) return
        playQueueAt(index, forcedMode)
    }

    /**
     * UI 直接调：暂停/恢复（屏幕按钮、迷你条、播放页大按钮）。
     * 生产者已停时要有"重新起播"语义，否则按下只会在 paused 里空转。
     */
    fun togglePauseNow(from: String = "ui") {
        if (!producer.isRunning()) { resumeFrom(from); return }
        applyPaused(!producer.paused, from)
    }

    /** UI 直接调：显式暂停/恢复（侧键、媒体键、通知栏都要"按暂停就暂停"，不能 toggle） */
    fun setPausedNow(paused: Boolean, from: String = "ui") {
        if (!paused && !producer.isRunning()) { resumeFrom(from); return }
        applyPaused(paused, from)
    }

    /**
     * **唯一的暂停/恢复实现**：所有入口（屏幕按钮 / 通知栏 / 媒体键 / Key Mapper /
     * 蓝牙线控 / 锁屏）都必须走这里。
     *
     * 顺序硬约束：先让 native 侧淡出→静音（PCM 走 10 ms 增益斜坡，DoP/DSD 走 0x69 静音码），
     * 再翻转状态并刷新 UI。历史 bug：媒体键回调只改了 PlaybackState 与 UI 标志、
     * 没调 native 静音 → 图标立刻变暂停，但泵仍把环形缓冲里 1 MB（44.1k ≈ 2.97 s）
     * 原速送给 DAC，听感就是"UI 已变、声音还在响约 2 秒"。
     */
    private fun applyPaused(paused: Boolean, from: String) {
        if (!paused && handoffPos >= 0) {
            // 上次暂停时把 DAC 让出去了：恢复 = 重新申请 DAC 并在原位置重开流
            Log.i(TAG, "恢复 from=$from：DAC 已让出，重新申请并回到 ${handoffPos} 字节")
            resumeAfterHandoff()
            return
        }
        val changed = producer.paused != paused
        if (changed) {
            producer.paused = paused
            if (paused) usb.pauseOutput() else usb.resumeOutput()
        }
        PlayerBus.playing = !paused
        Log.i(TAG, "暂停=$paused from=$from" +
            "（native=${if (paused) "淡出→静音" else "淡入"}，changed=$changed，" +
            "running=${producer.isRunning()}）")
        notifyUi()
        updateSession()
        // 暂停且设置里开了「让出 USB」：淡出之后把独占还给系统（官方 Apple Music 要用 DAC）
        if (paused && changed && com.dsd.player.data.PlaybackPrefs.handoff(this)) handoffUsb()
    }

    /** 让出 USB 独占：停生产者 + 关流 + release 接口 + 关连接，位置记下来供恢复 */
    private fun handoffUsb() {
        if (!usb.connected) return
        handoffPos = playPosBytes
        producer.stop()
        runCatching { usb.releaseExclusive() }
        PlayerBus.connected = false
        PlayerBus.usbLabel = ""
        PlayerBus.usbSummary = ""
        PlayerBus.supportsDsdNative = false
        Log.i(TAG, "让出 USB：已释放 DAC（记住位置 $handoffPos 字节），外部播放器可以接管")
        notifyUi()
    }

    /** 恢复：DAC 还连着就直接重开流；否则重新申请（会触发权限/attach 回调） */
    private fun resumeAfterHandoff() {
        val pos = handoffPos
        handoffPos = -1
        if (pos < 0) return
        if (usb.connected) { restartAt(pos); return }
        pendingResumeAt = pos
        monitor.scanExisting()
    }

    /** 在指定源字节位置重开流（让出 USB 后恢复用） */
    private fun restartAt(pos: Long) {
        if (isMainThread()) { postOpen("restartAt@$pos") { restartAt(pos) }; return }
        val s = source ?: return
        if (opening.compareAndSet(false, true)) {
            runCatching {
                producer.stop()
                val cfg = usb.openFor(s, PlayerBus.outputMode.ifBlank { null }?.let { parseMode(it) },
                    allowReuse = false)
                PlayerBus.outputMode = cfg.mode.name
                val bs = openStreamFor(s)
                setRates(s.info, cfg)
                runCatching { bs.seek(pos) }
                producer.start(s, cfg, bs, pos)
                producer.paused = false
                prefillRingThenUnmute()
                playPosBytes = pos
                PlayerBus.errorMessage = ""
                PlayerBus.connected = usb.connected
                PlayerBus.playing = true
                PlayerBus.status = "playing"
                Log.i(TAG, "恢复播放 @ ${pos} 字节（已重新建立独占流）")
            }.onFailure { PlayerBus.errorMessage = "恢复失败：${it.message}" }
            opening.set(false)
        }
        notifyUi()
    }

    /**
     * 媒体键/线控/Key Mapper 的「播放」：生产者已停时重新起播（与 UI 的语义保持一致），
     * 否则只在 paused 状态里空转。
     */
    private fun resumeFrom(from: String) {
        if (producer.isRunning()) { applyPaused(false, from); return }
        val idx = queueIndex.coerceAtLeast(0)
        when {
            queue.isNotEmpty() -> {
                Log.i(TAG, "$from play: 生产者已停 → 重新播放队列第 $idx 首")
                playQueueAt(idx, forcedMode)
            }
            source != null -> {
                Log.i(TAG, "$from play: 生产者已停 → 重新播放当前曲目")
                play(null, lastPath, forcedMode, displayTitle = PlayerBus.trackName)
            }
            else -> Log.w(TAG, "$from play: 无曲目可播")
        }
    }

    private lateinit var usb: UsbAudioOutput
    private lateinit var producer: Producer
    private lateinit var monitor: UsbDeviceMonitor
    private var mediaSession: MediaSession? = null
    private var source: AudioSource? = null
    @Volatile private var playPosBytes = 0L
    @Volatile private var totalBytes = -1L
    /** 源字节率：PCM=采样率×8（24-in-32 立体声）；DSD=位率/4（立体声 DSD 字节/秒） */
    @Volatile private var srcBytesPerSec = 0L
    @Volatile private var usbBytesPerSec = 0L
    private var lastUiTick = 0L
    /** 播放队列（专辑/艺术家/文件夹整列表连播） */
    private var queue: List<String> = emptyList()
    private var queueTitles: List<String> = emptyList()
    /** 与 queue 等长的封面直链（在线曲目用；本地留空，界面回落到 albumId） */
    private var queueCovers: List<String> = emptyList()
    private var queueIndex = -1
    /** 用户手动指定的输出模式（null=自动），下次开流沿用 */
    @Volatile private var forcedMode: String? = null
    /** 最近一次播放的文件路径（生产者停掉后"重新起播"要用） */
    @Volatile private var lastPath: String? = null
    private var lastNotifKey = ""
    private var channelReady = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** >0：因为"让出 USB"而暂停，恢复时要在这个字节位置重开流 */
    @Volatile private var handoffPos = -1L
    /** >0：正在等 DAC 重新授权后恢复播放的位置 */
    @Volatile private var pendingResumeAt = -1L
    /** 开流防重入：播放/切模式/DAC 回调可能并发进来，重复开流会听成「咔」一下 */
    private val opening = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 开流专用的单工作线程。
     *
     * 为什么要单独开线程：onStartCommand() 跑在主线程，而 Qobuz 直链在开流阶段就要联网
     * （HttpMediaDataSource 的 Range 探测）。主线程做阻塞网络 I/O 会抛
     * `NetworkOnMainThreadException`，它的 message 是 **null**，于是界面只看到
     * "网络 FLAC 打开失败：null"，日志里连一行"探测"都没有 —— 这就是第五十八轮
     * "取流成功但播放失败"的真实原因（本地文件不联网，所以只有在线播会踩到）。
     * 单线程还能顺带保证开流彼此串行（opening 标志之外的第二道保险）。
     */
    // ★ 进程级单例：以前是实例字段，Service 被销毁重建（idleStop 60s 后）就会再建一个
    //   "open-stream" 线程，旧线程作为 daemon 常驻不退出 —— 真机看到"每播一次 +1 个"，
    //   存活 13.7 小时的进程里累积 3 个，主线程 resume 被它们占着 USB/锁 → 黑屏 16 s。
    /**
     * 开流工作线程：**进程内唯一**。
     *
     * 用自己的队列 + 一个死循环线程，而不是 Executors：真机第一百四十九轮看到启动后
     * 进程里有 2 个 `open-stream`（其中一个空转 0.16 s），说明执行器的线程还会被重建/多建。
     * 自己管就只有一个：线程建好后永不退出，任务异常就地吞掉（不影响后续任务）。
     */
    private object Opener {
        private val queue = java.util.concurrent.LinkedBlockingQueue<Pair<String, () -> Unit>>()
        @Volatile private var started = false
        @Volatile var created = 0

        fun submit(tag: String, fn: () -> Unit) {
            synchronized(this) {
                if (!started) {
                    started = true
                    created++
                    Thread({
                        Log.i(TAG, "open-stream 工作线程启动（进程内唯一）")
                        while (true) {
                            val job = try { queue.take() } catch (ie: InterruptedException) { continue }
                            try {
                                job.second()
                            } catch (t: Throwable) {
                                Log.e(TAG, "开流失败（${job.first}）", t)
                                PlayerBus.errorMessage = t.message ?: "播放失败"
                                PlayerBus.status = "error"
                            }
                        }
                    }, "open-stream").apply { isDaemon = true; start() }
                }
            }
            queue.put(tag to fn)
        }
    }

    private fun isMainThread() =
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    /** 主线程来的开流请求一律转到 opener；已在工作线程则直接执行 */
    private fun postOpen(tag: String, block: () -> Unit) {
        Log.i(TAG, "开流转到工作线程：$tag")
        Opener.submit(tag) { block() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 先把前台通知挂上：冷启动时 startForegroundService 有 ~5s 窗口，
        // 之前它排在 monitor.register() / MediaSession 之后，冷启动慢的机型容易踩线。
        runCatching { startForeground(NOTIF_ID, buildNotification(getString(R.string.app_name))) }
        NativeBridge.ensureLoaded()
        com.dsd.player.data.QobuzResolver.init(this)
        com.dsd.player.data.WebDavResolver.init(this)
        PlayerBus.repeatMode = com.dsd.player.data.PlaybackPrefs.repeat(this)
        usb = UsbAudioOutput { msg -> PlayerBus.errorMessage = msg; notifyUi() }
        producer = Producer(
            usb,
            onProgress = { pos, total ->
                playPosBytes = pos
                if (total > 0) totalBytes = total
                val now = System.currentTimeMillis()
                if (now - lastUiTick > 120) { lastUiTick = now; notifyUi() }
            },
            onEnded = { mainHandler.post { onTrackEnded() } },
            onUnderrunStorm = {
                PlayerBus.errorMessage = "持续欠载：CPU/带宽不足，已停止"
                stopInternal()
            },
        )
        startStallWatchdog()
        // 一次性线程普查：确认进程内 open-stream 只有 1 个（真机第一百五十一轮仍看到 2 个）
        runCatching {
            val names = Thread.getAllStackTraces().keys.map { it.name }
            val n = names.count { it.startsWith("open-stream") }
            Log.i(TAG, "线程普查：open-stream=$n（预期 0 或 1）｜总线程=${names.size}")
        }
        monitor = UsbDeviceMonitor(this,
            onDevice = { d, conn -> onDacAttached(d, conn) },
            onDetached = { onDacDetached() },
            onError = { PlayerBus.errorMessage = it; notifyUi() },
        )
        monitor.register()
        mediaSession = MediaSession(this, "dsd").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
            setPlaybackState(android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                    android.media.session.PlaybackState.ACTION_SEEK_TO or
                    android.media.session.PlaybackState.ACTION_STOP)
                .setState(android.media.session.PlaybackState.STATE_NONE, 0, 1f).build())
            setCallback(object : MediaSession.Callback() {
                // 媒体键 / Key Mapper（侧键）/ 通知栏 / 线控 / 锁屏都走这两条 —— 必须真正静音
                override fun onPlay() { resumeFrom("mediaSession") }
                override fun onPause() { applyPaused(true, "mediaSession") }
                override fun onStop() { stopInternal() }
                override fun onSeekTo(pos: Long) { if (srcBytesPerSec > 0) doSeek(pos * srcBytesPerSec / 1000) }
            })
        }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.app_name)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        mainHandler.removeCallbacks(idleStop)
        when (intent?.action) {
            PlayerBus.ACTION_PLAY -> {
                val uri = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(PlayerBus.EXTRA_URI, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(PlayerBus.EXTRA_URI)
                val path = intent.getStringExtra(PlayerBus.EXTRA_PATH)
                val mode = intent.getStringExtra(PlayerBus.EXTRA_MODE)
                val q = intent.getStringArrayListExtra(PlayerBus.EXTRA_QUEUE)
                val qIdx = intent.getIntExtra(PlayerBus.EXTRA_QUEUE_INDEX, -1)
                if (q != null && q.isNotEmpty() && qIdx >= 0) {
                    queue = q
                    queueTitles = intent.getStringArrayListExtra(PlayerBus.EXTRA_QUEUE_TITLES)
                        ?: List(q.size) { java.io.File(q[it]).name }
                    queueCovers = intent.getStringArrayListExtra(PlayerBus.EXTRA_QUEUE_COVERS)
                        ?: emptyList()
                    queueIndex = qIdx.coerceIn(0, q.size - 1)
                    playQueueAt(queueIndex, null)
                } else if (uri != null || path != null) {
                    queue = emptyList(); queueTitles = emptyList(); queueCovers = emptyList(); queueIndex = -1
                    play(uri, path, mode)
                } else {
                    // 不带参数就点了播放（STOP 之后、通知栏/媒体键/UI 都可能这样发）：
                    // 以前这里什么都不做 → 界面停在 PAUSED 不动（第一百三十八轮实测）。
                    Log.i(TAG, "PLAY（无参数）：生产者已停 → 按当前队列/曲目恢复")
                    resumeFrom("intent:play")
                }
            }
            PlayerBus.ACTION_PLAY_URL -> {
                val url = intent.getStringExtra(PlayerBus.EXTRA_PATH)
                val title = intent.getStringExtra(PlayerBus.EXTRA_TITLE)
                if (url != null) {
                    // 单曲直链播放（没有队列信息时用；正常点歌走 playOnlineQueue）
                    queue = listOf(url)
                    queueTitles = listOf(title ?: "在线流")
                    queueCovers = emptyList()
                    queueIndex = 0
                    playQueueAt(0, null)
                }
            }
            PlayerBus.ACTION_SET_PAUSED -> {
                val want = intent.getBooleanExtra(PlayerBus.EXTRA_PAUSED, true)
                setPausedNow(want, "intent")
            }
            PlayerBus.ACTION_PAUSE -> {
                // 通知栏按钮 / UI 通过 Intent 兜底：与屏幕按钮同一条实现。
                // 通知上的图标是「暂停」还是「播放」由当前状态决定，所以这里按状态显式取反，
                // 不用 toggle（避免两次点击之间状态变化导致方向反了）。
                setPausedNow(PlayerBus.playing, "notification")
            }
            PlayerBus.ACTION_STOP -> stopInternal()
            PlayerBus.ACTION_SELFTEST -> {
                // --ei secs N：自检读取的音频秒数（默认 3；吞吐实测时用 20~30）
                val secs = intent.getIntExtra("secs", 3).coerceIn(1, 300)
                intent.getStringExtra(PlayerBus.EXTRA_PATH)?.let { selfTest(it, secs) }
            }
            PlayerBus.ACTION_JUMP -> playQueueAt(intent.getIntExtra(PlayerBus.EXTRA_QUEUE_INDEX, -1), null)
            PlayerBus.ACTION_SEEK_NEAR_END -> {
                // 调试入口：跳到离曲末 N 秒（N 放在 EXTRA_POS），用来快速看循环边界
                val secs = intent.getLongExtra(PlayerBus.EXTRA_POS, 5).toInt().coerceIn(1, 60)
                if (srcBytesPerSec > 0 && totalBytes > 0) {
                    val target = (totalBytes - secs.toLong() * srcBytesPerSec).coerceAtLeast(0)
                    Log.i(TAG, "调试：跳到离结尾 ${secs}s（byte $target / $totalBytes）")
                    doSeek(target)
                    // 流式源的 seek 是异步交给生产者线程的，这里主动刷新一次 UI 位置，
                    // 否则调试命令看起来"只改了字节偏移、进度没动"
                    notifyUi()
                } else {
                    Log.w(TAG, "调试：当前没有可 seek 的曲目")
                }
            }
            PlayerBus.ACTION_SET_REPEAT -> {
                // UI 只负责把新值放进来；真正决定行为的是下面 onTrackEnded 里读的 PlayerBus.repeatMode
                PlayerBus.repeatMode = com.dsd.player.data.RepeatMode.of(
                    intent.getStringExtra(PlayerBus.EXTRA_MODE))
                Log.i(TAG, "播放模式 → ${PlayerBus.repeatMode.label}")
            }
            PlayerBus.ACTION_NEXT, PlayerBus.ACTION_PREV -> {
                if (queue.size <= 1) {
                    // 队列里只有一首（例如在线单曲直链）：给提示，别静默停住
                    PlayerBus.errorMessage = "队列里只有 1 首；在曲目列表里点任意一首可整列表连播"
                    notifyUi()
                } else {
                    playQueueAt(nextIndex(forward = intent.action == PlayerBus.ACTION_NEXT), null)
                }
            }
            PlayerBus.ACTION_SEEK -> {
                val ms = intent.getLongExtra(PlayerBus.EXTRA_POS, 0)
                if (srcBytesPerSec > 0) doSeek(ms * srcBytesPerSec / 1000)
            }
            PlayerBus.ACTION_SET_MODE -> {
                val m = intent.getStringExtra(PlayerBus.EXTRA_MODE)
                forcedMode = m
                PlayerBus.forcedMode = m
                // 有曲目在放才即时切换；否则记住选择，下次开流生效
                if (source != null && producer.isRunning()) restartWithMode(m)
                else notifyUi()
            }
            PlayerBus.ACTION_CONNECT -> if (!usb.connected) monitor.scanExisting()
            null -> if (!usb.connected) monitor.scanExisting()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { usb.stopStream() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 不 shutdown 单例执行器：Service 可能立刻被重新创建（idleStop 宽限），
        // 复用同一个线程比反复新建更安全（泄漏就是这么来的）。
        if (instance === this) instance = null
        producer.stop()
        usb.detach()
        monitor.unregister()
        mediaSession?.release()
        super.onDestroy()
    }

    // ── DAC ────────────────────────────────────────────────────────────
    private fun onDacAttached(d: DacInfo, conn: android.hardware.usb.UsbDeviceConnection) {
        usb.attach(d, conn)
        PlayerBus.connected = usb.connected
        PlayerBus.usbLabel = usb.label
        PlayerBus.capsLine = usb.capsLine
        PlayerBus.usbSummary = usb.usbSummary
        PlayerBus.supportsDsdNative = usb.supportsDsdNative
        notifyUi()
        Log.i(TAG, "DAC 已插回：${usb.label}（${usb.usbSummary}）")
        // 让出 USB / 拔插之后的恢复请求：拿到 DAC 就在原位置继续
        val resumePos = pendingResumeAt
        if (resumePos >= 0) {
            pendingResumeAt = -1
            restartAt(resumePos)
            return
        }
        // 若正在播放，重建输出流
        source?.let { s ->
            if (isMainThread()) {
                postOpen("DAC 重连重建输出流") {
                    if (PlayerBus.playing && !producer.isRunning() && opening.compareAndSet(false, true)) {
                        runCatching {
                            producer.stop()
                            val cfg = usb.openFor(s, PlayerBus.outputMode.ifBlank { null }?.let { parseMode(it) })
                            val bs = openStreamFor(s)
                            setRates(s.info, cfg)
                            producer.start(s, cfg, bs, playPosBytes)
                            prefillRingThenUnmute()
                        }.onFailure { PlayerBus.errorMessage = "DAC 重连失败：${it.message}" }
                        opening.set(false)
                    }
                }
                return
            }
            if (PlayerBus.playing && !producer.isRunning() && opening.compareAndSet(false, true)) {
                runCatching {
                    producer.stop()
                    val cfg = usb.openFor(s, PlayerBus.outputMode.ifBlank { null }?.let { parseMode(it) })
                    val bs = openStreamFor(s)
                    setRates(s.info, cfg)
                    producer.start(s, cfg, bs, playPosBytes)
                    prefillRingThenUnmute()
                }.onFailure { PlayerBus.errorMessage = "DAC 重连失败：${it.message}" }
                opening.set(false)
            }
        }
    }

    private fun onDacDetached() {
        // 记录"拔 DAC 时播到哪"，插回后自动从这里继续（真机第一百二十二轮：以前插回完全没反应）
        if (PlayerBus.playing || producer.isRunning()) {
            pendingResumeAt = runCatching { producer.played() }.getOrDefault(playPosBytes)
            Log.i(TAG, "DAC 已拔出：停在 ${pendingResumeAt / 1024}KB，等插回自动恢复")
        } else {
            Log.i(TAG, "DAC 已拔出（当前未播放）")
        }
        producer.stop()
        usb.detach()
        PlayerBus.connected = false
        PlayerBus.playing = false
        PlayerBus.usbLabel = ""
        notifyUi()
    }

    // ── 播放 ────────────────────────────────────────────────────────────
    /** 尝试播放队列第 i 首；返回 false 表示这首不可播（调用方继续下一首） */
    private fun tryPlayQueueItem(i: Int, modeName: String?): Boolean {
        queueIndex = i
        PlayerBus.queueIndex = i
        PlayerBus.queueSize = queue.size
        val path = queue.getOrNull(i) ?: return false
        Log.i(TAG, "队列第 ${i + 1}/${queue.size} 首：${path.take(48)}（重复模式=${PlayerBus.repeatMode.label}）")
        PlayerBus.trackPath = path
        if (com.dsd.player.data.WebDavResolver.isDav(path)) {
            // WebDAV：封面要联网找（同目录 cover.* 或内嵌图）→ 先显示占位，后台解析完再刷新
            PlayerBus.trackCover = ""
            PlayerBus.trackCoverHeaders = emptyMap()
            resolveDavCover(path)
        } else {
            PlayerBus.trackCover = queueCovers.getOrNull(i).orEmpty()
            PlayerBus.trackCoverHeaders = emptyMap()
        }
        return try {
            playBlocking(null, path, modeName, queueTitles.getOrNull(i))
            true
        } catch (e: Exception) {
            Log.w(TAG, "第 ${i + 1} 首不可播放：${e.message}")
            PlayerBus.errorMessage = (e.message ?: "不可播放") + "（已跳过）"
            producer.stop()
            false
        }
    }

    /** 与 play() 相同，但异常向上抛（供队列跳过逻辑判断） */
    private fun playBlocking(uri: Uri?, path: String?, modeName: String?, displayTitle: String?) {
        if (!opening.compareAndSet(false, true)) throw IllegalStateException("开流忙碌")
        try {
            playInner(uri, path, modeName, displayTitle)
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
            runCatching { producer.stop() }   // 只停生产者，不释放接口（历史上 release 后死锁）
            PlayerBus.errorMessage = e.message ?: "播放失败"
            PlayerBus.status = "error"
            notifyUi()
        } finally {
            opening.set(false)
        }
    }

    private fun play(uri: Uri?, path: String?, modeName: String?, displayTitle: String? = null) {
        if (isMainThread()) { postOpen("play") { play(uri, path, modeName, displayTitle) }; return }
        if (!opening.compareAndSet(false, true)) {
            Log.w(TAG, "play: 已有开流操作在进行，忽略本次")
            return
        }
        try {
            playInner(uri, path, modeName, displayTitle)
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
            runCatching { producer.stop() }   // 只停生产者，不释放接口（历史上 release 后死锁）
            PlayerBus.errorMessage = e.message ?: "播放失败"
            PlayerBus.status = "error"
            notifyUi()
        } finally {
            opening.set(false)
        }
    }

    /**
     * 等 USB DAC 就绪（最多 [timeoutMs]）。
     *
     * 真机实测：装包后**第一次**点播放时 `lsusb` 里 `152a:8807` 已经在，但 `usb.connected`
     * 还是 false（枚举 + 授权 + attach 还没走完）。以前这里直接抛异常 → 用户看到"点了没反应、
     * 停在暂停"，重新打开 app 才好。现在先等一会儿，期间主动 scanExisting()。
     */
    private fun waitDacReady(timeoutMs: Long): Boolean {
        if (usb.connected) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        Log.i(TAG, "等待 USB DAC 就绪（最多 ${timeoutMs}ms）…")
        var lastScan = 0L
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            if (now - lastScan > 400) { lastScan = now; runCatching { monitor.scanExisting() } }
            if (usb.connected) {
                Log.i(TAG, "USB DAC 已就绪（等了 ${System.currentTimeMillis() - (deadline - timeoutMs)}ms）")
                return true
            }
            try { Thread.sleep(80) } catch (ie: InterruptedException) { break }
        }
        return usb.connected
    }

    /** 纯播放实现：**异常向上抛**，由调用方决定提示/跳过 */
    private fun playInner(uri: Uri?, path: String?, modeName: String?, displayTitle: String?) {
        if (!usb.connected && !waitDacReady(2500)) {
            // 不再抛异常：给一句能照做的提示，界面停在"未播放"而不是假装在缓冲
            Log.w(TAG, "no USB DAC attached, abort playback（已等待 2.5s）")
            PlayerBus.errorMessage = "未检测到 USB DAC：插好 OTG 后点播放会重试；若刚插上请再等一下"
            PlayerBus.status = "idle"
            PlayerBus.playing = false
            notifyUi()
            return
        }
        run {
            producer.stop()
            source?.close()
            // 在线取流要 2~3 s（取签名 + probeSize + 首块），界面上必须说清楚，否则像卡死
            PlayerBus.status = "opening"
            PlayerBus.errorMessage = ""
            // 切歌时先把进度状态清零：否则这一瞬间用的是**上一首**的 position/duration，
            // 迷你条会按旧进度画出"几乎满格"的蓝线（真机上的"切歌时进度条异常"就是这个）
            playPosBytes = 0
            totalBytes = -1
            srcBytesPerSec = 0
            PlayerBus.positionMs = 0
            PlayerBus.durationMs = 0
            notifyUi()
            val itemPath = path ?: (uri?.let { MediaLibrary.ensurePath(this, com.dsd.player.data.MediaItem(
                uri.lastPathSegment ?: "audio", it, it.path, false, 0)) })
                ?: error("无法解析路径")
            // 在线队列条目 `qobuz:<trackId>`：现在才换取流直链（签名有时效，必须临用现取）
            val resolved = if (com.dsd.player.data.QobuzResolver.isQobuz(itemPath)) {
                Log.i(TAG, "解析在线曲目：${itemPath.take(40)}…（换取流地址）")
                com.dsd.player.data.QobuzResolver.resolve(itemPath)
            } else itemPath
            val realPath = if (com.dsd.player.decoder.SourceFactory.isUrl(resolved)) resolved
            else MediaLibrary.ensurePath(this,
                com.dsd.player.data.MediaItem(java.io.File(itemPath).name,
                    uri ?: Uri.fromFile(java.io.File(itemPath)), itemPath, false, 0))
                ?: itemPath
            lastPath = itemPath      // 记原条目（qobuz:<id> 下次仍可重新解析）
            PlayerBus.trackPath = itemPath
            val src = SourceFactory.create(this, Uri.fromFile(java.io.File(realPath)), realPath)
            src.bindPath(realPath)
            source = src
            PlayerBus.trackName = displayTitle ?: java.io.File(itemPath).name
            PlayerBus.trackFmt = src.info.fmt()
            val mode = (modeName ?: forcedMode)?.let { parseMode(it) }
            val cfg = usb.openFor(src, mode)
            PlayerBus.outputMode = cfg.mode.name
            val bs = openStreamFor(src)
            setRates(src.info, cfg)
            // 有效位深：源标称 24bit 但平台解码器只给 16bit 时，UI 要诚实显示
            PlayerBus.sourceBits = src.info.bitsPerSample
            PlayerBus.outputBits = bs.outputBits
            val ob = PlayerBus.outputBits
            if (!src.info.isDsd && ob in 1..16 && src.info.bitsPerSample > ob) {
                Log.w(TAG, "位深降级：源 ${src.info.bitsPerSample}bit → 实际输出 ${ob}bit" +
                    "（平台解码器上限；DAC 面板仍显示 24bit）")
            }
            playPosBytes = 0
            producer.start(src, cfg, bs, 0)
            // 切歌/起播后强制状态一致：未暂停 + 未静音（否则按"播放"会走淡出）
            producer.paused = false
            prefillRingThenUnmute()
            PlayerBus.errorMessage = ""
            PlayerBus.playing = true
            PlayerBus.status = "playing"
            notifyUi()
        }
    }

    /** 单线程解析 WebDAV 封面（不阻塞开流；结果过期就丢弃） */
    private val coverExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dav-cover").apply { isDaemon = true }
    }

    private fun resolveDavCover(handle: String) {
        coverExec.execute {
            val r = runCatching { com.dsd.player.data.WebDavCover.ensureCover(this, handle) }.getOrNull()
            if (PlayerBus.trackPath != handle) return@execute   // 已经切歌，丢弃
            PlayerBus.trackCover = r?.first.orEmpty()
            PlayerBus.trackCoverHeaders = r?.second ?: emptyMap()
            mainHandler.post { notifyUi() }
        }
    }

    /** 播放队列中的第 idx 首（越界则停止） */
    private fun playQueueAt(idx: Int, modeName: String?) {
        if (isMainThread()) { postOpen("playQueueAt#$idx") { playQueueAt(idx, modeName) }; return }
        if (queue.isNotEmpty()) {
            // 队列里可能有 DAC 不支持的曲目（如 32k）：逐个尝试，失败就跳到下一首，
            // 绝不能把异常抛到服务循环里（那会让整队中断、界面停在"假播放"）
            var i = idx.coerceAtLeast(0)
            var tried = 0
            while (i in queue.indices && tried < queue.size) {
                if (tryPlayQueueItem(i, modeName)) return
                Log.w(TAG, "跳过不可播放的第 ${i + 1} 首：${queue[i]}")
                i++
                tried++
            }
            PlayerBus.errorMessage = "队列中没有可播放的曲目（采样率/格式不受支持）"
            stopInternal()
            return
        }
        // 单曲播放（根目录/远程单曲）没有队列：不要 stopInternal（会释放接口造成死锁）
        val p = lastPath
        if (p != null) {
            play(null, p, modeName, PlayerBus.trackName)
        } else {
            stopInternal()
        }
    }

    /**
     * 解码自检：不开 USB，只验证「容器解析 + 解码器 + 字节流」这条链路。
     * 用法：adb shell am start-foreground-service -n com.dsd.player/.playback.PlaybackService \
     *        -a com.dsd.player.SELFTEST --es path /sdcard/Music/xxx.wav
     */
    /** 自检时把位深信息发到 UI（真机不用播也能核对「源 24bit → 输出 16bit」提示） */
    private fun exposeDepth(path: String, src: com.dsd.player.decoder.AudioSource, bs: com.dsd.player.decoder.ByteSource) {
        PlayerBus.trackName = path.substringAfterLast('/')
        PlayerBus.trackFmt = src.info.fmt()
        PlayerBus.sourceBits = src.info.bitsPerSample
        PlayerBus.outputBits = bs.outputBits
        val note = PlayerBus.bitDepthNote()
        if (com.dsd.player.BuildConfig.DEBUG) {
            Log.i(TAG, "位深：源=${PlayerBus.sourceBits}bit 输出=${PlayerBus.outputBits}bit" +
                (if (note.isNotEmpty()) " → UI 提示「$note」" else "（一致或无信息）"))
        }
        mainHandler.post { notifyUi() }
    }

    private fun selfTest(path: String, secs: Int = 3) {
        // 自检/诊断钩子只在 debug 构建里生效（release 包里没有调试入口）：
        //   SELFTEST --es path <文件/URL/webdav:> [--ei secs N]  → 不开 DAC 也能验解码链路
        //   SELFTEST --es path "dl:<远程路径>"                   → 验 WebDAV 下载落点
        if (!com.dsd.player.BuildConfig.DEBUG) {
            Log.w(TAG, "selftest 只在 debug 构建可用（当前是 release）")
            return
        }
        if (path.startsWith("dl:")) {
            // dl:<远程路径> → 走真实下载链路并打印落地路径（验证下载目录/命名）
            val rp = path.removePrefix("dl:")
            Thread {
                runCatching {
                    val f = com.dsd.player.data.DavDownload.download(this, rp)
                    Log.i(TAG, "dl → 目标=${f.absolutePath}（${f.length()} B）" +
                        " 显示用相对路径=${com.dsd.player.data.DavDownload.displayPath(this, f)}")
                }.onFailure { Log.e(TAG, "dl 失败：${it.message}", it) }
            }.start()
            return
        }
        Thread {
            try {
                Log.i(TAG, "selftest: path=$path")
                // 在线直链也走同一套自检（不需要 DAC 就能验证流式解码）
                if (com.dsd.player.decoder.SourceFactory.isUrl(path) ||
                    com.dsd.player.data.WebDavResolver.isDav(path)) {
                    val src = com.dsd.player.decoder.SourceFactory.create(this, Uri.parse(path), path)
                    Log.i(TAG, "selftest(stream): container=${src.info.container} " +
                        "rate=${src.info.sampleRate} ch=${src.info.channels} " +
                        "bits=${src.info.bitsPerSample} durMs=${src.info.durationUs / 1000}")
                    val bs = src.openPcm()
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    // 读够 ~3 秒的 24-in-32 数据：位深判断错（2 倍速）会在源内部
                    // 的「解码自检」里报比例≈2，这里再用耗时/字节数旁证一次
                    val want = if (src.info.sampleRate > 0) src.info.sampleRate.toLong() * 8 * secs
                               else secs.toLong() * 1024 * 1024
                    var i = 0
                    while (total < want && i < 4000) {
                        val n = bs.read(buf, 0, buf.size)
                        if (i < 3) Log.i(TAG, "selftest(stream): read#$i n=$n head=" +
                            (if (n > 0) buf.copyOfRange(0, minOf(24, n)).joinToString("") { b -> "%02x".format(b) } else "-"))
                        if (n <= 0) break
                        total += n
                        i++
                    }
                    Log.i(TAG, "selftest(stream): 解码 $total B ≈ ${total * 1000 / (src.info.sampleRate.toLong() * 8).coerceAtLeast(1)} ms" +
                        "（标称 ${src.info.durationUs / 1000} ms）")
                    // 顺带把位深信息发到 UI，便于真机/模拟器截图核对「源 24bit → 输出 16bit」
                    exposeDepth(path, src, bs)
                    // 顺带验证 seek：有总长就跳到中点（24bit 精确路径要在这里验帧级重定位）
                    val lb = bs.lengthBytes
                    val target = if (lb > 0) lb / 2 else 10_000_000L
                    val okSeek = bs.seek(target)
                    val n2 = if (okSeek) bs.read(buf, 0, buf.size) else -1
                    // seek 准确性：从 target 读到 EOF 的字节数应 ≈ lengthBytes - target
                    var rest = if (n2 > 0) n2.toLong() else 0L
                    var calls = 0
                    if (okSeek) {
                        var guard = 0
                        while (rest < 12L * 1024 * 1024 && guard++ < 2000) {
                            val k = bs.read(buf, 0, buf.size)
                            calls++
                            if (k <= 0) break
                            rest += k
                        }
                    }
                    val producedDbg = (bs as? com.dsd.player.decoder.FlacFloatByteSource)?.produced ?: -1L
                    Log.i(TAG, "selftest(stream): seek($target) ok=$okSeek read=$n2" +
                        " 期望剩余=${if (lb > 0) lb - target else -1} 实际读到=$rest" +
                        "（$calls 次 read，源 produced=$producedDbg）" +
                        " head=" + (if (n2 > 0) buf.copyOfRange(0, minOf(16, n2)).joinToString("") { b -> "%02x".format(b) } else "-"))
                    bs.close(); src.close()
                    Log.i(TAG, "selftest(stream): OK total=$total")
                    return@Thread
                }
                val ok = java.io.File(path).canRead()
                Log.i(TAG, "selftest: canRead=$ok exists=${java.io.File(path).exists()}")
                val f = java.io.File(path)
                val head = ByteArray(16)
                java.io.FileInputStream(f).use { it.read(head) }
                Log.i(TAG, "selftest: head16=" + head.joinToString("") { b -> "%02x".format(b) })
                val src = SourceFactory.create(this, Uri.fromFile(f), path)
                src.bindPath(path)
                Log.i(TAG, "selftest: container=${src.info.container} rate=${src.info.sampleRate} " +
                    "ch=${src.info.channels} bits=${src.info.bitsPerSample} dsd=${src.info.isDsd} " +
                    "durMs=${src.info.durationUs / 1000}")
                val bs = if (src.info.isDsd) src.openDsd() else src.openPcm()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                // 非 DSD 时读够 ~3 秒（CODEC 分支位深错会在这里露出"2 倍速"）
                val want = when {
                    src.info.isDsd -> secs.toLong() * 1024 * 1024
                    src.info.sampleRate > 0 -> src.info.sampleRate.toLong() * 8 * secs
                    else -> secs.toLong() * 1024 * 1024
                }
                var i = 0
                while (total < want && i < 4000) {
                    val n = bs.read(buf, 0, buf.size)
                    if (i < 3) Log.i(TAG, "selftest: read#$i n=$n head=" +
                        (if (n > 0) buf.copyOfRange(0, minOf(24, n)).joinToString("") { b -> "%02x".format(b) } else "-"))
                    if (n <= 0) break
                    total += n
                    i++
                }
                if (!src.info.isDsd) {
                    val bps = src.info.sampleRate.toLong() * 8
                    Log.i(TAG, "selftest: 解码 $total B ≈ ${total * 1000 / bps.coerceAtLeast(1)} ms" +
                        "（标称 ${src.info.durationUs / 1000} ms）")
                    exposeDepth(path, src, bs)
                }
                bs.close()
                src.close()
                Log.i(TAG, "selftest: OK total=$total")
            } catch (e: Throwable) {
                Log.e(TAG, "selftest: FAIL", e)
            }
        }.start()
    }

    private fun openStreamFor(s: AudioSource) =
        if (s.info.isDsd) s.openDsd() else s.openPcm()

    /**
     * 起播/恢复前先把环形缓冲灌到"约 1.2 秒"的水位，再解除静音。
     *
     * 目标取 max(0.75 s 音频量, …)（上限 70% 容量），等待上限 2.5 s。
     * 为什么：native 泵在静音态不消耗环形缓冲，所以"灌饱再放开"能一次把水位抬到目标高度；
     * 否则水位会停在 0 附近徘徊（生产≈消耗），任何几十毫秒的抖动都会变成一次欠载——
     * 真机 96k/24bit 上就是"偶发滋啦"（约 8~10 次/s，且 ring 水位看着还行）。
     * 上限 1.5 s 或 70% 容量，超时就照常起播，不会卡住。
     */
    /**
     * 卡死看门狗（真机第一百三十八轮：列表循环重播死锁 55 秒，界面一直是 PLAYING、无任何提示）。
     *
     * 判据：`PLAYING` 但已播位置连续 3 s 推进 < 300 ms。
     * 处置：① 先打 W 日志（ringUsed / xfer 增量 / 反馈是否活着）；
     *      ② 再尝试 `ensureUnmuted()`（泵若停在"静音不读环"，这一步能救回来）；
     *      ③ 仍不动就按当前队列重开一次流 —— 宁可断一下，也不能无声卡死。
     */
    private var stallPos = -1L
    private var stallSince = 0L
    private var stallTries = 0
    private var lastWatchRing = -1
    private var lastWatchSent = 0L
    private var lastAccountingWarn = 0L

    private fun startStallWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                checkStall()
                mainHandler.postDelayed(this, 1000)
            }
        }, 3000)
    }

    private fun checkStall() {
        val playing = PlayerBus.playing && PlayerBus.status == "playing"
        // 曲末排空阶段位置本来就几乎不动，不参与卡死判定
        if (!playing || !producer.isRunning() || producer.isDraining()) {
            stallPos = -1L; stallSince = 0L; stallTries = 0; lastWatchRing = -1
            lastWatchSent = 0L
            return
        }
        val pos = playPosBytes
        val now = System.currentTimeMillis()
        if (stallPos < 0L || pos - stallPos > 300) {      // 正常推进
            stallPos = pos; stallSince = now; stallTries = 0
            return
        }
        if (now - stallSince < 5000) return
        val st = runCatching { usb.stats() }.getOrNull()
        // ★ 先判断"音频到底有没有在流动"：泵累计送出字节在涨 = 音频在播，
        //   此时 position 不动只可能是**记账问题**，绝不能自救（真机第一百四十七轮：
        //   环每秒 +352,440 B 在正常播放，只因为 pos 停住就被连开三级自救把好流杀了）。
        val sentNow = st?.getOrNull(1) ?: 0L
        val elapsed = now - stallSince
        val expect = if (usbBytesPerSec > 0) usbBytesPerSec * elapsed / 1000 else 0L
        val sentDelta = sentNow - lastWatchSent
        lastWatchSent = sentNow
        if (expect > 0 && sentDelta * 2 >= expect) {
            // 降频：首现一次 + 之后每 60 秒一次（真机 149 发现每 5 秒一条会把环指针日志淹掉）
            val last = lastAccountingWarn
            if (last == 0L || now - last >= 60_000) {
                lastAccountingWarn = now
                Log.w(TAG, "位置记账异常（泵在送数据 $sentDelta B / ${elapsed}ms，pos 未动）→ 不判定卡死")
            }
            stallSince = now
            return
        }
        val ringNow = runCatching { usb.ringUsed() }.getOrDefault(-1)
        val delta = if (lastWatchRing >= 0) ringNow - lastWatchRing else 0
        lastWatchRing = ringNow
        val ps = runCatching { com.dsd.player.usb.NativeBridge.nativePumpState() }.getOrDefault(0L)
        val gain = (ps and 0xFFFFFFFFL) / 1000.0
        val silent = (ps shr 32) and 1L == 1L
        val mute = (ps shr 33) and 1L == 1L
        val disc = (ps shr 34) and 1L == 1L
        Log.w(TAG, "播放疑似卡住：位置 ${now - stallSince}ms 未推进（pos=$pos ringUsed=$ringNow " +
            "Δ环=$delta xfer=${st?.getOrNull(0)} underrun=${st?.getOrNull(2)} " +
            "err=${st?.getOrNull(3)} gain=$gain silent=$silent mute=$mute 待丢弃=$disc " +
            "重置次数=$stallTries）")
        stallSince = now
        when (stallTries) {
            // ① 最轻：把输出状态整体重置为"正在播放"（幂等，专治"停在静音填充"）
            0 -> runCatching {
                Log.w(TAG, "卡住自救①：重置泵输出状态")
                usb.forcePlay()
            }
            // ② 重开流：连 USB 会话一起重建
            1 -> runCatching {
                Log.w(TAG, "卡住自救②：重开当前流")
                producer.stop()
                resumeFrom("watchdog")
            }
            // ③ 重建 USB 会话（close + open）：不直接"泵停止+清空会话"
            2 -> runCatching {
                Log.w(TAG, "卡住自救③：重建 USB 会话")
                producer.stop()
                usb.stopStream()
                resumeFrom("watchdog")
            }
            // ④ 放弃：明确告知并停在 PAUSED（不再让状态空掉）
            else -> runCatching {
                PlayerBus.errorMessage = "播放卡住（已自动恢复 3 次），请重新选择曲目"
                stopInternal()
            }
        }
        stallTries++
    }

    private fun prefillRingThenUnmute() {
        // 记下本次预填开始时的 seek 世代：如果预填期间**又有新的 seek 请求**进来
        // （第一百三十八轮：列表循环重播时两个线程同时 seek），这次淡入就是过期指令，
        // 必须丢弃，否则会把别人刚做的淡出盖掉，泵停在"只发静音不读环"的中间态。
        val gen = usb.seekGeneration()
        val cap = 8 * 1024 * 1024                      // 与 native kRingBytes 一致
        // 真机实测（第一百一十七轮）：96k/24bit 目标 0.8 s（600 KB）在首块网络读取 4 s 的场景下
        // 必然超时 → 起播欠载。现在：目标按 0.5 s 音频量算，超时放宽到 6 s；
        // 而且泵改成"启动即静音"，预填期间不会被抽干，这个目标基本都能达到。
        // 0.75 s：真机复测（第一百一十九轮）发现 0.5 s 预填在淡入后第一个 5 s 窗口
        // 就被吃到 ring 只剩 9 KB（靠静音启动才没顶穿），所以加 250 ms 余量。
        val byTime = usbBytesPerSec * 750 / 1000
        val target = minOf((cap * 0.7).toLong(), byTime).toInt()
        if (target > 0) {
            // 2.5 s 上限：泵是静音启动的，正常情况下 0.5~1 s 就能达标；网络极慢时也不能让
            // 用户干等 6 s —— 超时就带着已灌到的水位淡入（宁可开头少一点余量）。
            val deadline = System.currentTimeMillis() + 2500
            var last = 0
            while (System.currentTimeMillis() < deadline) {
                if (!usb.connected) break
                last = usb.ringUsed()
                if (last >= target) break
                try { Thread.sleep(10) } catch (ie: InterruptedException) { break }
            }
            if (com.dsd.player.BuildConfig.DEBUG) {
                Log.i(TAG, "起播预填：ring=${last / 1024}KB / 目标 ${target / 1024}KB" +
                    "（${if (last >= target) "已达标" else "超时"}）")
            }
        }
        // ★ 不是"增量解除静音"，而是把输出状态整体重置为"正在播放"（幂等、与历史无关）
        usb.forcePlay(gen)
        // 预填期（淡入前）的欠载是"无声段的空洞"，不计入健康度
        runCatching { com.dsd.player.usb.NativeBridge.nativeResetStats() }
    }

    private fun setRates(info: com.dsd.player.decoder.AudioInfo, cfg: com.dsd.player.usb.StreamConfig) {
        srcBytesPerSec = if (info.isDsd) (info.dsdRateHz / 4).toLong()
                         else info.sampleRate.toLong() * 8
        usbBytesPerSec = cfg.usbRate.toLong() * cfg.frameBytes
        totalBytes = if (info.durationUs > 0) info.durationUs * srcBytesPerSec / 1_000_000 else -1
    }

    private fun restartWithMode(modeName: String?) {
        if (isMainThread()) { postOpen("restartWithMode") { restartWithMode(modeName) }; return }
        val s = source ?: return
        // 先算目标配置：PCM 源无论选"自动/DoP/Native"实际都是 PCM@原采样率，
        // 这种情况下**什么都不用做**——以前照样 stop→reopen→seek→预填，白丢 1.2~6 s。
        val cur = usb.currentConfig
        val target = runCatching { usb.decide(s, modeName?.let { parseMode(it) }) }.getOrNull()
        if (cur != null && target != null && usb.connected &&
            cur.usbRate == target.usbRate && cur.mode == target.mode) {
            Log.i(TAG, "切换输出模式：对当前流无影响（${cur.mode} ${cur.usbRate}Hz），保持播放")
            PlayerBus.outputMode = cur.mode.name
            notifyUi()
            return
        }
        // 用"此刻真实已播字节"而不是上次上报的 playPosBytes：后者最多滞后一个读块，
        // 真机上切模式回退 0.2~1.8 s 就是这么来的。
        val pos = runCatching { producer.played() }.getOrDefault(playPosBytes)
        runCatching {
            producer.stop()
            val cfg = usb.openFor(s, modeName?.let { parseMode(it) })
            PlayerBus.outputMode = cfg.mode.name
            val bs = openStreamFor(s)
            setRates(s.info, cfg)
            bs.seek(pos)
            producer.start(s, cfg, bs, pos)
            // 泵是静音启动的：切模式重开流之后必须重新预填 + 淡入，否则"切了模式就听不到声"
            prefillRingThenUnmute()
            PlayerBus.errorMessage = ""
            PlayerBus.playing = true
        }.onFailure { PlayerBus.errorMessage = "切换模式失败：${it.message}" }
        notifyUi()
    }

    private fun doSeek(bytePos: Long) {
        if (producer.seek(bytePos)) playPosBytes = bytePos
        notifyUi()
    }

    /**
     * 播放模式决定队列末尾行为：
     *  - 单曲循环：重播当前这首
     *  - 列表循环：回到第一首
     *  - 顺序：停止
     */
    private fun onTrackEnded() {
        when (PlayerBus.repeatMode) {
            com.dsd.player.data.RepeatMode.ONE -> {
                if (queue.isNotEmpty() && queueIndex in queue.indices) {
                    Log.i(TAG, "单曲循环：重播第 ${queueIndex + 1} 首")
                    playQueueAt(queueIndex, null)
                } else if (lastPath != null) {
                    play(null, lastPath, forcedMode, displayTitle = PlayerBus.trackName)
                } else stopInternal()
            }
            com.dsd.player.data.RepeatMode.LIST -> {
                if (queue.isNotEmpty()) {
                    val next = (queueIndex + 1).mod(queue.size)
                    Log.i(TAG, "列表循环：第 ${queueIndex + 1}/${queue.size} 首播完 → 第 ${next + 1} 首")
                    playQueueAt(next, null)
                } else if (lastPath != null) {
                    play(null, lastPath, forcedMode, displayTitle = PlayerBus.trackName)
                } else stopInternal()
            }
            com.dsd.player.data.RepeatMode.ORDER -> {
                if (queueIndex >= 0 && queueIndex + 1 < queue.size) {
                    playQueueAt(queueIndex + 1, null)
                } else {
                    stopInternal()
                }
            }
        }
    }

    /** 上一首/下一首的目标索引：列表循环时首尾相接，顺序模式越界就留在原地 */
    private fun nextIndex(forward: Boolean): Int {
        if (queue.isEmpty()) return queueIndex
        val cur = queueIndex.coerceAtLeast(0)
        val wrap = PlayerBus.repeatMode != com.dsd.player.data.RepeatMode.ORDER
        var n = if (forward) cur + 1 else cur - 1
        if (n !in queue.indices) {
            if (!wrap) return queueIndex
            n = if (forward) 0 else queue.size - 1
        }
        return n
    }

    /** 停止后保留服务的宽限期：60s 内再播直接用现成进程，不用冷启动（真机"停止→再播没反应"） */
    private val idleStop = Runnable {
        Log.i(TAG, "空闲 60s：释放并停止服务")
        runCatching { producer.stop() }
        runCatching { usb.stopStream() }
        stopSelf()
    }

    private fun stopInternal() {
        // 开流还在工作线程里跑：把"停"排到它后面，避免 native 层一边开流一边 release
        if (isMainThread() && opening.get()) { postOpen("stopInternal") { stopInternal() }; return }
        producer.stop()
        runCatching {
            val st = usb.stats()
            Log.i(TAG, "停止统计: xfer=${st[0]} bytes=${st[1]} underrun=${st[2]} err=${st[3]} " +
                "ringUsed=${usb.ringUsed()}")
        }
        // 关键：把 alt 归 0 并释放接口，否则 DAC 还在取空数据（哒哒声）
        runCatching { usb.stopStream() }
        PlayerBus.playing = false
        PlayerBus.status = "idle"
        playPosBytes = 0
        totalBytes = -1
        srcBytesPerSec = 0
        PlayerBus.positionMs = 0
        PlayerBus.durationMs = 0
        PlayerBus.queueIndex = queueIndex
        PlayerBus.queueSize = queue.size
        notifyUi()
        // 不立刻 stopSelf：留 60s 宽限，期间"停止→再播"、切歌都是热路径
        mainHandler.removeCallbacks(idleStop)
        mainHandler.postDelayed(idleStop, 60_000)
    }

    private fun updateSession() = notifyUi()

    private fun parseMode(s: String): OutputMode = when (s.lowercase()) {
        "native" -> OutputMode.DSD_NATIVE
        "dop" -> OutputMode.DOP
        else -> OutputMode.PCM
    }

    // ── UI/通知 ─────────────────────────────────────────────────────────
    private fun notifyUi() {
        val dur = if (srcBytesPerSec > 0 && totalBytes > 0) totalBytes * 1000 / srcBytesPerSec else 0
        PlayerBus.durationMs = dur
        PlayerBus.positionMs = if (srcBytesPerSec > 0) playPosBytes * 1000 / srcBytesPerSec else 0
        PlayerBus.statusTick++
        mediaSession?.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                android.media.session.PlaybackState.ACTION_SEEK_TO or
                android.media.session.PlaybackState.ACTION_STOP)
            .setState(if (PlayerBus.playing) android.media.session.PlaybackState.STATE_PLAYING
                      else android.media.session.PlaybackState.STATE_PAUSED,
                      PlayerBus.positionMs, 1f)
            .build())
        val key = "${PlayerBus.trackName}|${PlayerBus.outputMode}|${PlayerBus.playing}|${PlayerBus.errorMessage}"
        if (key != lastNotifKey) {
            lastNotifKey = key
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(NOTIF_ID, buildNotification(PlayerBus.trackName)) }
        }
    }

    private fun buildNotification(title: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!channelReady) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
            channelReady = true
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pausePi = PendingIntent.getService(this, 1,
            Intent(PlayerBus.ACTION_PAUSE).setComponent(
                android.content.ComponentName(this, PlaybackService::class.java)),
            PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(PlayerBus.outputMode.ifBlank { PlayerBus.usbLabel })
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, if (PlayerBus.playing) getString(R.string.pause) else getString(R.string.play), pausePi)
        // mediaPlayback 类型的前台服务必须让通知与 MediaSession 关联，否则系统会认为
        // "FGS 没有绑定会话"（日志里的 setFgsIfNoSessionIsLinkedToNotification），
        // 进而在暂停/停止后把进程回收（真机表现为"停止→再播没反应"、intent 被丢一半）。
        val token = runCatching { mediaSession?.sessionToken }.getOrNull()
        if (token != null) {
            @Suppress("DEPRECATION")
            val compat = runCatching {
                android.support.v4.media.session.MediaSessionCompat.Token.fromToken(token)
            }.getOrNull()
            if (compat != null) {
                builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(compat)
                    .setShowActionsInCompactView(0))
            }
        }
        return builder.build()
    }
}
