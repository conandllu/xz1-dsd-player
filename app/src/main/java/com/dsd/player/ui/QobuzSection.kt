// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dsd.player.R
import com.dsd.player.data.OnlineAlbum
import com.dsd.player.data.OnlineTrack
import com.dsd.player.data.PlayerBus
import com.dsd.player.data.QobuzClient
import com.dsd.player.data.QobuzPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音乐库里的「在线（Qobuz）」部分：只负责状态与数据，不自己管视图。
 *
 * 与旧版 OnlinePage 的区别：以前它是整页替换（另开一层 page，顶栏标题也换成"在线音源"），
 * 现在它只是音乐库的一个**来源**，与本地共用同一套搜索框、chips、网格卡片和曲目行，
 * 所以两个来源看起来是一个库，只是数据从哪儿来的不同。
 *
 * 视图由 [LibraryPage] 渲染，这里的 listener 用来通知它"数据变了，重画"。
 */
class QobuzSection(private val act: MainActivity) {

    companion object {
        /** 网页登录 Activity 的请求码（MainActivity.onActivityResult 用） */
        const val REQ_WEB_LOGIN = 1001
    }

    /** 在线侧入口：收藏（默认）/ 专辑 / 曲目 —— 收藏是本机收藏，见 QobuzFavs */
    enum class Mode { FAV, ALBUMS, TRACKS }

    /** 在线内容的四种形态：列表 / 专辑详情 / 一行提示 / 空 */
    sealed class Content {
        object Empty : Content()
        data class Note(val text: String) : Content()
        data class Albums(val albums: List<OnlineAlbum>, val title: String) : Content()
        data class Tracks(val tracks: List<OnlineTrack>, val title: String) : Content()
        data class AlbumDetail(val album: OnlineAlbum, val tracks: List<OnlineTrack>) : Content()
    }

    var mode: Mode = Mode.FAV
        private set

    /** 搜索框内容（旋转后要还原，所以存在这里而不是视图里） */
    var query: String = ""

    var content: Content = Content.Empty
        private set

    var busy = false
        private set

    /** 数据变化回调：由 LibraryPage 注册（页面重建时要重新注册） */
    var listener: (() -> Unit)? = null

    private val client by lazy { QobuzClient(act) }

