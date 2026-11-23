package com.vasthread.webviewtv.widget

import android.widget.FrameLayout

/**
 * 播放 WebView 公共接口(v2.4.6 双内核)。
 *
 * 系统内核(WebpageAdapterWebView)与腾讯 X5 内核(X5WebpageAdapterWebView)
 * 各自继承不同的 WebView 基类, 无法共享父类; 上层(ChannelPlayerView)
 * 通过本接口创建与驱动, 与具体内核解耦。
 */
interface LiveWebView {

    companion object {
        /** 唯一播放页: 全程只用一个标签页 */
        const val URL_HOME = "https://www.yangshipin.cn/tv/home"
    }

    var fullscreenContainer: FrameLayout

    /** 页面脚本注入完成 */
    var onScriptReady: (() -> Unit)?

    /** 首帧就绪(视频起播且沉浸式样式已应用) */
    var onFirstFrame: (() -> Unit)?

    /** 页面加载完成(每次进度 100) */
    var onPageReady: (() -> Unit)?

    /** 页面频道检测上报(注入脚本扫描页面频道 pill 后回调) */
    var onChannelsDetected: ((String) -> Unit)?

    /** JS 发起的切流开始(页面自行切清晰度等), 提示上层盖切流遮罩 */
    var onStreamSwitchBegin: (() -> Unit)?

    /** 每次切流后视频真正恢复播放, 提示上层揭幕 */
    var onPlaybackResumed: (() -> Unit)?

    /** 页面出现版权限制提示, 上层应立即揭幕让用户看到版权界面 */
    var onCopyrightBlocked: (() -> Unit)?

    /** 播放卡顿(缓冲中), 提示用户尝试更换清晰度 */
    var onPlaybackStutter: (() -> Unit)?

    fun loadLiveUrl(url: String)

    /** 换台: resumeQuality=换台后跟随恢复的清晰度(页面切台会重置回蓝光) */
    fun switchChannel(name: String, resumeQuality: String = "")

    /** 设置清晰度 */
    fun setQuality(qualityLabel: String)

    /** 启动恢复: 切换到上次频道+上次清晰度 */
    fun restore(channel: String, quality: String)

    fun pauseVideo()

    fun playVideo()

    fun evaluate(js: String, callback: ((String) -> Unit)? = null)

    /**
     * 无图模式(v2.4.9): enabled=false 时在内核层禁用图片加载
     * (loadsImagesAutomatically=false + blockNetworkImage=true),
     * 图片请求完全不发出, 加载提速远大于 JS 隐藏;
     * 参照 CCTV_Viewer 的实测做法。
     */
    fun setImagesEnabled(enabled: Boolean)
}