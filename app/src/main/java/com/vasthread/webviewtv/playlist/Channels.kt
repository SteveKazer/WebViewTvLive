package com.vasthread.webviewtv.playlist

import android.graphics.Color

/**
 * 央视频(tv/home)内置频道表。
 * 名称必须与页面频道按钮文本一致(见 assets/yangshipin.js 的归一化匹配)。
 * 页面上的付费频道(限免/VIP)一律不收录。
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

    const val CATEGORY_CCTV = 0
    const val CATEGORY_WEISHI = 1

    data class Channel(
        val name: String,      // 与页面 pill 完全一致的频道名
        val category: Int,     // 央视 / 卫视
        val badge: String,     // 台标徽章文字
        val badgeColor: Int,   // 徽章底色
    )

    private val cctvNameBadge = listOf(
        "CCTV1" to "央一", "CCTV2" to "央二", "CCTV3" to "央三", "CCTV4" to "央四",
        "CCTV5" to "央五", "CCTV5+" to "五+", "CCTV6" to "央六", "CCTV7" to "央七",
        "CCTV8" to "央八", "CCTV9" to "央九", "CCTV10" to "央十", "CCTV11" to "央十一",
        "CCTV12" to "央十二", "CCTV13" to "央十三", "CCTV14" to "央十四", "CCTV15" to "央十五",
        "CCTV16-HD" to "十六", "CCTV16(4K）" to "16 4K", "CCTV17" to "央十七",
        "CCTV4K" to "4K", "CCTV8K" to "8K", "CGTN" to "CGTN",
        "CGTN法语频道" to "法语", "CGTN俄语频道" to "俄语", "CGTN阿拉伯语频道" to "阿语",
        "CGTN西班牙语频道" to "西语", "CGTN外语纪录频道" to "纪录",
    )

    private val weishiNameBadge = listOf(
        "北京卫视" to "京", "江苏卫视" to "苏", "东方卫视" to "沪", "浙江卫视" to "浙",
        "湖南卫视" to "湘", "湖北卫视" to "鄂", "广东卫视" to "粤", "广西卫视" to "桂",
        "黑龙江卫视" to "黑", "海南卫视" to "琼", "重庆卫视" to "渝", "深圳卫视" to "深",
        "四川卫视" to "川", "河南卫视" to "豫", "福建东南卫视" to "闽", "贵州卫视" to "黔",
        "江西卫视" to "赣", "辽宁卫视" to "辽", "安徽卫视" to "皖", "河北卫视" to "冀",
        "山东卫视" to "鲁", "天津卫视" to "津", "中国教育电视台1频道" to "教", "新疆卫视" to "新",
    )

    /** 央视徽章取红金暖色系渐变 */
    private fun cctvColor(index: Int): Int {
        val hue = 4f + (index % 8) * 7f
        return Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.62f))
    }

    /** 卫视徽章取蓝青冷色系渐变 */
    private fun weishiColor(index: Int): Int {
        val hue = 185f + (index % 12) * 9f
        return Color.HSVToColor(floatArrayOf(hue, 0.64f, 0.55f))
    }

    /** 全部频道: 央视在前, 卫视在后(与页面顺序一致) */
    val ALL: List<Channel> by lazy {
        val list = mutableListOf<Channel>()
        cctvNameBadge.forEachIndexed { i, (name, badge) ->
            list.add(Channel(name, CATEGORY_CCTV, badge, cctvColor(i)))
        }
        weishiNameBadge.forEachIndexed { i, (name, badge) ->
            list.add(Channel(name, CATEGORY_WEISHI, badge, weishiColor(i)))
        }
        list
    }

    val CCTV: List<Channel> get() = ALL.filter { it.category == CATEGORY_CCTV }

    val WEISHI: List<Channel> get() = ALL.filter { it.category == CATEGORY_WEISHI }

    fun indexOf(name: String): Int = ALL.indexOfFirst { it.name == name }

    fun next(name: String, forward: Boolean): Channel {
        var index = indexOf(name)
        if (index < 0) index = 0
        return if (forward) {
            if (index + 1 >= ALL.size) ALL[0] else ALL[index + 1]
        } else {
            if (index - 1 < 0) ALL[ALL.size - 1] else ALL[index - 1]
        }
    }
}
