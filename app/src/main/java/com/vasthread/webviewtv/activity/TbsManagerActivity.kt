package com.vasthread.webviewtv.activity

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsListener
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
import com.vasthread.webviewtv.misc.TbsCoreInstaller
import com.vasthread.webviewtv.misc.WebKernel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 腾讯 X5 内核管理页（v2.4.6）。
 *
 * 三个能力:
 *  1. 下载并安装内核: 官方在线下载通道近年不稳, 这里按设备位数
 *     (64 位 -> v46007 / 32 位 -> v45738)直接下载内核安装包(第三方
 *     镜像, 与开源项目 CCTV_Viewer 同源), 校验完整后调用
 *     QbSdk.installLocalTbsCore 本地安装, 重启应用即走 X5。
 *  2. 从本地文件安装: 优先列出应用下载目录中的内核 apk(把文件拷到
 *     内部存储/Android/data/<包名>/files/Download/), 也可调起系统
 *     文件选择器(U盘/任意位置); 安装前校验位数, 防止 32/64 位错装
 *     导致闪退或无限重启。
 *  3. 打开腾讯 TBS 调试页: X5 WebView 加载 debugtbs.qq.com(SDK 内置
 *     拦截页, 需内核环境), 保留为备用排查工具。
 *
 * 顶部工具条实时显示内核状态(版本/下载进度), 与 App 其它界面风格一致。
 */
@SuppressLint("SetJavaScriptEnabled")
class TbsManagerActivity : AppCompatActivity() {

    private companion object {
        const val DEBUG_URL = "https://debugtbs.qq.com"
        const val BG = 0xFF0B0D10.toInt()
        const val TEXT = 0xFFF2F4F7.toInt()
        const val SUB = 0xFF98A2B3.toInt()
        const val MUTED = 0xFF6B7484.toInt()
        const val ACCENT = 0xFFF2B33D.toInt()
        const val ROW_BG = 0xFF171B21.toInt()
        const val INSTALL_TIMEOUT_MS = 30_000L
    }

    private var webView: WebView? = null
    private var debugLoaded = false
    private lateinit var btnBack: TextView
    private lateinit var statusText: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var btnDownload: TextView
    private lateinit var btnLocal: TextView
    private lateinit var btnDebug: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var nativePanel: LinearLayout
    private lateinit var debugContainer: FrameLayout
    private lateinit var hintView: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** 下载/安装进行中(期间按钮禁用, 防重复触发) */
    @Volatile
    private var busy = false

    private val installTimeoutAction = Runnable {
        if (busy) {
            busy = false
            runOnUiThread {
                setBusyUi(false)
                toast("X5 内核安装超时，请重试或改用本地文件安装")
            }
        }
    }

