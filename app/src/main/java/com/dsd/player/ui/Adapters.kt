// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dsd.player.R
import com.dsd.player.data.Track
import com.dsd.player.data.TrackGroup
import java.io.File

/** 布局形态（对应「排序与布局」面板） */
enum class LayoutMode { COMPACT, STANDARD, LARGE, GRID4, GRID3, GRID2 }

/** 曲库维度 */
enum class LibMode { ALBUM, ARTIST, SONG, GENRE, YEAR, FOLDER }

/** 分组网格/列表 */
class GroupAdapter(
    private val grid: Boolean,
    private val spanCount: Int,
    private val onOpen: (TrackGroup) -> Unit,
    private val onPlayAll: (TrackGroup) -> Unit,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private var data: List<TrackGroup> = emptyList()
    /** 由页面在布局完成后写入的封面边长（px） */
    var cellSizePx: Int = 0

    fun submit(list: List<TrackGroup>) { data = list; notifyDataSetChanged() }

    class VH(val root: View) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // 网格形态（2/3/4 列）用竖直卡片；列表形态用"左封面 + 右文字"的横向行
        val layoutId = if (grid) R.layout.item_album_grid else R.layout.item_album_list
        val v = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return VH(v)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = data[position]
        val d = holder.root.resources.displayMetrics.density
        val cover = holder.root.findViewById<ImageView>(R.id.cover)
        val title = holder.root.findViewById<TextView>(R.id.title)
        val sub = holder.root.findViewById<TextView>(R.id.subtitle)
        title.text = g.title
        sub.text = g.subtitle
        // 用 INVISIBLE 而不是 GONE：没有副标题的卡片也保持同样高度（走查 #25 卡片高度不一致）
        sub.visibility = if (g.subtitle.isBlank()) View.INVISIBLE else View.VISIBLE
        // 先定封面尺寸，再加载（占位音符的内边距按边长算），最后 Coil 真封面覆盖
        if (grid) {
            val size = if (cellSizePx > 0) cellSizePx else (100 * d).toInt()
            cover.layoutParams = cover.layoutParams.apply { height = size; width = size }
        } else {
            // 列表形态的尺寸由 item_album_list.xml 固定（52dp 方形），这里不再改
            val side = (52 * d).toInt()
            cover.layoutParams = cover.layoutParams.apply { height = side; width = side }
        }
        Artwork.load(cover, g.albumId)
        holder.root.setOnClickListener { onOpen(g) }
        holder.root.setOnLongClickListener { onPlayAll(g); true }
    }

}

/** 曲目列表 */
class TrackAdapter(
    private val layout: LayoutMode,
    /** 专辑内页里不再重复显示专辑名 */
    private val showAlbum: Boolean = true,
    private val onPlay: (Track, Int) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private var data: List<Track> = emptyList()
    private var currentPath: String? = null

    fun submit(list: List<Track>) { data = list; notifyDataSetChanged() }

    fun setCurrent(path: String?) { currentPath = path; notifyDataSetChanged() }

    class VH(val root: View) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track_row, parent, false)
        return VH(v)
    }

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = data[position]
        val cover = holder.root.findViewById<ImageView>(R.id.cover)
        val title = holder.root.findViewById<TextView>(R.id.title)
        val sub = holder.root.findViewById<TextView>(R.id.subtitle)
        val trailing = holder.root.findViewById<TextView>(R.id.trailing)
        title.text = t.title
        sub.text = if (layout == LayoutMode.COMPACT) t.subtitle() else buildString {
            append(t.ext.uppercase())
            if (t.artist.isNotBlank()) append(" · ").append(t.artist)
            if (showAlbum && t.displayAlbum().isNotBlank()) append(" · ").append(t.displayAlbum())
            // 时长已在行尾右对齐显示，副标题不再重复一遍
        }
        trailing.text = if (t.durationMs > 0) Track.fmtDuration(t.durationMs) else ""
        // 歌曲列表不再显示专辑封面（用户诉求）：整列只有标题 / 副标题 / 时长，
        // 行高更紧凑、也避免小尺寸封面糊成一块色斑
        cover.visibility = View.GONE
        val isCurrent = currentPath != null && t.path == currentPath
        title.alpha = if (isCurrent) 1f else 0.92f
        title.setTextColor(if (isCurrent) 0xFF5B8DEF.toInt() else 0xFFFFFFFF.toInt())
        holder.root.setOnClickListener { onPlay(t, position) }
    }
}

/** 文件夹行 */
class DirAdapter(
    private val onOpen: (File) -> Unit,
    private val onOpenTrack: (Track) -> Unit,
    /** 非空时把 "/webdav" 占位行显示成远程入口 */
    private val davLabel: String? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private data class DirRow(val dir: File, val audioCount: Int, val subdirs: Int)
    private sealed class Item {
        data class Dir(val row: DirRow) : Item()
        data class TrackRow(val track: Track) : Item()
    }

    private var items: List<Item> = emptyList()

    fun submit(dirs: List<Pair<File, Pair<Int, Int>>>, tracks: List<Track>) {
        val list = ArrayList<Item>()
        dirs.forEach { (d, c) -> list.add(Item.Dir(DirRow(d, c.first, c.second))) }
        tracks.forEach { list.add(Item.TrackRow(it)) }
        items = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (items[position] is Item.Dir) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0)
            TrackAdapter.VH(inflater.inflate(R.layout.item_dir_row, parent, false))
        else
            TrackAdapter.VH(inflater.inflate(R.layout.item_track_row, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val item = items[position]) {
            is Item.Dir -> {
                val isDav = item.row.dir.absolutePath == "/webdav"
                v.findViewById<ImageView>(R.id.icon).setImageResource(
                    if (isDav) R.drawable.ic_cloud else R.drawable.ic_folder)
                val dirName = when {
                    isDav -> "WebDAV 服务器"
                    item.row.dir.absolutePath == "/storage/emulated/0" -> "内部存储"
                    else -> item.row.dir.name.ifBlank { item.row.dir.absolutePath }
                }
                v.findViewById<TextView>(R.id.name).text = dirName
                // 绝对路径已由页面副标题显示，这里只给数量，避免同一路径出现两次
                v.findViewById<TextView>(R.id.sub).text = when {
                    isDav -> davLabel ?: ""
                    item.row.audioCount >= 0 -> buildString {
                        append(item.row.audioCount).append(" 首")
                        if (item.row.subdirs > 0) append(" · ").append(item.row.subdirs).append(" 子目录")
                    }
                    else -> item.row.dir.absolutePath
                }
                v.findViewById<TextView>(R.id.count).text = ""   // 数量已在副标题，避免重复
                v.setOnClickListener { onOpen(item.row.dir) }
            }
            is Item.TrackRow -> {
                val t = item.track
                v.findViewById<ImageView>(R.id.cover).visibility = View.GONE
                v.findViewById<TextView>(R.id.title).text = t.title
                v.findViewById<TextView>(R.id.subtitle).text = t.subtitle()
                v.findViewById<TextView>(R.id.trailing).text =
                    if (t.durationMs > 0) Track.fmtDuration(t.durationMs) else ""
                v.setOnClickListener { onOpenTrack(t) }
            }
        }
    }
}
