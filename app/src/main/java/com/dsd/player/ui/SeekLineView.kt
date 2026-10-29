// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 进度条（仿 RawS Music 的 Classic 风格）：
 *  - **没有圆形滑块**，而是 4dp 圆角轨道 + 已播放段强调色 + 播放头一段白色渐隐光晕
 *  - 按下/拖动时轨道加粗到 6dp、光晕变亮（对应 RawS 的 `4f + 2f*press` dp）
 *  - 点击任意位置直接跳转；拖动过程回调 fraction
 */
class SeekLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 0..1 播放进度 */
    var fraction: Float = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) { field = c; invalidate() }
        }

    var isPlaying: Boolean = false
        set(v) { if (v != field) { field = v; invalidate() } }

    var accentColor: Int = 0xFF5B8DEF.toInt()
        set(v) { if (v != field) { field = v; invalidate() } }

    /** (是否开始拖动, 当前 fraction, 是否结束) */
    var onSeek: ((Boolean, Float, Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val trackH = 5f * d
    private val pressedH = 7f * d
    private val headLen = 34f * d
    private val headLenPressed = 46f * d

    private var pressed = false
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3DFFFFFF }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private var headGradient: LinearGradient? = null
    private var gradLen = -1f

    init {
        isClickable = true
        // 触控区域给足（视觉只有 4dp，但可点高度 ≥ 40dp）
        minimumHeight = (40 * d).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = maxOf(MeasureSpec.getSize(heightMeasureSpec), (40 * d).toInt())
        setMeasuredDimension(w, h)
    }

    private fun trackTop(bottom: Float): Float = (height - (bottom)) / 2f

    override fun onDraw(canvas: Canvas) {
        val thick = if (pressed) pressedH else trackH
        val cy = height / 2f
        val pad = if (pressed) 0f else 2f * d
        rect.set(pad, cy - thick / 2f, width - pad, cy + thick / 2f)
        val r = thick / 2f

        canvas.drawRoundRect(rect, r, r, trackPaint)
        if (fraction <= 0f) return

        val headX = rect.left + (rect.width()) * fraction
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)

        fillPaint.color = accentColor
        canvas.drawRoundRect(rect.left, rect.top, headX, rect.bottom, r, r, fillPaint)

        // 播放头：向右渐隐到透明的白色光带（没有圆形 thumb）
        val len = if (pressed) headLenPressed else headLen
        if (headGradient == null || len != gradLen) {
            gradLen = len
            headGradient = LinearGradient(0f, 0f, len, 0f,
                0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
        }
        headPaint.shader = headGradient
        headPaint.alpha = ((if (pressed) 1f else if (isPlaying) 0.85f else 0.4f) * 255).toInt()
        canvas.drawRoundRect(
            (headX - len).coerceAtLeast(rect.left), rect.top, headX, rect.bottom, r, r, headPaint)
        canvas.restoreToCount(save)

        // 抓取点：10dp 实心圆（可拖目标），比纯光带更明确
        dotPaint.alpha = if (pressed) 255 else 220
        canvas.drawCircle(headX, cy, (5f * d), dotPaint)
    }

    private fun fractionAt(x: Float): Float {
        val pad = if (pressed) 0f else 2f * d
        val usable = (width - 2 * pad).coerceAtLeast(1f)
        return ((x - pad) / usable).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                fraction = fractionAt(event.x)
                onSeek?.invoke(true, fraction, false)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                fraction = fractionAt(event.x)
                onSeek?.invoke(false, fraction, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onSeek?.invoke(false, fraction, true)
                invalidate()
            }
        }
        return true
    }
}
