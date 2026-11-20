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
 * - 触摸设备: 屏幕触摸一律不透传给网页(防误触遮罩), 单击只回调唤起换台弹窗。
 * - 电视: 按键不透传给网页, 全部交由 MainActivity 处理遥控导航。
 * - 换台/清晰度: 通过注入脚本以"隐藏鼠标"点击页面按钮, 永远停留在一个标签页。
 */
class ChannelPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val webView: WebpageAdapterWebView
    private val fullscreenContainer: FrameLayout

    /** 单击屏幕回调 */
    var clickCallback: (() -> Unit)? = null

    /** 页面就绪回调(脚本已注入, 可执行恢复动作) */
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

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float) = false
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
        }
    }

    /** 打开软件: 加载央视频页面(整个生命周期只加载一次) */
    fun start() {
        if (started) return
        started = true
        webView.loadUrl(WebpageAdapterWebView.URL_HOME)
    }

    /** 映射换台: 隐藏鼠标点击页面频道按钮 */
    fun switchChannel(name: String) = webView.switchChannel(name)

    /** 映射设置清晰度 */
    fun setQuality(qualityLabel: String) = webView.setQuality(qualityLabel)

    /** 启动恢复上次频道/清晰度 */
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
