// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.ViewGroup
import com.dsd.player.R
import com.dsd.player.data.Library
import com.dsd.player.data.OnlineAlbum
import com.dsd.player.data.OnlineTrack
import com.dsd.player.data.PlayerBus
import com.dsd.player.data.Track
import com.dsd.player.decoder.SourceFactory
import java.io.File

/** 顶部切换 chips（来源、分类、在线模式共用同一套胶囊样式） */
private fun <T> buildChips(host: LinearLayout, labels: List<Pair<T, String>>,
                           current: T, onPick: (T) -> Unit) {
    host.removeAllViews()
    val d = host.resources.displayMetrics.density
    labels.forEach { (mode, label) ->
        val tv = TextView(host.context)
        tv.text = label
        tv.textSize = 13f
        // ui-layout-review P0：原来只有 32dp 高、触摸目标 52.8×32dp，低于 48dp 下限。
        // 视觉仍是胶囊，但垂直内边距 + minHeight 把可点区做到 48dp。
        tv.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        tv.minHeight = (48 * d).toInt()
        tv.gravity = android.view.Gravity.CENTER
        val on = mode == current
        tv.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
        tv.setBackgroundResource(if (on) R.drawable.bg_chip_active else R.drawable.bg_chip)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = (8 * d).toInt()
        tv.layoutParams = lp
        tv.setOnClickListener { onPick(mode) }
        host.addView(tv)
    }
}

/** 曲库页：专辑 / 艺术家 / 歌曲 / 流派 / 年份 */
class LibraryPage(private val act: MainActivity, parent: FrameLayout) {
    private val root: View = LayoutInflater.from(act).inflate(R.layout.page_library, parent, false)
    private val list: RecyclerView = root.findViewById(R.id.list)
    private val empty: TextView = root.findViewById(R.id.empty)
    private val chipRow: LinearLayout = root.findViewById(R.id.chipRow)
    private val sourceRow: LinearLayout = root.findViewById(R.id.sourceRow)
    private val searchInput: android.widget.EditText = root.findViewById(R.id.searchInput)
    private val searchAction: TextView = root.findViewById(R.id.searchAction)
    private val searchClear: android.widget.ImageView = root.findViewById(R.id.searchClear)
    private val searchBar: View = root.findViewById(R.id.searchBar)
    private val favImportBtn: TextView = root.findViewById(R.id.favImportBtn)
    private val qbLoginBtn: TextView = root.findViewById(R.id.qbLoginBtn)

    private var groupAdapter: GroupAdapter? = null
    private var trackAdapter: TrackAdapter? = null
    private var onlineAdapter: OnlineAdapter? = null

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingFilter: Runnable? = null
    /** WebDAV 封面 url → 需要的认证头（Coil 取图时带） */
    private val davCoverAuth = HashMap<String, Map<String, String>>()

    private var bindingText = false
    private var watchingIme = false
    private var imeWasVisible = false

    init {
        parent.addView(root)
        // 在线数据变化 → 只重画内容（不动输入框，避免打字时被顶掉光标）
        act.qobuzSection.listener = {
            if (act.libSource == LibSource.QOBUZ) act.refreshOnline()
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (bindingText) return
                val text = s?.toString().orEmpty()
                searchClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                pendingFilter?.let { ui.removeCallbacks(it) }
                if (act.libSource == LibSource.LOCAL) {
                    // 本地是即时过滤：180ms 防抖，避免每敲一个字就重建整个网格
                    val r = Runnable { act.setQuery(text) }
                    pendingFilter = r
                    ui.postDelayed(r, 180)
                } else {
                    // 在线是远程搜索：800ms 防抖自动搜（键盘挡住了按钮时也不影响）
                    val r = Runnable {
                        android.util.Log.i("QobuzSearch", "防抖触发 auto text='$text'")
                        if (text.isNotBlank() && text == searchInput.text.toString()) {
                            act.qobuzSection.search(text)
                        }
                    }
                    pendingFilter = r
                    ui.postDelayed(r, 800)
                }
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, event ->
            android.util.Log.i("QobuzSearch", "IME action=$actionId event=${event?.keyCode}")
            if (act.libSource == LibSource.QOBUZ) { searchOnline("ime"); true }
            else {
                pendingFilter?.let { ui.removeCallbacks(it) }
                act.setQuery(searchInput.text.toString()); true
            }
        }
        // 有的键盘把回车当普通按键扔给视图（不触发 editor action）→ 兜底自己接
        searchInput.setOnKeyListener { _, keyCode, event ->
            val enter = keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (enter && event.action == android.view.KeyEvent.ACTION_UP) {
                android.util.Log.i("QobuzSearch", "Enter 键 → 搜索")
                if (act.libSource == LibSource.QOBUZ) searchOnline("enterKey")
                true
            } else false
        }
        searchAction.setOnClickListener {
            android.util.Log.i("QobuzSearch", "点「搜索」按钮")
            searchOnline("button")
        }
        searchClear.setOnClickListener {
            android.util.Log.i("QobuzSearch", "点「清除」")
            searchInput.setText("")
            if (act.libSource == LibSource.QOBUZ) act.qobuzSection.clearSearch()
            else act.setQuery("")
            searchClear.visibility = View.GONE
        }
        watchIme()
        // 未登录时只在来源行右侧给一个「登录」动作；登录状态本身不在音乐库里显示
        qbLoginBtn.setOnClickListener { act.qobuzSection.showLoginDialog() }
        // 收藏空状态里直接给"从账号导入"，省得去设置里找（第七十二轮建议）
        favImportBtn.setOnClickListener { act.importFavsFromAccount() }
    }

    fun setVisible(v: Boolean) { root.visibility = if (v) View.VISIBLE else View.GONE }

    /** 顶栏搜索图标：展开/收起搜索框（默认隐藏，见 ui-layout-review：「搜索框默认隐藏」） */
    fun toggleSearch() {
        val show = searchBar.visibility != View.VISIBLE
        searchBar.visibility = if (show) View.VISIBLE else View.GONE
        android.util.Log.i("QobuzSearch", "搜索框${if (show) "展开" else "收起"}")
        if (show) {
            focusSearch()
        } else {
            searchInput.clearFocus()
            runCatching {
                val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
            }
        }
    }

