// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.graphics.PorterDuff
import android.net.Uri
import android.widget.ImageView
import coil.load
import com.dsd.player.BuildConfig
import com.dsd.player.R

/**
 * 封面加载：MediaStore albumArt / 远程封面；没有封面时显示统一占位（音符 + 渐变底）。
 *
 * ★ 解码尺寸必须显式给，而且**不能小于显示尺寸**。
 *   真机第一百三十二轮实测：网格封面显示 205 px、实际按 ~100 px 解码（锐度只有参考值的 7.7%）；
 *   播放页显示 476 px、按 ~360 px 解码（27.6%）。根因有两处：
 *     · 网格：布局完成前 `cellSizePx = 0`，退化成 `100dp` 这个常量；
 *     · 播放页：读的是 XML 里的 `layout_height`（260dp），而 fitCover() 之后视图已被改成
 *       接近屏宽的正方形 —— 解码尺寸停在旧值，于是"小图硬放大"。
 *   现在：调用方传真实显示边长（布局完成后按 `iv.width` 重取），这里再给 1.5× 余量
 *   （Coil 不会上采样，源图 600 px 时请求 700 也只解到 600，所以多要一点不花内存）。
 */
object Artwork {
    fun albumArtUri(albumId: Long): Uri? =
        if (albumId > 0L) Uri.parse("content://media/external/audio/albumart/$albumId") else null

    /** 统一占位：渐变底（ImageView 自身背景）+ 40% 白音符，居中留白 */
    fun placeholder(iv: ImageView) {
        val d = iv.resources.displayMetrics.density
        val pad = (iv.height.takeIf { it > 0 }?.let { (it * 0.33f).toInt() }
            ?: (24 * d).toInt())
        iv.setImageResource(R.drawable.ic_music_note)
        iv.setColorFilter(0x44FFFFFF, PorterDuff.Mode.SRC_IN)
        iv.setPadding(pad, pad, pad, pad)
    }

    /** 解码边长 = 1.5 × 显示边长（Coil 不会上采样，安全） */
    fun targetPx(viewPx: Int): Int = (viewPx * 3 / 2).coerceAtLeast(1)

    /** 视图还没布局时的兜底：宁大勿小 */
    private fun fallbackPx(iv: ImageView): Int {
        if (iv.width > 0) return iv.width
        iv.layoutParams?.height?.takeIf { it > 0 }?.let { return it }
        return (iv.resources.displayMetrics.widthPixels / 2).coerceAtLeast(1)
    }

    /** viewPx = 视图真实显示边长（px）；<= 0 时用兜底值 */
    fun load(iv: ImageView, albumId: Long, viewPx: Int = 0) {
        placeholder(iv)
        val uri = albumArtUri(albumId) ?: return
        loadSized(iv, uri, viewPx, emptyMap(), "album:$albumId")
    }

    fun load(iv: ImageView, uri: Uri?, viewPx: Int = 0) {
        placeholder(iv)
        if (uri == null) return
        loadSized(iv, uri, viewPx, emptyMap(), "uri:$uri")
    }

    fun loadSized(
        iv: ImageView,
        uri: Uri,
        viewPx: Int,
        headers: Map<String, String> = emptyMap(),
        tag: String = uri.toString(),
    ) {
        val display = (if (viewPx > 0) viewPx else fallbackPx(iv)).coerceAtLeast(1)
        // 解码上界 = 屏宽（源图 600 时就解 600，源图超大时封顶 1024）：
        // 先把中间图拿够大，再由 BoxScale 按**精确显示尺寸**做面积平均（BOX）缩小。
        // 以前的 1.5× 只是"够用"，但缩小时走的仍是双线性 —— 真机实测 600→205 只有理想锐度的 51%。
        val dm = iv.resources.displayMetrics
        val decodeSide = minOf(maxOf(dm.widthPixels, dm.heightPixels), 1024)
        iv.load(uri) {
            size(decodeSide, decodeSide)
            // ★ INEXACT：不要为了"凑到请求尺寸"而把源图**放大**再交给我们。
            //   实测（600 px 源 → 300 px 显示）：放大到 1080 再面积平均，方差只有 1036；
            //   直接用 600 做面积平均是 1667（参考上限）。FILL+EXACT 会先 bilinear 放大，白丢细节。
            precision(coil.size.Precision.INEXACT)
            scale(coil.size.Scale.FILL)          // centerCrop 语义：铺满不变形
            allowHardware(false)                 // 面积平均要读像素，不能用硬件位图
            transformations(BoxScale(display))   // ★ 面积平均缩小（BOX），不是双线性
            crossfade(120)
            headers.forEach { (k, v) -> addHeader(k, v) }
            listener(
                onSuccess = { _, r ->
                    // 真封面到了要清掉占位的着色与内边距，否则位图会被染色/缩小
                    iv.clearColorFilter()
                    iv.setPadding(0, 0, 0, 0)
                    iv.drawable?.setFilterBitmap(true)   // 放大时也要双线性过滤，别用最近邻
                    if (BuildConfig.DEBUG) {
                        // 记"实际解码出的位图边长"和"视图显示边长"：这两个数一致才不糊
                        val decoded = r.drawable.intrinsicWidth
                        android.util.Log.i(
                            "Artwork",
                            "解码 ${decoded}px → 显示 ${iv.width}px（$tag）"
                        )
                    }
                },
                // 有封面但系统里其实没有（模拟器/未嵌封面）→ 回到占位音符，
                // 否则 Coil 失败会把 drawable 清空，只剩一块空渐变（看起来像"加载失败"）
                onError = { _, _ -> placeholder(iv) },
            )
        }
    }
}
