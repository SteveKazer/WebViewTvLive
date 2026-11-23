package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.widget.ScrollView
import android.widget.TextView
import com.vasthread.webviewtv.activity.TbsManagerActivity
import com.vasthread.webviewtv.misc.UiScale

/**
 * 设置弹窗(居中面板, 不占满屏)。
 *
 * 目前三部分设置:
 *   1. 上下按键切换: 开关。开启=反转(手机上滑/电视按下键 改为切上一个台)
 *   2. 播放内核: 点击弹窗选择(系统内核 / 腾讯 X5), 重启应用后生效;
 *      其下为无图模式开关(隐藏大图加速加载, 即时生效)
 *   3. 应用自动更新: 开关(启动时自动检测新版本) + 手动"立即检查更新"
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
        /** 无图模式开关变化(隐藏大图保留图标) */
        fun onNoImageChanged(enabled: Boolean)
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
    private val toggleNoImage: ToggleView
    private val kernelValue: TextView
    private val kernelNote: TextView
    private val kernelOverlay: FrameLayout
    private lateinit var kernelRowRef: TextView
    private val kernelOptions = mutableListOf<LinearLayout>()
    private var kernelX5 = false
    /** 主面板容器(电视焦点定位起点) */
    private lateinit var panelRef: FrameLayout
    /** 电视十字键导航链: 设置面板内容行(按可见性重连) */
    private val tvRows = mutableListOf<View>()

    init {
        isClickable = true
        isFocusable = false

        // 半透明遮罩, 点击关闭。
        // 电视: 可聚焦 —— 行内左右/上下误逸出面板时焦点落在遮罩(OK/触摸=关闭),
        // 不会钻进遮罩下面的 WebView 导致按键失去反应
        val dim = View(context)
        dim.setBackgroundColor(COLOR_DIM)
        dim.isFocusable = true
        dim.setOnClickListener { dismiss() }
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 居中面板: 手机 86% / 平板 60% / 电视 52% 屏宽;
        // 内容超出屏幕时可上下滚动(面板高度上限 86% 屏高)
        val panelW = ui.widthOf(when { ui.isTv -> 0.52f; ui.isTablet -> 0.60f; else -> 0.86f }, 320f, 560f)
        val panel = FrameLayout(context).apply {
            background = rounded(COLOR_PANEL, ui.dp(10f) * 1.8f)
        }
        panelRef = panel
        addView(panel, LayoutParams(panelW, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            // 电视: 焦点必须落在内容行(十字键导航 + OK 触发), 不进滚动容器本身,
            // 否则焦点被 ScrollView 授走、十字键变成滚动、OK 无响应(v2.4.6 实测 bug)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16f), ui.dp(14f), ui.dp(16f), ui.dp(14f))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        // 内容超出屏幕 86% 时限制面板高度, 由滚动条滑动查看剩余内容
        panel.post {
            val maxH = (resources.displayMetrics.heightPixels * 0.86f).toInt()
            if (panel.height > maxH) {
                panel.layoutParams.height = maxH
                scroll.layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                panel.requestLayout()
            }
        }

        // ---- 标题 ----
        content.addView(TextView(context).apply {
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
        content.addView(rowInvert, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(6f) })
        tvRows.add(rowInvert)
        rowInvert.setOnClickListener {
            toggleInvert.checked = !toggleInvert.checked
            callback?.onInvertSwitchChanged(toggleInvert.checked)
        }

        // ---- 项 2: 播放内核(点击弹窗选择) ----
        val (rowKernel, valueView) = createValueRow(
            title = "播放内核",
            note = "默认系统内核；老设备(如 Android 6.0 老电视)可选腾讯 X5。切换后重启应用生效。"
        )
        kernelValue = valueView
        kernelNote = rowKernel.findViewWithTag("note")
            ?: throw IllegalStateException("kernel note view missing")
        content.addView(rowKernel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(6f) })
        tvRows.add(rowKernel)
        rowKernel.setOnClickListener { showKernelPicker() }

        // ---- 项 3: 无图模式(隐藏大图加速加载, 即时生效) ----
        val (rowNoImage, t3) = createToggleRow(
            title = "无图模式",
            note = "隐藏海报等大图以加快加载，小图标不受影响。默认关闭。"
        )
        toggleNoImage = t3
        content.addView(rowNoImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(6f) })
        tvRows.add(rowNoImage)
        rowNoImage.setOnClickListener {
            toggleNoImage.checked = !toggleNoImage.checked
            callback?.onNoImageChanged(toggleNoImage.checked)
        }

        // ---- 项 4: 应用自动更新 ----
        val (rowAuto, t2) = createToggleRow(
            title = "应用自动更新",
            note = "启动时自动检测新版本，发现后提示更新。"
        )
        toggleAutoUpdate = t2
        content.addView(rowAuto, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = ui.dp(2f) })
        tvRows.add(rowAuto)
        rowAuto.setOnClickListener {
            toggleAutoUpdate.checked = !toggleAutoUpdate.checked
            callback?.onAutoUpdateChanged(toggleAutoUpdate.checked)
        }

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
        content.addView(checkRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ui.dp(6f) })
        tvRows.add(checkRow)

        // ---- TBS 内核管理(仅选 X5 时显示; 下载安装/本地导入内核) ----
        val tbsRow = TextView(context).apply {
            text = "X5 内核管理（下载 / 安装内核）›"
            setTextColor(0xFF7A8496.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.sp(11f))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = itemBackground(ui.dp(5f) * 1.6f)
            setPadding(0, ui.dp(9f), 0, 0)
            setOnClickListener {
                context.startActivity(Intent(context, TbsManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // 焦点态: 黄底黑字(与其他行一致, 否则电视上看不出焦点在哪)
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(if (hasFocus) COLOR_FOCUS_TEXT else 0xFF7A8496.toInt())
            }
        }
        content.addView(tbsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        tbsRow.visibility = GONE
        kernelRowRef = tbsRow
        tvRows.add(tbsRow)
        // ---- 内核选择弹窗(覆盖层, 默认隐藏) ----
        kernelOverlay = FrameLayout(context).apply {
            visibility = GONE
            isClickable = true
        }
        addView(kernelOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        kernelOverlay.addView(View(context).apply {
            setBackgroundColor(COLOR_DIM)
            isFocusable = true
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
            note = "老设备（如 Android 6.0 老电视）可选。内核由腾讯服务器在线下载；也可在下方「X5 内核管理」手动安装线上内核（原仓库 TBS 测试同款）。切换后重启生效。"
        ) { selectKernel(true) }
        kernelOptions.add(optX5)
        pickerPanel.addView(optX5, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 电视: 内核选择弹窗两个选项间上下循环导航(焦点不逸出弹窗)
        kernelOptions.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        kernelOptions[0].nextFocusUpId = kernelOptions[1].id
        kernelOptions[0].nextFocusDownId = kernelOptions[1].id
        kernelOptions[1].nextFocusUpId = kernelOptions[0].id
        kernelOptions[1].nextFocusDownId = kernelOptions[0].id
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
                tag = "note"
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

    fun open(
        invert: Boolean,
        autoUpdate: Boolean,
        kernel: Boolean = false,
        kernelNoteText: String? = null,
        noImage: Boolean = false
    ) {
        toggleInvert.checked = invert
        toggleAutoUpdate.checked = autoUpdate
        toggleNoImage.checked = noImage
        kernelX5 = kernel
        updateKernelValue()
        if (!kernelNoteText.isNullOrBlank()) {
            kernelNote.text = kernelNoteText
        }
        // TBS 管理入口仅在选择腾讯 X5 时显示
        kernelRowRef.visibility = if (kernel) VISIBLE else GONE
        kernelOverlay.visibility = GONE
        visibility = VISIBLE
        if (ui.isTv) {
            rewireTvFocus()
            post { findFocusable(panelRef)?.requestFocus() }
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
            post { findFocusable(panelRef)?.requestFocus() }
        }
    }

    private fun selectKernel(x5: Boolean) {
        kernelX5 = x5
        updateKernelValue()
        closeKernelPicker()
        callback?.onKernelChanged(x5)
    }

    /**
     * 电视十字键适配(v2.4.7): 设置面板内容行上下循环导航。
     * 面板外只有可聚焦的半透明遮罩(落在上面=OK 关闭面板), 焦点不会
     * 逸出到面板下方铺满全屏的 WebView(否则遥控按键失去反应)。
     * X5 内核管理入口的可见性随内核选择变化, 每次打开时按当前可见行重连。
     */
    private fun rewireTvFocus() {
        val rows = tvRows.filter { it.visibility == View.VISIBLE }
        rows.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        for (i in rows.indices) {
            rows[i].nextFocusUpId = rows[(i - 1 + rows.size) % rows.size].id
            rows[i].nextFocusDownId = rows[(i + 1) % rows.size].id
        }
    }

    /**
     * 查找首个可聚焦的内容行。
     * 从主面板容器出发(不经半透明遮罩); ScrollView 等可滚动容器
     * 一律跳过自身继续向内找 —— 焦点必须落在内容行上, 否则电视上
     * 十字键变成滚动、OK 键无响应(v2.4.6 实测 bug)。
     */
    private fun findFocusable(parent: View): View? {
        if (parent.visibility != View.VISIBLE) return null
        if (parent is android.view.ViewGroup) {
            if (parent !is ScrollView && parent.isFocusable) return parent
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
     * 自绘小型开关(v2.4.9 重绘): 深色底盘 + 胶囊轨道 + 反色圆点。
     * 黄色聚焦行上旧配色(琥珀轨道+白点)与行背景融为一体, 开/关难辨;
     * 现在先铺一层深色底盘, 开关形状在任何背景上都清晰:
     *   开启: 琥珀轨道 + 深黑圆点(右)   关闭: 深灰蓝轨道 + 白圆点(左)
     * 不聚焦自身(由整行聚焦)。
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
        private val plateColor = 0xD9101318.toInt()   // 深色底盘(黄底上也清晰)
        private val trackOff = 0xFF2A3442.toInt()
        private val trackOn = 0xFFF2B33D.toInt()
        private val thumbOn = 0xFF14161A.toInt()      // 开: 深色圆点(与琥珀轨道强对比)
        private val thumbOff = 0xFFF5F7FA.toInt()     // 关: 白色圆点
        private val strokeOff = 0x66F2B33D.toInt()    // 关: 琥珀细描边(提示可开启)

        init {
            isFocusable = false
            isClickable = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // 深色底盘: 占满整个 View, 轨道内缩 —— 任何背景(含黄色聚焦行)上都清晰
            val r = h / 2f
            paint.color = plateColor
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)

            val pad = h * 0.14f
            val trackH = h - pad * 2f
            val tr = trackH / 2f
            trackRect.set(pad, pad, w - pad, h - pad)
            paint.color = if (checked) trackOn else trackOff
            canvas.drawRoundRect(trackRect, tr, tr, paint)
            // 关闭态: 琥珀细描边, 暗示可开启
            if (!checked) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = Math.max(1f, trackH * 0.07f)
                paint.color = strokeOff
                canvas.drawRoundRect(trackRect, tr, tr, paint)
                paint.style = Paint.Style.FILL
            }
            // 滑块: 贴轨道端部, 开=深色(琥珀轨道上强对比), 关=白色
            val thumbR = tr - trackH * 0.16f
            val cx = if (checked) w - pad - tr else pad + tr
            paint.color = if (checked) thumbOn else thumbOff
            canvas.drawCircle(cx, h / 2f, thumbR, paint)
        }
    }
}