    fun focusSearch() {
        searchInput.requestFocus()
        searchInput.post {
            val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun searchText(): String = searchInput.text.toString()

    /** 当前来源下按「搜索」：在线＝查 Qobuz（本地是即时过滤，无需按）。from 用于排查触发来源 */
    fun searchOnline(from: String = "api") {
        val q = searchText().trim()
        android.util.Log.i("QobuzSearch", "searchOnline(from=$from q='$q')")
        if (q.isBlank()) { act.toast("先输入关键词"); return }
        if (!act.qobuzSection.loggedIn()) { act.qobuzSection.showLoginDialog(); return }
        act.qobuzSection.search(q)
    }

    /**
     * 兜底触发：**键盘收起时**如果输入框内容和上一次搜索不同，就自动搜一次。
     * 真机上出现"键盘在场时按钮/回车/防抖都不灵，收起键盘再点就好了"，
     * 这条让"收键盘"本身就成为一次搜索触发，用户不用再点。
     */
    private fun watchIme() {
        if (watchingIme) return
        watchingIme = true
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val screenH = root.rootView.height
            val imeVisible = screenH > 0 && screenH - r.bottom > screenH * 0.15
            if (imeVisible) {
                imeWasVisible = true
            } else if (imeWasVisible) {
                imeWasVisible = false
                val text = searchInput.text.toString().trim()
                if (act.libSource == LibSource.QOBUZ && text.isNotBlank() &&
                    text != act.qobuzSection.query) {
                    android.util.Log.i("QobuzSearch", "键盘收起 → 自动搜索 text='$text'")
                    act.qobuzSection.search(text)
                }
            }
        }
    }

    fun render() {
        val online = act.libSource == LibSource.QOBUZ
        syncSearchText(if (online) act.qobuzSection.query else act.query)
        searchInput.hint = if (online) "搜索 Qobuz 专辑 / 曲目" else act.getString(R.string.search_hint)
        searchAction.visibility = if (online) View.VISIBLE else View.GONE

        // 来源切换：与分类 chips 同一套样式，视觉上是一层「库 → 来源 → 分类」
        buildChips(sourceRow,
            listOf(
                LibSource.LOCAL to "本地曲库",
                LibSource.DAV to "WebDAV",
                LibSource.QOBUZ to "在线 Qobuz",
            ),
            act.libSource) { act.pickSource(it) }

        qbLoginBtn.visibility =
            if (online && !act.qobuzSection.loggedIn()) View.VISIBLE else View.GONE

        if (act.libSource == LibSource.DAV) {
            // WebDAV：与本地一样按 专辑 / 艺术家 / 歌曲 浏览
            buildChips(chipRow,
                listOf(
                    DavMode.ALBUM to "专辑",
                    DavMode.ARTIST to "艺术家",
                    DavMode.SONG to "歌曲",
                ),
                act.davMode) { act.pickDavMode(it) }
            renderDav()
        } else if (online) {
            buildChips(chipRow, act.qobuzSection.chips(), act.qobuzSection.mode) { mode ->
                act.qobuzSection.pickMode(mode, searchInput.text.toString())
                render()      // 立刻更新选中态（数据是异步回来的）
            }
            renderOnline()
        } else {
            buildChips(chipRow,
                listOf(
                    LibMode.ALBUM to "专辑",
                    LibMode.ARTIST to "艺术家",
                    LibMode.SONG to "歌曲",
                    LibMode.GENRE to "流派",
                    LibMode.YEAR to "年份",
                ),
                act.libMode) { act.pickLibMode(it) }
            renderLocal()
        }
    }

    private fun syncSearchText(want: String) {
        if (searchInput.text.toString() == want) return
        bindingText = true
        searchInput.setText(want)
        searchInput.setSelection(want.length)
        bindingText = false
        searchClear.visibility = if (want.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── 本地 ──────────────────────────────────────────────────────────
    private fun renderLocal() {
        // 「从账号导入」只在在线来源显示（之前切回本地会残留，第七十六轮反馈）
        favImportBtn.visibility = View.GONE
        val opened = act.openedGroup
        // 限定扫描目录时空态文案要指向那个目录（否则用户会以为权限丢了）
        val root = com.dsd.player.data.ScanPrefs.localRoot(act)
        empty.text = if (root.isBlank()) act.getString(R.string.empty_library)
        else "这个目录里没有音乐：\n$root\n（设置 → 本地音乐目录 可改回自动扫描）"
        when {
            opened != null -> showTracks(act.groupTracks(opened))
            act.libMode == LibMode.SONG -> showTracks(act.songs())
            else -> showGroups()
        }
    }

    private fun spanCount(): Int = when (act.layoutMode) {
        LayoutMode.GRID4, LayoutMode.COMPACT -> if (act.layoutMode == LayoutMode.GRID4) 4 else 1
        LayoutMode.GRID3 -> 3
        LayoutMode.GRID2 -> 2
        else -> 1
    }

    private fun showGroups() {
        val groups = act.groups()
        empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        val grid = act.layoutMode == LayoutMode.GRID4 || act.layoutMode == LayoutMode.GRID3 ||
            act.layoutMode == LayoutMode.GRID2
        val span = if (grid) spanCount() else 1
        trackAdapter = null
        onlineAdapter = null
        val adapter = GroupAdapter(
            grid, span,
            onOpen = { g -> act.openGroup(g) },
            onPlayAll = { g -> act.playQueue(act.groupTracks(g), 0) },
        )
        groupAdapter = adapter
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
        list.setPadding(dp(12), 0, dp(12), dp(12))
        if (grid) list.addItemDecoration(itemGap(12))
        list.layoutManager = if (grid) GridLayoutManager(act, span) else LinearLayoutManager(act)
        list.adapter = adapter
        adapter.submit(groups)
        if (grid) {
            // 等 RecyclerView 完成测量后再算正方形封面边长，否则首帧 rv.width 为 0。
            // ⚠ 这里**不再自适应增/减列**：用户选了「四列/双列」就必须是四列/双列
            //   （之前 130dp/112dp 的自适应会把 2 列抬成 3 列、把 4 列压成 3 列，
            //    表现为"布局切换不生效"，见第七十一轮）。
            list.post {
                val avail = list.width - list.paddingLeft - list.paddingRight
                if (avail <= 0) return@post
                val cell = avail / span
                adapter.cellSizePx = (cell - dp(14)).coerceAtLeast(dp(44))
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showTracks(tracks: List<Track>) {
        // 从网格切到列表时要把网格的行距装饰器摘掉，否则列表行会继承 12dp 底部间距
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
        empty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        groupAdapter = null
        onlineAdapter = null
        list.setPadding(0, 0, 0, dp(12))
        val adapter = TrackAdapter(
            act.layoutMode,
            showAlbum = act.openedGroup == null,     // 专辑内页不再重复专辑名
        ) { t, idx -> act.playQueue(tracks, idx) }
        trackAdapter = adapter
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        adapter.submit(tracks)
        // 当前播放曲目高亮
        adapter.setCurrent(PlayerBus.trackName.takeIf { it.isNotBlank() }?.let { name ->
            tracks.firstOrNull { it.title == name }?.path
        })
    }

    // ── 在线（Qobuz）──────────────────────────────────────────────────
    private fun renderOnline() {
        val sec = act.qobuzSection
        sec.ensureInitial()          // 默认把「收藏」列表显示出来
        groupAdapter = null
        trackAdapter = null
        empty.visibility = View.GONE
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)

        // 收藏页且没有任何收藏时，在列表上方给一个「从账号导入」入口
        favImportBtn.visibility =
            if (sec.mode == QobuzSection.Mode.FAV && act.qobuzSection.loggedIn() &&
                com.dsd.player.data.QobuzFavs.count(act) == 0) View.VISIBLE else View.GONE

        val mode = act.layoutMode
        val grid = mode == LayoutMode.GRID4 || mode == LayoutMode.GRID3 || mode == LayoutMode.GRID2
        val span = when (mode) {
            LayoutMode.GRID4 -> 4
            LayoutMode.GRID3 -> 3
            LayoutMode.GRID2 -> 2
            else -> 1
        }

        val items = ArrayList<OnlineItem>()
        when (val c = sec.content) {
            is QobuzSection.Content.Albums -> {
                sortAlbums(c.albums).forEach { items.add(OnlineItem.Album(it)) }
            }
            is QobuzSection.Content.Tracks -> {
                items.add(OnlineItem.Header("", c.title, sec.qualityLine(),
                    "点任意一行按列表顺序播放 · 点 ☆ 收藏"))
                sortOnlineTracks(c.tracks).forEachIndexed { i, t -> items.add(OnlineItem.Track(t, i)) }
            }
            is QobuzSection.Content.AlbumDetail -> {
                val a = c.album
                val meta = buildList {
                    if (c.tracks.isNotEmpty()) add("${c.tracks.size} 首")
                    val hd = c.tracks.firstOrNull { it.bitDepth > 0 }
                    if (hd != null) add("${hd.bitDepth}bit/${"%.1f".format(hd.sampleRate / 1000.0)}kHz")
                    if (a.releaseDate.isNotBlank()) add(a.releaseDate)
                }.joinToString(" · ")
                items.add(OnlineItem.Header(a.imageUrl, a.title, a.artist.ifBlank { "未知艺术家" }, meta))
                // 专辑内保持唱片顺序，不套排序
                c.tracks.forEachIndexed { i, t -> items.add(OnlineItem.Track(t, i)) }
            }
            is QobuzSection.Content.Note -> items.add(OnlineItem.Note(c.text))
            QobuzSection.Content.Empty -> items.add(OnlineItem.Note(onlineHelp()))
        }

        val lm = GridLayoutManager(act, if (grid) span else 1)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (grid && items.getOrNull(position) is OnlineItem.Album) 1 else lm.spanCount
        }
        val trackList = (sec.content as? QobuzSection.Content.Tracks)?.tracks
            ?: (sec.content as? QobuzSection.Content.AlbumDetail)?.tracks
            ?: emptyList()
        val adapter = OnlineAdapter(
            grid,
            { a -> sec.openAlbum(a.id, a) },
            { t -> sec.playTracks(trackList, trackList.indexOf(t).coerceAtLeast(0)) },
            onFav = { t ->
                val added = sec.toggleFavorite(t)
                act.toast(if (added) "已收藏" else "已取消收藏")
            },
            favOf = { id -> sec.isFav(id) },
        )
        onlineAdapter = adapter
        if (grid) {
            list.setPadding(dp(12), 0, dp(12), dp(12))
            list.addItemDecoration(itemGap(12))
        } else {
            list.setPadding(0, 0, 0, dp(12))
        }
        list.layoutManager = lm
        list.adapter = adapter
        adapter.submit(items)
        if (grid) {
            list.post {
                val avail = list.width - list.paddingLeft - list.paddingRight
                if (avail <= 0) return@post
                // 严格按用户选择（不再自适应降级），见第七十一轮"布局切换不生效"
                adapter.cellSizePx = (avail / span - dp(14)).coerceAtLeast(dp(44))
                adapter.notifyDataSetChanged()
            }
        }
    }

    /** 在线专辑排序（跟本地共用同一套排序字段/倒序开关） */
    private fun sortAlbums(list: List<com.dsd.player.data.OnlineAlbum>):
        List<com.dsd.player.data.OnlineAlbum> {
        val base = when (act.sortKey) {
            Library.SortKey.TITLE, Library.SortKey.ALBUM -> list.sortedBy { it.title.lowercase() }
            Library.SortKey.YEAR -> list.sortedBy { it.releaseDate }
            else -> list
        }
        return if (act.sortDesc) base.reversed() else base
    }

    private fun sortOnlineTracks(list: List<com.dsd.player.data.OnlineTrack>):
        List<com.dsd.player.data.OnlineTrack> {
        val base = when (act.sortKey) {
            Library.SortKey.TITLE -> list.sortedBy { it.title.lowercase() }
            Library.SortKey.ALBUM -> list.sortedWith(
                compareBy({ it.album.lowercase() }, { it.trackNo }, { it.title.lowercase() }))
            else -> list
        }
        return if (act.sortDesc) base.reversed() else base
    }

    /**
     * WebDAV 曲库：把 [DavLibrary] 的缓存列表渲染成与在线一致的曲目行。
     *
     * 行内点击 → 整列表入队（`webdav:<路径>` 句柄），流播逻辑见 PlaybackService/SourceFactory；
     * 标题用文件名，副标题放「格式 · 大小 · 目录」，没有图片时由占位图标兜底。
     */
    private fun renderDav() {
        favImportBtn.visibility = View.GONE
        groupAdapter = null
        trackAdapter = null
        empty.visibility = View.GONE
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)

        val all = com.dsd.player.data.DavLibrary.cached()
        val q = act.query.trim().lowercase()
        val matched = if (q.isBlank()) all else all.filter {
            it.name.lowercase().contains(q) || it.dir.lowercase().contains(q)
        }

        // 分组键：专辑 = 所在目录名（"Artist - Album" 取后半段），艺术家 = 目录名的前半段
        fun albumOf(t: com.dsd.player.data.DavTrack): String =
            com.dsd.player.data.DavLibrary.albumOf(t.dir)

        fun artistOf(t: com.dsd.player.data.DavTrack): String =
            com.dsd.player.data.DavLibrary.artistOf(t.dir)

        val rows: List<com.dsd.player.data.DavTrack>
        val groupMode = act.davMode
        val entered = act.davGroup
        val items = ArrayList<OnlineItem>()
        val grid = act.layoutMode == LayoutMode.GRID4 ||
            act.layoutMode == LayoutMode.GRID3 || act.layoutMode == LayoutMode.GRID2
        val span = when (act.layoutMode) {
            LayoutMode.GRID4 -> 4
            LayoutMode.GRID3 -> 3
            LayoutMode.GRID2 -> 2
            else -> 1
        }

        when {
            entered != null -> {
                // 已进入某张专辑/某个艺术家：列出该分组下的歌（不再显示标题块）
                rows = matched.filter {
                    if (groupMode == DavMode.ARTIST) artistOf(it) == entered else albumOf(it) == entered
                }
                rows.forEachIndexed { i, t ->
                    items.add(davTrackRow(t, i))
                }
            }
            groupMode == DavMode.SONG -> {
                val sortedBy = matched.sortedBy { it.name.lowercase() }
                rows = if (act.sortDesc) sortedBy.reversed() else sortedBy
                rows.forEachIndexed { i, t -> items.add(davTrackRow(t, i)) }
            }
            else -> {
                // 专辑 / 艺术家 列表：按分组名聚合，行里显示「N 首」
                rows = emptyList()
                val grouped = LinkedHashMap<String, MutableList<com.dsd.player.data.DavTrack>>()
                matched.forEach { t ->
                    val k = if (groupMode == DavMode.ARTIST) artistOf(t) else albumOf(t)
                    grouped.getOrPut(k) { ArrayList() }.add(t)
                }
                val ordered = grouped.entries.sortedBy { it.key.lowercase() }
                val sorted = if (act.sortDesc) ordered.reversed() else ordered
                sorted.forEachIndexed { i, (name, list) ->
                    if (grid) {
                        // 网格：专辑卡片，封面取该目录的 cover.*（带认证头）
                        val cover = com.dsd.player.data.WebDavCover.cachedDirCover(list.first().dir)
                        if (cover != null) davCoverAuth[cover.first] = cover.second
                        items.add(
                            OnlineItem.Album(
                                OnlineAlbum(
                                    id = name, title = name,
                                    artist = "${list.size} 首",
                                    tracks = list.size, bitDepth = 0, sampleRate = 0,
                                    imageUrl = cover?.first ?: "",
                                )
                            )
                        )
                    } else {
                        items.add(
                            OnlineItem.Track(
                                OnlineTrack(
                                    id = i.toLong(), title = name,
                                    artist = "${list.size} 首 · " +
                                        (list.firstOrNull()?.ext ?: "").ifBlank { "音频" },
                                    album = "", durationSec = 0, bitDepth = 0, sampleRate = 0,
                                    trackNo = 0, imageUrl = coverUrlOf(list.first()),
                                ), i,
                            )
                        )
                    }
                }
            }
        }

        val lm = GridLayoutManager(act, if (grid) span else 1)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (grid && items.getOrNull(position) is OnlineItem.Album) 1 else lm.spanCount
        }
        val adapter = OnlineAdapter(
            grid = grid,
            onAlbum = { a -> if (entered == null && groupMode != DavMode.SONG) act.openDavGroup(a.title) },
            onTrack = { t ->
                val idx = t.id.toInt()
                when {
                    entered != null -> if (rows.isNotEmpty()) {
                        PlayerBus.playQueue(act, rows.map { it.handle }, rows.map { it.title },
                            idx.coerceIn(0, rows.size - 1))
                    }
                    groupMode == DavMode.SONG -> if (rows.isNotEmpty()) {
                        PlayerBus.playQueue(act, rows.map { it.handle }, rows.map { it.title },
                            idx.coerceIn(0, rows.size - 1))
                    }
                    else -> {
                        // 点分组行 → 进入该专辑/艺术家
                        val groupNames = (0 until adapterItemCount(items)).map { pos ->
                            (items.getOrNull(pos) as? OnlineItem.Track)?.track?.title.orEmpty()
                        }
                        groupNames.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let {
                            act.openDavGroup(it)
                        }
                    }
                }
            },
            onFav = {}, favOf = { false }, showFav = false,
            coverHeaders = { url -> davCoverAuth[url] ?: emptyMap() },
            onLongTrack = { t ->
                rows.getOrNull(t.id.toInt())?.let { act.downloadDav(it.handle, it.name, it.size) }
            },
        )
        onlineAdapter = adapter
        while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
        if (grid) {
            list.setPadding(dp(12), 0, dp(12), dp(12))
            list.addItemDecoration(itemGap(12))
            list.post {
                val avail = list.width - list.paddingLeft - list.paddingRight
                if (avail > 0) {
                    adapter.cellSizePx = (avail / span - dp(14)).coerceAtLeast(dp(44))
                    adapter.notifyDataSetChanged()
                }
            }
        } else {
            list.setPadding(0, 0, 0, dp(16))
        }
        list.layoutManager = lm
        list.adapter = adapter
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            empty.text = if (com.dsd.player.data.DavLibrary.cached().isEmpty())
                "WebDAV 曲库为空：\n设置 → 高级 → WebDAV 服务器 →「选择音乐目录」并「刷新曲库」"
            else "没有匹配的歌曲"
        }
    }

    private fun adapterItemCount(items: List<OnlineItem>) = items.size

    /** 某曲目所在目录的封面（带缓存） */
    private fun coverUrlOf(t: com.dsd.player.data.DavTrack?): String {
        t ?: return ""
        val c = com.dsd.player.data.WebDavCover.cachedDirCover(t.dir) ?: return ""
        davCoverAuth[c.first] = c.second
        return c.first
    }

    /** WebDAV 曲目行（标题 / 格式·大小·目录） */
    private fun davTrackRow(t: com.dsd.player.data.DavTrack, index: Int): OnlineItem =
        OnlineItem.Track(
            OnlineTrack(
                id = index.toLong(),
                title = t.title,
                artist = buildString {
                    append(t.ext)
                    if (t.size > 0) append(" · ")
                        .append(android.text.format.Formatter.formatFileSize(act, t.size))
                    if (t.dir.isNotBlank()) append(" · ").append(t.dir)
                },
                album = t.dir, durationSec = 0, bitDepth = 0, sampleRate = 0,
                trackNo = 0, imageUrl = "",
            ),
            index,
        )

    private fun onlineHelp(): String {
        val login = if (act.qobuzSection.loggedIn()) "" else
            "还没登录：点上方「登录」→ 打开 Qobuz 网页登录（推荐），或粘贴 user_auth_token。\n\n"
        return login + "· 专辑：在上面的搜索框输入关键词，停手约 1 秒自动搜索\n" +
            "· 曲目：切到「曲目」再搜，点任意一行即按列表顺序连播\n" +
            "· 播放模式：播放页左侧按钮切换 顺序 / 列表循环 / 单曲循环\n\n" +
            "输出与本地播放同一条链路：Qobuz 无损 FLAC → 解码 → USB 独占给 DAC。"
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
}

/**
 * 网格行距装饰：卡片自身有 6dp 上下 padding，再加 12dp → 行间距约 24dp
 * （走查 #25：原来行距 76px≈28dp 偏松且不统一）。
 */
private fun itemGap(dp: Int) = object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: android.graphics.Rect, view: View,
                                parent: androidx.recyclerview.widget.RecyclerView,
                                state: androidx.recyclerview.widget.RecyclerView.State) {
        outRect.bottom = (dp * view.resources.displayMetrics.density).toInt()
    }
}

