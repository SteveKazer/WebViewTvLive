package com.vasthread.webviewtv.activity

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tencent.smtt.sdk.QbSdk
import com.vasthread.webviewtv.misc.WebKernel

/**
 * 腾讯 X5 内核管理页（自绘 UI，v2.4.6）。
 *
 * 参照原仓库 3.0.1 的 TbsDebugActivity 功能（其本质是内嵌 debugtbs.qq.com）：
 * 因腾讯该网页服务常年 503，改为 App 内自绘同等功能界面，直接调用 QbSdk 公开 API：
 *  - 安装线上内核：允许非 Wi-Fi + reset 内核状态 + 重新触发 initX5Environment 下载
 *  - 查看版本信息：getTbsVersion / canLoadX5 / getX5CoreLoadHelp 等
 *  - 清除 TBS 内核：deleteStableCore
 *  - 切回系统内核：forceSysWebView + 设置切换
 *
 * 风格与主界面一致：影院深色 + 琥珀强调。
 */
class TbsManagerActivity : AppCompatActivity() {

    private companion object {
        const val BG = 0xFF0B0D10.toInt()
        const val CARD = 0xFF13161B.toInt()
        const val TEXT = 0xFFF2F4F7.toInt()
        const val SUB = 0xFF98A2B3.toInt()
        const val MUTED = 0xFF6B7484.toInt()
        const val ACCENT = 0xFFF2B33D.toInt()
        const val LINE = 0xFF232833.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var helpText: TextView
    private lateinit var actionHint: TextView

    private val refreshAction = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(24f))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        // ---- 标题 ----
        content.addView(TextView(this).apply {
            text = "腾讯 X5 内核管理"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            letterSpacing = 0.02f
        })
        content.addView(TextView(this).apply {
            text = "腾讯浏览服务（TBS / X5）内核由腾讯服务器下发，与系统 WebView 相互独立"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(dp(2f).toFloat(), 1f)
            setPadding(0, dp(6f), 0, dp(16f))
        })

