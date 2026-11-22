package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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
import com.vasthread.webviewtv.R

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
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    val webView: WebpageAdapterWebView
    private val fullscreenContainer: FrameLayout
    private val loadingOverlay: View
    private val switchCover: View
    private val hideSwitchCoverAction = Runnable { hideSwitchCover() }

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
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        switchCover = findViewById(R.id.switchCover)
        webView.apply {
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
        }
    }

    /** 打开软件: 加载央视频页面(整个生命周期只加载一次) */
    fun start() {
        if (started) return
        started = true
        showLoading()
        webView.loadUrl(WebpageAdapterWebView.URL_HOME)
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
     */
    fun showSwitchCover() {
        mainHandler.removeCallbacks(hideSwitchCoverAction)
        switchCover.animate().cancel()
        switchCover.alpha = 1f
        switchCover.visibility = View.VISIBLE
        mainHandler.postDelayed(hideSwitchCoverAction, SWITCH_COVER_SAFETY_TIMEOUT)
    }

    /** 收到"视频真正起播"后揭幕(快速淡出) */
    fun hideSwitchCover() {
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
        showSwitchCover()
        webView.switchChannel(name, resumeQuality)
    }

    /** 切清晰度: 全程黑幕(v2.4.4, JS 侧 beginStreamSwitch 立即通知盖黑幕) */
    fun setQuality(qualityLabel: String) {
        showSwitchCover()
        webView.setQuality(qualityLabel)
    }

    fun restore(channel: String, quality: String) = webView.restore(channel, quality)

    fun pauseVideo() = webView.pauseVideo()

    fun playVideo() = webView.playVideo()

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        return webView.requestFocus(direction, previouslyFocusedRect)
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