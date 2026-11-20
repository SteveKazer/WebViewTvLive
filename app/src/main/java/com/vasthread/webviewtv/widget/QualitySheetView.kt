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
import com.vasthread.webviewtv.playlist.Channels
import kotlin.math.min

/**
 * 清晰度弹窗(右侧紧凑面板)。
 * 手机: 点击屏幕右半边唤起; 电视: MENU 键唤起。
 */
class QualitySheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_ACCENT = 0xFFF2B33D.toInt()
        private const val COLOR_TEXT = 0xFFF2F4F7.toInt()
        private const val COLOR_SUB = 0xFF98A2B3.toInt()
        private const val COLOR_PANEL = 0xF513161B.toInt()
        private const val COLOR_DIM = 0xB3000000.toInt()
    }

    interface Callback {
        fun onQualitySelected(label: String)
    }

    private val uiScale: Float
    private val options = mutableListOf<TextView>()
    private val labels = Channels.QUALITIES.toList()

    var callback: Callback? = null
    var onDismissCallback: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = false
        val dm = context.resources.displayMetrics
        uiScale = min(dm.heightPixels / 720f, dm.widthPixels / 1280f).coerceAtLeast(0.75f)

        val dim = View(context)
        dim.setBackgroundColor(COLOR_DIM)
        dim.setOnClickListener { dismiss() }
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val panelW = (dm.widthPixels * 0.30f).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f * uiScale
                setColor(COLOR_PANEL)
            }
            setPadding((10 * uiScale).toInt(), (14 * uiScale).toInt(), (10 * uiScale).toInt(), (10 * uiScale).toInt())
        }
        val lp = LayoutParams(panelW, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
        lp.rightMargin = (28f * uiScale).toInt()
        addView(panel, lp)

        panel.addView(TextView(context).apply {
            text = "播放清晰度"
            setTextColor(COLOR_SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * uiScale)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (10 * uiScale).toInt()
        })

        labels.forEachIndexed { index, label ->
            val option = TextView(context).apply {
                text = label
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * uiScale)
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = itemBackground(8f * uiScale)
                setOnClickListener { callback?.onQualitySelected(labels[index]) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (44f * uiScale).toInt()
                ).apply { bottomMargin = (4 * uiScale).toInt() }
            }
            options.add(option)
            panel.addView(option)
        }
    }

    fun open(quality: String) {
        val prefix = quality.substringBefore(' ')
        var selected = 0
        options.forEachIndexed { index, option ->
            val isCurrent = labels[index].startsWith(prefix)
            if (isCurrent) selected = index
            option.setTextColor(if (isCurrent) COLOR_ACCENT else COLOR_TEXT)
        }
        visibility = VISIBLE
        post { options.getOrNull(selected)?.requestFocus() }
    }

    fun dismiss() {
        visibility = GONE
        onDismissCallback?.invoke()
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