/** 文件夹页：本地目录 + WebDAV 远程目录 */
class FolderPage(private val act: MainActivity, parent: FrameLayout) {
    private val root: View = LayoutInflater.from(act).inflate(R.layout.page_folders, parent, false)
    private val list: RecyclerView = root.findViewById(R.id.list)
    private val crumb: TextView = root.findViewById(R.id.crumb)

    /** 远程模式状态 */
    private var remoteMode = false
    private val remoteStack = ArrayList<String>()   // 依次进入的远程路径
    private var remoteEntries: List<com.dsd.player.data.DavEntry> = emptyList()
    private var loading = false

    init { parent.addView(root) }

    fun setVisible(v: Boolean) { root.visibility = if (v) View.VISIBLE else View.GONE }

    /** 返回键：远程模式优先退栈；返回 true 表示已消费 */
    fun goUp(): Boolean {
        if (!remoteMode) return false
        if (remoteStack.isNotEmpty()) {
            remoteStack.removeAt(remoteStack.size - 1)
            render()
            return true
        }
        remoteMode = false
        render()
        return true
    }

    fun render() {
        if (remoteMode) renderRemote() else renderLocal()
    }

    private fun renderLocal() {
        val cfg = com.dsd.player.data.DavConfig.load(act)
        val dir = act.folderStack.lastOrNull()
        if (dir == null) {
            crumb.text = if (cfg.configured) "本地目录 + WebDAV 远程" else "仅本地目录（设置里可加 WebDAV）"
            val rows = ArrayList<Pair<File, Pair<Int, Int>>>()
            if (cfg.configured) {
                // 用特殊 file 占位表示"远程根"，点击进远程模式
                rows.add(File("/webdav") to (-1 to -1))
            }
            for (r in Library.audioRoots()) rows.add(r to (-1 to -1))
            val adapter = DirAdapter(
                onOpen = { f ->
                    if (f.absolutePath == "/webdav") { remoteMode = true; remoteStack.clear(); render() }
                    else act.openFolder(f)
                },
                onOpenTrack = { act.playSingle(it) },   // 根目录没有曲目，兜底
                davLabel = cfg.url)
            list.layoutManager = LinearLayoutManager(act)
            list.adapter = adapter
            adapter.submit(rows, emptyList())
            return
        }
        val (dirs, tracks) = Library.browse(dir, act.tracks)
        // 路径已由顶部副标题显示，这里只给统计，避免同一行路径出现两次
        crumb.text = "${dirs.size} 个目录 · ${tracks.size} 首音频"
        val adapter = DirAdapter(
            onOpen = { act.openFolder(it) },
            // 点目录里的曲目 = 把该目录当队列，这样「下一首」/自动连播才有的走
            onOpenTrack = { t -> act.playQueue(tracks, tracks.indexOf(t)) })
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        adapter.submit(dirs.map { it.dir to (it.audioCount to it.subdirCount) }, tracks)
    }

