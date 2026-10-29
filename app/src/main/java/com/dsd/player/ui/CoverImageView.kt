// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.appcompat.widget.AppCompatImageView
import android.util.AttributeSet
import com.dsd.player.R

/**
 * 专辑封面：**圆角 + 极轻压暗（淡化）+ 1 dp 描边**，与卡片/胶囊/面板的圆角语言统一。
 *
 * 为什么不用 `clipToOutline`：那只能裁到背景 drawable 的圆角，仍画不出描边，
 * 而且每一处都要重复设置 outlineProvider。这里在 `onDraw` 里裁一次、压一层、描一圈，
 * 所有封面（网格 / 大封面 / 播放页）表现完全一致。
 */
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** 圆角半径（dp）：网格 12、播放页 16 */
    var radiusDp: Float = 12f
        set(v) { field = v; invalidate() }

    /** 淡化：在封面上叠一层极轻的黑色，避免亮封面在深色 UI 里"跳"出来 */
    var scrimColor: Int = 0x14000000
        set(v) { field = v; invalidate() }

    /** 描边：让封面边界与卡片描边一致（0 表示不画） */
    var borderColor: Int = 0x1FFFFFFF
        set(v) { field = v; invalidate() }

    private val path = Path()
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        scaleType = ScaleType.CENTER_CROP
        val ta = context.obtainStyledAttributes(attrs, R.styleable.CoverImageView)
        radiusDp = ta.getFloat(R.styleable.CoverImageView_radiusDp, radiusDp)
        scrimColor = ta.getColor(R.styleable.CoverImageView_coverScrim, scrimColor)
        borderColor = ta.getColor(R.styleable.CoverImageView_coverBorder, borderColor)
        ta.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) { super.onDraw(canvas); return }
        val r = radiusDp * resources.displayMetrics.density
        path.reset()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        path.addRoundRect(rect, r, r, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)
        super.onDraw(canvas)
        if (Color.alpha(scrimColor) > 0) {
            paint.style = Paint.Style.FILL
            paint.color = scrimColor
            canvas.drawRect(rect, paint)
        }
        canvas.restore()

        if (Color.alpha(borderColor) > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density
            paint.color = borderColor
            canvas.drawPath(path, paint)
        }
    }
}
