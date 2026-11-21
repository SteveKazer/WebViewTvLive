package com.vasthread.webviewtv.misc

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics

/**
 * 统一的自适应尺寸体系(替代 AndroidAutoSize 与像素比例硬编码)。
 *
 * 设计尺寸一律以 dp 给出, 按设备类别放大:
 *   手机(dp 宽 < 600):   1.00x —— 标准安卓密度缩放
 *   平板(dp 宽 >= 600):  1.12x
 *   电视/大屏(dp 宽 >= 840 或 UI 模式为 TELEVISION): 1.30x
 *
 * 电视观看距离远, 同样的 dp 尺寸在电视上视觉更小, 因此需要额外倍率。
 * 面板宽度等"占比"尺寸用屏宽百分比 + dp 上下限钳制,
 * 保证手机不超宽、电视不缩成一条。
 */
class UiScale(context: Context) {

    private val dm: DisplayMetrics = context.resources.displayMetrics

    /** 物理像素宽(横屏即长边) */
    val screenWidth: Int = dm.widthPixels

    /** 密度(dp -> px) */
    val density: Float = dm.density

    /** dp 宽度(设备类别判定依据) */
    private val dpWidth: Float = screenWidth / density

    /** 是否电视设备(UI 模式或大 dp 宽) */
    val isTv: Boolean = isTelevision(context) || dpWidth >= 840f

    /** 是否平板 */
    val isTablet: Boolean = !isTv && dpWidth >= 600f

    /** 设备类别倍率 */
    val classMult: Float = when {
        isTv -> 1.30f
        isTablet -> 1.12f
        else -> 1.00f
    }

    /** 设计 dp -> 像素(整数) */
    fun dp(designDp: Float): Int = (designDp * classMult * density + 0.5f).toInt()

    /** 设计字号 -> 像素(Float, 供 setTextSize(COMPLEX_UNIT_PX) 使用) */
    fun sp(designSp: Float): Float = designSp * classMult * density

    /** 屏宽占比, 钳制在 [minDp, maxDp] 像素区间内 */
    fun widthOf(fraction: Float, minDp: Float, maxDp: Float): Int {
        val raw = (screenWidth * fraction).toInt()
        return raw.coerceIn(dp(minDp), dp(maxDp))
    }

    /** 屏宽占比 -> Int */
    fun pct(fraction: Float): Int = (screenWidth * fraction).toInt()

    private fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