    private fun renderRemote() {
        val cfg = com.dsd.player.data.DavConfig.load(act)
        if (!cfg.configured) {
            crumb.text = "未配置 WebDAV（设置页可添加）"
            list.adapter = null
            return
        }
        val path = remoteStack.joinToString("/")
        crumb.text = "WebDAV: /" + path
        if (loading) return
        loading = true
        com.dsd.player.data.PlayerBus.errorMessage = ""
        act.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.dsd.player.data.WebDavClient(cfg).list(path)
                }
            }
            loading = false
            result.onFailure { e ->
                crumb.text = "WebDAV 出错：${e.message}"
                act.toast("WebDAV 出错：${e.message}")
            }
            remoteEntries = result.getOrDefault(emptyList())
            showRemote()
        }
    }

    private fun showRemote() {
        val rows = ArrayList<Triple<String, Boolean, com.dsd.player.data.DavEntry?>>()
        // 上一级
        if (remoteStack.isNotEmpty()) rows.add(Triple("..", true, null))
        val dirs = remoteEntries.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val files = remoteEntries.filter { !it.isDir && SourceFactory.isAudio(it.name) }
            .sortedBy { it.name.lowercase() }
        dirs.forEach { rows.add(Triple(it.name, true, it)) }
        files.forEach { rows.add(Triple(it.name, false, it)) }
        crumb.text = "WebDAV: /" + remoteStack.joinToString("/") + "（${dirs.size} 目录 · ${files.size} 音频）"

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(val v: View) : RecyclerView.ViewHolder(v)
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
                LayoutInflater.from(parent.context).inflate(R.layout.item_dir_row, parent, false))
            override fun getItemCount() = rows.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val (name, isDir, entry) = rows[position]
                val v = holder.itemView
                v.findViewById<android.widget.ImageView>(R.id.icon)
                    .setImageResource(if (isDir) R.drawable.ic_folder else R.drawable.ic_cloud)
                v.findViewById<TextView>(R.id.name).text = name
                v.findViewById<TextView>(R.id.sub).text = when {
                    entry == null -> "返回上级"
                    isDir -> "远程目录"
                    else -> android.text.format.Formatter.formatFileSize(act, entry.size) +
                        " · 点击流播（长按可下载）"
                }
                v.findViewById<TextView>(R.id.count).text = ""
                v.setOnClickListener {
                    when {
                        entry == null -> { remoteStack.removeAt(remoteStack.size - 1); render() }
                        isDir -> { remoteStack.add(entry.name); render() }
                        else -> streamRemote(entry)
                    }
                }
                v.setOnLongClickListener {
                    if (entry != null && !isDir) { askDownload(entry); true } else false
                }
            }
        }
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
    }

    /**
     * 远程曲目：**直接流播**（第七十七轮需求）。
     *
     * 队列条目用 `webdav:<远程路径>`（不带账号密码），开流时才由 WebDavResolver
     * 拼直链 + Basic 认证头，交给 HttpFlacSource 走 Range 随机读 → 与本地/在线同一条链路。
     */
    private fun streamRemote(entry: com.dsd.player.data.DavEntry) {
        val dirPath = remoteStack.joinToString("/")
        val prefix = if (dirPath.isEmpty()) "" else "$dirPath/"
        val files = remoteEntries.filter { !it.isDir && SourceFactory.isAudio(it.name) }
            .sortedBy { it.name.lowercase() }
        if (files.isEmpty()) { act.toast("该目录没有可播放的音频"); return }
        val paths = files.map { com.dsd.player.data.WebDavResolver.handle(prefix + it.name) }
        val titles = files.map { it.name }
        val idx = files.indexOfFirst { it.name == entry.name }.coerceAtLeast(0)
        act.toast("流播：${entry.name}")
        PlayerBus.playQueue(act, paths, titles, idx)
    }

    /** 长按远程曲目：仍然可以下载到缓存（离线/弱网用） */
    private fun askDownload(entry: com.dsd.player.data.DavEntry) {
        androidx.appcompat.app.AlertDialog.Builder(act)
            .setTitle(entry.name)
            .setMessage("流播（推荐）还是下载到本地缓存后再播放？")
            .setPositiveButton("流播") { _, _ -> streamRemote(entry) }
            .setNegativeButton("下载") { _, _ -> downloadAndPlay(entry) }
            .setNeutralButton("取消", null)
            .show()
    }

    /**
     * 远程曲目：下载到本地 `Music/WebDAV/…` 再播放（长按「下载」入口）。
     * 与流播区分：流播不落盘；下载后它会进本地曲库，可离线听。
     */
    private fun downloadAndPlay(entry: com.dsd.player.data.DavEntry) {
        val remotePath = (remoteStack + entry.name).joinToString("/")
        val dlg = android.app.ProgressDialog(act).apply {
            setTitle("下载 ${entry.name}")
            setMessage("0%")
            setCancelable(true)
            show()
        }
        act.lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    com.dsd.player.data.DavDownload.download(act, remotePath, entry.size) { done, total ->
                        if (total > 0) act.runOnUiThread {
                            dlg.setMessage("%d%%（%.1f / %.1f MB）".format(
                                done * 100 / total, done / 1048576.0, total / 1048576.0))
                        }
                    }
                }
            }
            runCatching { dlg.dismiss() }
            r.onSuccess { f ->
                val rel = com.dsd.player.data.DavDownload.displayPath(act, f)
                android.util.Log.i("DavDownload", "已下载 → ${f.absolutePath}（${f.length()} B）")
                act.toast("已下载到 $rel")
                act.rescan()
                PlayerBus.bindCommand(act, PlayerBus.ACTION_PLAY, path = f.absolutePath)
            }.onFailure { act.toast("下载失败：${it.message}") }
        }
    }

}

