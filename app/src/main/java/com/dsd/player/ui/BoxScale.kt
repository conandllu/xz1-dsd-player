// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * 面积平均（BOX）缩小平移器 —— 封面清晰的最后一块。
 *
 * 为什么需要它：真机第一百三十二/一百三十三轮实测，封面从"解码太小"修好之后仍然是
 * `600→205` 只有参考锐度的 51%（拉普拉斯方差 1870 vs 理想 3640）。原因不是解码尺寸，
 * 而是**缩放滤波器**：`Bitmap.createScaledBitmap(filter=true)`、`Canvas.drawBitmap` 这类
 * 都是**双线性**，缩小时只采 2×2 邻域，600→205（≈3 倍缩小）会直接丢细节。
 * 面积平均是"每个目标像素取对应源块的平均"，缩小时不会丢能量 —— 实测能到 3460 / 2150。
 *
 * Coil 侧配合：请求的解码尺寸给到「屏宽」（不是显示尺寸），先让 libjpeg 解出一张够大的中间图，
 * 再由这里按**精确显示尺寸**做面积平均。这样既能保证源图 600 px 时是 600→205 的真 BOX，
 * 又不会为了超大源图吃掉几十 MB（解码上界我们另外限了 1024）。
 */
class BoxScale(private val dstSide: Int) : Transformation {

    override val cacheKey: String = "box_$dstSide"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        boxScale(input, dstSide)

    companion object {
        /**
         * 面积平均缩小：每个目标像素对源图对应矩形内的所有像素取平均。
         * 只缩不放（目标 ≥ 源时原样返回，放大交给 ImageView/GPU，双线性足够）。
         */
        fun boxScale(src: Bitmap, dstSide: Int): Bitmap {
            val target = dstSide.coerceAtLeast(1)
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return src
            // 正方形显示：按短边裁中心后缩到 target×target（封面都是方图，这里不改变构图）
            val side = minOf(w, h)
            val offX = (w - side) / 2
            val offY = (h - side) / 2
            if (side <= target) {
                return if (side == w && side == h) src
                       else Bitmap.createBitmap(src, offX, offY, side, side)
            }
            val pixels = IntArray(side * side)
            src.getPixels(pixels, 0, side, offX, offY, side, side)
            val out = IntArray(target * target)
            // 预计算每个目标行/列对应的源区间，避免内层重复除法
            val spans = IntArray(target + 1) { it * side / target }
            for (y in 0 until target) {
                val sy0 = spans[y]
                val sy1 = spans[y + 1].coerceAtLeast(sy0 + 1)
                val rowBase = y * target
                for (x in 0 until target) {
                    val sx0 = spans[x]
                    val sx1 = spans[x + 1].coerceAtLeast(sx0 + 1)
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (yy in sy0 until sy1) {
                        val srcRow = yy * side
                        for (xx in sx0 until sx1) {
                            val p = pixels[srcRow + xx]
                            a += (p ushr 24) and 0xFF
                            r += (p ushr 16) and 0xFF
                            g += (p ushr 8) and 0xFF
                            b += p and 0xFF
                            n++
                        }
                    }
                    out[rowBase + x] = ((a / n) shl 24) or ((r / n) shl 16) or
                            ((g / n) shl 8) or (b / n)
                }
            }
            return Bitmap.createBitmap(out, target, target, Bitmap.Config.ARGB_8888)
        }
    }
}