    /**
     * 在线请求统一加超时：DNS 半死时 okhttp 的 execute() 可能长时间不返回，
     * 没有这层保护界面会永远停在"搜索专辑中…"（实测模拟器 DNS 挂掉时就是这样）。
     */
    private suspend fun <T> net(block: suspend () -> T): Result<T> = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(25_000) { block() }
            ?: throw IllegalStateException("网络超时（25s 无响应，检查网络/DNS 后重试）")
    }
    private var pendingDialog: AlertDialog? = null
    private var lastAlbum: OnlineAlbum? = null
    /** 进入专辑详情前的列表（返回时要回到它，而不是清空） */
    private var lastList: Content = Content.Empty
    /** 防重：真机上出现过一次点击触发两次切歌（RecyclerView 事件重放） */
    private var lastPlayAt = 0L
    /** 当前进行中的搜索（新搜索会取消它，而不是被 busy 挡住） */
    private var searchJob: kotlinx.coroutines.Job? = null

    fun loggedIn(): Boolean = QobuzPrefs.loggedIn(act)

    fun chips(): List<Pair<Mode, String>> = listOf(
        Mode.FAV to "收藏",
        Mode.ALBUMS to "专辑",
        Mode.TRACKS to "曲目",
    )

    /** 收藏的 track id（星标渲染用），收藏变化后刷新 */
    private var favIds: Set<Long> = emptySet()

    fun isFav(id: Long): Boolean = favIds.contains(id)

    fun reloadFavIds() {
        favIds = com.dsd.player.data.QobuzFavs.ids(act)
    }

    /** 点星标：切换收藏（本机收藏），返回 true 表示现在已收藏 */
    fun toggleFavorite(t: OnlineTrack): Boolean {
        val added = com.dsd.player.data.QobuzFavs.toggle(act, t)
        reloadFavIds()
        android.util.Log.i("QobuzFav", "收藏 ${if (added) "＋" else "－"} ${t.title}（共 ${favIds.size} 首）")
        // 正在看收藏列表时，取消收藏应立刻从列表里消失
        if (mode == Mode.FAV) showFavorites()
        else notifyUi()
        return added
    }

    /**
     * 「从账号导入收藏」：Qobuz 的**读**接口可用（写不可用），所以可以把账号里已有的
     * 收藏拉过来合并进本机收藏。返回导入条数；失败抛异常。
     */
    suspend fun importAccountFavorites(): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val remote = client.favoriteTracks(limit = 200)
        if (remote.isEmpty()) return@withContext 0
        val local = com.dsd.player.data.QobuzFavs.all(act).toMutableList()
        val known = local.map { it.id }.toMutableSet()
        var added = 0
        remote.forEach { t -> if (known.add(t.id)) { local.add(t); added++ } }
        com.dsd.player.data.QobuzFavs.save(act, local)
        reloadFavIds()
        added
    }

    /** 收藏列表（本机） */
    fun showFavorites() {
        reloadFavIds()
        val list = com.dsd.player.data.QobuzFavs.all(act)
        content = if (list.isEmpty()) Content.Note(FAV_EMPTY)
        else Content.Tracks(list, "收藏的歌曲 · ${list.size} 首（仅本机）")
        notifyUi()
    }

    private object Const {
        const val FAV_EMPTY = "还没有收藏的歌曲。\n\n在「曲目」搜索结果或专辑详情里点行尾的 ☆ 就能收藏，" +
            "收藏后会出现在这里，点任意一行即按收藏列表顺序播放。\n\n" +
            "⚠ 收藏保存在本机（Qobuz 当前 app_id 没有写收藏的权限），换机会丢。"
    }

    private val FAV_EMPTY = Const.FAV_EMPTY

    fun stateLine(): String = if (!loggedIn()) {
        "Qobuz 未登录 · 登录后可在线播放无损"
    } else {
        "已登录 " + QobuzPrefs.email(act) +
            QobuzPrefs.tier(act).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }

    /** 顶栏副标题：只说"能到什么音质"，登录态交给下方状态条，避免同一句话出现两遍 */
    fun qualityLine(): String = "优先 " + when (QobuzPrefs.formatId(act)) {
        27 -> "24bit/192k"
        7 -> "24bit/96k"
        6 -> "16bit/44.1k"
        else -> "MP3 320k"
    } + " · 走 USB 独占"

    // ── 交互 ──────────────────────────────────────────────────────────
    fun pickMode(m: Mode, queryText: String) {
        query = queryText
        if (mode == m) return
        mode = m
        when (m) {
            Mode.FAV -> showFavorites()
            else -> if (queryText.isBlank()) { content = Content.Empty; notifyUi() } else search(queryText)
        }
    }

    /**
     * 首次进入在线来源时默认显示收藏列表。
     *
     * 必须"改内容 → 再通知"，且不能再对空内容重复 notifyUi()：
     * 否则 render → ensureInitial → notifyUi → render … 会无限递归（真机表现为
     * StackOverflowError 闪退）。initialising 是第二道保险。
     */
    private var initialising = false

    fun ensureInitial() {
        if (initialising || content !is Content.Empty) return
        if (mode != Mode.FAV) return
        initialising = true
        try {
            showFavorites()
        } finally {
            initialising = false
        }
    }

    /** 清空搜索框时回到初始提示（并取消进行中的搜索） */
    fun clearSearch() {
        query = ""
        searchJob?.cancel()
        busy = false
        content = Content.Empty
        notifyUi()
    }

    fun search(queryText: String) {
        val q = queryText.trim()
        query = q
        // 这条日志是"键盘在场却搜不了"的判据：能看到 search(...) 说明触发是好的，
        // 看不到就是触发路径（输入监听/按钮/回车）没进来。
        android.util.Log.i("QobuzSearch", "search(q='$q' mode=$mode busy=$busy loggedIn=${loggedIn()})")
        if (q.isBlank()) {
            android.util.Log.i("QobuzSearch", "→ 关键词为空，清空内容")
            content = Content.Empty
            notifyUi()
            return
        }
        if (!loggedIn()) {
            android.util.Log.i("QobuzSearch", "→ 未登录，提示登录")
            content = Content.Note("先点上方「登录」用你自己的 Qobuz 账号登录（网页登录最省事），登录后即可搜索与播放。")
            notifyUi()
            return
        }
        // 收藏模式下直接搜索 = 想找歌 → 自动切到「专辑」视图（chips 会跟着变）
        if (mode == Mode.FAV) mode = Mode.ALBUMS
        // 之前用 busy 直接 return，一旦上一次请求卡住（超时前）用户就"点什么都没反应"；
        // 现在直接取消上一次，保证每次触发都真的发出去
        searchJob?.cancel()
        busy = true
        val albums = mode == Mode.ALBUMS
        content = Content.Note(if (albums) "搜索专辑中…" else "搜索曲目中…")
        notifyUi()
        searchJob = act.lifecycleScope.launch {
          try {
            val r = net { withContext(Dispatchers.IO) { if (albums) client.searchAlbums(q) else client.searchTracks(q) } }
            busy = false
            r.onSuccess { items ->
                content = when {
                    items.isEmpty() -> Content.Note("Qobuz 没有匹配“$q”的${if (albums) "专辑" else "曲目"}")
                    albums -> @Suppress("UNCHECKED_CAST")
                    Content.Albums(items as List<OnlineAlbum>, "搜索“$q” · ${items.size} 张专辑")
                    else -> @Suppress("UNCHECKED_CAST")
                    Content.Tracks(items as List<OnlineTrack>, "搜索“$q” · ${items.size} 首曲目")
                }
            }.onFailure { content = Content.Note("搜索失败：${it.message}") }
            android.util.Log.i("QobuzSearch", "→ 完成：${(content as? Content.Albums)?.albums?.size ?: (content as? Content.Tracks)?.tracks?.size ?: 0} 条")
            notifyUi()
          } finally {
            busy = false
          }
        }
    }

    fun openAlbum(albumId: String, fallback: OnlineAlbum? = null) {
        if (busy) return
        val cur = content
        if (cur is Content.Albums || cur is Content.Tracks) lastList = cur
        busy = true
        content = Content.Note("载入专辑…")
        notifyUi()
        act.lifecycleScope.launch {
            val r = net {
                withContext(Dispatchers.IO) {
                    val full = client.albumInfo(albumId) ?: fallback ?: error("拿不到专辑信息")
                    full to client.albumTracks(albumId)
                }
            }
            busy = false
            r.onSuccess { (full, tracks) ->
                android.util.Log.i("QobuzSection",
                    "专辑「${full.title}」tracks_count=${full.tracks} 实际拿到=${tracks.size}")
                lastAlbum = full
                content = if (tracks.isEmpty()) Content.Note("这张专辑没有曲目")
                else Content.AlbumDetail(full, tracks)
            }.onFailure { content = Content.Note("载入失败：${it.message}") }
            notifyUi()
        }
    }

    /** 返回上一级（专辑详情 → 列表）。返回 true 表示消费了返回键 */
    fun back(): Boolean {
        if (content is Content.AlbumDetail) {
            content = lastList          // 回到原来的搜索结果，而不是清空
            notifyUi()
            return true
        }
        return false
    }

    /** 深链：`-a com.dsd.player.SEARCH --es query 周杰伦` */
    fun searchNow(q: String) {
        mode = Mode.ALBUMS
        query = q
        search(q)
    }

    /** 深链：`--es album <album_id>` */
    fun openAlbumById(id: String) {
        openAlbum(id)
    }

    /**
     * 播放列表里的第 index 首：**整列表入队**（这样专辑能按顺序连播、下一首/上一首才有意义）。
     * 队列里放 `qobuz:<trackId>`，真正取流在开流线程里做（直链有时效，不能提前取）。
     */
    fun playTracks(list: List<OnlineTrack>, index: Int) {
        android.util.Log.i("QobuzSection", "playTracks(list=${list.size}, index=$index)")
        val t = list.getOrNull(index) ?: return
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastPlayAt < 600) return
        lastPlayAt = now
        act.toast("正在获取音源…")
        PlayerBus.playOnlineQueue(
            act,
            list.map { it.id },
            list.map { track ->
                track.title + (if (track.artist.isNotBlank()) " · ${track.artist}" else "")
            },
            list.map { it.imageUrl },
            index,
        )
    }

    // ── 登录 ──────────────────────────────────────────────────────────
    fun logout() {
        QobuzPrefs.logout(act)
        content = Content.Empty
        notifyUi()
        act.toast("已退出 Qobuz")
    }

    fun showLoginDialog() {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        // 主路径：网页登录（Qobuz 自己的登录页 → 自动截获 token）
        box.addView(TextView(act).apply {
            text = "① 打开 Qobuz 网页登录（推荐）"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_chip_active)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                act.startActivityForResult(
                    android.content.Intent(act, QobuzLoginActivity::class.java), REQ_WEB_LOGIN)
                runCatching { pendingDialog?.dismiss() }
                pendingDialog = null
            }
        })
        box.addView(TextView(act).apply {
            text = "邮箱密码在网页里输入，登录成功后自动返回；\n" +
                "Qobuz 已下线第三方直连的密码登录接口，所以只能这样。"
            textSize = 11f
            setTextColor(0xFF99AAB5.toInt())
            setPadding(0, dp(6), 0, dp(12))
        })
        box.addView(TextView(act).apply {
            text = "② 或直接粘贴 user_auth_token"
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
        })
        val tokenEt = EditText(act).apply {
            hint = "user_auth_token"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 12f
        }
        box.addView(tokenEt)
        box.addView(TextView(act).apply {
            text = "token 拿法：浏览器登录 play.qobuz.com → F12 Console →\n" +
                "JSON.parse(localStorage.getItem('localuser')).token"
            textSize = 11f
            setTextColor(0xFF99AAB5.toInt())
            setPadding(0, dp(4), 0, dp(10))
        })
        val status = TextView(act).apply {
            setTextColor(0xFFFFD479.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(status)

        val dlg = AlertDialog.Builder(act).setTitle("登录 Qobuz").setView(box)
            .setPositiveButton("用 token 登录", null)
            .setNegativeButton("取消", null)
            .create()
        pendingDialog = dlg
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tok = tokenEt.text.toString().trim()
                if (tok.isBlank()) { status.text = "先粘贴 token，或点上面的网页登录"; return@setOnClickListener }
                status.text = "正在校验 token…"
                act.lifecycleScope.launch {
                    val r = net { withContext(Dispatchers.IO) { client.loginWithToken(tok) } }
                    r.onSuccess {
                        dlg.dismiss()
                        notifyUi()
                        if (query.isNotBlank()) search(query) else notifyUi()
                        act.toast("Qobuz token 登录成功")
                    }.onFailure { status.text = "token 校验失败：${it.message}" }
                }
            }
        }
        dlg.show()
    }

    fun onWebLoginResult(ok: Boolean) {
        notifyUi()
        if (ok) {
            act.toast("Qobuz 登录成功")
            if (query.isNotBlank()) search(query)
        } else {
            act.toast("未获取到登录令牌")
        }
    }

    private fun notifyUi() { listener?.invoke() }
}