/** 设置页：DAC / 输出模式 / 曲库目录 / WebDAV / Qobuz / 权限 */
class SettingsPage(private val act: MainActivity, parent: FrameLayout) {
    private val root: View = LayoutInflater.from(act).inflate(R.layout.page_settings, parent, false)
    private val dacTitle: TextView = root.findViewById(R.id.dacTitle)
    private val dacCaps: TextView = root.findViewById(R.id.dacCaps)

    /** 输出模式三段控件（自绘：自动 / DoP / Native DSD），选中段是胶囊 */
    private val modeButtons = listOf(R.id.modeAuto, R.id.modeDop, R.id.modeNative)

    init {
        parent.addView(root)
        modeButtons.forEach { id ->
            root.findViewById<TextView>(id).setOnClickListener {
                val m = when (id) {
                    R.id.modeDop -> "dop"
                    R.id.modeNative -> "native"
                    else -> null
                }
                PlayerBus.forcedMode = m
                PlayerBus.bindCommand(act, PlayerBus.ACTION_SET_MODE, mode = m)
                renderMode()
            }
        }
        root.findViewById<TextView>(R.id.rowWebDav).setOnClickListener { showWebDavDialog() }
        root.findViewById<TextView>(R.id.rowHandoff).setOnClickListener { toggleHandoff() }
        root.findViewById<TextView>(R.id.rowQobuz).setOnClickListener { qobuzAccountDialog() }
        root.findViewById<TextView>(R.id.rowPermission).setOnClickListener { act.askAllFilesAccess() }
        root.findViewById<TextView>(R.id.rowMusicDir).setOnClickListener { pickLocalMusicDir() }
        root.findViewById<TextView>(R.id.rowRefresh).setOnClickListener { act.rescan() }
    }

    /** 三段控件的选中态：优先看用户手动选择（forcedMode），否则按实际输出模式回显 */
    private fun renderMode() {
        val cur = PlayerBus.forcedMode ?: when (PlayerBus.outputMode.lowercase()) {
            "dop" -> "dop"
            "dsd_native", "dsd_native_pcm" -> "native"
            else -> null
        }
        val sel = when (cur) {
            "dop" -> R.id.modeDop
            "native" -> R.id.modeNative
            else -> R.id.modeAuto
        }
        modeButtons.forEach { id ->
            val tv = root.findViewById<TextView>(id)
            val on = id == sel
            tv.setBackgroundResource(if (on) R.drawable.bg_mode_selected else 0)
            tv.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
        }
    }


    fun setVisible(v: Boolean) { root.visibility = if (v) View.VISIBLE else View.GONE }

