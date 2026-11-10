// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import coil.request.ImageRequest
import coil.target.Target
import com.dsd.player.BuildConfig
import com.dsd.player.R
import java.io.File
import java.io.FileOutputStream
import java.util.WeakHashMap

/**
 * 封面加载：Coil 只负责「取图 + 解码」，**缩放由我们自己按面积平均（BOX）做**。
 *
 * 为什么不再依赖 Coil 的 transformations：真机第一百三十四轮实测 BOX 改动"未生效"
 * （网格 1879 / 播放页 803，与上一版逐位相同）。Coil 内部顺序是 decode → transform → scale，
 * 且 `Scale.FILL` 在"请求尺寸 > 源图"时会先把源图 bilinear **放大**再交给变换 ——
 * 与其继续猜它的内部状态，不如把缩放拿回自己手里：
 *   1. 解码尺寸 = 屏宽（上限 1024），`precision(INEXACT)` 保证**只降采样、不放大**；
 *   2. `Target.onSuccess` 拿到 BitmapDrawable 的原始位图；
 *   3. 后台线程 `BoxScale.boxScale(bitmap, 显示边长)`（真面积平均）；
 *   4. 主线程 setImageBitmap，并按 (uri, 边长) 缓存。
 * 好处：显示尺寸与解码尺寸彻底解耦、网格/播放页各用各的缓存、行为与设备无关。
 */
object Artwork {
    /** (uri|边长) → 已缩好的位图；键里带边长，网格与播放页不会互相污染 */
    private val cache = LruCache<String, Bitmap>(16)

    /** 视图 → 本次请求的 key（复用视图时丢弃旧结果，避免"串图"） */
    private val pending = WeakHashMap<ImageView, String>()

    fun albumArtUri(albumId: Long): Uri? =
        if (albumId > 0L) Uri.parse("content://media/external/audio/albumart/$albumId") else null

    /** 统一占位：渐变底（ImageView 自身背景）+ 40% 白音符 */
    fun placeholder(iv: ImageView) {
        val d = iv.resources.displayMetrics.density
        val pad = (iv.height.takeIf { it > 0 }?.let { (it * 0.33f).toInt() } ?: (24 * d).toInt())
        iv.setImageResource(R.drawable.ic_music_note)
        iv.setColorFilter(0x44FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN)
        iv.setPadding(pad, pad, pad, pad)
    }

    /** 视图还没布局时的兜底边长：宁大勿小 */
    private fun fallbackPx(iv: ImageView): Int {
        if (iv.width > 0) return iv.width
        iv.layoutParams?.height?.takeIf { it > 0 }?.let { return it }
        return (iv.resources.displayMetrics.widthPixels / 2).coerceAtLeast(1)
    }

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
        val side = (if (viewPx > 0) viewPx else fallbackPx(iv)).coerceAtLeast(1)
        val key = "$uri|$side"
        cache.get(key)?.let {
            iv.clearColorFilter()
            iv.setPadding(0, 0, 0, 0)
            iv.setImageBitmap(it)
            return
        }
        synchronized(pending) { pending[iv] = key }

        val dm = iv.resources.displayMetrics
        val decodeSide = minOf(maxOf(dm.widthPixels, dm.heightPixels), 1024)
        val req = ImageRequest.Builder(iv.context)
            .data(uri)
            .size(decodeSide, decodeSide)
            // ★ INEXACT：不要为了"凑到请求尺寸"把源图放大（放大 = 白丢细节）
            .precision(coil.size.Precision.INEXACT)
            .allowHardware(false)                    // 面积平均要读像素
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .target(object : Target {
                override fun onStart(placeholder: Drawable?) {}

                override fun onSuccess(result: Drawable) {
                    val src = (result as? BitmapDrawable)?.bitmap ?: return
                    Thread {
                        val scaled = BoxScale.boxScale(src, side)
                        cache.put(key, scaled)
                        if (BuildConfig.DEBUG) dumpDebug(iv, scaled, src, side, tag)
                        iv.post {
                            val still = synchronized(pending) { pending[iv] } == key
                            if (!still) return@post          // 视图已被复用
                            iv.clearColorFilter()
                            iv.setPadding(0, 0, 0, 0)
                            iv.setImageBitmap(scaled)
                        }
                    }.start()
                }

                override fun onError(error: Drawable?) {
                    val still = synchronized(pending) { pending[iv] } == key
                    if (still) placeholder(iv)
                }
            })
            .build()
        coil.Coil.imageLoader(iv.context).enqueue(req)
    }

    /**
     * 调试图落盘（debug 包）：`cacheDir/cover_debug_<边长>.png`。
     * 真机复测时可以直接把这张样张取出来和源图比对，不用再猜"BOX 有没有生效"。
     */
    private fun dumpDebug(iv: ImageView, out: Bitmap, src: Bitmap, side: Int, tag: String) {
        try {
            // 文件名带上专辑标识：否则不同专辑互相覆盖，真机复测时分不清样张是哪一张
            val safe = tag.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.takeLast(24)
            val name = if (safe.isEmpty()) "cover_debug_$side.png" else "cover_debug_${safe}_$side.png"
            val f = File(iv.context.cacheDir, name)
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(
                "Artwork",
                "源 ${src.width}×${src.height} → 面积平均 ${out.width}×${out.height}" +
                        "（视图 ${iv.width}px，样张 ${f.absolutePath}）"
            )
        } catch (t: Throwable) {
            Log.w("Artwork", "样张落盘失败：${t.message}")
        }
    }
}
