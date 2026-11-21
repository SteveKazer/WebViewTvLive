package com.vasthread.webviewtv.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vasthread.webviewtv.misc.UiScale
import com.vasthread.webviewtv.playlist.Channels

/**
 * 清晰度弹窗(右侧全高抽屉)。
 *
 * 布局: 上下占满整个屏幕高度, 贴右缘展示
 *   - 上方: 播放清晰度选项
 *   - 底部: 设置按钮占位(未来扩展, 当前点击提示即将上线)
 *
 * 手机: 点击屏幕右侧 30% 区域唤起; 电视: MENU 键唤起。
 */
class QualitySheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_ACCENT = 0xFFF2B33D.toInt()
        private const val COLOR_TEXT = 0xFFF2F4F7.toInt()
        private const val COLOR_SUB = 0xFF98A2B3.toInt()
        private const val COLOR_MUTED = 0xFF6B7484.toInt()
        private const val COLOR_PANEL = 0xF513161B.toInt()
        private const val COLOR_DIM = 0xB3000000.toInt()
    }

    interface Callback {
        fun onQualitySelected(label: String)
        fun onSettingsClick()
    }

    private val ui: UiScale = UiScale(context)
    private val options = mutableListOf<TextView>()
    private val labels = Channels.QUALITIES.toList()

    /** 当前清晰度前缀(如"蓝光"), 焦点失焦时按它恢复选中项颜色 */
    private var selectedPrefix = ""

    var callback: Callback? = null
    var onDismissCallback: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = false

        val dim = View(context)
        dim.setBackgroundColor(COLOR_DIM)
        dim.setOnClickListener { dismiss() }
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 右侧全高抽屉: 宽度 手机 30% / 平板 26% / 电视 24% 屏宽, 高度上下占满
        val panelW = ui.widthOf(
            when { ui.isTv -> 0.24f; ui.isTablet -> 0.26f; else -> 0.30f },
            180f, 320f
        )
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedLeft(COLOR_PANEL, ui.dp(9f) * 1.8f)
            setPadding(ui.dp(8f), ui.dp(14f), ui.dp(8f), ui.dp(14f))
        }
        addView(panel, LayoutParams(panelW, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL))

        // ---- 上方: 清晰度选项 ----
        panel.addView(TextView(context).apply {
            text = "播放清晰度"
            setTextColor(COLOR_SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.sp(11f))
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ui.dp(8f)
        })

        labels.forEachIndexed { index, label ->
            val option = TextView(context).apply {
                text = label
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.sp(13f))
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = itemBackground(ui.dp(5f) * 1.6f)
                setOnClickListener { callback?.onQualitySelected(labels[index]) }
                // 焦点态: 黄底黑字; 失焦按选中状态恢复(修复焦点黄底看不见字)
                setOnFocusChangeListener { v, hasFocus ->
                    v as TextView
                    if (hasFocus) v.setTextColor(focusTextColor())
                    else v.setTextColor(if (labels[index].startsWith(selectedPrefix)) COLOR_ACCENT else COLOR_TEXT)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(40f)
                ).apply { bottomMargin = ui.dp(3f) }
            }
            options.add(option)
            panel.addView(option)
        }

        // ---- 中间弹性留白 ----
        val spacer = View(context)
        panel.addView(spacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部: 设置按钮占位(未来扩展) ----
        val settings = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = itemBackground(ui.dp(5f) * 1.6f)
            setOnClickListener { callback?.onSettingsClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(46f)
            )
        }
        val settingsTitle = TextView(context).apply {
            text = "设置"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.sp(13f))
            gravity = Gravity.CENTER
        }
        val settingsHint = TextView(context).apply {
            text = "按键与更新设置"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.sp(9f))
            gravity = Gravity.CENTER
        }
        settings.addView(settingsTitle)
        settings.addView(settingsHint)
        panel.addView(settings)

        // 焦点态文字颜色: 焦点背景为琥珀黄, 文字用深黑保证可读(修复黄底看不见字)
        val focusText = 0xFF1A1A1A.toInt()
        settings.setOnFocusChangeListener { _, hasFocus ->
            settingsTitle.setTextColor(if (hasFocus) focusText else COLOR_TEXT)
            settingsHint.setTextColor(if (hasFocus) focusText else COLOR_MUTED)
        }
    }

    /** 焦点态文字色 */
    private fun focusTextColor() = 0xFF1A1A1A.toInt()

    fun open(quality: String) {
        val prefix = quality.substringBefore(' ')
        selectedPrefix = prefix
        var selected = 0
        options.forEachIndexed { index, option ->
            val isCurrent = labels[index].startsWith(prefix)
            if (isCurrent) selected = index
            // 焦点中的选项交给焦点监听器保持黑字(黄底可读)
            if (!option.isFocused) option.setTextColor(if (isCurrent) COLOR_ACCENT else COLOR_TEXT)
        }
        visibility = VISIBLE
        post { options.getOrNull(selected)?.requestFocus() }
    }

    fun dismiss() {
        visibility = GONE
        onDismissCallback?.invoke()
    }

    private fun roundedLeft(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
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
}