    /**
     * Qobuz 账号管理（登录 / 退出）。
     * 登录状态只在设置页显示——音乐库页不再占一行显示"已登录 xxx · 已授权"。
     */
    /** 选择 WebDAV 上的「音乐目录」（远程逐级浏览，PROPFIND 列目录） */
    private fun pickDavMusicDir(onPicked: () -> Unit = {}) {
        val cfg = com.dsd.player.data.DavConfig.load(act)
        if (!cfg.configured) { act.toast("先填好 WebDAV 服务器地址并保存"); return }
        var cur = com.dsd.player.data.ScanPrefs.davMusicDir(act)
        fun parentOf(p: String): String? = if (p.isBlank()) null else p.substringBeforeLast('/', "")
        fun show() {
            val entries = withContextBlocking { com.dsd.player.data.WebDavClient(cfg).list(cur) }
            if (entries == null) { act.toast("列目录失败（检查地址/账号）"); return }
            val dirs = entries.filter { it.isDir }.sortedBy { it.name.lowercase() }
            val parent = parentOf(cur)
            val labels = ArrayList<String>()
            labels.add("✅ 选此目录：/" + cur.ifBlank { "" })
            if (parent != null) labels.add("✅ 选上一级：/" + parent.ifBlank { "" })
            if (parent != null) labels.add("⬆ 进入上一级")
            dirs.forEach { labels.add("📁 ${it.name}") }
            val base = if (parent != null) 3 else 1
            fun apply(dir: String) {
                com.dsd.player.data.ScanPrefs.setDavMusicDir(act, dir)
                act.toast("已设置：/" + dir.ifBlank { "" } + "，正在刷新曲库…")
                onPicked()                      // 立刻刷新对话框文案（第九十轮建议 1）
                act.refreshDavLibrary()         // 自动刷库（建议 2）
            }
            AlertDialog.Builder(act)
                .setTitle("WebDAV 音乐目录")
                .setItems(labels.toTypedArray()) { _, i ->
                    when {
                        i == 0 -> apply(cur)
                        i == 1 && parent != null -> apply(parent!!)
                        i == 2 && parent != null -> { cur = parent!!; show() }
                        else -> {
                            val name = dirs[i - base].name
                            cur = if (cur.isBlank()) name else "$cur/$name"
                            show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        show()
    }

    /** AlertDialog 里的同步网络调用：放到 IO 线程并等待（最多 20s），避免卡 UI */
    private fun <T> withContextBlocking(block: () -> T): T? =
        runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() } }.getOrNull()

    /**
     * 选择「本地音乐目录」：从内部存储根开始逐级浏览，选中后**只扫描该目录**。
     * 用自带的目录浏览（而不是 SAF 树）：播放需要真实文件路径，SAF 的 Uri 不等于路径。
     */
    private fun pickLocalMusicDir() {
        var cur = java.io.File(
            com.dsd.player.data.ScanPrefs.localRoot(act)
                .ifBlank { android.os.Environment.getExternalStorageDirectory().absolutePath })
        fun show() {
            val dirs = runCatching {
                cur.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
            }.getOrNull().orEmpty()
            val parent = cur.parentFile
            val labels = ArrayList<String>()
            labels.add("✅ 选此目录：${cur.absolutePath}")
            if (parent != null) labels.add("✅ 选上一级：${parent.absolutePath}")
            if (parent != null) labels.add("⬆ 进入上一级")
            dirs.forEach { labels.add("📁 ${it.name}") }
            val base = if (parent != null) 3 else 1
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("本地音乐目录")
                .setItems(labels.toTypedArray()) { _, i ->
                    when {
                        i == 0 -> {
                            com.dsd.player.data.ScanPrefs.setLocalRoot(act, cur.absolutePath)
                            act.rescan(); act.toast("已限定：${cur.name}（只扫描该目录）"); render()
                        }
                        i == 1 && parent != null -> {
                            com.dsd.player.data.ScanPrefs.setLocalRoot(act, parent.absolutePath)
                            act.rescan(); act.toast("已限定：${parent.name}（只扫描该目录）"); render()
                        }
                        i == 2 && parent != null -> { cur = parent; show() }
                        else -> { cur = dirs[i - base]; show() }
                    }
                }
                .setNeutralButton("恢复自动") { _, _ ->
                    com.dsd.player.data.ScanPrefs.setLocalRoot(act, "")
                    act.rescan(); act.toast("已恢复自动扫描"); render()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        show()
    }

    private fun qobuzAccountDialog() {
        val logged = com.dsd.player.data.QobuzPrefs.loggedIn(act)
        if (!logged) {
            act.qobuzSection.showLoginDialog()
            return
        }
        AlertDialog.Builder(act)
            .setTitle("Qobuz 账号")
            .setMessage(
                "已登录：" + com.dsd.player.data.QobuzPrefs.email(act) +
                    com.dsd.player.data.QobuzPrefs.tier(act).takeIf { it.isNotBlank() }
                        ?.let { "\n档位：" + it }.orEmpty() +
                    "\n\n在线播放入口在「音乐库 → 在线 Qobuz」。")
            .setPositiveButton("退出登录") { _, _ ->
                act.qobuzSection.logout()
                render()
                act.toast("已退出 Qobuz")
            }
            .setNeutralButton("导入账号收藏") { _, _ -> act.importFavsFromAccount() }
            .setNegativeButton("去音乐库") { _, _ -> act.showQobuz() }
            .show()
    }

    /** 「让出 USB 独占」：暂停/停止时把 DAC 还给系统，让官方 Apple Music 等外部播放器能接管 */
    private fun toggleHandoff() {
        val now = com.dsd.player.data.PlaybackPrefs.handoff(act)
        AlertDialog.Builder(act)
            .setTitle(if (now) "关闭「让出 USB 独占」" else "开启「让出 USB 独占」")
            .setMessage(if (now) {
                "关闭后：暂停时仍保持 USB 独占（外部播放器无法使用 DAC）。\n" +
                    "本应用暂停 → 恢复的响应更快，但 Apple Music 只能走系统混音或无声。"
            } else {
                "开启后：本应用**暂停或停止**时会主动停流、release 接口并关闭 USB 连接，" +
                    "内核驱动会重新绑定，官方 Apple Music 等就能把 XD05 Bal 当普通 USB 声卡用。\n\n" +
                    "再点播放时本应用会重新申请 DAC 并从原位置继续（可能弹一次 USB 授权）。\n\n" +
                    "注意：Apple Music 的无损流是 FairPlay 加密的，第三方应用无法解码，" +
                    "所以 Apple Music 的无损只能由官方 App 自己输出；本应用负责把 DAC 让出来。"
            })
            .setPositiveButton(if (now) "关闭" else "开启") { _, _ ->
                com.dsd.player.data.PlaybackPrefs.setHandoff(act, !now)
                render()
                act.toast(if (!now) "已开启：暂停时让出 USB" else "已关闭：保持独占")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showWebDavDialog() {
        val cfg = com.dsd.player.data.DavConfig.load(act)
        val d = act.resources.displayMetrics.density.toInt()
        val pad = 20 * d
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun field(label: String, value: String, hint: String = ""): android.widget.EditText {
            val tv = TextView(act).apply { text = label; setTextColor(0x99FFFFFF.toInt()); textSize = 12f }
            val et = android.widget.EditText(act).apply {
                setText(value); this.hint = hint
                setTextColor(0xFFFFFFFF.toInt())
            }
            box.addView(tv); box.addView(et)
            return et
        }
        val urlEt = field("服务器地址", cfg.url, act.getString(R.string.webdav_hint))
        val userEt = field("用户名（可空）", cfg.user)
        val passEt = field("密码（可空）", cfg.password).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val trust = android.widget.CheckBox(act).apply {
            text = "忽略证书错误（自签 HTTPS）"
            isChecked = cfg.trustAll
            setTextColor(0xCCFFFFFF.toInt())
        }
        box.addView(trust)
        val status = TextView(act).apply {
            setTextColor(0xFF7FD1A6.toInt()); textSize = 12f
            setPadding(0, 8 * d, 0, 0)
        }
        box.addView(status)

        // ── 曲库范围：WebDAV 音乐目录（只收该目录下的歌）──
        val dirRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10 * d, 0, 0)
        }
        fun smallBtn(label: String, onClick: () -> Unit): TextView =
            TextView(act).apply {
                text = label
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(14 * d, 10 * d, 14 * d, 10 * d)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8 * d }
            }
        val dirLabel = TextView(act).apply {
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, 6 * d, 0, 0)
        }
        fun refreshDirLabel() {
            val dd = com.dsd.player.data.ScanPrefs.davMusicDir(act)
            dirLabel.text = "音乐目录：" + (if (dd.isBlank()) "/（服务器根）" else "/$dd") +
                " · 曲库 ${com.dsd.player.data.DavLibrary.cached().size} 首"
        }
        refreshDirLabel()
        dirRow.addView(smallBtn("选择音乐目录") {
            pickDavMusicDir {
                refreshDirLabel()
                status.text = "音乐目录已更改，正在后台刷新曲库…"
            }
        })
        dirRow.addView(smallBtn("刷新曲库") {
            act.toast("正在扫描 WebDAV 曲库…")
            act.lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { com.dsd.player.data.DavLibrary.scan(act) } }
                refreshDirLabel()
                act.toast("WebDAV 曲库：${com.dsd.player.data.DavLibrary.cached().size} 首")
                act.refreshDavLibrary()
            }
        })
        box.addView(dirRow)
        box.addView(dirLabel)

        val scroll = android.widget.ScrollView(act).apply { addView(box) }
        val dialog = AlertDialog.Builder(act)
            .setTitle(R.string.webdav_settings)
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNeutralButton("测试连接", null)
            .setNegativeButton("清除", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newCfg = com.dsd.player.data.DavConfig(
                    url = urlEt.text.toString().trim(),
                    user = userEt.text.toString(),
                    password = passEt.text.toString(),
                    trustAll = trust.isChecked)
                com.dsd.player.data.DavConfig.save(act, newCfg)
                act.toast("已保存 WebDAV 配置")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val testCfg = com.dsd.player.data.DavConfig(
                    urlEt.text.toString().trim(), userEt.text.toString(),
                    passEt.text.toString(), trust.isChecked)
                status.text = "正在连接…"
                act.lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) {
                        runCatching { com.dsd.player.data.WebDavClient(testCfg).list("").size }
                    }
                    status.text = n.fold(
                        { ok -> "连接成功，根目录 $ok 项" },
                        { e -> "失败：${e.message}" })
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                com.dsd.player.data.DavConfig.save(act, com.dsd.player.data.DavConfig())
                com.dsd.player.data.DavCache.clear(act)
                act.toast("已清除 WebDAV 配置与缓存")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun render() {
        renderDac()
        val dav = com.dsd.player.data.DavConfig.load(act)
        root.findViewById<TextView>(R.id.rowWebDav).text =
            "WebDAV 服务器\n" + if (dav.configured) dav.url else "未配置 · 点击添加"
        root.findViewById<TextView>(R.id.rowPermission).text =
            "所有文件访问\n" + (if (act.hasAllFilesAccess()) "已授予 ✓" else "未授予 · 点击开启（DSD 需要）")
        root.findViewById<TextView>(R.id.rowQobuz).text = "Qobuz 在线无损（在音乐库里）\n" +
            if (com.dsd.player.data.QobuzPrefs.loggedIn(act))
                "已登录 " + com.dsd.player.data.QobuzPrefs.email(act)
            else "未登录 · 点击登录你自己的账号"
        val handoff = com.dsd.player.data.PlaybackPrefs.handoff(act)
        root.findViewById<TextView>(R.id.rowHandoff).text = "让出 USB 独占\n" + when {
            !handoff -> "关闭 · 暂停时保持独占"
            PlayerBus.connected -> "开启 · 暂停后 DAC 交给外部播放器"
            else -> "开启 · 当前 DAC 未被本应用占用"
        }
        root.findViewById<TextView>(R.id.rowRefresh).text =
            "重新扫描曲库\n已有 ${Library.cached().size} 首"
        val localRoot = com.dsd.player.data.ScanPrefs.localRoot(act)
        root.findViewById<TextView>(R.id.rowMusicDir).text = "本地音乐目录\n" +
            if (localRoot.isBlank()) "自动（Music / Download / 内部存储）· 点此指定"
            else localRoot + "（只扫描此目录）· 点此更改"
        root.findViewById<TextView>(R.id.about).text =
            "XZ1 DSD Player · USB 独占 / DoP / Native DSD\n" +
            "曲库 ${Library.cached().size} 首 · 所有文件访问：" +
            if (act.hasAllFilesAccess()) "已授予" else "未授予"
    }

    fun renderDac() {
        dacTitle.text = if (PlayerBus.connected) "DAC: ${PlayerBus.usbLabel}（独占）"
                        else act.getString(R.string.no_device)
        // 走查 #3：设置页也不再堆描述符调试文本，折叠到「长按查看详细信息」
        dacCaps.text = buildString {
            if (PlayerBus.connected) {
                append(PlayerBus.usbSummary.ifBlank { "独占输出" })
                if (PlayerBus.supportsDsdNative) append("  ·  DSD Native")
            } else {
                append("插上 USB DAC 后自动独占；点模式可强制 DoP / Native")
            }
            if (PlayerBus.errorMessage.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("⚠ ").append(PlayerBus.errorMessage)
            }
            if (isNotEmpty()) append("\n长按查看设备详细信息")
        }
        root.findViewById<View>(R.id.dacCard)?.setOnLongClickListener {
            AlertDialog.Builder(act)
                .setTitle(if (PlayerBus.usbLabel.isNotBlank()) PlayerBus.usbLabel else "USB DAC")
                .setMessage(PlayerBus.capsLine.ifBlank { "未检测到 USB DAC" })
                .setPositiveButton("关闭", null)
                .show()
            true
        }
        // 只在没有手动选择时跟随实际输出模式（否则用户点选会被立刻覆盖）
        renderMode()
    }
}

/** 播放页：封面 / 标题 / 进度 / 控制（含播放模式）/ 队列提示 / DAC 卡片 */
class PlayerPage(private val act: MainActivity, parent: FrameLayout) {
    private val root: View = LayoutInflater.from(act).inflate(R.layout.page_player, parent, false)
    private val cover: ImageView = root.findViewById(R.id.cover)
    private val title: TextView = root.findViewById(R.id.title)
    private val subtitle: TextView = root.findViewById(R.id.subtitle)
    private val seek: SeekLineView = root.findViewById(R.id.seek)
    private val pos: TextView = root.findViewById(R.id.pos)
    private val dur: TextView = root.findViewById(R.id.dur)
    private val btnPlay: ImageView = root.findViewById(R.id.btnPlay)
    private val queueInfo: TextView = root.findViewById(R.id.queueInfo)
    private val dacTitle: TextView = root.findViewById(R.id.dacTitle)
    private val dacCaps: TextView = root.findViewById(R.id.dacCaps)
    private val timeRow: View = root.findViewById(R.id.timeRow)
    private var userSeeking = false

