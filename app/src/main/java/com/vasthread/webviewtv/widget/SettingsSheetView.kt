package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vasthread.webviewtv.misc.UiScale

/**
 * 设置弹窗(居中面板, 不占满屏)。
 *
 * 目前三项设置:
 *   1. 上下按键切换: 开关。开启=反转(手机上滑/电视按下键 改为切上一个台)
 *   2. 应用自动更新: 开关(启动时自动检测新版本) + 手动"立即检查更新"
 *   3. 播放内核: 点击弹窗选择(系统内核 / 腾讯 X5), 重启应用后生效
 *
 * 视觉与频道/清晰度面板完全一致: 影院深色面板 + 琥珀强调 + 焦点黄底黑字。
 * 手机触摸切换; 电视十字键导航 + OK 切换 + BACK 关闭。
 */
class SettingsSheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_ACCENT = 0xFFF2B33D.toInt()
        private const val COLOR_TEXT = 0xFFF2F4F7.toInt()
        private const val COLOR_SUB = 0xFF98A2B3.toInt()
        private const val COLOR_MUTED = 0xFF6B7484.toInt()
        private const val COLOR_PANEL = 0xF513161B.toInt()
        private const val COLOR_DIM = 0xB3000000.toInt()
        private const val COLOR_FOCUS_TEXT = 0xFF1A1A1A.toInt()
    }

    interface Callback {
        /** 上下键反转开关变化(true=反转) */
        fun onInvertSwitchChanged(invert: Boolean)
        /** 自动更新开关变化 */
        fun onAutoUpdateChanged(enabled: Boolean)
        /** 点击"立即检查更新" */
        fun onCheckUpdateClick()
        /** 切换播放内核(true=X5, false=系统) */
        fun onKernelChanged(x5: Boolean)
    }

    private val ui: UiScale = UiScale(context)

    var callback: Callback? = null
    var onDismissCallback: (() -> Unit)? = null

    private val toggleInvert: ToggleView
    private val toggleAutoUpdate: ToggleView
    private val kernelValue: TextView
    private val kernelOverlay: FrameLayout
    private val kernelOptions = mutableListOf<LinearLayout>()
    private var kernelX5 = false

    init {
        isClickable = true
        isFocusable = false

        // 半透明遮罩, 点击关闭
        val dim = View(context)
        dim.setBackgroundColor(COLOR_DIM)
        dim.setOnClickListener { dismiss() }
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 居中面板: 手机 86% / 平板 60% / 电视 52% 屏宽, 高度随内容
        val panelW = ui.widthOf(when { ui.isTv -> 0.52f; ui.isTablet -> 0.60f; else -> 0.86f }, 320f, 560f)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_PANEL, ui.dp(10f) * 1.8f)
            setPadding(ui.dp(16f), ui.dp(14f), ui.dp(16f), ui.dp(14f))
        }
        addView(panel, LayoutParams(panelW, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ---- 标题 ----
        panel.addView(TextView(context).apply {
            text = "设置"
            setTextColor(COLOR_SUB)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(11f))
            letterSpacing = 0.08f
            setPadding(ui.dp(4f), 0, 0, ui.dp(10f))
        })

        // ---- 项 1: 上下按键切换 ----
        val (rowInvert, t1) = createToggleRow(
            title = "上下按键切换",
            note = "默认关闭。手机上滑＝下一个台，电视按 ↓＝下一个台；开启后反转。"
        )
        toggleInvert = t1
        panel.addView(rowInvert, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(6f) })
        rowInvert.setOnClickListener {
            toggleInvert.checked = !toggleInvert.checked
            callback?.onInvertSwitchChanged(toggleInvert.checked)
        }

        // ---- 项 2: 应用自动更新 ----
        val (rowAuto, t2) = createToggleRow(
            title = "应用自动更新",
            note = "启动时自动检测新版本，发现后提示更新。"
        )
        toggleAutoUpdate = t2
        panel.addView(rowAuto, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(2f) })
        rowAuto.setOnClickListener {
            toggleAutoUpdate.checked = !toggleAutoUpdate.checked
            callback?.onAutoUpdateChanged(toggleAutoUpdate.checked)
        }

        // ---- 项 3: 播放内核(点击弹窗选择) ----
        val (rowKernel, valueView) = createValueRow(
            title = "播放内核",
            note = "默认系统内核；老设备(如 Android 6.0 老电视)可选腾讯 X5。切换后重启应用生效。"
        )
        kernelValue = valueView
        panel.addView(rowKernel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(2f) })
        rowKernel.setOnClickListener { showKernelPicker() }

        // ---- 手动检查更新 ----
        val checkRow = TextView(context).apply {
            text = "立即检查更新"
            setTextColor(COLOR_ACCENT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(13f))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = itemBackground(ui.dp(5f) * 1.6f)
            setPadding(0, ui.dp(11f), 0, ui.dp(11f))
            setOnClickListener { callback?.onCheckUpdateClick() }
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_ACCENT)
            }
        }
        panel.addView(checkRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ui.dp(6f) })

        // ---- 内核选择弹窗(覆盖层, 默认隐藏) ----
        kernelOverlay = FrameLayout(context).apply {
            visibility = GONE
            isClickable = true
        }
        addView(kernelOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        kernelOverlay.addView(View(context).apply {
            setBackgroundColor(COLOR_DIM)
            setOnClickListener { closeKernelPicker() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val pickerPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_PANEL, ui.dp(10f) * 1.8f)
            setPadding(ui.dp(16f), ui.dp(14f), ui.dp(16f), ui.dp(14f))
            isClickable = true
        }
        kernelOverlay.addView(pickerPanel, FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        pickerPanel.addView(TextView(context).apply {
            text = "选择播放内核"
            setTextColor(COLOR_SUB)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(11f))
            letterSpacing = 0.08f
            setPadding(ui.dp(4f), 0, 0, ui.dp(10f))
        })
        val optSystem = createKernelOption(
            title = "系统内核（WebView）",
            note = "默认。系统自带 Chromium 内核，无需下载，推荐新设备使用。"
        ) { selectKernel(false) }
        kernelOptions.add(optSystem)
        pickerPanel.addView(optSystem, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(6f) })
        val optX5 = createKernelOption(
            title = "腾讯 X5 内核",
            note = "适用于系统内核过旧的老设备（如 Android 6.0 老电视）。首次使用需联网自动下载内核，下载完成后重启生效。"
        ) { selectKernel(true) }
        kernelOptions.add(optX5)
        pickerPanel.addView(optX5, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    /** 构建"标题 + 右侧当前值"的可聚焦设置行; 焦点态文字变黑 */
    private fun createValueRow(title: String, note: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = itemBackground(ui.dp(5f) * 1.6f)
            setPadding(ui.dp(10f), ui.dp(9f), ui.dp(10f), ui.dp(9f))

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(context).apply {
                text = title
                setTextColor(COLOR_TEXT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(14f))
            }
            val valueView = TextView(context).apply {
                text = ""
                setTextColor(COLOR_ACCENT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(12f))
                setPadding(ui.dp(10f), 0, 0, 0)
                tag = "value"
            }
            header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(valueView)
            addView(header)

            val noteView = TextView(context).apply {
                text = note
                setTextColor(COLOR_MUTED)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(10f))
                setLineSpacing(ui.sp(3f), 1f)
                setPadding(0, ui.dp(4f), 0, 0)
            }
            addView(noteView)

            setOnFocusChangeListener { _, hasFocus ->
                titleView.setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_TEXT)
                noteView.setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_MUTED)
                valueView.setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_ACCENT)
            }
        }
        return row to (row.findViewWithTag<TextView>("value") ?: valueViewIn(row))
    }

    private fun valueViewIn(row: LinearLayout): TextView {
        // 第二行标题里的右侧值 TextView
        val header = row.getChildAt(0) as LinearLayout
        return header.getChildAt(1) as TextView
    }

    /** 构建内核选择项行 */
    private fun createKernelOption(title: String, note: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = itemBackground(ui.dp(5f) * 1.6f)
            setPadding(ui.dp(10f), ui.dp(9f), ui.dp(10f), ui.dp(9f))

            val titleView = TextView(context).apply {
                text = title
                setTextColor(COLOR_TEXT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(14f))
            }
            addView(titleView)
            val noteView = TextView(context).apply {
                text = note
                setTextColor(COLOR_MUTED)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(10f))
                setLineSpacing(ui.sp(3f), 1f)
                setPadding(0, ui.dp(4f), 0, 0)
            }
            addView(noteView)

            setOnFocusChangeListener { _, hasFocus ->
                titleView.setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_TEXT)
                noteView.setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_MUTED)
            }
            setOnClickListener { onClick() }
        }
        return row
    }
    /** 构建"标题 + 开关 + 小字备注"的可聚焦设置行; 焦点态文字变黑 */
    private fun createToggleRow(title: String, note: String): Pair<LinearLayout, ToggleView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = itemBackground(ui.dp(5f) * 1.6f)
            setPadding(ui.dp(10f), ui.dp(9f), ui.dp(10f), ui.dp(9f))

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(context).apply {
                text = title
                setTextColor(COLOR_TEXT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(14f))
                tag = "title"
            }
            val toggle = ToggleView(context).apply {
                tag = "toggle"
                layoutParams = LinearLayout.LayoutParams(ui.dp(44f), ui.dp(24f)).apply {
                    leftMargin = ui.dp(10f)
                }
            }
            header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(toggle)
            addView(header)

            val noteView = TextView(context).apply {
                text = note
                setTextColor(COLOR_MUTED)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(10f))
                setLineSpacing(ui.sp(3f), 1f)
                setPadding(0, ui.dp(4f), 0, 0)
                tag = "note"
            }
            addView(noteView)

            // 焦点态: 黄底黑字(标题与备注), 失焦恢复
            setOnFocusChangeListener { _, hasFocus ->
                titleView.setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_TEXT)
                noteView.setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else COLOR_MUTED)
            }
        }
        val toggle = row.findViewWithTag<ToggleView>("toggle")
            ?: throw IllegalStateException("toggle view missing")
        return row to toggle
    }

    // ---------------- 对外 API ----------------

    fun open(invert: Boolean, autoUpdate: Boolean, kernel: Boolean = false) {
        toggleInvert.checked = invert
        toggleAutoUpdate.checked = autoUpdate
        kernelX5 = kernel
        updateKernelValue()
        kernelOverlay.visibility = GONE
        visibility = VISIBLE
        if (ui.isTv) {
            post { findFocusable(this)?.requestFocus() }
        }
    }

    /** 返回键处理: 内核选择层打开时先关闭它(返回 true 表示已消费) */
    fun handleBack(): Boolean {
        if (kernelOverlay.visibility == VISIBLE) {
            closeKernelPicker()
            return true
        }
        return false
    }

    private fun updateKernelValue() {
        kernelValue.text = if (kernelX5) "腾讯 X5" else "系统内核"
    }

    private fun showKernelPicker() {
        updateKernelValue()
        kernelOverlay.visibility = VISIBLE
        if (ui.isTv) {
            val target = kernelOptions.getOrNull(if (kernelX5) 1 else 0) ?: kernelOptions.firstOrNull()
            post { target?.requestFocus() }
        }
    }

    private fun closeKernelPicker() {
        kernelOverlay.visibility = GONE
        if (ui.isTv) {
            post { findFocusable(this)?.requestFocus() }
        }
    }

    private fun selectKernel(x5: Boolean) {
        kernelX5 = x5
        updateKernelValue()
        closeKernelPicker()
        callback?.onKernelChanged(x5)
    }

    private fun findFocusable(parent: View): View? {
        if (parent.visibility != View.VISIBLE) return null
        if (parent.isFocusable) return parent
        if (parent is android.view.ViewGroup) {
            for (i in 0 until parent.childCount) {
                findFocusable(parent.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    fun dismiss() {
        visibility = GONE
        onDismissCallback?.invoke()
    }

    // ---------------- 样式助手 ----------------

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }

    private fun itemBackground(radius: Float): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(COLOR_ACCENT))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x33FFFFFF))
            addState(intArrayOf(), shape(Color.TRANSPARENT))
        }
    }

    /**
     * 自绘小型开关: 圆角胶囊轨道 + 圆点。
     * 开启=琥珀轨道, 圆点在右; 关闭=深灰轨道, 圆点在左。
     * 不聚焦自身(由整行聚焦), 黄底上轨道颜色仍清晰。
     */
    @SuppressLint("ViewConstructor")
    private class ToggleView(context: Context) : View(context) {

        var checked: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackRect = RectF()
        private val trackOff = 0xFF37404E.toInt()
        private val trackOn = 0xFFF2B33D.toInt()
        private val thumbColor = 0xFFF5F7FA.toInt()

        init {
            isFocusable = false
            isClickable = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f
            trackRect.set(0f, 0f, w, h)
            paint.color = if (checked) trackOn else trackOff
            canvas.drawRoundRect(trackRect, r, r, paint)
            val margin = h * 0.16f
            val cx = if (checked) w - r else r
            paint.color = thumbColor
            canvas.drawCircle(cx, r, r - margin, paint)
        }
    }
}