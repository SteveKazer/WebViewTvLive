package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.WebKernel
import com.vasthread.webviewtv.misc.preference

/**
 * 播放器视图: 启动遮罩 + 防误触遮罩 + 映射点击。
 *
 * 触摸:
 *   - 单击屏幕左侧 30%: 唤起频道列表
 *   - 单击屏幕右侧 30%: 唤起清晰度(全高右抽屉, 底部含设置占位)
 *   - 中间 40% 不响应, 避免误触
 *   - 向上滑动: 下一个频道; 向下滑动: 上一个频道
 *   触摸不透传给网页。
 * 电视: 按键不透传给网页, 全部交由 MainActivity 处理。
 */
class ChannelPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val SWIPE_VELOCITY_THRESHOLD = 900f
        private const val SWIPE_DISTANCE_THRESHOLD = 60f
        private const val LOADING_SAFETY_TIMEOUT = 30000L
        private const val SWITCH_COVER_SAFETY_TIMEOUT = 15000L
        /** 与 MainActivity.KEY_HW_RENDER 同名(硬件加速渲染偏好, WebView 创建时读取) */
        private const val PREF_HW_RENDER = "hw_render"
    }

    // ---------------- 系统级切流静音锁(v2.4.9) ----------------
    // 页面切流时旧流音频实例可能未销毁, 元素级 muted 压不住"残留声音"
    // (实测小米 G27 Pro: 画面已是新台, 声音还是旧台)。切流开始直接静音
    // 系统音乐流(AudioManager.STREAM_MUSIC), 首帧确认揭幕时恢复切流前
    // 状态 —— 不管声音来自哪个元素/哪条流都彻底静音。
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var sysMutedByUs = false
    private var sysMutedBefore = false

    private fun isSysStreamMuted(): Boolean =
        if (Build.VERSION.SDK_INT >= 23) {
            try { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) } catch (t: Throwable) { false }
        } else false

    private fun setSysStreamMuted(mute: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, mute)
            }
        } catch (t: Throwable) { /* ignore */ }
    }

    /** 切流开始: 静音系统音乐流(记录切流前的用户静音状态, 不覆盖) */
    private fun lockSystemAudio() {
        if (!sysMutedByUs) {
            sysMutedBefore = isSysStreamMuted()
            sysMutedByUs = true
        }
        if (!isSysStreamMuted()) { setSysStreamMuted(true) }
    }

    /** 切流结束(揭幕): 恢复切流前的静音状态 */
    private fun unlockSystemAudio() {
        if (!sysMutedByUs) { return }
        sysMutedByUs = false
        if (isSysStreamMuted() && !sysMutedBefore) { setSysStreamMuted(false) }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前播放内核(系统 WebView / 腾讯 X5), 由设置决定 */
    val webView: LiveWebView
    private val webViewImpl: View
    private val fullscreenContainer: FrameLayout
    private val loadingOverlay: View
    private val switchCover: View
    private val hideSwitchCoverAction = Runnable { hideSwitchCover() }

    /** 当前节目是否处于版权受限状态(JS 检测命中; 换台/重新恢复时重置) */
    private var copyrightBlocked = false

    /** 单击左半边 -> 频道列表 */
    var leftTapCallback: (() -> Unit)? = null

    /** 单击右半边 -> 清晰度 */
    var rightTapCallback: (() -> Unit)? = null

    /** 向上滑动 -> 下一个频道 */
    var swipeNextCallback: (() -> Unit)? = null

    /** 向下滑动 -> 上一个频道 */
    var swipePrevCallback: (() -> Unit)? = null

    /** 注入脚本就绪 */
    var onScriptReady: (() -> Unit)? = null

    /** 页面加载完成 */
    var onPageReady: (() -> Unit)? = null

    /** 页面频道检测上报(JSON 数组字符串) */
    var onChannelsDetected: ((String) -> Unit)? = null

    /** 首帧就绪(视频起播且沉浸式样式已应用) */
    var onFirstFrame: (() -> Unit)? = null

    /** 每次切流后视频真正恢复播放(JS 判定 rs>=3 且未暂停) */
    var onPlaybackResumed: (() -> Unit)? = null

    /** 页面出现版权限制提示(已自动揭幕, 供上层做额外提示) */
    var onCopyrightBlocked: (() -> Unit)? = null

    /** 播放持续卡顿, 上层底部提示用户更换清晰度 */
    var onPlaybackStutter: (() -> Unit)? = null

    private var started = false
    private var firstFrame = false

    private val hideLoadingAction = Runnable { hideLoading() }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.OnGestureListener {

        override fun onDown(e: MotionEvent) = true

        override fun onShowPress(e: MotionEvent) = Unit

        @SuppressLint("ClickableViewAccessibility")
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (firstFrame) {
                // 左侧 30% -> 频道列表; 右侧 30% -> 清晰度; 中间 40% 不响应
                val edge = width * 0.30f
                if (e.x < edge) leftTapCallback?.invoke()
                else if (e.x > width - edge) rightTapCallback?.invoke()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float) = false

        override fun onLongPress(e: MotionEvent) = Unit

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!firstFrame || e1 == null || e2 == null) return false
            val dy = e2.y - e1.y
            val absDy = Math.abs(dy)
            val absDx = Math.abs(e2.x - e1.x)
            if (absDy < SWIPE_DISTANCE_THRESHOLD || absDy < absDx) return false
            val fastEnough = Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD || absDy > 160f
            if (!fastEnough) return false
            if (dy < 0) {
                // 手指向上滑 -> 往下切换(下一个)
                swipeNextCallback?.invoke()
            } else {
                // 手指向下滑 -> 往上切换(上一个)
                swipePrevCallback?.invoke()
            }
            return true
        }
    })

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        LayoutInflater.from(context).inflate(R.layout.widget_channel_player, this)
        setBackgroundColor(Color.BLACK)
        // 启动加载画面显示当前内核(常驻小字, 加载多久显示多久)
        findViewById<TextView>(R.id.tvKernel).text =
            if (WebKernel.isX5(context)) "当前内核：腾讯 X5" else "当前内核：系统 WebView"
        // v2.4.6 双内核: 按设置创建系统 WebView 或腾讯 X5 实例
        val host = findViewById<FrameLayout>(R.id.webViewHost)
        val wv: LiveWebView = if (WebKernel.isX5(context)) {
            X5WebpageAdapterWebView(context)
        } else {
            WebpageAdapterWebView(context)
        }
        webView = wv
        webViewImpl = wv as View
        host.addView(webViewImpl, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 硬件加速渲染: 跟随偏好应用(LAYER_TYPE_NONE=GPU 渲染 / SOFTWARE=软渲染兑底)
        if (!preference.getBoolean(PREF_HW_RENDER, true)) { applyHwRender(false) }
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        switchCover = findViewById(R.id.switchCover)
        wv.apply {
            this.fullscreenContainer = this@ChannelPlayerView.fullscreenContainer
            onScriptReady = { this@ChannelPlayerView.onScriptReady?.invoke() }
            onPageReady = { this@ChannelPlayerView.onPageReady?.invoke() }
            onChannelsDetected = { json -> this@ChannelPlayerView.onChannelsDetected?.invoke(json) }
            onFirstFrame = {
                this@ChannelPlayerView.firstFrame = true
                hideLoading()
                this@ChannelPlayerView.onFirstFrame?.invoke()
            }
            // JS 看门狗自动降档等场景: 切流开始 -> 盖黑幕
            onStreamSwitchBegin = { this@ChannelPlayerView.showSwitchCover() }
            // 视频真正起播 -> 揭幕
            onPlaybackResumed = {
                this@ChannelPlayerView.hideSwitchCover()
                this@ChannelPlayerView.onPlaybackResumed?.invoke()
            }
            // 版权限制: 立即揭幕, 展示页面版权提示(direct show, 不等起播)
            // 同时解开启动遮罩并放行手势(否则被盖住/无法唤起换台面板)
            // v2.4.9: 版权节目无音源, 系统静音锁在此恢复(避免残留)
            onCopyrightBlocked = {
                copyrightBlocked = true
                mainHandler.removeCallbacks(hideSwitchCoverAction)
                switchCover.animate().cancel()
                switchCover.visibility = View.GONE
                switchCover.alpha = 1f
                this@ChannelPlayerView.firstFrame = true
                this@ChannelPlayerView.hideLoading()
                this@ChannelPlayerView.unlockSystemAudio()
                this@ChannelPlayerView.onCopyrightBlocked?.invoke()
            }
            onPlaybackStutter = { this@ChannelPlayerView.onPlaybackStutter?.invoke() }
        }
    }

    /** 打开软件: 加载央视频页面(整个生命周期只加载一次) */
    fun start() {
        if (started) return
        started = true
        showLoading()
        webView.loadLiveUrl(LiveWebView.URL_HOME)
    }

    /** 显示启动遮罩 */
    fun showLoading() {
        mainHandler.removeCallbacks(hideLoadingAction)
        loadingOverlay.animate().cancel()
        loadingOverlay.alpha = 1f
        loadingOverlay.visibility = View.VISIBLE
        // 保险: 即使首帧异常也不永远黑屏
        mainHandler.postDelayed(hideLoadingAction, LOADING_SAFETY_TIMEOUT)
    }

    /** 隐藏启动遮罩(淡出) */
    fun hideLoading() {
        if (loadingOverlay.visibility != View.VISIBLE) return
        mainHandler.removeCallbacks(hideLoadingAction)
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(320)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
                loadingOverlay.alpha = 1f
            }
            .start()
    }

    /**
     * 显示切流遮罩: 换台/切清晰度全程纯黑盖住 WebView,
     * 防止设备端 WebView 在视频表面渲染原生占位图形(黑色大三角)。
     * 若播放恢复通知丢失, 15 秒后自动揭幕兑底。
     * v2.4.9: 同时静音系统音乐流(声音残留兑底)
     */
    fun showSwitchCover() {
        lockSystemAudio()
        mainHandler.removeCallbacks(hideSwitchCoverAction)
        switchCover.animate().cancel()
        switchCover.alpha = 1f
        switchCover.visibility = View.VISIBLE
        mainHandler.postDelayed(hideSwitchCoverAction, SWITCH_COVER_SAFETY_TIMEOUT)
    }

    /** 收到"视频真正起播"后揭幕(快速淡出), 并恢复切流前音频状态 */
    fun hideSwitchCover() {
        unlockSystemAudio()
        mainHandler.removeCallbacks(hideSwitchCoverAction)
        if (switchCover.visibility != View.VISIBLE) return
        switchCover.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                switchCover.visibility = View.GONE
                switchCover.alpha = 1f
            }
            .start()
    }

    /** 换台: 全程黑幕; resumeQuality=换台后跟随恢复的清晰度(页面切台会重置回蓝光) */
    fun switchChannel(name: String, resumeQuality: String = "") {
        copyrightBlocked = false
        showSwitchCover()
        webView.switchChannel(name, resumeQuality)
    }

    /** 切清晰度: 全程黑幕(v2.4.4, JS 侧 beginStreamSwitch 立即通知盖黑幕) */
    fun setQuality(qualityLabel: String) {
        showSwitchCover()
        webView.setQuality(qualityLabel)
    }

    fun restore(channel: String, quality: String) {
        copyrightBlocked = false
        webView.restore(channel, quality)
    }

    /** 版权受限态下清晰度切换被禁止(供上层拦截, 避免卡"正在切换中") */
    fun isCopyrightBlocked(): Boolean = copyrightBlocked

    /**
     * 硬件加速渲染(v2.4.9): enabled=true 清除图层覆盖(跟随窗口默认 GPU 渲染);
     * enabled=false 切软件渲染(CPU, 部分异常设备的兼容兑底, 流畅度会下降)。
     * 视频解码本身由内核自动选硬解(MediaCodec/GPU), 需 GPU 渲染管线配合输出,
     * 关闭后画面输出改走 CPU, 个别设备可解决花屏/黑纹等问题。即时生效。
     */
    fun applyHwRender(enabled: Boolean) {
        webViewImpl.setLayerType(
            if (enabled) View.LAYER_TYPE_NONE else View.LAYER_TYPE_SOFTWARE,
            null
        )
    }

    override fun onDetachedFromWindow() {
        // 防残留: 视图销毁时若系统静音仍由我们持有, 恢复切流前状态
        unlockSystemAudio()
        super.onDetachedFromWindow()
    }

    fun pauseVideo() = webView.pauseVideo()

    fun playVideo() = webView.playVideo()

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        return webViewImpl.requestFocus(direction, previouslyFocusedRect)
    }

    /** 遮罩: 触摸一律不透传 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /** 遮罩: 按键一律不透传, 由 MainActivity 统一处理 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return false
    }
}