    /** 左滑 → 播放列表 */
    private val gesture by lazy {
        android.view.GestureDetector(act, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                                 vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                val dy = e2.y - (e1?.y ?: return false)
                if (dx < -120 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    act.showPlaylist()
                    return true
                }
                return false
            }
        })
    }

    init {
        parent.addView(root)
        root.setOnTouchListener { _, ev -> gesture.onTouchEvent(ev); false }
        // 封面必须是正方形：边长 = min(可用宽度, 扣掉其它控件后的可用高度)
        (root as? android.view.ViewGroup)?.post { fitCover() }
        root.findViewById<ImageView>(R.id.btnPrev).setOnClickListener {
            PlayerBus.bindCommand(act, PlayerBus.ACTION_PREV)
        }
        root.findViewById<ImageView>(R.id.btnNext).setOnClickListener {
            PlayerBus.bindCommand(act, PlayerBus.ACTION_NEXT)
        }
        btnPlay.setOnClickListener { act.togglePause() }
        root.findViewById<ImageView>(R.id.btnStop).setOnClickListener {
            PlayerBus.bindCommand(act, PlayerBus.ACTION_STOP)
        }
        // 播放模式：顺序 → 列表循环 → 单曲循环
        root.findViewById<ImageView>(R.id.btnRepeat)?.setOnClickListener {
            PlayerBus.cycleRepeat(act)
            render()
            act.toast(PlayerBus.repeatMode.label)
        }
        seek.onSeek = { start, frac, done ->
            if (start) userSeeking = true
            val ms = (frac * PlayerBus.durationMs).toLong()
            pos.text = Track.fmtDuration(ms)
            if (done) {
                userSeeking = false
                PlayerBus.bindCommand(act, PlayerBus.ACTION_SEEK, posMs = ms)
            }
        }
    }

    fun setVisible(v: Boolean) {
        root.visibility = if (v) View.VISIBLE else View.GONE
        if (v) root.post { fitCover() }
    }

    /** 把封面调成正方形（横屏按 coverBox 的宽高取小值） */
    private fun fitCover() {
        val d0 = act.resources.displayMetrics.density
        val box = root.findViewById<View>(R.id.coverBox)
        if (box != null) {
            val availW = box.width - box.paddingLeft - box.paddingRight
            val availH = box.height - box.paddingTop - box.paddingBottom
            if (availW <= 0 || availH <= 0) return
            val side = minOf(availW, availH).coerceAtLeast((100 * d0).toInt())
            if (cover.width == side && cover.height == side) return
            cover.layoutParams = cover.layoutParams.apply { width = side; height = side }
            cover.requestLayout()
            return
        }
        val parent = cover.parent as? android.view.ViewGroup ?: return
        val rootH = root.height
        if (rootH <= 0) return
        var others = 0
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c === cover || c.visibility == View.GONE) continue
            others += c.height
        }
        val availW = parent.width - parent.paddingLeft - parent.paddingRight
        val availH = rootH - others - parent.paddingTop - parent.paddingBottom - (8 * d0).toInt()
        val side = minOf(availW, availH).coerceAtLeast((120 * d0).toInt())
        if (cover.width == side && cover.height == side) return
        cover.layoutParams = cover.layoutParams.apply { width = side; height = side }
        cover.requestLayout()
    }

    private fun showDacDetails() {
        AlertDialog.Builder(act)
            .setTitle(if (PlayerBus.usbLabel.isNotBlank()) PlayerBus.usbLabel else "USB DAC")
            .setMessage(PlayerBus.capsLine.ifBlank { "未检测到 USB DAC" })
            .setPositiveButton("关闭", null)
            .show()
    }

    fun render() {
        title.text = PlayerBus.trackName.ifBlank { act.getString(R.string.nothing_playing) }
        subtitle.text = buildString {
            if (PlayerBus.trackFmt.isNotBlank()) append(PlayerBus.trackFmt)
            if (PlayerBus.outputMode.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(PlayerBus.outputMode)
            }
            // 位深降级诚实提示：源 24bit 但平台只给 16bit
            val note = PlayerBus.bitDepthNote()
            if (note.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(note)
            }
        }
        pos.text = Track.fmtDuration(PlayerBus.positionMs)
        dur.text = Track.fmtDuration(PlayerBus.durationMs)
        btnPlay.setImageResource(if (PlayerBus.playing) R.drawable.ic_pause else R.drawable.ic_play)
        root.findViewById<ImageView>(R.id.btnRepeat)?.let { iv ->
            iv.setImageResource(when (PlayerBus.repeatMode) {
                com.dsd.player.data.RepeatMode.ORDER -> R.drawable.ic_repeat_order
                com.dsd.player.data.RepeatMode.LIST -> R.drawable.ic_repeat_all
                com.dsd.player.data.RepeatMode.ONE -> R.drawable.ic_repeat_one
            })
            iv.setColorFilter(
                if (PlayerBus.repeatMode == com.dsd.player.data.RepeatMode.ORDER) 0x88FFFFFF.toInt()
                else 0xFF5B8DEF.toInt())
        }
        val hasTrack = PlayerBus.durationMs > 0
        seek.visibility = if (hasTrack) View.VISIBLE else View.GONE
        timeRow.visibility = if (hasTrack) View.VISIBLE else View.GONE
        seek.isPlaying = PlayerBus.playing
        if (hasTrack && !userSeeking) {
            seek.fraction = (PlayerBus.positionMs.toFloat() / PlayerBus.durationMs).coerceIn(0f, 1f)
        }
        val emptyState = PlayerBus.durationMs <= 0 && PlayerBus.trackName.isBlank()
        queueInfo.text = when {
            emptyState -> "从音乐库选一首歌开始播放 · 点此去音乐库"
            PlayerBus.queueSize > 0 && PlayerBus.queueIndex >= 0 ->
                "队列 ${PlayerBus.queueIndex + 1}/${PlayerBus.queueSize} · 点此或左滑查看播放列表"
            else -> "左滑查看播放列表"
        }
        queueInfo.setOnClickListener {
            if (emptyState) act.switchTab(Tab.LIBRARY) else act.showPlaylist()
        }
        act.loadArtwork(cover)
        dacTitle.text = if (PlayerBus.connected) PlayerBus.usbSummary.ifBlank { PlayerBus.usbLabel }
                        else act.getString(R.string.no_device)
        dacCaps.text = buildString {
            if (PlayerBus.outputMode.isNotBlank()) append("当前输出 ").append(PlayerBus.outputMode)
            if (isEmpty()) append("长按查看设备详细信息")
        }
        root.findViewById<View>(R.id.dacCard)?.setOnLongClickListener { showDacDetails(); true }
    }
}

