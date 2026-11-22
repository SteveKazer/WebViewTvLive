package com.vasthread.webviewtv.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * 腾讯 X5 内核调试/管理页(v2.4.6)。
 *
 * 提取自原仓库(hxh19950701/WebViewTvLive 3.0.1)的 TbsDebugActivity:
 * 用系统 WebView 打开 https://debugtbs.qq.com —— 腾讯官方 TBS 调试页,
 * 可查看内核版本、手动"安装内核"触发下载。
 *
 * 注意:
 *  - 只有 App 已初始化 X5 环境(即设置中选择腾讯 X5)时, 该页面的
 *    "安装内核"才能给本 App 下载安装内核; 未初始化时页面仍可浏览,
 *    但操作对本 App 无效。
 *  - 腾讯 TBS 服务近年不稳定, 页面 503 时无法使用(服务端问题)。
 *  - 调试页用系统 WebView 承载(不依赖 X5 内核本身), 保证在 X5
 *    未就绪时也能打开排查。
 */
@SuppressLint("SetJavaScriptEnabled")
class TbsDebugActivity : AppCompatActivity() {

    companion object {
        private const val DEBUG_URL = "https://debugtbs.qq.com"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(DEBUG_URL)
        }
        setContentView(webView)

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 返回键: 网页可回退则先回退, 否则退出本页
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView?.let {
                if (it.canGoBack()) {
                    it.goBack()
                    return true
                }
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}