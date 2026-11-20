package com.vasthread.webviewtv.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.playlist.Channels
import com.vasthread.webviewtv.playlist.Channels.Channel

/**
 * 换台弹窗: 顶部 全部/央视/卫视 三个分类 + 清晰度入口, 下方频道网格(带台标徽章)。
 * 手机平板触摸选台, 电视十字键导航 + OK 确认。
 */
class ChannelSheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val TAB_ALL = 0
        const val TAB_CCTV = 1
        const val TAB_WEISHI = 2
        private const val SPAN_COUNT = 5
    }

    interface Callback {
        /** 用户选择了频道 */
        fun onChannelSelected(name: String)

        /** 用户点击清晰度入口 */
        fun onQualityRequested()
    }

    private val tabAll: TextView
    private val tabCctv: TextView
    private val tabWeishi: TextView
    private val btnQuality: TextView
    private val grid: RecyclerView
    private val adapter = ChannelAdapter()

    var callback: Callback? = null

    var currentTab = TAB_ALL
        private set

    private var currentChannel = Channels.DEFAULT_CHANNEL

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_channel_sheet, this)
        isClickable = true
        isFocusable = false

        tabAll = findViewById(R.id.tabAll)
        tabCctv = findViewById(R.id.tabCctv)
        tabWeishi = findViewById(R.id.tabWeishi)
        btnQuality = findViewById(R.id.btnQuality)
        grid = findViewById(R.id.gridChannels)

        tabAll.setOnClickListener { setTab(TAB_ALL) }
        tabCctv.setOnClickListener { setTab(TAB_CCTV) }
        tabWeishi.setOnClickListener { setTab(TAB_WEISHI) }
        btnQuality.setOnClickListener { callback?.onQualityRequested() }
        findViewById<View>(R.id.sheetMask).setOnClickListener { requestDismiss() }

        grid.layoutManager = GridLayoutManager(context, SPAN_COUNT)
        grid.adapter = adapter
    }

    private fun requestDismiss() {
        visibility = GONE
        onDismissCallback?.invoke()
    }

    var onDismissCallback: (() -> Unit)? = null

    /** 弹窗打开时调用: 定位当前频道所在分类并滚动到可视区 */
    fun open(channel: String, quality: String) {
        currentChannel = channel
        btnQuality.text = "清晰度: $quality"
        // 当前 tab 看不到当前频道时, 切到它所在分类
        if (currentTab != TAB_ALL && channelsOf(currentTab).none { it.name == channel }) {
            currentTab = if (Channels.WEISHI.any { it.name == channel }) TAB_WEISHI else TAB_CCTV
        }
        setTab(currentTab)
        visibility = VISIBLE
        post {
            refreshSelection()
            focusCurrent()
        }
    }

    fun setQualityLabel(quality: String) {
        btnQuality.text = "清晰度: $quality"
    }

    private fun setTab(tab: Int) {
        currentTab = tab
        val active = 0xFF0183FD.toInt()
        val inactive = 0xFF555F6E.toInt()
        tabAll.setTextColor(if (tab == TAB_ALL) active else inactive)
        tabCctv.setTextColor(if (tab == TAB_CCTV) active else inactive)
        tabWeishi.setTextColor(if (tab == TAB_WEISHI) active else inactive)
        adapter.reload(channelsOf(tab))
        post { refreshSelection() }
    }

    private fun channelsOf(tab: Int): List<Channel> = when (tab) {
        TAB_CCTV -> Channels.CCTV
        TAB_WEISHI -> Channels.WEISHI
        else -> Channels.ALL
    }

    private fun refreshSelection() {
        adapter.notifyDataSetChanged()
    }

    /** 将当前频道滚动到可视区并聚焦 */
    private fun focusCurrent() {
        val position = channelsOf(currentTab).indexOfFirst { it.name == currentChannel }
        if (position >= 0) {
            grid.scrollToPosition(position)
            grid.post {
                val holder = grid.findViewHolderForAdapterPosition(position)
                holder?.itemView?.requestFocus()
            }
        } else {
            grid.post { grid.getChildAt(0)?.requestFocus() }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {

        private var channels: List<Channel> = emptyList()

        fun reload(list: List<Channel>) {
            channels = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sheet_channel, parent, false)
            return ChannelHolder(view)
        }

        override fun getItemCount() = channels.size

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            holder.bind(channels[position])
        }
    }

    private inner class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val tvBadge: TextView = view.findViewById(R.id.tvBadge)
        private val tvName: TextView = view.findViewById(R.id.tvChannelName)

        init {
            view.setOnClickListener {
                val channel = channelsOf(currentTab).getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                callback?.onChannelSelected(channel.name)
            }
        }

        fun bind(channel: Channel) {
            tvBadge.text = channel.badge
            tvBadge.setPadding(4, 2, 4, 2)
            tvBadge.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (channel.badge.length > 3) 11f else 13f
            )
            tvBadge.background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(channel.badgeColor)
            }
            tvName.text = channel.name
            val selected = channel.name == currentChannel
            tvName.setTextColor(
                if (selected) 0xFF0183FD.toInt() else 0xFFAAB2BF.toInt()
            )
        }
    }
}