/** 播放列表（播放页左滑进入） */
class PlaylistPage(private val act: MainActivity, parent: FrameLayout) {
    private val root: View = LayoutInflater.from(act).inflate(R.layout.page_playlist, parent, false)
    private val list: RecyclerView = root.findViewById(R.id.queueList)
    private val empty: TextView = root.findViewById(R.id.queueEmpty)
    private val gesture by lazy {
        android.view.GestureDetector(act, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                                 vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                val dy = e2.y - (e1?.y ?: return false)
                if (dx > 120 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    act.hidePlaylist()
                    return true
                }
                return false
            }
        })
    }

    init {
        parent.addView(root)
        root.setOnTouchListener { _, ev -> gesture.onTouchEvent(ev); false }
        root.visibility = View.GONE
    }

    fun setVisible(v: Boolean) { root.visibility = if (v) View.VISIBLE else View.GONE }

    fun render() {
        val svc = com.dsd.player.playback.PlaybackService.current()
        val (titles, current) = svc?.queueSnapshot() ?: (emptyList<String>() to -1)
        empty.visibility = if (titles.isEmpty()) View.VISIBLE else View.GONE
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class VH(val v: View) : RecyclerView.ViewHolder(v)
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
                LayoutInflater.from(parent.context).inflate(R.layout.item_queue_row, parent, false))
            override fun getItemCount() = titles.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val v = holder.itemView
                v.findViewById<TextView>(R.id.index).text = (position + 1).toString()
                v.findViewById<TextView>(R.id.title).text = titles[position]
                val isCurrent = position == current
                v.findViewById<TextView>(R.id.title)
                    .setTextColor(if (isCurrent) 0xFF5B8DEF.toInt() else 0xFFFFFFFF.toInt())
                v.findViewById<android.widget.ImageView>(R.id.playingIcon)
                    .visibility = if (isCurrent) View.VISIBLE else View.GONE
                v.setOnClickListener {
                    val svc = com.dsd.player.playback.PlaybackService.current()
                    if (svc != null) svc.jumpTo(position) else PlayerBus.jumpQueue(act, position)
                }
            }
        }
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
    }
}
