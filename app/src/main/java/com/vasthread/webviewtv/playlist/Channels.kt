package com.vasthread.webviewtv.playlist

/**
 * 央视频(tv/home)内置频道表。
 * 名称必须与页面频道按钮文本一致(见 assets/yangshipin.js 的归一化匹配)。
 * 页面上的付费频道(限免/VIP)一律不收录。
 *
 * 台标: assets/logos/logo_<编号>.png, 取自央视频页面官方台标图(已逐张视觉核验)。
 * 央视: CCTV1 到 CCTV8K 截止(共 21 个, 不含 CGTN 及外语频道), 按页面顺序, 数字键 1-21。
 * 卫视: 页面已有的 24 个 + 备用 14 个(陕西/吉林/云南等, 页面暂未提供,
 *       播放列表默认隐藏; 启动后 JS 检测页面频道, 检测到则自动显示)。
 *
 * 可用性: setDetected() 由注入脚本上报页面实际频道集;
 * 未上报或上报异常时, 保持默认(页面已知频道全部可见)。
 */
object Channels {

    /** 页面唯一地址: 全程只开这一个标签页 */
    const val HOME_URL = "https://www.yangshipin.cn/tv/home"

    /** 默认频道(页面初始频道, 无需点击) */
    const val DEFAULT_CHANNEL = "CCTV1"

    /** 默认清晰度(页面初始档位, 无需点击) */
    const val DEFAULT_QUALITY = "蓝光"

    /** 可选清晰度, 与页面 .bei-list 选项一致 */
    val QUALITIES = arrayOf("蓝光 1080P", "超清 720P", "高清 540P")

    // 分类: 全部=0, 央视=1, 卫视=2, 收藏=3
    const val TAB_ALL = 0
    const val TAB_CCTV = 1
    const val TAB_WEISHI = 2
    const val TAB_FAVORITE = 3

    data class Channel(
        val name: String,      // 与页面 pill 完全一致的频道名
        val category: Int,     // 央视 / 卫视
        val number: Int,       // 数字键选台编号(央视 1-21, 卫视 101-138)
        val logoAsset: String, // assets 台标图(空串=无图, 用文字徽章)
        val badge: String,     // 无台标图时的文字徽章(省份简称等)
    )

    /** 央视: CCTV1 到 CCTV8K 截止(不含 CGTN 及外语频道) */
    private val cctvDefs = listOf(
        "CCTV1" to "央1", "CCTV2" to "央2", "CCTV3" to "央3", "CCTV4" to "央4",
        "CCTV5" to "央5", "CCTV5+" to "5+", "CCTV6" to "央6", "CCTV7" to "央7",
        "CCTV8" to "央8", "CCTV9" to "央9", "CCTV10" to "央10", "CCTV11" to "央11",
        "CCTV12" to "央12", "CCTV13" to "央13", "CCTV14" to "央14", "CCTV15" to "央15",
        "CCTV16-HD" to "16HD", "CCTV16(4K）" to "16 4K", "CCTV17" to "央17",
        "CCTV4K" to "4K", "CCTV8K" to "8K",
    )

    /**
     * 卫视: 前 24 个为页面已有(顺序一致); 其后为备用频道(页面暂未提供, 检测到才显示)。
     * 编号 101-138, 与页面顺序解耦, 地区优先只影响排序。
     */
    private val weishiDefs = listOf(
        // ---- 页面已有 24 个 ----
        "北京卫视" to "京", "江苏卫视" to "苏", "东方卫视" to "沪", "浙江卫视" to "浙",
        "湖南卫视" to "湘", "湖北卫视" to "鄂", "广东卫视" to "粤", "广西卫视" to "桂",
        "黑龙江卫视" to "黑", "海南卫视" to "琼", "重庆卫视" to "渝", "深圳卫视" to "深",
        "四川卫视" to "川", "河南卫视" to "豫", "福建东南卫视" to "闽", "贵州卫视" to "黔",
        "江西卫视" to "赣", "辽宁卫视" to "辽", "安徽卫视" to "皖", "河北卫视" to "冀",
        "山东卫视" to "鲁", "天津卫视" to "津", "中国教育电视台1频道" to "教", "新疆卫视" to "新",
        // ---- 备用(页面暂未提供, 检测到自动显示) ----
        "陕西卫视" to "陕", "吉林卫视" to "吉", "云南卫视" to "滇", "山西卫视" to "晋",
        "甘肃卫视" to "甘", "内蒙古卫视" to "蒙", "宁夏卫视" to "宁", "青海卫视" to "青",
        "西藏卫视" to "藏", "兵团卫视" to "兵", "三沙卫视" to "沙", "厦门卫视" to "厦",
        "延边卫视" to "延", "大湾区卫视" to "湾",
    )

