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
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import com.tencent.smtt.export.external.extension.interfaces.IX5WebSettingsExtension
import com.tencent.smtt.export.external.extension.proxy.ProxyWebChromeClientExtension
import com.tencent.smtt.export.external.extension.proxy.ProxyWebViewClientExtension
import com.tencent.smtt.export.external.interfaces.ConsoleMessage
import com.tencent.smtt.export.external.interfaces.IX5WebChromeClient
import com.tencent.smtt.export.external.interfaces.JsResult
import com.tencent.smtt.export.external.interfaces.MediaAccessPermissionsCallback
import com.tencent.smtt.export.external.interfaces.PermissionRequest
import com.tencent.smtt.export.external.interfaces.SslError
import com.tencent.smtt.export.external.interfaces.SslErrorHandler
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.sdk.WebChromeClient
import com.tencent.smtt.sdk.WebSettings
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import com.vasthread.webviewtv.misc.isMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.lang3.math.Fraction
import java.io.BufferedReader
import kotlin.system.measureTimeMillis

typealias LP = FrameLayout.LayoutParams

/**
 * 央视频专用 WebView。
 * 保留原项目的腾讯 X5 电视浏览器环境与全屏视频(onShowCustomView)机制;
 * 全部页面适配逻辑(注入脚本/F键兜底)收敛在本类, 不再有适配器体系。
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
        private const val KEY_CLICK_DURATION = 50L
        private const val FS_KEY_MIN_INTERVAL = 2500L

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
    private var lastFullscreenKeyAt = 0L
    private val loadingInfo = PageLoadingInfo("", false)

    lateinit var fullscreenContainer: FrameLayout

    /** 页面加载并注入脚本完成, 可执行 restore 等后续指令 */
    var onPageReady: (() -> Unit)? = null

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

    private val clientExtension = object : ProxyWebViewClientExtension() {}

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
            fullscreenContainer.addView(view, generateLayoutParams())
            isInFullscreen = true
        }

        override fun onHideCustomView() {
            Log.i(TAG, "onHideCustomView")
            fullscreenContainer.removeView(view)
            callback?.onCustomViewHidden()
            view = null
            callback = null
            isInFullscreen = false
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            super.onPermissionRequest(request)
        }
    }

    private val chromeClientExtension = object : ProxyWebChromeClientExtension() {
        override fun onPermissionRequest(origin: String, resources: Long, callback: MediaAccessPermissionsCallback): Boolean {
            callback.invoke(origin, 0, true)
            return true
        }
    }

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setAppCacheEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = PC_USER_AGENT
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }
        apply {
            webViewClient = client
            webViewClientExtension = clientExtension
            webChromeClient = chromeClient
            webChromeClientExtension = chromeClientExtension
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
    fun loadUrl(url: String) {
        requestedUrl = url
        CoroutineScope(Dispatchers.Main).launch {
            resetPage(url)
            setVideoSize(0, 0)
            if (requestedUrl == url) {
                settings.apply {
                    userAgentString = PC_USER_AGENT
                }
                chromeClient.videoRatio = RATIO_16_9
                settingsExtension?.apply {
                    setPicModel(IX5WebSettingsExtension.PicModel_NoPic)
                }
                injectedUrl = ""
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

    fun queryState(callback: JsCallback) {
        evaluate("window.__WVT__ ? __WVT__.getState() : '{}'", callback)
    }

    fun pauseVideo() = evaluate("window.__WVT__ && __WVT__.pause()")

    fun playVideo() = evaluate("window.__WVT__ && __WVT__.play()")

    // ---------------- JS -> 原生 ----------------

    /** 页面请求原生注入一次 F 键(全屏兜底, video.js 快捷键) */
    @JavascriptInterface
    fun requestKeyFullscreen() {
        if (isInFullscreen()) return
        CoroutineScope(Dispatchers.Main).launch { pressFullscreenKey() }
    }

    /** 视频在播状态上报 */
    @JavascriptInterface
    fun notifyVideoPlaying() {}

    @JavascriptInterface
    fun setVideoSize(width: Int, height: Int) {
        videoSize.set(width, height)
    }

    fun getVideoSize() = videoSize

    private suspend fun pressFullscreenKey() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFullscreenKeyAt < FS_KEY_MIN_INTERVAL) return
        lastFullscreenKeyAt = now
        requestFocus()
        val downTime = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0))
        delay(KEY_CLICK_DURATION)
        if (isInFullscreen()) return
        dispatchKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0))
        Log.i(TAG, "Fullscreen key pressed.")
    }

    private fun String.escapeJs() = replace("\\", "\\\\").replace("'", "\\'")

    private class PageLoadingInfo(var url: String, var isPageLoading: Boolean) {
        fun set(url: String, isPageLoading: Boolean) {
            this.url = url
            this.isPageLoading = isPageLoading
        }
    }
}