    private val statusAction = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    /** 文件选择器: 选中后复制到应用下载目录再走统一安装流程 */
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(buildTopBar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48f)
        ))

        nativePanel = buildNativePanel()
        root.addView(nativePanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        debugContainer = buildDebugContainer()
        debugContainer.visibility = View.GONE
        root.addView(debugContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        refreshStatus()
        refreshDeviceInfo()
        rewireTvFocus()
    }

    /** 电视十字键适配(v2.4.7): 返回/下载/本地/调试按钮间上下循环导航, 焦点不逸出页面 */
    private fun rewireTvFocus() {
        val chain = listOfNotNull(
            btnBack,
            btnDownload.takeIf { it.visibility == View.VISIBLE },
            btnLocal.takeIf { it.visibility == View.VISIBLE },
            btnDebug
        )
        chain.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        for (i in chain.indices) {
            chain[i].nextFocusUpId = chain[(i - 1 + chain.size) % chain.size].id
            chain[i].nextFocusDownId = chain[(i + 1) % chain.size].id
        }
    }

    // ---------------- 界面构建 ----------------

    private fun buildTopBar(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(14f), 0)
            background = GradientDrawable().apply { setColor(0xFF13161B.toInt()) }
            // 返回按钮(电视可聚焦, 焦点态变琥珀色)
            btnBack = TextView(this@TbsManagerActivity).apply {
                text = "‹"
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                gravity = Gravity.CENTER
                setPadding(dp(10f), 0, dp(10f), dp(2f))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    (v as TextView).setTextColor(if (hasFocus) ACCENT else TEXT)
                }
                setOnClickListener { onBackRequested() }
            }
            addView(btnBack)
            addView(TextView(this@TbsManagerActivity).apply {
                text = "X5 内核管理"
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                letterSpacing = 0.02f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statusText = TextView(this@TbsManagerActivity).apply {
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            addView(statusText)
        }

    private fun buildNativePanel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(14f), dp(18f), dp(10f))

            // ---- 本机适配信息 ----
            deviceInfo = TextView(this@TbsManagerActivity).apply {
                setTextColor(SUB)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(dp(3f).toFloat(), 1f)
                setPadding(dp(4f), 0, dp(4f), dp(10f))
            }
            addView(deviceInfo, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            // ---- 进度条(下载中显示) ----
            progressBar = ProgressBar(this@TbsManagerActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                visibility = View.GONE
            }
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8f)
            ).apply { topMargin = dp(2f) })
            progressText = TextView(this@TbsManagerActivity).apply {
                setTextColor(SUB)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.END
                visibility = View.GONE
            }
            addView(progressText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) })

            // ---- 按钮 ----
            btnDownload = makeButton("") { startDownloadInstall() }
            addView(btnDownload, rowParams())
            btnLocal = makeButton("从本地文件安装内核") { onLocalInstall() }
            addView(btnLocal, rowParams())
            btnDebug = makeButton("打开腾讯 TBS 调试页 ›") { openDebugPage() }
            addView(btnDebug, rowParams())

            // ---- 说明 ----
            addView(TextView(this@TbsManagerActivity).apply {
                text = "说明：官方在线下载通道近年不稳定，此页改用第三方镜像直下内核包" +
                    "（与开源项目 CCTV_Viewer 同源）。也可把内核 apk 拷到" +
                    "\n内部存储/Android/data/" + packageName + "/files/Download/\n后用「从本地文件安装」。"
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setLineSpacing(dp(3f).toFloat(), 1f)
                setPadding(dp(4f), dp(14f), dp(4f), 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

    private fun buildDebugContainer(): FrameLayout {
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
        }
        webView = wv
        content.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        hintView = TextView(this).apply {
            text = "腾讯调试页需要 X5 内核环境。\n当前内核未就绪，页面返回 503。\n请先安装好内核并重启应用后再回到此页。"
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
        return content
    }

    /** 设置行样式的按钮: 可聚焦, 焦点/按压黄底 */
    private fun makeButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat(); setColor(ACCENT)
                })
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat(); setColor(0x33FFFFFF)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat(); setColor(ROW_BG)
                })
            }
            setPadding(0, dp(13f), 0, dp(13f))
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(if (hasFocus) 0xFF1A1A1A.toInt() else TEXT)
            }
            setOnClickListener { onClick() }
        }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }

    // ---------------- 状态展示 ----------------

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

    /** 本机 CPU/推荐内核信息; x86 无解则禁用安装入口 */
    private fun refreshDeviceInfo() {
        if (!TbsCoreInstaller.isArmDevice()) {
            deviceInfo.text = "本机 CPU：${TbsCoreInstaller.primaryAbi()}\n" +
                "非 ARM 架构设备不支持腾讯 X5 内核（内核无 x86 库），请继续使用系统内核。"
            btnDownload.visibility = View.GONE
            btnLocal.visibility = View.GONE
            return
        }
        val bits = if (TbsCoreInstaller.is64BitDevice()) "64" else "32"
        val ver = TbsCoreInstaller.targetVersion()
        deviceInfo.text = "本机 CPU：${TbsCoreInstaller.primaryAbi()}（$bits 位）→ 推荐内核 v$ver"
        btnDownload.text = "下载并安装内核（v$ver · $bits 位 · 约 ${TbsCoreInstaller.targetSizeMb()}MB）"
    }

    // ---------------- 下载并安装 ----------------

    private fun startDownloadInstall() {
        if (busy) return
        val version = TbsCoreInstaller.targetVersion()
        if (version == 0) return
        val dir = TbsCoreInstaller.coreDir(this)
        val target = TbsCoreInstaller.targetFile(this)
        if (dir == null || target == null) {
            toast("无法访问内核下载目录")
            return
        }
        busy = true
        setBusyUi(true)
        progressBar.progress = 0
        progressText.text = "准备下载…"
        toast("开始下载 X5 内核，约 ${TbsCoreInstaller.targetSizeMb()}MB，请保持网络畅通")
        Thread {
            val ok = downloadFile(TbsCoreInstaller.targetUrl(), target) { percent, bps ->
                runOnUiThread {
                    progressBar.progress = percent
                    progressText.text = if (bps > 0) {
                        "下载中 $percent% · " + if (bps >= 1024 * 1024) {
                            String.format("%.1f MB/s", bps / 1024f / 1024f)
                        } else {
                            String.format("%.0f KB/s", bps / 1024f)
                        }
                    } else "下载中 $percent%"
                }
            }
            runOnUiThread {
                if (!ok || !TbsCoreInstaller.isCompleteApk(target)) {
                    if (target.exists()) target.delete()
                    busy = false
                    setBusyUi(false)
                    toast("内核下载失败，请检查网络后重试，或改用本地文件安装")
                } else {
                    installCore(target, version, removeOnFail = false)
                }
            }
        }.start()
    }

    /** 断点无所谓的简单下载: HTTP 直连, 带进度回调 */
    private fun downloadFile(url: String, target: File, onProgress: (Int, Long) -> Unit): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            if (conn.responseCode != 200) {
                android.util.Log.w("TbsManager", "download http ${conn.responseCode}")
                conn.disconnect()
                return false
            }
            val total = conn.contentLengthLong
            var done = 0L
            var lastTick = 0L
            var lastBytes = 0L
            val buffer = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        if (!busy) return false
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTick > 300) {
                            val bps = if (now > lastTick) (done - lastBytes) * 1000 / (now - lastTick) else 0
                            lastTick = now
                            lastBytes = done
                            val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                            onProgress(percent, bps)
                        }
                    }
                }
            }
            conn.disconnect()
            target.length() > 1024 * 1024
        } catch (e: Exception) {
            android.util.Log.w("TbsManager", "download failed: ${e.message}")
            false
        }
    }

    // ---------------- 本地导入安装 ----------------

    private fun onLocalInstall() {
        if (busy) return
        // 优先列出应用下载目录中已有的内核文件(免权限, 电视端友好)
        val found = TbsCoreInstaller.scanLocalApks(this)
        when (found.size) {
            0 -> {
                toast("应用下载目录没有内核文件，打开文件选择器")
                pickFile.launch(arrayOf("*/*"))
            }
            1 -> confirmInstall(found[0], TbsCoreInstaller.parseVersionFromName(found[0].name))
            else -> {
                val names = found.map { it.name }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("选择要安装的内核文件")
                    .setItems(names) { _, which ->
                        confirmInstall(found[which], TbsCoreInstaller.parseVersionFromName(found[which].name))
                    }
                    .setNegativeButton("取消", null)
                    .setNeutralButton("从文件选择器打开") { _, _ -> pickFile.launch(arrayOf("*/*")) }
                    .show()
            }
        }
    }

    /** 导入前的最后确认(带位数校验结果) */
    private fun confirmInstall(file: File, versionFromName: Int?) {
        val bits64 = TbsCoreInstaller.is64BitDevice()
        if (!TbsCoreInstaller.isCompleteApk(file)) {
            toast("文件不完整(非有效内核安装包)，请重新下载或拷贝")
            return
        }
        if (!TbsCoreInstaller.apkSupportsDeviceAbi(file, bits64)) {
            toast("位数不匹配：这是${if (bits64) "32" else "64"}位设备的内核包？已拒绝安装以防闪退")
            return
        }
        val version = versionFromName ?: TbsCoreInstaller.targetVersion()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("安装 X5 内核")
            .setMessage("文件：${file.name}\n版本：v$version\n\n安装完成后需重启应用生效。继续？")
            .setPositiveButton("安装") { _, _ -> installCore(file, version, removeOnFail = false) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从 SAF uri 复制到应用目录后安装 */
    private fun importFromUri(uri: Uri) {
        if (busy) return
        val dir = TbsCoreInstaller.coreDir(this)
        if (dir == null) {
            toast("无法访问内核存储目录")
            return
        }
        busy = true
        setBusyUi(true)
        progressText.text = "正在导入文件…"
        progressText.visibility = View.VISIBLE
        Thread {
            try {
                val name = queryDisplayName(uri) ?: ("import_" + System.currentTimeMillis() + "_x5.apk")
                val target = File(dir, "import_" + name.takeLast(60))
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("openInputStream null")
                runOnUiThread {
                    if (TbsCoreInstaller.isCompleteApk(target)) {
                        confirmInstall(target, TbsCoreInstaller.parseVersionFromName(target.name))
                    } else {
                        toast("所选文件不是有效的内核安装包(非 APK)")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TbsManager", "import failed: ${e.message}")
                runOnUiThread { toast("导入失败：" + (e.message ?: "未知错误")) }
            } finally {
                runOnUiThread {
                    busy = false
                    setBusyUi(false)
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }

    // ---------------- 统一安装流程(参照 CCTV_Viewer 实证可用) ----------------

    private fun installCore(apk: File, version: Int, removeOnFail: Boolean) {
        busy = true
        setBusyUi(true)
        progressText.text = "正在安装内核…"
        try {
            QbSdk.unForceSysWebView()
            QbSdk.reset(applicationContext)
            // 先注册监听再安装, 避免回调早于监听设置而丢失
            QbSdk.setTbsListener(object : TbsListener {
                override fun onDownloadFinish(code: Int) {
                    android.util.Log.i("TbsManager", "install-flow download finish $code")
                }

                override fun onDownloadProgress(progress: Int) {}

                override fun onInstallFinish(code: Int) {
                    runOnUiThread { onInstallFinished(code, apk, removeOnFail) }
                }
            })
            WebKernel.x5ErrorCode = -1
            WebKernel.x5Progress = -1
            QbSdk.installLocalTbsCore(applicationContext, version, apk.absolutePath)
            handler.postDelayed(installTimeoutAction, INSTALL_TIMEOUT_MS)
        } catch (t: Throwable) {
            android.util.Log.e("TbsManager", "installLocalTbsCore failed", t)
            busy = false
            setBusyUi(false)
            if (removeOnFail && apk.exists()) apk.delete()
            toast("内核安装调用失败：" + (t.message ?: "未知异常"))
        }
    }

    private fun onInstallFinished(code: Int, apk: File, removeOnFail: Boolean) {
        handler.removeCallbacks(installTimeoutAction)
        if (!busy) return
        busy = false
        val canLoad = try {
            QbSdk.canLoadX5(applicationContext)
        } catch (t: Throwable) {
            false
        }
        android.util.Log.i("TbsManager", "install finish $code, canLoad=$canLoad")
        if (code == 200 && canLoad) {
            WebKernel.x5ErrorCode = -1
            setBusyUi(false)
            toast("X5 内核安装成功，重启应用后生效")
            refreshStatus()
        } else if (code == 200) {
            // 安装流程完成但本进程暂无法加载: 保留安装包, 重启后即正常
            WebKernel.x5ErrorCode = -1
            setBusyUi(false)
            toast("内核安装完成，本次未能加载，请重启应用后再查看")
            refreshStatus()
        } else {
            WebKernel.x5ErrorCode = code
            setBusyUi(false)
            if (removeOnFail && apk.exists()) apk.delete()
            toast("X5 内核安装失败（错误码 $code），请重试或换一个内核文件")
        }
    }

    private fun setBusyUi(busy: Boolean) {
        btnDownload.isEnabled = !busy
        btnLocal.isEnabled = !busy
        btnDownload.alpha = if (busy) 0.5f else 1f
        btnLocal.alpha = if (busy) 0.5f else 1f
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        progressText.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) progressText.text = ""
    }

    // ---------------- 腾讯调试页 ----------------

    private fun openDebugPage() {
        nativePanel.visibility = View.GONE
        debugContainer.visibility = View.VISIBLE
        if (!debugLoaded) {
            debugLoaded = true
            webView?.loadUrl(DEBUG_URL)
        }
    }

    private fun closeDebugPage() {
        debugContainer.visibility = View.GONE
        nativePanel.visibility = View.VISIBLE
    }

    private fun onBackRequested() {
        if (debugContainer.visibility == View.VISIBLE) {
            webView?.let {
                if (it.canGoBack()) {
                    it.goBack()
                    return
                }
            }
            closeDebugPage()
            return
        }
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event?.action == KeyEvent.ACTION_DOWN) onBackRequested()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
