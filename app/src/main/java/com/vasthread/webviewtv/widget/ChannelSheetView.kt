package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vasthread.webviewtv.playlist.Channels
import com.vasthread.webviewtv.playlist.Channels.Channel
import kotlin.math.min

/**
 * 频道列表弹窗(左侧抽屉式, 程序化自适应布局)。
 *
 * 左侧分类: 全部 / 央视 / 卫视 / ★收藏(底部, 可滚动)
 * 右侧频道: 数字键编号 + 台标 + 名称 + 播放中标识
 * 尺寸按屏幕真实像素计算, 手机与电视一致体验。
 *
 * 手机触摸: 点击选台, 长按收藏
 * 电视遥控: 十字键导航 + OK 选台 + 长按 OK 收藏
 */
class ChannelSheetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        // 影院深色系
        private const val COLOR_ACCENT = 0xFFF2B33D.toInt()     // 琥珀强调色
        private const val COLOR_TEXT = 0xFFF2F4F7.toInt()       // 主文本
        private const val COLOR_SUB = 0xFF98A2B3.toInt()        // 次文本
        private const val COLOR_MUTED = 0xFF6B7484.toInt()      // 弱文本
        private const val COLOR_PANEL = 0xF513161B.toInt()      // 面板底
        private const val COLOR_RAIL = 0xFF181C22.toInt()       // 分类栏底
        private const val COLOR_CHIP = 0xFF22262E.toInt()       // 编号底
        private const val COLOR_DIM = 0xB3000000.toInt()        // 遮罩
    }

    interface Callback {
        fun onChannelSelected(name: String, tab: Int)
        fun onFavoriteToggle(name: String)
    }

    private val uiScale: Float

    private val rail: LinearLayout
    private val tabAll: TextView
    private val tabCctv: TextView
    private val tabWeishi: TextView
    private val tabFavorite: TextView
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
        isClickable = true
        isFocusable = false
        val dm = context.resources.displayMetrics
        uiScale = min(dm.heightPixels / 720f, dm.widthPixels / 1280f).coerceAtLeast(0.75f)

        // 半透明遮罩, 点击关闭
        val dim = View(context)
        dim.setBackgroundColor(COLOR_DIM)
        dim.setOnClickListener { dismiss() }
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 左侧抽屉面板
        val panelW = (dm.widthPixels * 0.66f).toInt()
        val panelH = (dm.heightPixels * 0.88f).toInt()
        val railW = (panelW * 0.30f).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(COLOR_PANEL, 18f * uiScale)
        }
        val panelLp = LayoutParams(panelW, panelH, Gravity.START or Gravity.CENTER_VERTICAL)
        panelLp.leftMargin = (14f * uiScale).toInt()
        addView(panel, panelLp)

        // ---- 左侧分类栏: 上方分类可滚动, 底部收藏固定 ----
        rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedLeft(COLOR_RAIL, 18f * uiScale)
            setPadding(0, (14 * uiScale).toInt(), 0, (10 * uiScale).toInt())
        }
        panel.addView(rail, LinearLayout.LayoutParams(railW, LinearLayout.LayoutParams.MATCH_PARENT))

        val tabsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val railScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(tabsBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        rail.addView(railScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        tabsBox.addView(TextView(context).apply {
            text = "频道列表"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * uiScale)
            setPadding((14 * uiScale).toInt(), 0, 0, (10 * uiScale).toInt())
            letterSpacing = 0.08f
        })

        tabAll = createTab("全部")
        tabCctv = createTab("央视")
        tabWeishi = createTab("卫视")
        tabsBox.addView(tabAll)
        tabsBox.addView(tabCctv)
        tabsBox.addView(tabWeishi)

        // 收藏固定底部
        tabFavorite = createTab("★ 收藏")
        tabFavorite.setOnClickListener { setTab(Channels.TAB_FAVORITE) }
        rail.addView(tabFavorite)

        tabAll.setOnClickListener { setTab(Channels.TAB_ALL) }
        tabCctv.setOnClickListener { setTab(Channels.TAB_CCTV) }
        tabWeishi.setOnClickListener { setTab(Channels.TAB_WEISHI) }

        // ---- 右侧内容区 ----
        val content = FrameLayout(context)
        panel.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        listTitle = TextView(context).apply {
            setTextColor(COLOR_SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * uiScale)
            setPadding((14 * uiScale).toInt(), (14 * uiScale).toInt(), (14 * uiScale).toInt(), (8 * uiScale).toInt())
        }
        content.addView(listTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ChannelSheetView.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding((10 * uiScale).toInt(), 0, (10 * uiScale).toInt(), (10 * uiScale).toInt())
        }
        val listLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        listLp.topMargin = (36 * uiScale).toInt()
        content.addView(list, listLp)

        emptyHint = TextView(context).apply {
            text = "收藏夹还是空的\n长按频道即可加入收藏"
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * uiScale)
            gravity = Gravity.CENTER
            setLineSpacing(6f * uiScale, 1f)
            visibility = View.GONE
        }
        content.addView(emptyHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    // ---------------- 对外 API ----------------

    fun dismiss() {
        visibility = GONE
        onDismissCallback?.invoke()
    }

    fun open(tab: Int, channel: String, favorites: Set<String>, province: String?) {
        this.currentChannel = channel
        this.favorites = favorites
        this.province = province
        currentTab = if (tab == Channels.TAB_FAVORITE && favorites.isEmpty()) Channels.TAB_ALL else tab
        applyTab()
        visibility = VISIBLE
        post { focusCurrent() }
    }

    fun setCurrent(channel: String) {
        currentChannel = channel
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

    /** 当前分类对应的频道列表(顺序换台上下文) */
    fun channelsOf(tab: Int): List<Channel> = when (tab) {
        Channels.TAB_CCTV -> Channels.CCTV
        Channels.TAB_WEISHI -> Channels.weishiOrdered(province)
        Channels.TAB_FAVORITE -> Channels.favorites(favorites, province)
        else -> Channels.allOrdered(province)
    }

    /** 当前聚焦频道名(空串=焦点不在频道上) */
    fun focusedChannel(): String = focusedChannel

    /** 电视: OK 键选中当前聚焦频道 */
    fun selectFocused() {
        if (focusedChannel.isNotEmpty()) callback?.onChannelSelected(focusedChannel, currentTab)
    }

    // ---------------- 内部实现 ----------------

    private fun createTab(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(COLOR_SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * uiScale)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding((16 * uiScale).toInt(), 0, 0, 0)
            background = itemBackground(10f * uiScale)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (46f * uiScale).toInt()
            ).apply { setMargins((8 * uiScale).toInt(), 0, (8 * uiScale).toInt(), (3 * uiScale).toInt()) }
        }
    }

    private fun setTab(tab: Int) {
        if (tab == Channels.TAB_FAVORITE && favorites.isEmpty()) return
        currentTab = tab
        applyTab()
        post { focusCurrent() }
    }

    private fun applyTab() {
        tabAll.setTextColor(if (currentTab == Channels.TAB_ALL) COLOR_ACCENT else COLOR_SUB)
        tabCctv.setTextColor(if (currentTab == Channels.TAB_CCTV) COLOR_ACCENT else COLOR_SUB)
        tabWeishi.setTextColor(if (currentTab == Channels.TAB_WEISHI) COLOR_ACCENT else COLOR_SUB)
        tabFavorite.setTextColor(if (currentTab == Channels.TAB_FAVORITE) COLOR_ACCENT else COLOR_SUB)
        tabAll.alpha = if (currentTab == Channels.TAB_ALL) 1f else 0.85f
        tabCctv.alpha = if (currentTab == Channels.TAB_CCTV) 1f else 0.85f
        tabWeishi.alpha = if (currentTab == Channels.TAB_WEISHI) 1f else 0.85f
        tabFavorite.alpha = if (currentTab == Channels.TAB_FAVORITE) 0.85f else 0.85f

        tabAll.text = "全部 ${Channels.ALL.size}"
        tabCctv.text = "央视 ${Channels.CCTV.size}"
        tabWeishi.text = "卫视 ${Channels.WEISHI.size}"
        tabFavorite.text = "★ 收藏 ${favorites.size}"

        val data = channelsOf(currentTab)
        listTitle.text = when (currentTab) {
            Channels.TAB_CCTV -> "央视频道 · 数字键 1-14"
            Channels.TAB_WEISHI -> "卫视频道 · 本省优先 · 数字键 101-124"
            Channels.TAB_FAVORITE -> "我的收藏 · 上下换台仅在本列表内"
            else -> "全部频道 · 数字键直接选台"
        }
        val empty = currentTab == Channels.TAB_FAVORITE && data.isEmpty()
        emptyHint.visibility = if (empty) VISIBLE else GONE
        list.visibility = if (empty) INVISIBLE else VISIBLE
        adapter.reload(data)
        // 分类栏滚动到选中项
        tabFavorite.post {
            val target = when (currentTab) {
                Channels.TAB_CCTV, Channels.TAB_WEISHI -> tabWeishi
                else -> tabAll
            }
            (target.parent as? View)?.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, target.width, target.height), false
            )
        }
    }

    private fun focusCurrent() {
        val data = adapter.items
        if (data.isEmpty()) return
        val position = data.indexOfFirst { it.name == currentChannel }.coerceAtLeast(0)
        list.scrollToPosition(position)
        list.post {
            list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                ?: list.getChildAt(0)?.requestFocus()
        }
    }

    // ---------------- 样式助手 ----------------

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
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
            addState(intArrayOf(android.R.attr.state_selected), shape(0x1FF2B33D))
            addState(intArrayOf(), shape(Color.TRANSPARENT))
        }
    }

    // ---------------- 列表 ----------------

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {

        var items: List<Channel> = emptyList()
            private set

        fun reload(data: List<Channel>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
            return ChannelHolder(buildRow())
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    /** 程序化构建频道行 */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = itemBackground(8f * uiScale)
            setPadding((6 * uiScale).toInt(), 0, (10 * uiScale).toInt(), 0)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, (46f * uiScale).toInt()
            ).apply { bottomMargin = (4 * uiScale).toInt() }
        }

        val number = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(COLOR_SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * uiScale)
            background = rounded(COLOR_CHIP, 5f * uiScale)
            layoutParams = LinearLayout.LayoutParams((44f * uiScale).toInt(), (23f * uiScale).toInt())
            tag = "number"
        }
        row.addView(number)

        val badge = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * uiScale)
            layoutParams = LinearLayout.LayoutParams((38f * uiScale).toInt(), (23f * uiScale).toInt()).apply {
                leftMargin = (8 * uiScale).toInt()
            }
            tag = "badge"
        }
        row.addView(badge)

        val name = TextView(context).apply {
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * uiScale)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (10 * uiScale).toInt()
                rightMargin = (6 * uiScale).toInt()
            }
            tag = "name"
        }
        row.addView(name)

        val playing = TextView(context).apply {
            text = "播放中"
            setTextColor(COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 10f * uiScale)
            visibility = View.GONE
            tag = "playing"
        }
        row.addView(playing)

        return row
    }

    private inner class ChannelHolder(private val row: View) : RecyclerView.ViewHolder(row) {

        private val tvNumber: TextView = row.findViewWithTag("number")
        private val tvBadge: TextView = row.findViewWithTag("badge")
        private val tvName: TextView = row.findViewWithTag("name")
        private val tvPlaying: TextView = row.findViewWithTag("playing")
        private var channelName: String = ""

        init {
            row.setOnClickListener {
                if (channelName.isNotEmpty()) callback?.onChannelSelected(channelName, currentTab)
            }
            row.setOnLongClickListener {
                if (channelName.isNotEmpty()) callback?.onFavoriteToggle(channelName)
                true
            }
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusedChannel = channelName
                    tvNumber.setTextColor(0xFF1A1A1A.toInt())
                    tvName.setTextColor(0xFF1A1A1A.toInt())
                    tvPlaying.setTextColor(0xFF1A1A1A.toInt())
                } else {
                    if (focusedChannel == channelName) focusedChannel = ""
                    tvNumber.setTextColor(COLOR_SUB)
                    tvName.setTextColor(if (channelName == currentChannel) COLOR_ACCENT else COLOR_TEXT)
                    tvPlaying.setTextColor(COLOR_ACCENT)
                }
            }
        }

        fun bind(channel: Channel) {
            channelName = channel.name
            tvNumber.text = String.format("%03d", channel.number)
            tvBadge.text = channel.badge
            tvBadge.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                (if (channel.badge.length > 2) 9f else 11f) * uiScale
            )
            tvBadge.background = rounded(channel.badgeColor, 5f * uiScale)
            tvName.text = channel.name
            val isCurrent = channel.name == currentChannel
            tvPlaying.visibility = if (isCurrent) VISIBLE else GONE
            tvName.setTextColor(if (isCurrent) COLOR_ACCENT else COLOR_TEXT)
        }
    }
}