        // ---- 状态卡片 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, dp(14f).toFloat())
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        }
        content.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(sectionLabel("当前内核状态"))
        statusText = TextView(this).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6f), 0, dp(2f))
        }
        card.addView(statusText)
        versionText = TextView(this).apply {
            setTextColor(SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        card.addView(versionText)
        helpText = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(dp(2f).toFloat(), 1f)
            setPadding(0, dp(8f), 0, 0)
            visibility = View.GONE
        }
        card.addView(helpText)

        // ---- 操作区 ----
        content.addView(sectionLabel("内核操作").apply {
            setPadding(dp(2f), dp(18f), 0, dp(8f))
        })

        content.addView(primaryButton("↓  安装线上内核") { installOnlineKernel() })
        content.addView(secondaryButton("查看版本信息") { showVersionInfo() })
        content.addView(secondaryButton("清除 TBS 内核") { confirmClearKernel() })
        content.addView(secondaryButton("切回系统内核") { backToSystemKernel() })

        actionHint = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(dp(2f), dp(14f), dp(2f), 0)
            text = "说明：腾讯 TBS 的在线下载服务近年不稳定（官方页面常返回 503）。" +
                "若多次点击“安装线上内核”仍未开始下载，说明腾讯服务器未响应，请继续使用系统内核（不影响播放）。"
        }
        content.addView(actionHint)

        // ---- 窗口 ----
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshAction)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshAction)
    }

    // ---------------- 状态刷新 ----------------

    private fun refreshStatus() {
        val ver = safeVersion()
        val ready = WebKernel.x5Ready
        val progress = WebKernel.x5Progress
        statusText.text = when {
            ready -> "已启用腾讯 X5 内核"
            progress in 0..99 -> "正在下载内核：$progress%"
            ver > 0 -> "内核已安装（v$ver），重启应用后生效"
            else -> "未安装腾讯内核，当前回落系统内核"
        }
        statusText.setTextColor(if (ready) ACCENT else TEXT)
        versionText.text = "内核版本：${if (ver > 0) "v$ver" else "未安装"}"

        val help = try {
            QbSdk.getX5CoreLoadHelp(this)
        } catch (t: Throwable) {
            null
        }
        if (!help.isNullOrBlank() && !ready) {
            helpText.text = help.take(220)
            helpText.visibility = View.VISIBLE
        } else {
            helpText.visibility = View.GONE
        }
    }

    private fun safeVersion(): Int = try {
        QbSdk.getTbsVersion(this)
    } catch (t: Throwable) {
        0
    }

    // ---------------- 操作 ----------------

    /** 安装线上内核: 允许非 Wi-Fi + 重置状态 + 重新触发内核检查/下载 */
    private fun installOnlineKernel() {
        try {
            QbSdk.setDownloadWithoutWifi(true)
            runCatching { QbSdk.resumeDownload() }
            QbSdk.reset(this)
            WebKernel.x5Progress = -1
            WebKernel.x5ErrorCode = -1
            QbSdk.initX5Environment(this, object : QbSdk.PreInitCallback {
                override fun onCoreInitFinished() {
                    runOnUiThread { refreshStatus() }
                }

                override fun onViewInitFinished(isX5: Boolean) {
                    WebKernel.x5Ready = isX5
                    runOnUiThread {
                        refreshStatus()
                        toast(if (isX5) "腾讯 X5 内核已就绪" else "已请求下载内核，等待腾讯服务器响应…")
                    }
                }
            })
            toast("已向腾讯服务器请求下载内核…")
        } catch (t: Throwable) {
            toast("请求失败：${t.message}")
        }
        handler.postDelayed({ refreshStatus() }, 800)
    }

    /** 查看版本信息 */
    private fun showVersionInfo() {
        val info = buildString {
            appendLine("内核版本：${safeVersion()}")
            appendLine("SDK 版本：${safe { QbSdk.getTbsSdkVersion().toString() }}")
            appendLine("临时目录内核：${safe { QbSdk.getTmpDirTbsVersion(this@TbsManagerActivity).toString() }}")
            appendLine("可加载 X5：${safe { QbSdk.canLoadX5(this@TbsManagerActivity).toString() }}")
            appendLine("当前是 X5：${safe { QbSdk.isX5Core().toString() }}")
            appendLine("内核已初始化：${safe { QbSdk.isTbsCoreInited().toString() }}")
            appendLine("已选择 X5：${WebKernel.isX5(this@TbsManagerActivity)}")
            append("下载进度：${WebKernel.x5Progress}")
        }
        AlertDialog.Builder(this)
            .setTitle("内核版本信息")
            .setMessage(info)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 清除 TBS 内核(卸载) */
    private fun confirmClearKernel() {
        AlertDialog.Builder(this)
            .setTitle("清除 TBS 内核")
            .setMessage("将卸载已安装的腾讯内核，应用继续使用系统内核。确定？")
            .setPositiveButton("清除") { _, _ ->
                try {
                    val ver = safeVersion()
                    if (ver > 0) QbSdk.deleteStableCore(this, ver)
                    runCatching { QbSdk.reset(this) }
                    WebKernel.x5Ready = false
                    WebKernel.x5Progress = -1
                    toast("已请求清除内核，重启应用后生效")
                    refreshStatus()
                } catch (t: Throwable) {
                    toast("清除失败：${t.message}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 切回系统内核 */
    private fun backToSystemKernel() {
        try {
            runCatching { QbSdk.forceSysWebView() }
            WebKernel.setX5(this, false)
            toast("已切回系统内核，重启应用后生效")
        } catch (t: Throwable) {
            toast("操作失败：${t.message}")
        }
    }

    // ---------------- UI 助手 ----------------

    private fun <T> safe(block: () -> T): String = try {
        block().toString()
    } catch (t: Throwable) {
        "未知"
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(SUB)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.08f
        setPadding(dp(2f), 0, 0, 0)
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF1A1A1A.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(0, dp(14f), 0, dp(14f))
        background = rounded(ACCENT, dp(12f).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(0, dp(13f), 0, dp(13f))
        background = roundedOutline(CARD, dp(12f).toFloat(), LINE)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }

    private fun roundedOutline(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            setStroke(dp(1f), stroke)
        }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}