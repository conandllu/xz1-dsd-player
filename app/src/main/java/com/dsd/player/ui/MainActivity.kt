// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.dsd.player.R
import com.dsd.player.data.Library
import com.dsd.player.data.MediaItem
import com.dsd.player.data.MediaLibrary
import com.dsd.player.data.PlayerBus
import com.dsd.player.data.Track
import com.dsd.player.data.TrackGroup
import com.dsd.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Tab { LIBRARY, FOLDERS, PLAYER, SETTINGS }

/** 音乐库的来源：本地曲库 / WebDAV / Qobuz 在线（同一个页面，只换数据来源） */
enum class LibSource { LOCAL, DAV, QOBUZ }

/** WebDAV 曲库的浏览维度 */
enum class DavMode { ALBUM, ARTIST, SONG }

/** 深链：直接打开在线音源并搜索 */
const val ACTION_SEARCH = "com.dsd.player.SEARCH"

class MainActivity : AppCompatActivity() {

    lateinit var b: ActivityMainBinding
        private set

    var tracks: List<Track> = emptyList()
        private set
    // 默认三列：四列在 402dp 宽度下每列只有 94.5dp，中文专辑名必然椭断（ui-layout-review P1）
    var libMode = LibMode.ALBUM
    var layoutMode = LayoutMode.GRID3
    var sortKey = Library.SortKey.ALBUM
    var sortDesc = false
    var openedGroup: TrackGroup? = null
        private set
    var query: String = ""
        private set
    val folderStack = ArrayList<File>()

    var tab = Tab.LIBRARY
        private set

    private lateinit var libraryPage: LibraryPage
    private lateinit var folderPage: FolderPage
    private lateinit var playerPage: PlayerPage
    private lateinit var settingsPage: SettingsPage
    private lateinit var playlistPage: PlaylistPage

    /** 在线（Qobuz）状态与数据；放在 Activity 上，旋转重建视图后仍然保留 */
    val qobuzSection: QobuzSection by lazy { QobuzSection(this) }

    var libSource = LibSource.LOCAL
        private set

