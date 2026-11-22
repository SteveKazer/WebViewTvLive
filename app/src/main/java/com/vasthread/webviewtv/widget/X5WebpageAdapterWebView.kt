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
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import com.tencent.smtt.export.external.interfaces.ConsoleMessage
import com.tencent.smtt.export.external.interfaces.IX5WebChromeClient
import com.tencent.smtt.export.external.interfaces.JsResult
import com.tencent.smtt.export.external.interfaces.PermissionRequest
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.sdk.WebChromeClient
import com.tencent.smtt.sdk.ValueCallback
import com.tencent.smtt.sdk.WebSettings
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.lang3.math.Fraction
import java.io.BufferedReader
import kotlin.system.measureTimeMillis

/**
 * 央视频专用 WebView —— 腾讯 X5 内核版本(v2.4.6 双内核)。
 *
 * 仅在设置中选择"腾讯 X5"时创建。X5 内核由 tbssdk 运行时静默下载:
 *  - 内核缺失/下载中: X5 WebView 自动回落系统内核, 功能不受影响
 *  - 内核就绪后: 走 X5 渲染, 供系统 WebView 过旧(如 Android 6.0 老电视)
 *    的设备播放页面(页面依赖较新 Chromium 特性)
 *
 * 逻辑与系统版 WebpageAdapterWebView 完全一致(JS 注入/沉浸式/切流黑幕/
 * 频道检测等), 仅基类与回调类型替换为 X5 包路径。
 */
@Suppress("unused", "DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
class X5WebpageAdapterWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr), LiveWebView {

    companion object {
        private const val TAG = "X5WebpageAdapterWebView"
        const val URL_BLANK = "about:blank"

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

    override lateinit var fullscreenContainer: FrameLayout

    override var onScriptReady: (() -> Unit)? = null
    override var onFirstFrame: (() -> Unit)? = null
    override var onPageReady: (() -> Unit)? = null
    override var onChannelsDetected: ((String) -> Unit)? = null
    override var onStreamSwitchBegin: (() -> Unit)? = null
    override var onPlaybackResumed: (() -> Unit)? = null
    override var onCopyrightBlocked: (() -> Unit)? = null
    override var onPlaybackStutter: (() -> Unit)? = null

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
        private var callback: IX5WebChromeClient.CustomViewCallback? = null
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
            try {
                request.grant(request.resources)
            } catch (t: Throwable) {
                Log.w(TAG, "grant permission failed", t)
            }
        }

        fun generateLayoutParams(): FrameLayout.LayoutParams {
            val screenRadio = Fraction.getFraction(fullscreenContainer.width, fullscreenContainer.height)
            val compare = screenRadio.compareTo(videoRatio)
            return if (compare == 0) LP(screenRadio.numerator, screenRadio.denominator, Gravity.CENTER)
            else if (compare > 0) LP(screenRadio.denominator * videoRatio.numerator / videoRatio.denominator, screenRadio.denominator, Gravity.CENTER)
            else LP(screenRadio.numerator, screenRadio.numerator * videoRatio.denominator / videoRatio.numerator, Gravity.CENTER)
        }

        override fun onShowCustomView(view: View, callback: IX5WebChromeClient.CustomViewCallback) {
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
            // 允许加载图片(站内"努力加载中"转圈动画是图片, 必须开启)
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
                super@X5WebpageAdapterWebView.loadUrl(url)
            } else {
                Log.i(TAG, "New url requested, ignore.")
            }
        }
    }

    private suspend fun resetPage(destUrl: String) {
        Log.i(TAG, "Resetting page...")
        val cost = measureTimeMillis {
            super@X5WebpageAdapterWebView.loadUrl(URL_BLANK)
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

    override fun loadLiveUrl(url: String) = loadUrl(url)

    override fun evaluate(js: String, callback: ((String) -> Unit)?) {
        post { evaluateJavascript(js, ValueCallback { value -> callback?.invoke(value) }) }
    }

    override fun switchChannel(name: String, resumeQuality: String) {
        evaluate("window.__WVT__ && __WVT__.switchChannel('${name.escapeJs()}', '${resumeQuality.escapeJs()}')", null)
    }

    override fun setQuality(qualityLabel: String) {
        evaluate("window.__WVT__ && __WVT__.setQuality('${qualityLabel.escapeJs()}')", null)
    }

    override fun restore(channel: String, quality: String) {
        evaluate("window.__WVT__ && __WVT__.restore('${channel.escapeJs()}','${quality.escapeJs()}')", null)
    }

    override fun pauseVideo() = evaluate("window.__WVT__ && __WVT__.pause()", null)

    override fun playVideo() = evaluate("window.__WVT__ && __WVT__.play()", null)

    // ---------------- JS -> 原生 ----------------

    @JavascriptInterface
    fun notifyScriptReady() {
        scriptReady = true
        post { onScriptReady?.invoke() }
    }

    @JavascriptInterface
    fun notifyFirstFrame() {
        post { onFirstFrame?.invoke() }
    }

    @JavascriptInterface
    fun notifyChannels(json: String) {
        post { onChannelsDetected?.invoke(json) }
    }

    @JavascriptInterface
    fun notifySwitchBegin() {
        post { onStreamSwitchBegin?.invoke() }
    }

    @JavascriptInterface
    fun notifyPlaybackResumed() {
        post { onPlaybackResumed?.invoke() }
    }

    /** 页面出现版权限制提示: 立即揭幕展示版权界面 */
    @JavascriptInterface
    fun notifyCopyrightBlocked() {
        post { onCopyrightBlocked?.invoke() }
    }

    /** 播放持续卡顿(缓冲中): 提示用户更换清晰度 */
    @JavascriptInterface
    fun notifyStutter() {
        post { onPlaybackStutter?.invoke() }
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