package com.vasthread.webviewtv.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.playlist.Channels
import com.vasthread.webviewtv.playlist.Channels.Channel

/**
 * 换台弹窗(侧边抽屉式):
 *   左侧: 全部 / 央视 / 卫视 分类 + 收藏(底部)
 *   右侧: 频道卡片列表(数字键编号 + 台标徽章 + 频道名 + 播放中标识)
 * 手机平板触摸选台/长按收藏, 电视十字键导航 + OK 选台 + 长按 OK 收藏。
 */
class ChannelSheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Callback {
        fun onChannelSelected(name: String, tab: Int)
        fun onFavoriteToggle(name: String)
        fun onQualityRequested()
    }

    private val tabAll: TextView
    private val tabCctv: TextView
    private val tabWeishi: TextView
    private val tabFavorite: TextView
    private val btnQuality: TextView
    private val listTitle: TextView
    private val list: RecyclerView
    private val emptyHint: TextView
    private val adapter = ChannelAdapter()

    var callback: Callback? = null
    var onDismissCallback: (() -> Unit)? = null

    var currentTab = Channels.TAB_ALL
        private set

    private var currentChannel = Channels.DEFAULT_CHANNEL
    private var favorites: Set<String> = emptySet()
    private var province: String? = null
    private var focusedChannel = ""

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_channel_sheet, this)
        isClickable = true
        isFocusable = false

        tabAll = findViewById(R.id.tabAll)
        tabCctv = findViewById(R.id.tabCctv)
        tabWeishi = findViewById(R.id.tabWeishi)
        tabFavorite = findViewById(R.id.tabFavorite)
        btnQuality = findViewById(R.id.btnQuality)
        listTitle = findViewById(R.id.listTitle)
        list = findViewById(R.id.listChannels)
        emptyHint = findViewById(R.id.emptyHint)

        tabAll.setOnClickListener { setTab(Channels.TAB_ALL) }
        tabCctv.setOnClickListener { setTab(Channels.TAB_CCTV) }
        tabWeishi.setOnClickListener { setTab(Channels.TAB_WEISHI) }
        tabFavorite.setOnClickListener { setTab(Channels.TAB_FAVORITE) }
        btnQuality.setOnClickListener { callback?.onQualityRequested() }
        findViewById<View>(R.id.sheetMask).setOnClickListener { dismiss() }

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
    }

    fun dismiss() {
        visibility = GONE
        onDismissCallback?.invoke()
    }

    /** 打开弹窗, 定位上次分类与当前频道 */
    fun open(tab: Int, channel: String, quality: String, favorites: Set<String>, province: String?) {
        this.currentChannel = channel
        this.favorites = favorites
        this.province = province
        btnQuality.text = "清晰度 $quality"
        // 收藏列表为空时无法停留在收藏页
        currentTab = if (tab == Channels.TAB_FAVORITE && favorites.isEmpty()) Channels.TAB_ALL else tab
        applyTab()
        visibility = VISIBLE
        post {
            focusCurrent()
        }
    }

    fun setCurrent(channel: String, quality: String) {
        currentChannel = channel
        btnQuality.text = "清晰度 $quality"
        adapter.notifyDataSetChanged()
    }

    fun setFavorites(favorites: Set<String>, province: String?) {
        this.favorites = favorites
        this.province = province
        applyTab()
        if (currentTab == Channels.TAB_FAVORITE && favorites.isEmpty() && visibility == VISIBLE) {
            setTab(Channels.TAB_ALL)
        }
    }

    /** 当前分类对应的频道列表(顺序切换上下文) */
    fun channelsOf(tab: Int): List<Channel> = when (tab) {
        Channels.TAB_CCTV -> Channels.CCTV
        Channels.TAB_WEISHI -> Channels.weishiOrdered(province)
        Channels.TAB_FAVORITE -> Channels.favorites(favorites, province)
        else -> Channels.allOrdered(province)
    }

    /** 当前聚焦频道名(电视长按 OK 收藏用; 空串=焦点不在频道上) */
    fun focusedChannel(): String = focusedChannel

    /** 电视: OK 键选中当前聚焦频道 */
    fun selectFocused() {
        if (focusedChannel.isNotEmpty()) callback?.onChannelSelected(focusedChannel, currentTab)
    }

    private fun setTab(tab: Int) {
        if (tab == Channels.TAB_FAVORITE && favorites.isEmpty()) return
        currentTab = tab
        applyTab()
        post { focusCurrent() }
    }

    private fun applyTab() {
        val active = 0xFF4C9AFF.toInt()
        val inactive = 0xFFAAB2BF.toInt()
        tabAll.setTextColor(if (currentTab == Channels.TAB_ALL) active else inactive)
        tabCctv.setTextColor(if (currentTab == Channels.TAB_CCTV) active else inactive)
        tabWeishi.setTextColor(if (currentTab == Channels.TAB_WEISHI) active else inactive)
        tabFavorite.setTextColor(if (currentTab == Channels.TAB_FAVORITE) active else inactive)

        val all = Channels.ALL
        val cctv = Channels.CCTV
        val weishi = Channels.WEISHI
        tabAll.text = "全部 ${all.size}"
        tabCctv.text = "央视 ${cctv.size}"
        tabWeishi.text = "卫视 ${weishi.size}"
        tabFavorite.text = "★ 收藏 ${favorites.size}"

        val data = channelsOf(currentTab)
        listTitle.text = when (currentTab) {
            Channels.TAB_CCTV -> "央视频道 · 数字键 1-14"
            Channels.TAB_WEISHI -> "卫视频道 · 数字键 101-124"
            Channels.TAB_FAVORITE -> "我的收藏"
            else -> "全部频道 · 数字键直接选台"
        }
        emptyHint.visibility =
            if (currentTab == Channels.TAB_FAVORITE && data.isEmpty()) VISIBLE else GONE
        list.visibility =
            if (currentTab == Channels.TAB_FAVORITE && data.isEmpty()) INVISIBLE else VISIBLE
        adapter.reload(data)
    }

    private fun focusCurrent() {
        val data = adapter.items
        val position = data.indexOfFirst { it.name == currentChannel }
        val target = if (position >= 0) position else 0
        if (data.isNotEmpty()) {
            list.scrollToPosition(target)
            list.post {
                list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                    ?: list.getChildAt(0)?.requestFocus()
            }
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {

        var items: List<Channel> = emptyList()
            private set

        fun reload(data: List<Channel>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sheet_channel, parent, false)
            return ChannelHolder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val tvNumber: TextView = view.findViewById(R.id.tvNumber)
        private val tvBadge: TextView = view.findViewById(R.id.tvBadge)
        private val tvName: TextView = view.findViewById(R.id.tvChannelName)
        private val tvPlaying: TextView = view.findViewById(R.id.tvPlaying)
        private var channelName: String = ""

        init {
            view.setOnClickListener {
                if (channelName.isNotEmpty()) callback?.onChannelSelected(channelName, currentTab)
            }
            view.setOnLongClickListener {
                if (channelName.isNotEmpty()) callback?.onFavoriteToggle(channelName)
                true
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusedChannel = channelName
                } else if (focusedChannel == channelName) {
                    focusedChannel = ""
                }
            }
        }

        fun bind(channel: Channel) {
            channelName = channel.name
            tvNumber.text = String.format("%03d", channel.number)
            tvBadge.text = channel.badge
            tvBadge.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (channel.badge.length > 2) 11f else 12f
            )
            tvBadge.background = GradientDrawable().apply {
                cornerRadius = 6f
                setColor(channel.badgeColor)
            }
            tvName.text = channel.name
            val isCurrent = channel.name == currentChannel
            tvPlaying.visibility = if (isCurrent) VISIBLE else GONE
            tvName.setTextColor(if (isCurrent) 0xFF4C9AFF.toInt() else 0xFFEDF1F7.toInt())
        }
    }
}