// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.dsd.player.R
import com.dsd.player.data.Library

/**
 * 「排序与布局」底部面板。
 *
 * 走查 P0 #4 / P2 #23 #24 的问题：原来用一个 setSingleChoiceItems 把「排序字段（互斥）」
 * 「倒序（开关）」「布局（互斥）」混在一个单选组里，11 项还要滚动，且全是空心圆看不出当前值。
 * 这里改成三段式：排序方式（单选 chips）· 倒序（开关）· 布局（单选 chips）。
 * 点选即时生效并刷新选中态，面板不关闭（用户通常要连改两项），底部只有一个「完成」。
 */
object SortSheet {

    fun show(act: MainActivity) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val land = act.isLandscape
        val cols = if (land) 5 else 3
        val dlg = Dialog(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = act.getDrawable(R.drawable.bg_sheet)
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }

        // 顶部小握把
        root.addView(View(act).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10)
            }
            setBackgroundColor(0x33FFFFFF)
        })

        // 标题行：标题 + 完成
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = "排序与布局"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(act).apply {
            text = "完成"
            textSize = 14f
            setTextColor(0xFF5B8DEF.toInt())
            setPadding(dp(14), dp(8), dp(2), dp(8))
            setOnClickListener { dlg.dismiss() }
        })
        root.addView(head)

        // ── 排序方式 ────────────────────────────────────────────────
        root.addView(sectionLabel(act, "排序方式"))
        val sortRow = chipGrid(act)
        root.addView(sortRow)

        // ── 倒序 ───────────────────────────────────────────────────
        val descRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
        }
        descRow.addView(TextView(act).apply {
            text = "倒序（从大到小 / Z→A）"
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = SwitchCompat(act).apply {
            isChecked = act.sortDesc
            setOnCheckedChangeListener { _, checked -> act.setSort(act.sortKey, checked) }
        }
        descRow.addView(sw)
        root.addView(descRow)

        // ── 布局 ───────────────────────────────────────────────────
        root.addView(sectionLabel(act, "布局"))
        val layoutRow = chipGrid(act)
        root.addView(layoutRow)

        val sorts = listOf(
            Library.SortKey.ALBUM to "专辑",
            Library.SortKey.YEAR to "年份",
            Library.SortKey.ADDED to "添加日期",
            Library.SortKey.MODIFIED to "修改日期",
            Library.SortKey.TITLE to "标题",
        )
        val layouts = listOf(
            LayoutMode.GRID4 to "四列网格",
            LayoutMode.GRID3 to "三列网格",
            LayoutMode.GRID2 to "双列网格",
            LayoutMode.LARGE to "大封面",
            LayoutMode.STANDARD to "标准列表",
            LayoutMode.COMPACT to "紧凑列表",
        )

        fun fillSort(): Unit = fillChips(
            act, sortRow, cols,
            sorts.map { (k, n) -> n to (k == act.sortKey) },
        ) { i: Int -> act.setSort(sorts[i].first, act.sortDesc); fillSort() }

        fun fillLayout(): Unit = fillChips(
            act, layoutRow, cols,
            layouts.map { (m, n) -> n to (m == act.layoutMode) },
        ) { i: Int -> act.setLayout(layouts[i].first); fillLayout() }

        fillSort()
        fillLayout()

        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 横屏限宽居中：铺满 1920px 会让每一格 chip 宽成 220dp
            val maxW = (560 * act.resources.displayMetrics.density).toInt()
            val sw = act.resources.configuration.screenWidthDp * act.resources.displayMetrics.density
            setLayout(if (land) minOf(maxW, sw.toInt()) else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            // 走查 #23：遮罩透明度偏低 → 加深到 ~72%，背景不再抢眼
            setDimAmount(0.72f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(R.style.SheetAnim)
        }
        dlg.show()
    }

    private fun sectionLabel(act: MainActivity, s: String): TextView {
        val d = act.resources.displayMetrics.density
        return TextView(act).apply {
            text = s
            textSize = 12f
            setTextColor(0x80FFFFFF.toInt())
            setPadding((12 * d).toInt(), (14 * d).toInt(), 0, (2 * d).toInt())
        }
    }

    private fun chipGrid(act: MainActivity): LinearLayout =
        LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    /** 等宽网格：最后一行不足一列时用空 View 占位，保证列宽对齐 */
    private fun fillChips(act: MainActivity, host: LinearLayout, cols: Int,
                          items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        host.removeAllViews()
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var row: LinearLayout? = null
        items.forEachIndexed { i, (label, on) ->
            if (i % cols == 0) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                host.addView(row)
            }
            val tv = TextView(act).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
                setBackgroundResource(if (on) R.drawable.bg_chip_active else R.drawable.bg_chip)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6); topMargin = dp(6) }
                setOnClickListener { onPick(i) }
            }
            row?.addView(tv)
        }
        // 补空位
        val rest = items.size % cols
        if (rest != 0) {
            for (k in rest until cols) {
                row?.addView(View(act).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(6) }
                })
            }
        }
        // 每行最后一个 chip 去掉右边距，避免右侧多出一条空隙
        for (r in 0 until host.childCount) {
            val rr = host.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until rr.childCount) {
                val lp = rr.getChildAt(c).layoutParams as? LinearLayout.LayoutParams ?: continue
                if (c == rr.childCount - 1) { lp.marginEnd = 0; rr.getChildAt(c).layoutParams = lp }
            }
        }
    }
}
