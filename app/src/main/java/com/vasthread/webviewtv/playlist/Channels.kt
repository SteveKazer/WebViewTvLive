package com.vasthread.webviewtv.playlist

/**
 * 央视频(tv/home)内置频道表。
 * 名称必须与页面频道按钮文本一致(见 assets/yangshipin.js 的归一化匹配)。
 * 页面上的付费频道(限免/VIP)一律不收录。
 *
 * 台标: assets/logos/logo_<编号>.png, 取自央视频页面官方台标图。
 * 央视: 全部免费频道按页面顺序(数字键 1-27)。
 * 卫视: 固定编号 101-124(与显示顺序解耦, 地区优先只影响排序)。
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
        val number: Int,       // 数字键选台编号(央视 1-27, 卫视 101-124)
        val logoAsset: String, // assets/logos 下的台标图文件名
    )

    /** 央视: 页面全部免费频道, 按页面顺序 */
    private val cctvDefs = listOf(
        "CCTV1", "CCTV2", "CCTV3", "CCTV4", "CCTV5", "CCTV5+",
        "CCTV6", "CCTV7", "CCTV8", "CCTV9", "CCTV10", "CCTV11",
        "CCTV12", "CCTV13", "CCTV14", "CCTV15", "CCTV16-HD", "CCTV16(4K）",
        "CCTV17", "CCTV4K", "CCTV8K", "CGTN", "CGTN法语频道", "CGTN俄语频道",
        "CGTN阿拉伯语频道", "CGTN西班牙语频道", "CGTN外语纪录频道",
    )

    /** 卫视: 页面顺序, 编号 101 起 */
    private val weishiDefs = listOf(
        "北京卫视", "江苏卫视", "东方卫视", "浙江卫视", "湖南卫视", "湖北卫视",
        "广东卫视", "广西卫视", "黑龙江卫视", "海南卫视", "重庆卫视", "深圳卫视",
        "四川卫视", "河南卫视", "福建东南卫视", "贵州卫视", "江西卫视", "辽宁卫视",
        "安徽卫视", "河北卫视", "山东卫视", "天津卫视", "中国教育电视台1频道", "新疆卫视",
    )

    /** 省份 -> 对应卫视(用于地区优先排序) */
    private val provinceWeishi = mapOf(
        "北京" to "北京卫视", "江苏" to "江苏卫视", "上海" to "东方卫视", "浙江" to "浙江卫视",
        "湖南" to "湖南卫视", "湖北" to "湖北卫视", "广东" to "广东卫视", "广西" to "广西卫视",
        "黑龙江" to "黑龙江卫视", "海南" to "海南卫视", "重庆" to "重庆卫视", "四川" to "四川卫视",
        "河南" to "河南卫视", "福建" to "福建东南卫视", "贵州" to "贵州卫视", "江西" to "江西卫视",
        "辽宁" to "辽宁卫视", "安徽" to "安徽卫视", "河北" to "河北卫视", "山东" to "山东卫视",
        "天津" to "天津卫视", "新疆" to "新疆卫视", "深圳" to "深圳卫视",
    )

    /** 央视频道(固定顺序, 编号 1-27) */
    val CCTV: List<Channel> by lazy {
        cctvDefs.mapIndexed { i, name ->
            Channel(name, TAB_CCTV, i + 1, "logos/logo_${i + 1}.png")
        }
    }

    /** 卫视频道(固定顺序, 编号 101-124) */
    val WEISHI: List<Channel> by lazy {
        weishiDefs.mapIndexed { i, name ->
            Channel(name, TAB_WEISHI, 101 + i, "logos/logo_${101 + i}.png")
        }
    }

    /** 全部频道(央视 + 卫视) */
    val ALL: List<Channel> by lazy { CCTV + WEISHI }

    /** 按编号查频道(数字键选台) */
    fun byNumber(number: Int): Channel? = ALL.firstOrNull { it.number == number }

    fun indexOf(name: String): Int = ALL.indexOfFirst { it.name == name }

    /** 根据省份给出卫视列表: 本省卫视排第一, 其余保持原顺序 */
    fun weishiOrdered(province: String?): List<Channel> {
        if (province.isNullOrBlank()) return WEISHI
        val local = provinceWeishi[province] ?: return WEISHI
        val target = WEISHI.firstOrNull { it.name == local } ?: return WEISHI
        return listOf(target) + WEISHI.filter { it.name != local }
    }

    /** 全部频道(卫视按地区优先) */
    fun allOrdered(province: String?): List<Channel> = CCTV + weishiOrdered(province)

    /** 某个分类对应的频道列表(收藏传空, 由调用方过滤) */
    fun listFor(tab: Int, province: String?): List<Channel> = when (tab) {
        TAB_CCTV -> CCTV
        TAB_WEISHI -> weishiOrdered(province)
        TAB_FAVORITE -> emptyList()
        else -> allOrdered(province)
    }

    /** 收藏列表(按全部频道规范顺序过滤) */
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
