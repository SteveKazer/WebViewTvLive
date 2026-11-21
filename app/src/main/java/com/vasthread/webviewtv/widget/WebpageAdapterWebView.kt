package com.vasthread.webviewtv.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.lang3.math.Fraction
import java.io.BufferedReader
import kotlin.system.measureTimeMillis

typealias LP = FrameLayout.LayoutParams

/**
 * 央视频专用 WebView —— 系统 WebView(Chromium) 版本。
 *
 * v2.4.0 起弃用腾讯 X5, 全面切换 Android 系统 WebView:
 *  - 行为与桌面 Chrome 完全一致(注入 JS/沉浸式 CSS/隐藏大播放按钮等手段全部生效)
 *  - 无 X5 私有原生视频层(不存在无法定制的原生大播放图标/控制条)
 *  - 视频 HLS 直播由 Chromium 原生支持
 *
 * 视频铺满采用注入 JS 的沉浸式 CSS 方案(见 assets/yangshipin.js),
 * onShowCustomView 仅作为系统 WebView 意外全屏时的兜底处理。
 */
@Suppress("unused", "DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class WebpageAdapterWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "WebpageAdapterWebView"
        const val URL_BLANK = "about:blank"

        /** 唯一播放页: 全程只用一个标签页 */
        const val URL_HOME = "https://www.yangshipin.cn/tv/home"

        private const val PC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        private const val CHECK_PAGE_LOADING_INTERVAL = 50L
        private const val BLANK_PAGE_WAIT = 800L

        val RATIO_16_9 = Fraction.getFraction(16, 9)!!
        val RATIO_4_3 = Fraction.getFraction(4, 3)!!
    }

    private val injectedJs: String by lazy {
        context.assets.open("yangshipin.js").bufferedReader().use(BufferedReader::readText)
    }

    private var requestedUrl = ""
    private val videoSize = Point()
    private var isInFullscreen = false
    private var injectedUrl = ""
    private var scriptReady = false
    private val loadingInfo = PageLoadingInfo("", false)

    lateinit var fullscreenContainer: FrameLayout

    /** 页面脚本注入完成 */
    var onScriptReady: (() -> Unit)? = null

    /** 首帧就绪(视频起播且沉浸式样式已应用) */
    var onFirstFrame: (() -> Unit)? = null

    /** 页面加载完成(每次进度 100) */
    var onPageReady: (() -> Unit)? = null

    /** 页面频道检测上报(注入脚本扫描页面频道 pill 后回调) */
    var onChannelsDetected: ((String) -> Unit)? = null

    fun interface JsCallback { fun onResult(result: String) }

    private val client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            // 只允许停留在央视频页面内(SPA 内部路由不受影响)
            return true
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.proceed()
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            Log.i(TAG, "onPageStarted, $url")
            loadingInfo.set(url, true)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            Log.i(TAG, "onPageFinished, $url")
            loadingInfo.set(url, false)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            super.onReceivedHttpError(view, request, response)
            Log.i(TAG, "Http error: ${response.statusCode} ${request.url}")
        }
    }

    private val chromeClient = object : WebChromeClient() {

        private var view: View? = null
        private var callback: CustomViewCallback? = null
        var videoRatio = RATIO_16_9

        override fun onJsAlert(view: WebView, url: String, message: String?, result: JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onProgressChanged(view: WebView, progress: Int) {
            super.onProgressChanged(view, progress)
            Log.i(TAG, "$url, progress=$progress")
            if (url == URL_BLANK) return
            if (progress == 100) {
                injectScriptOnce()
                onPageReady?.invoke()
            }
        }

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            when (msg.messageLevel()) {
                ConsoleMessage.MessageLevel.DEBUG, null -> Log.d(TAG, msg.message())
                ConsoleMessage.MessageLevel.LOG, ConsoleMessage.MessageLevel.TIP -> Log.i(TAG, msg.message())
                ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg.message())
                ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg.message())
            }
            return true
        }

        /** 页面请求媒体权限(播放音视频): 全部授权 */
        override fun onPermissionRequest(request: PermissionRequest) {
            request.grant(request.resources)
        }

        fun generateLayoutParams(): FrameLayout.LayoutParams {
            val screenRadio = Fraction.getFraction(fullscreenContainer.width, fullscreenContainer.height)
            val compare = screenRadio.compareTo(videoRatio)
            return if (compare == 0) LP(screenRadio.numerator, screenRadio.denominator, Gravity.CENTER)
            else if (compare > 0) LP(screenRadio.denominator * videoRatio.numerator / videoRatio.denominator, screenRadio.denominator, Gravity.CENTER)
            else LP(screenRadio.numerator, screenRadio.numerator * videoRatio.denominator / videoRatio.numerator, Gravity.CENTER)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            Log.i(TAG, "onShowCustomView")
            this.view = view
            this.callback = callback
            fullscreenContainer.setBackgroundColor(Color.BLACK)
            fullscreenContainer.addView(view, generateLayoutParams())
            isInFullscreen = true
        }

        override fun onHideCustomView() {
            Log.i(TAG, "onHideCustomView")
            fullscreenContainer.removeView(view)
            fullscreenContainer.setBackgroundColor(Color.TRANSPARENT)
            callback?.onCustomViewHidden()
            view = null
            callback = null
            isInFullscreen = false
        }
    }

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = PC_USER_AGENT
            // 允许加载图片(站内\"努力加载中\"转圈动画是图片, 必须开启)
            loadsImagesAutomatically = true
            blockNetworkImage = false
        }
        apply {
            webViewClient = client
            webChromeClient = chromeClient
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(this, "main")
        }
    }

    private fun injectScriptOnce() {
        val current = url
        if (current == null || current == URL_BLANK || current == injectedUrl) return
        injectedUrl = current
        Log.i(TAG, "Inject yangshipin.js into $current")
        evaluateJavascript(injectedJs, null)
    }

    @JavascriptInterface
    override fun loadUrl(url: String) {
        requestedUrl = url
        CoroutineScope(Dispatchers.Main).launch {
            resetPage(url)
            setVideoSize(0, 0)
            if (requestedUrl == url) {
                settings.apply {
                    userAgentString = PC_USER_AGENT
                }
                chromeClient.videoRatio = RATIO_16_9
                injectedUrl = ""
                scriptReady = false
                Log.i(TAG, "Load url $url")
                super@WebpageAdapterWebView.loadUrl(url)
            } else {
                Log.i(TAG, "New url requested, ignore.")
            }
        }
    }

    private suspend fun resetPage(destUrl: String) {
        Log.i(TAG, "Resetting page...")
        val cost = measureTimeMillis {
            super@WebpageAdapterWebView.loadUrl(URL_BLANK)
            while (loadingInfo.url != URL_BLANK && !loadingInfo.isPageLoading) {
                if (destUrl != requestedUrl) break
                delay(CHECK_PAGE_LOADING_INTERVAL)
            }
            if (destUrl == requestedUrl) {
                val endTime = SystemClock.uptimeMillis() + BLANK_PAGE_WAIT
                while (SystemClock.uptimeMillis() < endTime) {
                    if (destUrl != requestedUrl) break
                    delay(CHECK_PAGE_LOADING_INTERVAL)
                }
            }
        }
        Log.i(TAG, "Done Resetting, cost ${cost}ms.")
    }

    // ---------------- 提供给上层/Kotlin调用的能力 ----------------

    fun isInFullscreen() = isInFullscreen

    fun evaluate(js: String, callback: JsCallback? = null) {
        post { evaluateJavascript(js) { value -> callback?.onResult(value) } }
    }

    /** 换台: 隐藏鼠标点击页面频道按钮 */
    fun switchChannel(name: String) {
        evaluate("window.__WVT__ && __WVT__.switchChannel('${name.escapeJs()}')")
    }

    /** 设置清晰度: 点击页面隐藏的清晰度菜单项 */
    fun setQuality(qualityLabel: String) {
        evaluate("window.__WVT__ && __WVT__.setQuality('${qualityLabel.escapeJs()}')")
    }

    /** 启动恢复: 切换到上次频道+上次清晰度 */
    fun restore(channel: String, quality: String) {
        evaluate("window.__WVT__ && __WVT__.restore('${channel.escapeJs()}','${quality.escapeJs()}')")
    }

    fun pauseVideo() = evaluate("window.__WVT__ && __WVT__.pause()")

    fun playVideo() = evaluate("window.__WVT__ && __WVT__.play()")

    // ---------------- JS -> 原生 ----------------

    /** 注入脚本就绪 */
    @JavascriptInterface
    fun notifyScriptReady() {
        scriptReady = true
        post { onScriptReady?.invoke() }
    }

    /** 首帧就绪(视频起播且沉浸式样式已应用) */
    @JavascriptInterface
    fun notifyFirstFrame() {
        post { onFirstFrame?.invoke() }
    }

    /** 页面频道检测上报(JSON 数组字符串) */
    @JavascriptInterface
    fun notifyChannels(json: String) {
        post { onChannelsDetected?.invoke(json) }
    }

    @JavascriptInterface
    fun notifyVideoPlaying() {}

    @JavascriptInterface
    fun setVideoSize(width: Int, height: Int) {
        videoSize.set(width, height)
    }

    private fun String.escapeJs() = replace("\\", "\\\\").replace("'", "\\'")

    private class PageLoadingInfo(var url: String, var isPageLoading: Boolean) {
        fun set(url: String, isPageLoading: Boolean) {
            this.url = url
            this.isPageLoading = isPageLoading
        }
    }
}
