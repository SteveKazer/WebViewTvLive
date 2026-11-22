package com.vasthread.webviewtv.activity

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import com.vasthread.webviewtv.misc.WebKernel

/**
 * 腾讯 X5 内核管理页（v2.4.6，按原仓库 3.0.1 的做法实现）。
 *
 * 关键：必须用 X5 内核的 WebView(com.tencent.smtt.sdk.WebView) 加载
 * https://debugtbs.qq.com —— X5 SDK 会拦截该地址并展示其内置的调试页面
 * （清除TBS内核 / 查看版本信息 / 安装线上内核，安装走 X5 内部下载通道）。
 * 用系统 WebView 打开会直接请求公网并返回 503（腾讯该网页服务不可用）。
 *
 * 顶部保留一条深色工具条（返回 + 标题 + 内核状态），与 App 其它界面风格一致。
 */
@SuppressLint("SetJavaScriptEnabled")
class TbsManagerActivity : AppCompatActivity() {

    private companion object {
        const val DEBUG_URL = "https://debugtbs.qq.com"
        const val BG = 0xFF0B0D10.toInt()
        const val TEXT = 0xFFF2F4F7.toInt()
        const val MUTED = 0xFF6B7484.toInt()
        const val ACCENT = 0xFFF2B33D.toInt()
    }

    private var webView: WebView? = null
    private lateinit var statusText: TextView
    private lateinit var hintView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val statusAction = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // ---- 顶部工具条 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(14f), 0)
            background = GradientDrawable().apply { setColor(0xFF13161B.toInt()) }
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), dp(2f))
            isClickable = true
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "X5 内核管理"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            letterSpacing = 0.02f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusText = TextView(this).apply {
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        bar.addView(statusText)
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48f)
        ))

        // ---- X5 WebView(内核调试页) + 503 兜底提示 ----
        val content = FrameLayout(this)
        val wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    android.util.Log.w("TbsManager", "http ${errorResponse.statusCode} ${request.url}")
                    if (errorResponse.statusCode == 503) {
                        runOnUiThread { hintView.visibility = View.VISIBLE }
                    }
                }
            }
            setBackgroundColor(Color.BLACK)
            loadUrl(DEBUG_URL)
        }
        webView = wv
        content.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        hintView = TextView(this).apply {
            text = "腾讯调试页需要 X5 内核环境。\n当前内核未就绪（回落系统内核），页面返回 503。\n请先在设置中选择“腾讯 X5”并重启应用，安装好内核后再回到此页。"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4f).toFloat(), 1f)
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
            background = GradientDrawable().apply {
                setColor(0xE613161B.toInt())
                cornerRadius = dp(12f).toFloat()
            }
            visibility = View.GONE
            isClickable = true
        }
        content.addView(hintView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusAction)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusAction)
    }

    private fun refreshStatus() {
        val ver = try {
            QbSdk.getTbsVersion(this)
        } catch (t: Throwable) {
            0
        }
        val progress = WebKernel.x5Progress
        statusText.text = when {
            WebKernel.x5Ready && ver > 0 -> "X5 v$ver 已生效"
            progress in 0..99 -> "下载中 $progress%"
            ver > 0 -> "v$ver 已安装"
            else -> "内核未安装"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}