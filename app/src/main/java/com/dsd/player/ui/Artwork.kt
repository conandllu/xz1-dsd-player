// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.graphics.PorterDuff
import android.net.Uri
import android.widget.ImageView
import coil.load
import com.dsd.player.R

/** 封面加载：MediaStore albumArt；没有封面时显示统一占位（音符 + 渐变底） */
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

    fun load(iv: ImageView, albumId: Long) {
        placeholder(iv)
        val uri = albumArtUri(albumId) ?: return
        iv.load(uri) {
            scale(coil.size.Scale.FILL)
            crossfade(120)
            // 真封面到了要清掉占位的着色与内边距，否则位图会被染色/缩小
            listener(
                onSuccess = { _, _ ->
                    iv.clearColorFilter()
                    iv.setPadding(0, 0, 0, 0)
                },
                // 有 albumId 但系统里其实没有这张封面（模拟器/未嵌封面）→ 回到占位音符，
                // 否则 Coil 失败会把 drawable 清空，只剩一块空渐变（看起来像"加载失败"）
                onError = { _, _ -> placeholder(iv) },
            )
        }
    }

    fun load(iv: ImageView, uri: Uri?) {
        placeholder(iv)
        if (uri == null) return
        iv.load(uri) {
            scale(coil.size.Scale.FILL)
            crossfade(120)
            listener(
                onSuccess = { _, _ ->
                    iv.clearColorFilter()
                    iv.setPadding(0, 0, 0, 0)
                },
                onError = { _, _ -> placeholder(iv) },
            )
        }
    }
}