    /** 省份 -> 对应卫视(用于地区优先排序) */
    private val provinceWeishi = mapOf(
        "北京" to "北京卫视", "江苏" to "江苏卫视", "上海" to "东方卫视", "浙江" to "浙江卫视",
        "湖南" to "湖南卫视", "湖北" to "湖北卫视", "广东" to "广东卫视", "广西" to "广西卫视",
        "黑龙江" to "黑龙江卫视", "海南" to "海南卫视", "重庆" to "重庆卫视", "四川" to "四川卫视",
        "河南" to "河南卫视", "福建" to "福建东南卫视", "贵州" to "贵州卫视", "江西" to "江西卫视",
        "辽宁" to "辽宁卫视", "安徽" to "安徽卫视", "河北" to "河北卫视", "山东" to "山东卫视",
        "天津" to "天津卫视", "新疆" to "新疆卫视", "深圳" to "深圳卫视",
        "陕西" to "陕西卫视", "吉林" to "吉林卫视", "云南" to "云南卫视", "山西" to "山西卫视",
        "甘肃" to "甘肃卫视", "内蒙古" to "内蒙古卫视", "宁夏" to "宁夏卫视", "青海" to "青海卫视",
        "西藏" to "西藏卫视",
    )

    /** 央视频道(固定顺序, 编号 1-21) */
    val CCTV: List<Channel> by lazy {
        cctvDefs.mapIndexed { i, (name, badge) ->
            Channel(name, TAB_CCTV, i + 1, "logos/logo_${i + 1}.png", badge)
        }
    }

    /** 卫视频道(固定顺序, 编号 101-138; 含备用) */
    val WEISHI: List<Channel> by lazy {
        weishiDefs.mapIndexed { i, (name, badge) ->
            val hasLogo = i < 24 // 备用频道无官方台标图
            Channel(name, TAB_WEISHI, 101 + i, if (hasLogo) "logos/logo_${101 + i}.png" else "", badge)
        }
    }

    /** 全部频道(库: 央视 + 卫视含备用) */
    val ALL: List<Channel> by lazy { CCTV + WEISHI }

    // ---------------- 可用性(页面检测) ----------------

    /** null = 尚未上报, 全部已知频道可见 */
    @Volatile
    private var detectedNorms: Set<String>? = null

    /** 注入脚本上报页面实际频道(名字原文), 少于 40 个视为异常忽略 */
    fun setDetected(names: Collection<String>) {
        if (names.size < 40) return
        synchronized(this) { detectedNorms = names.map { norm(it) }.toSet() }
    }

    fun isAvailable(channel: Channel): Boolean {
        val set = detectedNorms ?: return true
        return norm(channel.name) in set
    }

    /** 名称归一化: 去空白, 全角括号转半角, 统一大写 */
    private fun norm(s: String): String = s.replace(" ", "")
        .replace("（", "(").replace("）", ")")
        .replace("［", "[").replace("］", "]")
        .uppercase()

    // ---------------- 可见列表 ----------------

    /** 可见央视频道 */
    fun cctvVisible(): List<Channel> = CCTV.filter { isAvailable(it) }

    /** 根据省份给出可见卫视列表: 本省卫视排第一, 其余保持原顺序 */
    fun weishiOrdered(province: String?): List<Channel> {
        val list = WEISHI.filter { isAvailable(it) }
        if (province.isNullOrBlank()) return list
        val local = provinceWeishi[province] ?: return list
        val target = list.firstOrNull { it.name == local } ?: return list
        return listOf(target) + list.filter { it.name != local }
    }

    /** 全部可见频道(卫视按地区优先) */
    fun allOrdered(province: String?): List<Channel> = cctvVisible() + weishiOrdered(province)

    /** 按编号查可见频道(数字键选台) */
    fun byNumber(number: Int): Channel? = ALL.firstOrNull { it.number == number && isAvailable(it) }

    fun indexOf(name: String): Int = ALL.indexOfFirst { it.name == name }

    /** 收藏列表(按全部频道规范顺序过滤, 仅含可见频道) */
    fun favorites(favoriteNames: Set<String>, province: String?): List<Channel> =
        allOrdered(province).filter { it.name in favoriteNames }

    /** 顺序切换: 在当前播放列表内循环 */
    fun nextInList(list: List<Channel>, current: String, forward: Boolean): Channel? {
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.name == current }
        return if (index < 0) list.first()
        else if (forward) list[(index + 1) % list.size]
        else list[(index - 1 + list.size) % list.size]
    }
}
