package com.vasthread.webviewtv.misc

import android.content.Context

/**
 * 播放内核偏好(v2.4.6 双内核)。
 *
 * 0 = 系统内置内核(Android WebView / Chromium, 默认)
 * 1 = 腾讯 X5 内核(tbssdk 运行时下载, 老设备兼容路径)
 *
 * 选择后需重启应用生效: Application 启动时据此决定是否初始化 X5,
 * ChannelPlayerView 据此创建对应的 WebView 实现。
 */
object WebKernel {

    const val KEY_WEB_KERNEL = "web_kernel"

    /** X5 内核是否可用(启动初始化回调更新; 未选 X5 时恒为 false) */
    @Volatile
    var x5Ready: Boolean = false

    /** X5 内核下载进度(-1=无下载/未知, 0..100=下载中) */
    @Volatile
    var x5Progress: Int = -1

    fun isX5(context: Context): Boolean =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getInt(KEY_WEB_KERNEL, 0) == 1

    fun setX5(context: Context, x5: Boolean) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putInt(KEY_WEB_KERNEL, if (x5) 1 else 0).apply()
    }
}