/** 在线列表里的一行：专辑卡片 / 曲目行 / 专辑详情头 / 文字提示 */
sealed class OnlineItem {
    data class Album(val album: OnlineAlbum) : OnlineItem()
    data class Track(val track: OnlineTrack, val index: Int) : OnlineItem()
    data class Header(val cover: String, val title: String, val sub: String, val meta: String) : OnlineItem()
    data class Note(val text: String) : OnlineItem()
}

/**
 * 在线内容的统一适配器。
 *
 * 专辑 → 复用本地专辑卡片 item_album_grid.xml（同圆角、同标题/副标题排版、同 4 列网格），
 * 这是"两个来源看起来是一个库"的关键；曲目 → item_online_row.xml；头和提示行程序化创建。
 */
class OnlineAdapter(
    private val grid: Boolean,
    private val onAlbum: (OnlineAlbum) -> Unit,
    private val onTrack: (OnlineTrack) -> Unit,
    /** 点星标切换收藏 */
    private val onFav: (OnlineTrack) -> Unit = {},
    /** 该曲目当前是否已收藏 */
    private val favOf: (Long) -> Boolean = { false },
    /** 是否显示收藏星标（WebDAV 曲库不显示） */
    private val showFav: Boolean = true,
    /** 取封面时要带的请求头（WebDAV 需要 Basic 认证） */
    private val coverHeaders: (String) -> Map<String, String> = { emptyMap() },
    /** 长按曲目（WebDAV 曲库用来"下载到本地"） */
    private val onLongTrack: ((OnlineTrack) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<OnlineItem> = emptyList()

    /** 网格封面的边长（px），由页面在布局完成后写入 */
    var cellSizePx: Int = 0

    fun submit(list: List<OnlineItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int = when (val i = items[position]) {
        // 列表布局下专辑也用"行"来显示（封面 + 标题 + 副标题）
        is OnlineItem.Album -> if (grid) 0 else 1
        is OnlineItem.Track -> 1
        is OnlineItem.Header -> 2
        is OnlineItem.Note -> 3
    }

    class VH(val root: View) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> VH(inflater.inflate(R.layout.item_album_grid, parent, false))
            1 -> VH(inflater.inflate(R.layout.item_online_row, parent, false))
            2 -> VH(buildHeader(parent.context))
            else -> VH(buildNote(parent.context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        val d = v.resources.displayMetrics.density
        when (val it = items[position]) {
            is OnlineItem.Album -> {
                val a = it.album
                val cover = v.findViewById<ImageView>(R.id.cover)
                if (grid) {
                    val size = if (cellSizePx > 0) cellSizePx else (100 * d).toInt()
                    cover.layoutParams = cover.layoutParams.apply { width = size; height = size }
                }
                loadCover(cover, a.imageUrl, if (grid) (cellSizePx.takeIf { it > 0 } ?: (100 * d).toInt()) else (46 * d).toInt())
                v.findViewById<TextView>(R.id.title).text = a.title
                val sub = v.findViewById<TextView>(R.id.subtitle)
                sub.text = buildString {
                    if (a.artist.isNotBlank()) append(a.artist)
                    if (!grid && a.tracks > 0) { if (isNotEmpty()) append(" · "); append("${a.tracks} 首") }
                    if (!grid && a.bitDepth > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${a.bitDepth}bit/${"%.1f".format(a.sampleRate / 1000.0)}kHz")
                    }
                }
                sub.visibility = View.VISIBLE
                if (!grid) {
                    v.findViewById<TextView>(R.id.trailing).text = a.releaseDate
                    v.findViewById<ImageView>(R.id.favBtn).visibility = View.GONE
                }
                v.setOnClickListener { onAlbum(a) }
            }
            is OnlineItem.Track -> {
                val t = it.track
                v.findViewById<ImageView>(R.id.cover).let { loadCover(it, t.imageUrl, (46 * d).toInt()) }
                v.findViewById<TextView>(R.id.title).text = t.title
                v.findViewById<TextView>(R.id.subtitle).text = buildString {
                    if (t.trackNo > 0) { append(t.trackNo.toString().padStart(2, '0')); append(" · ") }
                    if (t.artist.isNotBlank()) append(t.artist)
                    if (t.bitDepth > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${t.bitDepth}bit/${"%.1f".format(t.sampleRate / 1000.0)}kHz")
                    }
                }
                v.findViewById<TextView>(R.id.trailing).text =
                    if (t.durationSec > 0) "%d:%02d".format(t.durationSec / 60, t.durationSec % 60) else ""
                // 星标：本机收藏（Qobuz 写接口对此 app_id 不可用，见 QobuzFavs 注释）
                val star = v.findViewById<ImageView>(R.id.favBtn)
                val fav = showFav && favOf(t.id)
                star.visibility = if (showFav) View.VISIBLE else View.GONE
                star.setImageResource(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                star.setColorFilter(if (fav) 0xFFFFC857.toInt() else 0x66FFFFFF.toInt())
                star.setOnClickListener { onFav(t) }
                v.setOnClickListener { onTrack(t) }
                v.setOnLongClickListener {
                    if (onLongTrack != null) { onLongTrack.invoke(t); true } else false
                }
            }
            is OnlineItem.Header -> {
                // 没有封面（曲目搜索结果）时把封面位收起来，标题直接占满宽度
                val hc = v.findViewById<ImageView>(R.id.headCover)
                if (it.cover.isBlank()) hc.visibility = View.GONE
                else { hc.visibility = View.VISIBLE; loadCover(hc, it.cover, (108 * d).toInt()) }
                v.findViewById<TextView>(R.id.headTitle).text = it.title
                v.findViewById<TextView>(R.id.headSub).text = it.sub
                v.findViewById<TextView>(R.id.headMeta).text = it.meta
            }
            is OnlineItem.Note -> {
                v.findViewById<TextView>(R.id.noteText).text = it.text
            }
        }
    }

    // ── 程序化视图 ────────────────────────────────────────────────────
    private fun buildHeader(ctx: android.content.Context): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(12))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val cover = CoverImageView(ctx).apply {
            id = R.id.headCover
            radiusDp = 12f
            setBackgroundResource(R.drawable.bg_cover_placeholder)
            layoutParams = LinearLayout.LayoutParams(dp(108), dp(108))
        }
        row.addView(cover)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(14), 0, 0, 0)
        }
        fun text(id: Int, size: Float, color: Int, topPad: Int = 0): TextView =
            TextView(ctx).apply {
                this.id = id
                textSize = size
                setTextColor(color)
                if (topPad > 0) setPadding(0, dp(topPad), 0, 0)
            }
        col.addView(text(R.id.headTitle, 17f, 0xFFFFFFFF.toInt()).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(text(R.id.headSub, 13f, 0xCCFFFFFF.toInt(), 4))
        col.addView(text(R.id.headMeta, 11f, 0x99FFFFFF.toInt(), 4).apply {
            maxLines = 3
        })
        row.addView(col)
        return row
    }

    private fun buildNote(ctx: android.content.Context): View {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            id = R.id.noteText
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            setLineSpacing(4f * d, 1f)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 封面加载。
     *
     * 必须显式给 size：RecyclerView 绑定行时 setWidth 还是 0，靠视图测量去推尺寸会
     * 出现"专辑网格能出图、曲目行永远空白"的怪象（同一份代码、同一个 CDN）。
     */
    /**
     * 封面加载。
     *
     * ⚠ 不能直接把 ImageView 交给 Coil：网格卡片会被复用，早先那次请求晚到时会把
     * 图贴到**别的格子**上（第九十四轮反馈的"串图"）。这里改成：
     *  1. 先给视图打上本次的 key（url+尺寸）；
     *  2. 用无视图的 Target 请求，成功回调里比对 key，不一致就丢弃。
     */
    private fun loadCover(iv: ImageView, url: String, sizePx: Int) {
        val key = "$url|$sizePx"
        iv.tag = key
        Artwork.placeholder(iv)
        if (url.isBlank()) return
        val req = coil.request.ImageRequest.Builder(iv.context)
            .data(url)
            .size(sizePx.coerceAtLeast(1))
            .scale(coil.size.Scale.FILL)
            .apply { coverHeaders(url).forEach { (k, v) -> addHeader(k, v) } }
            .target(object : coil.target.Target {
                override fun onStart(placeholder: android.graphics.drawable.Drawable?) {}
                override fun onError(error: android.graphics.drawable.Drawable?) {
                    if (iv.tag == key) Artwork.placeholder(iv)
                }
                override fun onSuccess(result: android.graphics.drawable.Drawable) {
                    if (iv.tag != key) return          // 视图已被复用，丢弃这次结果
                    iv.clearColorFilter()
                    iv.setPadding(0, 0, 0, 0)
                    iv.setImageDrawable(result)
                }
            })
            .build()
        coil.Coil.imageLoader(iv.context).enqueue(req)
    }

}
