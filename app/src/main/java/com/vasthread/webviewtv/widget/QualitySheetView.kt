package com.vasthread.webviewtv.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.playlist.Channels

/**
 * 清晰度弹窗: 蓝光/超清/高清 三选一。
 * 手机平板从换台弹窗右上角入口打开, 电视按遥控 MENU 键直接调出。
 */
class QualitySheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onQualitySelected(label: String)
    }

    private val options: List<TextView>
    private val labels = Channels.QUALITIES.toList()

    var callback: Callback? = null

    var onDismissCallback: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_quality_sheet, this)
        isClickable = true
        isFocusable = false
        options = listOf(
            findViewById(R.id.option0),
            findViewById(R.id.option1),
            findViewById(R.id.option2),
        )
        options.forEachIndexed { index, option ->
            option.text = labels[index]
            option.setOnClickListener { callback?.onQualitySelected(labels[index]) }
        }
        findViewById<View>(R.id.qualityMask).setOnClickListener { dismiss() }
    }

    fun open(quality: String) {
        val prefix = quality.substringBefore(' ')
        var selected = 0
        options.forEachIndexed { index, option ->
            val isCurrent = labels[index].startsWith(prefix)
            if (isCurrent) selected = index
            option.setTextColor(if (isCurrent) COLOR_ACTIVE else COLOR_NORMAL)
        }
        visibility = VISIBLE
        post { options.getOrNull(selected)?.requestFocus() }
    }

    fun dismiss() {
        visibility = GONE
        onDismissCallback?.invoke()
    }

    companion object {
        private const val COLOR_ACTIVE = 0xFF0183FD.toInt()
        private const val COLOR_NORMAL = 0xFFAAB2BF.toInt()
    }
}
