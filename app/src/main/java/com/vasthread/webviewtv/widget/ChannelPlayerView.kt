package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import com.vasthread.webviewtv.R

/**
 * 播放器视图: 遮罩防误触 + 映射点击。
 *
 * 触摸:
 *   - 单击屏幕: 唤起换台弹窗
 *   - 向上滑动: 下一个频道(往下播放)
 *   - 向下滑动: 上一个频道(往上播放)
 *   触摸不透传给网页, 防止误触。
 * 电视: 按键不透传给网页, 全部交由 MainActivity 处理。
 */
class ChannelPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val SWIPE_VELOCITY_THRESHOLD = 900f
        private const val SWIPE_DISTANCE_THRESHOLD = 60f
    }

    val webView: WebpageAdapterWebView
    private val fullscreenContainer: FrameLayout

    /** 单击屏幕 */
    var clickCallback: (() -> Unit)? = null

    /** 向上滑动 -> 下一个频道 */
    var swipeNextCallback: (() -> Unit)? = null

    /** 向下滑动 -> 上一个频道 */
    var swipePrevCallback: (() -> Unit)? = null

    /** 注入脚本就绪 */
    var onScriptReady: (() -> Unit)? = null

    /** 页面加载完成 */
    var onPageReady: (() -> Unit)? = null

    private var started = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.OnGestureListener {

        override fun onDown(e: MotionEvent) = true

        override fun onShowPress(e: MotionEvent) = Unit

        @SuppressLint("ClickableViewAccessibility")
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            clickCallback?.invoke()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float) = false

        override fun onLongPress(e: MotionEvent) = Unit

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null || e2 == null) return false
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
        webView.apply {
            this.fullscreenContainer = this@ChannelPlayerView.fullscreenContainer
            onPageReady = { this@ChannelPlayerView.onPageReady?.invoke() }
            onScriptReady = { this@ChannelPlayerView.onScriptReady?.invoke() }
        }
    }

    /** 打开软件: 加载央视频页面(整个生命周期只加载一次) */
    fun start() {
        if (started) return
        started = true
        webView.loadUrl(WebpageAdapterWebView.URL_HOME)
    }

    fun switchChannel(name: String) = webView.switchChannel(name)

    fun setQuality(qualityLabel: String) = webView.setQuality(qualityLabel)

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