    /** WebDAV 曲库维度（专辑/艺术家/歌曲）与当前进入的分组（专辑名或艺术家） */
    var davMode = DavMode.SONG
        private set
    var davGroup: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = -1L
    /** 键盘（IME）是否在场：在场时收起迷你条与底部导航，把空间还给内容区 */
    var imeVisible = false
        private set
    private var imeWatching = false
    /** 已经用 Toast 提示过的错误文案（避免每个 tick 都弹） */
    private var lastErrorShown = ""
    private var scanning = false
    private var allFilesAtStart = false
    private var askedAllFiles = false

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { rescan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadUiPrefs()       // 布局/排序/曲库维度都要在 buildUi 之前读回（否则首帧是默认值）
        com.dsd.player.data.WebDavResolver.init(this)
        buildUi()
        // 播放模式是持久化的：先把 UI 侧的值对齐，等 Service 起来了它也会设一次
        PlayerBus.repeatMode = com.dsd.player.data.PlaybackPrefs.repeat(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!onBackAction()) finish() }
        })

        allFilesAtStart = hasAllFilesAccess()
        requestPermsIfNeeded()
        PlayerBus.bindCommand(this, PlayerBus.ACTION_CONNECT)
        handleViewIntent(intent)
        rescan()
        switchTab(Tab.LIBRARY)
    }

    /**
     * 构建整棵视图树。旋转时（configChanges 已声明，Activity 不重建）会再次调用它，
     * 这样布局资源能按 layout / layout-land 重新选取，而 USB/播放状态完全不受影响。
     */
    private fun buildUi() {
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        lastLandscape = isLandscape

        libraryPage = LibraryPage(this, b.pageHost)
        folderPage = FolderPage(this, b.pageHost)
        playerPage = PlayerPage(this, b.pageHost)
        settingsPage = SettingsPage(this, b.pageHost)
        playlistPage = PlaylistPage(this, b.pageHost)

        b.btnBack.setOnClickListener { onBackAction() }
        // 页内搜索框默认隐藏，顶栏这个图标负责展开/收起（和排序/布局按钮并排）
        b.btnSearch.setOnClickListener { libraryPage.toggleSearch() }
        b.btnSort.setOnClickListener { showSortSheet() }

        b.miniPlay.setOnClickListener { togglePause() }
        b.miniNext.setOnClickListener { PlayerBus.bindCommand(this, PlayerBus.ACTION_NEXT) }
        b.miniPrev.setOnClickListener { PlayerBus.bindCommand(this, PlayerBus.ACTION_PREV) }
        b.miniPlayer.setOnClickListener { switchTab(Tab.PLAYER) }

        imeWatching = false
        watchIme()
        b.navLibrary.setOnClickListener { switchTab(Tab.LIBRARY) }
        b.navFolders.setOnClickListener { switchTab(Tab.FOLDERS) }
        b.navPlayer.setOnClickListener { switchTab(Tab.PLAYER) }
        b.navSettings.setOnClickListener { switchTab(Tab.SETTINGS) }
    }

    /** 旋转：重建视图树（保留 tab / 打开的专辑 / 文件夹栈 / 队列等状态） */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val nowLandscape = newConfig.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val changed = nowLandscape != lastLandscape
        super.onConfigurationChanged(newConfig)
        if (!changed) return
        val keepPlaylist = playlistVisible
        hidePlaylistQuiet()
        buildUi()
        // 在线来源、搜索结果都存在 qobuzSection 里，这里只要按 tab 重画即可
        if (keepPlaylist) showPlaylist() else switchTab(tab)
        folderPage.render()
        updateSubtitle()
    }

    private fun hidePlaylistQuiet() {
        playlistVisible = false
        playlistPage.setVisible(false)
    }

    val isLandscape: Boolean
        get() = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** 上一次构建视图时的屏幕方向，用来判断真的旋转了（而不是键盘弹出之类的配置变化） */
    private var lastLandscape = false

    /**
     * 侧键 / 媒体键的**应用内直通**（本窗口有焦点时）：
     *
     * - 索尼侧键（相机键）= KEYCODE_CAMERA(27)，真机 scanCode 766。走 Key Mapper 的
     *   无障碍链路有 1~2 s 判定延迟；本窗口直接拿到按键则 < 50 ms。
     * - 耳机线控 / 蓝牙按键：KEYCODE_MEDIA_*、KEYCODE_HEADSETHOOK。
     *
     * 只有「已有曲目」时才吃掉按键（否则在曲库页按侧键应当照常打开相机）；
     * 若 Key Mapper 那条映射还在，无障碍层会先拦下事件，这里自然不会重复触发。
     * 应用不在前台时这些键仍由 MediaSession 处理（同一条 applyPaused 实现）。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        val isPlayPauseKey = k == android.view.KeyEvent.KEYCODE_CAMERA ||
            k == android.view.KeyEvent.KEYCODE_HEADSETHOOK ||
            k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        val isPlayKey = k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        val isPauseKey = k == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        if (!(isPlayPauseKey || isPlayKey || isPauseKey)) return super.dispatchKeyEvent(event)
        val hasTrack = PlayerBus.trackName.isNotBlank() || PlayerBus.durationMs > 0
        if (!hasTrack) return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_UP && event.repeatCount == 0) {
            // 入口名要如实上报：相机键 from=camera、耳机/蓝牙键 from=mediaKey
            // （第三十二轮反馈：日志里侧键也写 from=ui，排查时分不清是哪条入口）
            val from = if (k == android.view.KeyEvent.KEYCODE_CAMERA) "camera" else "mediaKey"
            when {
                isPlayKey -> setPaused(false, from)
                isPauseKey -> setPaused(true, from)
                else -> togglePause(from)
            }
        }
        return true          // 吃掉，避免系统再拉起相机/重复处理
    }

    /** 显式暂停/恢复（同进程直调，失败退化 Intent） */
    private fun setPaused(paused: Boolean, from: String = "ui") {
        val svc = com.dsd.player.playback.PlaybackService.current()
        if (svc != null) svc.setPausedNow(paused, from)
        else PlayerBus.bindCommand(this, PlayerBus.ACTION_SET_PAUSED, paused = paused)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == QobuzSection.REQ_WEB_LOGIN) {
            qobuzSection.onWebLoginResult(resultCode == RESULT_OK)
            if (tab == Tab.LIBRARY) libraryPage.render()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    // ── 页面切换 ──────────────────────────────────────────────────────
    fun switchTab(t: Tab) {
        tab = t
        playlistVisible = false
        playlistPage.setVisible(false)
        libraryPage.setVisible(t == Tab.LIBRARY)
        folderPage.setVisible(t == Tab.FOLDERS)
        playerPage.setVisible(t == Tab.PLAYER)
        settingsPage.setVisible(t == Tab.SETTINGS)
        navHighlight(b.navLibrary, b.navLibraryIcon, b.navLibraryText, t == Tab.LIBRARY, 0)
        navHighlight(b.navFolders, b.navFoldersIcon, b.navFoldersText, t == Tab.FOLDERS, 1)
        navHighlight(b.navPlayer, b.navPlayerIcon, b.navPlayerText, t == Tab.PLAYER, 2)
        navHighlight(b.navSettings, b.navSettingsIcon, b.navSettingsText, t == Tab.SETTINGS, 3)
        updateChrome()
        when (t) {
            Tab.LIBRARY -> libraryPage.render()
            Tab.FOLDERS -> folderPage.render()
            Tab.PLAYER -> playerPage.render()
            Tab.SETTINGS -> settingsPage.render()
        }
        updateSubtitle()
    }

    /**
     * 顶栏能力按页面决定：返回箭头只在**二级页**出现（专辑/艺术家内页、文件夹子目录、
     * 播放列表），搜索与排序只在音乐库/文件夹出现。任何会改变层级的操作都要调它，
     * 否则会出现"进了内页却没有返回箭头"。
     */
    private fun updateChrome() {
        val t = tab
        // 播放页自带完整控件，胶囊没有存在意义 → 收起来把空间让给进度条和按钮；
        // 键盘在场时迷你条与底部导航整体让位（否则内容区会被压到 0，见 ui-layout-review P0）
        applyBottomBars()
        val qobuzDetail = t == Tab.LIBRARY && libSource == LibSource.QOBUZ &&
            qobuzSection.content is QobuzSection.Content.AlbumDetail
        val davGroup = t == Tab.LIBRARY && libSource == LibSource.DAV && this.davGroup != null
        val canBack = t == Tab.LIBRARY && (openedGroup != null || qobuzDetail || davGroup) ||
            t == Tab.FOLDERS && (folderStack.isNotEmpty() || playlistVisible)
        b.btnBack.visibility = if (canBack || playlistVisible) View.VISIBLE else View.GONE
        // 音乐库（本地/在线）都能用搜索图标展开搜索框；文件夹页不支持搜索
        b.btnSearch.visibility =
            if (!playlistVisible && t == Tab.LIBRARY) View.VISIBLE else View.GONE
        // 排序/布局对本地与在线都生效（在线：网格/列表 + 标题/年份排序）
        val canSort = !playlistVisible && (t == Tab.FOLDERS || t == Tab.LIBRARY)
        b.btnSort.visibility = if (canSort) View.VISIBLE else View.GONE
        b.pageTitle.text = if (playlistVisible) "播放列表" else when (t) {
            Tab.LIBRARY -> getString(R.string.lib_title)
            Tab.FOLDERS -> getString(R.string.lib_folder)
            Tab.PLAYER -> getString(R.string.now_playing)
            Tab.SETTINGS -> getString(R.string.settings)
        }
    }

    private fun navHighlight(item: View, icon: ImageView, label: TextView,
                             on: Boolean, index: Int) {
        // 首尾项的外侧圆角与导航胶囊(28dp)一致、内侧 18dp；中间项全 18dp —— 贴边不留缝
        // 横屏用左侧 rail：选中项是独立的小方块，位置无关，统一 18dp 圆角
        val bg = when {
            !on -> 0
            isLandscape -> R.drawable.bg_nav_sel_mid
            index == 0 -> R.drawable.bg_nav_sel_first
            index == 3 -> R.drawable.bg_nav_sel_last
            else -> R.drawable.bg_nav_sel_mid
        }
        item.setBackgroundResource(bg)
        val color = if (on) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    fun updateSubtitle() {
        b.pageSubtitle.text = when (tab) {
            Tab.LIBRARY -> if (libSource == LibSource.DAV) {
                val g = davGroup
                if (g != null) "WebDAV · $g · ${davGroupCount(g)} 首"
                else {
                    val dir = com.dsd.player.data.ScanPrefs.davMusicDir(this)
                    "WebDAV · /${dir.ifBlank { "" }} · ${com.dsd.player.data.DavLibrary.cached().size} 首"
                }
            } else if (libSource == LibSource.QOBUZ) {
                "在线 · " + qobuzSection.qualityLine()
            } else {
                val g = openedGroup
                when {
                    g != null -> "${g.title} · ${g.count} 首"
                    query.isNotBlank() -> "搜索“$query” · ${libraryRowsCount()} 项"
                    else -> "${tracks.size} 首歌曲 · " + when (libMode) {
                        LibMode.ALBUM -> "${Library.byAlbum(tracks).size} 张专辑"
                        LibMode.ARTIST -> "${Library.byArtist(tracks).size} 位艺术家"
                        LibMode.GENRE -> "${Library.byGenre(tracks).size} 个流派"
                        LibMode.YEAR -> "${Library.byYear(tracks).size} 个年份"
                        else -> "全部歌曲"
                    }
                }
            }
            Tab.FOLDERS -> folderStack.lastOrNull()?.absolutePath ?: "常用音乐目录"
            Tab.PLAYER -> if (playlistVisible) {
                val n = PlayerBus.queueSize
                val i = PlayerBus.queueIndex
                if (n > 0 && i >= 0) "${i + 1}/$n 首 · 右滑返回" else "播放列表为空"
            } else currentTrackSubtitle()
            Tab.SETTINGS -> "USB 独占输出 · 所有文件访问 · 曲库"
        }
    }

    /** 播放页副标题：艺术家 · 专辑（曲名与采样率在页内，避免重复） */
    private fun currentTrackSubtitle(): String {
        val name = PlayerBus.trackName
        if (name.isBlank()) return ""
        val t = tracks.firstOrNull { it.title == name }
        return buildList {
            if (t != null) {
                if (t.artist.isNotBlank()) add(t.artist)
                val al = t.displayAlbum()
                if (al.isNotBlank()) add(al)
            }
        }.joinToString(" · ")
    }

    /** WebDAV 某分组（专辑名/艺术家）下的曲目数（与列表渲染同一套分组规则） */
    private fun davGroupCount(name: String): Int {
        val mode = davMode
        return com.dsd.player.data.DavLibrary.cached().count { t ->
            if (mode == DavMode.ARTIST)
                com.dsd.player.data.DavLibrary.artistOf(t.dir) == name
            else com.dsd.player.data.DavLibrary.albumOf(t.dir) == name
        }
    }

    private fun libraryRowsCount(): Int = if (libMode == LibMode.SONG) songs().size else groups().size

    // ── 曲库数据 ──────────────────────────────────────────────────────
    fun rescan() {
        if (scanning) return
        scanning = true
        b.pageSubtitle.text = getString(R.string.scanning)
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { Library.reload(this@MainActivity) }
            tracks = list
            scanning = false
            val st = Library.lastStats
            if (st.diskBlocked && !hasAllFilesAccess()) {
                toast("磁盘扫描被系统限制（只找到 ${st.dbCount} 首媒体库条目）——请授予「所有文件访问」")
            } else if (list.size < st.dbCount) {
                // 理论上不会发生，留个兜底日志
                android.util.Log.w("MainActivity", "曲库合并异常：db=${st.dbCount} merged=${list.size}")
            }
            if (list.isEmpty()) {
                toast(getString(R.string.empty_library))
                if (!hasAllFilesAccess() && !askedAllFiles) {
                    askedAllFiles = true
                    askAllFilesAccess()
                }
            }
            libraryPage.render()
            folderPage.render()
            updateSubtitle()
        }
    }

    fun groups(): List<TrackGroup> {
        val base = filtered()
        val groups = when (libMode) {
            LibMode.ALBUM -> Library.byAlbum(base)
            LibMode.ARTIST -> Library.byArtist(base)
            LibMode.GENRE -> Library.byGenre(base)
            LibMode.YEAR -> Library.byYear(base)
            else -> emptyList()
        }
        return Library.sortGroups(groups, sortKey, sortDesc)
    }

    fun filtered(): List<Track> {
        if (query.isBlank()) return tracks
        val q = query.lowercase()
        return tracks.filter {
            it.title.lowercase().contains(q) || it.album.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) || it.genre.lowercase().contains(q)
        }
    }

    fun songs(): List<Track> = Library.sortTracks(filtered(), sortKey, sortDesc)

    fun groupTracks(g: TrackGroup): List<Track> = Library.sortTracks(g.tracks, sortKey, sortDesc)

    /** WebDAV 维度切换（专辑/艺术家/歌曲）；切换时退出已进入的分组 */
    fun pickDavMode(m: DavMode) {
        davMode = m
        davGroup = null
        saveUiPrefs()
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    /** 进入某张专辑/某个艺术家（WebDAV） */
    fun openDavGroup(name: String) {
        davGroup = name
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    fun closeDavGroup() {
        davGroup = null
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    fun pickLibMode(m: LibMode) {
        libMode = m
        saveUiPrefs()
        openedGroup = null
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    fun openGroup(g: TrackGroup) {
        openedGroup = g
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    fun closeGroup() {
        openedGroup = null
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    fun setLayout(m: LayoutMode) {
        layoutMode = m
        saveUiPrefs()
        libraryPage.render()
    }

    fun setSort(key: Library.SortKey, desc: Boolean) {
        sortKey = key
        sortDesc = desc
        saveUiPrefs()
        libraryPage.render()
        folderPage.render()
    }

    fun setQuery(q: String) {
        query = q
        openedGroup = null
        libraryPage.render()
        updateSubtitle()
    }

    // ── 播放 ──────────────────────────────────────────────────────────
    private var lastQueueRequestAt = 0L

    fun playQueue(list: List<Track>, index: Int) {
        // 同一次点击可能被 RecyclerView/长按判定重放 → 500ms 内的重复请求直接忽略
        val now = System.currentTimeMillis()
        if (now - lastQueueRequestAt < 500) return
        lastQueueRequestAt = now
        val playable = list.filter { !it.path.isNullOrBlank() }
        if (playable.isEmpty()) { toast("无法解析文件路径"); return }
        val target = list.getOrNull(index)?.path
        val idx = playable.indexOfFirst { it.path == target }.let { if (it < 0) 0 else it }
        PlayerBus.playQueue(this, playable.map { it.path!! }, playable.map { it.title }, idx)
    }

    fun playSingle(t: Track) {
        val path = t.path ?: run { toast("无法解析文件路径"); return }
        PlayerBus.bindCommand(this, PlayerBus.ACTION_PLAY, path = path)
    }

    // ── 导航 ──────────────────────────────────────────────────────────
    fun onBackAction(): Boolean {
        if (playlistVisible) { hidePlaylist(); return true }
        if (tab == Tab.LIBRARY && libSource == LibSource.DAV && davGroup != null) {
            closeDavGroup(); return true
        }
        if (tab == Tab.LIBRARY && libSource == LibSource.QOBUZ) {
            // 专辑详情 → 上一级列表；已经在顶层 → 回本地曲库
            if (qobuzSection.back()) { libraryPage.render(); updateChrome(); updateSubtitle(); return true }
            pickSource(LibSource.LOCAL)
            return true
        }
        if (openedGroup != null) { closeGroup(); return true }
        if (tab == Tab.FOLDERS && folderPage.goUp()) return true
        if (tab == Tab.FOLDERS && folderStack.isNotEmpty()) { folderUp(); return true }
        if (tab != Tab.LIBRARY) { switchTab(Tab.LIBRARY); return true }
        return false
    }

    fun openFolder(dir: File) {
        folderStack.add(dir)
        folderPage.render()
        updateChrome()
        updateSubtitle()
    }

    fun folderUp() {
        if (folderStack.isNotEmpty()) folderStack.removeAt(folderStack.size - 1)
        folderPage.render()
        updateChrome()
        updateSubtitle()
    }

    /** 排序与布局面板：见 SortSheet（三段式，选中态用主色 chip 而非空心圆） */
    private fun showSortSheet() = SortSheet.show(this)

    // ── 权限 / 意图 ──────────────────────────────────────────────────
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()

    fun askAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { toast("Android 10 及以下无需该权限"); return }
        if (hasAllFilesAccess()) { toast("已有所有文件访问权限"); return }
        AlertDialog.Builder(this)
            .setTitle("需要「所有文件访问」")
            .setMessage("DSD（.dsf/.dff）不被系统媒体库收录，只能按文件路径读取。\n" +
                "授予后 DSD 专辑才会出现在曲库里；解码仍全部在本地进行。")
            .setPositiveButton("去授权") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                }.onFailure {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun requestPermsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            reqPerms.launch(perms)
        }
    }

    private fun handleViewIntent(intent: Intent?) {
        // 深链搜索：am start ... -a com.dsd.player.SEARCH --es query 周杰伦
        val albumId = intent?.getStringExtra("album")?.trim().orEmpty()
        if (albumId.isNotEmpty()) {
            showQobuz()
            qobuzSection.openAlbumById(albumId)
            return
        }
        if (intent?.action == ACTION_SEARCH) {
            val q = intent.getStringExtra("query")?.trim().orEmpty()
            if (q.isNotEmpty()) {
                showQobuz()
                qobuzSection.searchNow(q)
            }
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val path = MediaLibrary.ensurePath(this, MediaItem(uri.lastPathSegment ?: "audio", uri, uri.path, false, 0))
        if (path != null) PlayerBus.bindCommand(this, PlayerBus.ACTION_PLAY, path = path)
        else toast("需要文件路径，SAF URI 请先缓存")
    }

    // ── 状态轮询 ──────────────────────────────────────────────────────
    private val poller = object : Runnable {
        override fun run() {
            if (PlayerBus.statusTick != lastTick) {
                lastTick = PlayerBus.statusTick
                renderPlayback()
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poller)
        handler.post(poller)
        if (hasAllFilesAccess() != allFilesAtStart) {
            allFilesAtStart = hasAllFilesAccess()
            rescan()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    // ── UI 偏好的持久化（第七十二轮反馈：布局/排序重启后回到默认）────────────
    private fun uiPrefs() = getSharedPreferences("ui_prefs", MODE_PRIVATE)

    private fun loadUiPrefs() {
        val p = uiPrefs()
        layoutMode = runCatching { LayoutMode.valueOf(p.getString("layout", "GRID3")!!) }
            .getOrDefault(LayoutMode.GRID3)
        sortKey = runCatching { Library.SortKey.valueOf(p.getString("sort_key", "ALBUM")!!) }
            .getOrDefault(Library.SortKey.ALBUM)
        sortDesc = p.getBoolean("sort_desc", false)
        libMode = runCatching { LibMode.valueOf(p.getString("lib_mode", "ALBUM")!!) }
            .getOrDefault(LibMode.ALBUM)
        davMode = runCatching { DavMode.valueOf(p.getString("dav_mode", "SONG")!!) }
            .getOrDefault(DavMode.SONG)
        android.util.Log.i("UiPrefs", "恢复：layout=$layoutMode sort=$sortKey desc=$sortDesc mode=$libMode")
    }

    private fun saveUiPrefs() {
        uiPrefs().edit()
            .putString("layout", layoutMode.name)
            .putString("sort_key", sortKey.name)
            .putBoolean("sort_desc", sortDesc)
            .putString("lib_mode", libMode.name)
            .putString("dav_mode", davMode.name)
            .apply()
    }

    private var lastQueueIndex = -1

    private fun renderPlayback() {
        if (playlistVisible && (PlayerBus.queueIndex != lastQueueIndex ||
                PlayerBus.queueSize != playlistRenderedSize)) {
            lastQueueIndex = PlayerBus.queueIndex
            playlistRenderedSize = PlayerBus.queueSize
            playlistPage.render()
            updateSubtitle()
        }
        b.miniTitle.text = PlayerBus.trackName.ifBlank { getString(R.string.nothing_playing) }
        b.miniSubtitle.text = if (PlayerBus.status == "opening") {
            "缓冲中…"      // 在线取流要 2~3 秒，不提示会被误判成卡死
        } else buildString {
            if (PlayerBus.trackFmt.isNotBlank()) append(PlayerBus.trackFmt)
            if (PlayerBus.outputMode.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(PlayerBus.outputMode)
            }
            // 位深降级要明说（否则 DAC 显 24bit、实际 16bit，用户以为听到真 24bit）
            val note = PlayerBus.bitDepthNote()
            if (note.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(note)
            }
        }
        b.miniPlay.setImageResource(if (PlayerBus.playing) R.drawable.ic_pause else R.drawable.ic_play)
        // 临时性提示用 Toast 弹一次（不再占用 DAC 卡片那行小字）
        val err = PlayerBus.errorMessage
        if (err.isBlank()) lastErrorShown = ""
        else if (err != lastErrorShown) { lastErrorShown = err; toast(err) }
        updateMiniProgress()
        loadArtwork(b.miniCover)
        playerPage.render()
        settingsPage.renderDac()
    }

    /**
     * 监听 IME 显隐。ui-layout-review 的 P0：键盘展开时内容区会被压到 0 dp，
     * 搜索结果取到了却看不见（被误判成"搜索坏了"）。这里让迷你条与底部导航在键盘
     * 在场时收起，把 ~126 dp 还给内容区。
     */
    private fun watchIme() {
        if (imeWatching) return
        imeWatching = true
        b.root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            b.root.getWindowVisibleDisplayFrame(r)
            val h = b.root.rootView.height
            val visible = h > 0 && (h - r.bottom) > h * 0.15
            if (visible == imeVisible) return@addOnGlobalLayoutListener
            imeVisible = visible
            android.util.Log.i("Ime", "键盘${if (visible) "展开" else "收起"} → 底部三件让位")
            applyBottomBars()
            updateMiniProgress()
        }
    }

    /** 迷你条与底部导航的可见性（播放页隐藏胶囊 + 键盘让位，两件事合在一处判断） */
    private fun applyBottomBars() {
        // 键盘在场时把副标题也收起来，多给内容区 ~23dp（review 第七十轮第 6 条）
        b.pageSubtitle.visibility = if (imeVisible) View.GONE else View.VISIBLE
        val showBottom = !imeVisible
        b.bottomNav.visibility = if (showBottom) View.VISIBLE else View.GONE
        b.miniPlayer.visibility =
            if (showBottom && !(tab == Tab.PLAYER && !playlistVisible)) View.VISIBLE else View.GONE
        // visibility 变化后让 ConstraintLayout 重新解一次约束：
        // 否则 pageHost 可能保留"迷你条隐藏时"的高度，列表末行会延伸到迷你条底下
        b.pageHost.requestLayout()
        b.root.requestLayout()
    }

    /**
     * 迷你条底部进度线。
     *
     * 判据必须是"真的在放这一首"：曲名非空 + 时长有效 + 不在开流中 + 键盘不在场。
     * 之前只用 `dur > 0`，而切歌瞬间 dur/position 还是**上一首**的值（甚至 position≈dur），
     * 于是会闪出一条满格的蓝线 —— 就是"切歌时进度条异常"。
     */
    private fun updateMiniProgress() {
        val dur = PlayerBus.durationMs
        val bgW = b.miniProgressBg.width
        // frac 为 0 时也不能把宽度设成 0：ConstraintLayout 里 0dp = match_constraint，
        // 没有 end 约束的 View 会被拉满整条（这正是"切歌瞬间进度条变成满格"的真凶）。
        val show = !imeVisible && PlayerBus.trackName.isNotBlank() && dur > 0 &&
            bgW > 0 && PlayerBus.status != "opening" && PlayerBus.positionMs > 0
        if (!show) {
            b.miniProgress.layoutParams = b.miniProgress.layoutParams.apply { width = 1 }
            b.miniProgress.visibility = View.INVISIBLE
            b.miniProgressBg.visibility = View.INVISIBLE
            return
        }
        val frac = (PlayerBus.positionMs.toFloat() / dur).coerceIn(0f, 1f)
        val px = (bgW * frac).toInt()
        if (px < 2) {                     // 太细也当"没有"，避免一条贴边的像素线
            b.miniProgress.layoutParams = b.miniProgress.layoutParams.apply { width = 1 }
            b.miniProgress.visibility = View.INVISIBLE
            b.miniProgressBg.visibility = View.INVISIBLE
            return
        }
        b.miniProgress.layoutParams = b.miniProgress.layoutParams.apply {
            width = px.coerceAtMost(bgW)
        }
        b.miniProgress.visibility = View.VISIBLE
        b.miniProgressBg.visibility = View.VISIBLE
    }

    fun currentAlbumId(): Long {
        // 先按"正在播放的路径"精确匹配（同名曲目不会串），再退回标题匹配
        val path = PlayerBus.trackPath
        if (path.isNotBlank()) {
            tracks.firstOrNull { it.path == path }?.albumId?.let { if (it > 0) return it }
        }
        val name = PlayerBus.trackName
        if (name.isBlank()) return 0
        return tracks.firstOrNull { it.title == name }?.albumId ?: 0
    }

    /**
     * 当前曲目封面：在线曲目用服务下发的封面直链（`PlayerBus.trackCover`），
     * 本地曲目走 MediaStore albumId。以前只按 albumId 取，所以在线播放时是大片占位。
     */
    fun loadArtwork(iv: ImageView) {
        val cover = PlayerBus.trackCover
        // 同一张封面只加载一次。
        // 播放中 renderPlayback() 每 ~120ms 跑一次（进度刷新），每次重设占位 + crossfade
        // 会表现为"专辑图片一直闪"——这是真机上看到的那个 bug 的根因。
        val key = when {
            cover.startsWith("http") || cover.startsWith("file") ->
                "$cover|${PlayerBus.trackCoverHeaders.size}"
            else -> "album:${currentAlbumId()}"
        }
        if (iv.tag == key) return
        iv.tag = key
        android.util.Log.i("Artwork", "load $key（视图 ${iv.id}）")
        if (cover.startsWith("http") || cover.startsWith("file")) {
            Artwork.placeholder(iv)
            val px = iv.layoutParams?.height?.takeIf { it > 0 }
                ?: (160 * resources.displayMetrics.density).toInt()
            iv.load(cover) {
                size(px)
                scale(coil.size.Scale.FILL)
                crossfade(120)
                // WebDAV 的封面图同样需要 Basic 认证（Qobuz/本地为空 map，不影响）
                PlayerBus.trackCoverHeaders.forEach { (k, v) -> addHeader(k, v) }
                listener(
                    onSuccess = { _, _ -> iv.clearColorFilter(); iv.setPadding(0, 0, 0, 0) },
                    onError = { _, _ -> Artwork.placeholder(iv) },
                )
            }
            return
        }
        Artwork.load(iv, currentAlbumId())
    }

    /** 暂停/恢复：优先同进程直调（无延迟），退化为 Intent；from 用于日志区分入口 */
    fun togglePause(from: String = "ui") {
        val svc = com.dsd.player.playback.PlaybackService.current()
        if (svc != null) svc.togglePauseNow(from)
        else PlayerBus.bindCommand(this, PlayerBus.ACTION_PAUSE)
    }

    var playlistVisible = false
        private set
    private var playlistRenderedSize = -1

    /** 切换音乐库的来源（本地 / WebDAV / 在线），标题栏与内容一起刷新 */
    fun pickSource(src: LibSource) {
        if (libSource == src) return
        libSource = src
        openedGroup = null
        if (src == LibSource.DAV) {
            com.dsd.player.data.DavLibrary.loadCached(this)   // 先出缓存，界面立刻有内容
            refreshDavLibrary()
        }
        if (tab != Tab.LIBRARY) { switchTab(Tab.LIBRARY); return }
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    /** 后台重扫 WebDAV 曲库（网络 IO → 完成后刷新界面） */
    fun refreshDavLibrary() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { com.dsd.player.data.DavLibrary.scan(this@MainActivity) }
                    .onFailure { android.util.Log.w("MainActivity", "WebDAV 曲库扫描失败：${it.message}") }
            }
            if (libSource == LibSource.DAV) {
                libraryPage.render(); updateSubtitle()
                // 目录封面要在后台解析（PROPFIND 需要认证，不能放 UI 线程）
                val samples = LinkedHashMap<String, String?>()
                com.dsd.player.data.DavLibrary.cached().forEach { t ->
                    if (!samples.containsKey(t.dir)) samples[t.dir] = t.handle
                }
                com.dsd.player.data.WebDavCover.prefetchDirsWithSamples(this@MainActivity, samples) {
                    if (libSource == LibSource.DAV) libraryPage.render()
                }
            }
        }
    }

    /**
     * 下载 WebDAV 曲目到本地 `Music/WebDAV/…`（长按触发），下载完成后重扫本地曲库。
     * 与"流播"区分：流播不落盘。
     */
    fun downloadDav(handle: String, name: String, size: Long = 0) {
        if (!com.dsd.player.data.WebDavResolver.isDav(handle)) return
        val remote = com.dsd.player.data.WebDavResolver.remotePath(handle)
        val dlg = android.app.ProgressDialog(this).apply {
            setTitle("下载 $name")
            setMessage("0%")
            setCancelable(true)
            show()
        }
        lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    com.dsd.player.data.DavDownload.download(this@MainActivity, remote, size) { done, total ->
                        if (total > 0) runOnUiThread {
                            dlg.setMessage("%d%%（%.1f / %.1f MB）".format(
                                done * 100 / total, done / 1048576.0, total / 1048576.0))
                        }
                    }
                }
            }
            runCatching { dlg.dismiss() }
            r.onSuccess { f ->
                val rel = com.dsd.player.data.DavDownload.displayPath(this@MainActivity, f)
                android.util.Log.i("DavDownload", "已下载 → ${f.absolutePath}（${f.length()} B）")
                toast("已下载到 $rel")
                rescan()      // 让它出现在本地曲库里
            }.onFailure { toast("下载失败：${it.message}") }
        }
    }

    /** 从 Qobuz 账号导入收藏（读接口可用）→ 合并进本机收藏 */
    fun importFavsFromAccount() {
        toast("正在从账号导入收藏…")
        lifecycleScope.launch {
            val r = runCatching { qobuzSection.importAccountFavorites() }
            r.onSuccess { n ->
                toast(if (n > 0) "已导入 $n 首收藏" else "账号里没有新的收藏")
                qobuzSection.showFavorites()
            }.onFailure { toast("导入失败：${it.message}") }
        }
    }

    /** 在线侧数据变化（搜索完成 / 进入专辑 / 登录态变化）→ 内容、顶栏、副标题一起刷新 */
    fun refreshOnline() {
        libraryPage.render()
        updateChrome()
        updateSubtitle()
    }

    /** 打开音乐库的在线来源（设置页入口 / 深链） */
    fun showQobuz() {
        if (libSource != LibSource.QOBUZ) libSource = LibSource.QOBUZ
        if (tab != Tab.LIBRARY) switchTab(Tab.LIBRARY)
        else { libraryPage.render(); updateChrome(); updateSubtitle() }
    }

    fun showPlaylist() {
        playlistVisible = true
        playerPage.setVisible(false)
        playlistPage.setVisible(true)
        updateChrome()
        playlistPage.render()
        updateSubtitle()
    }

    fun hidePlaylist() {
        playlistVisible = false
        playlistPage.setVisible(false)
        switchTab(Tab.PLAYER)
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
