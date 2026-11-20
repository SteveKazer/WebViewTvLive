package com.vasthread.webviewtv.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.vasthread.webviewtv.BuildConfig
import com.vasthread.webviewtv.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 开机自动检测更新:
 *   - GET GitHub Releases 最新发行
 *   - 版本更高则在屏幕中间弹窗: 取消 / 立即更新
 *   - 确认后从发行附件下载 APK(优先镜像加速), 下载完成拉起安装
 */
class UpdateManager(private val activity: Activity) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val REPO = "SteveKazer/WebViewTvLive"
        private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        private const val MIRROR_PREFIX = "https://gh-proxy.com/"
        private const val COLOR_TEXT = 0xFFF2F4F7.toInt()
        private const val COLOR_SUB = 0xFF98A2B3.toInt()
        private const val COLOR_ACCENT = 0xFFF2B33D.toInt()
    }

    data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String)

    private var prompted = false

    @Volatile
    private var downloading = false

    /** 入口: 后台检查, 有新版本则弹窗 */
    fun checkAndPrompt() {
        if (prompted) return
        Thread {
            try {
                val info = fetchLatest() ?: return@Thread
                if (isNewer(info.version)) {
                    prompted = true
                    activity.runOnUiThread { promptUpdate(info) }
                } else {
                    Log.i(TAG, "already latest: ${info.version}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "check failed: ${e.message}")
            }
        }.start()
    }

    private fun fetchLatest(): ReleaseInfo? {
        val conn = URL(API_LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "YangshipinTV-App")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) {
            Log.w(TAG, "api http ${conn.responseCode}")
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val notes = json.optString("body")
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
        }
        if (tag.isBlank() || apkUrl.isBlank()) return null
        return ReleaseInfo(tag, notes, apkUrl)
    }

    private fun isNewer(remoteVersion: String): Boolean {
        fun nums(v: String): List<Int> =
            v.replace("[vV]".toRegex(), "")
                .split(".", "-")
                .mapNotNull { it.toIntOrNull() }
        val remote = nums(remoteVersion)
        val local = nums(BuildConfig.VERSION_NAME)
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun promptUpdate(info: ReleaseInfo) {
        val message = buildString {
            append("当前版本 ")
            append(BuildConfig.VERSION_NAME.substringBefore('-'))
            append("  →  ")
            append(info.version)
            val brief = info.notes.trim().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .take(6)
                .joinToString("\n")
            if (brief.isNotBlank()) {
                append("\n\n")
                append(brief)
            }
        }
        AlertDialog.Builder(activity, R.style.DarkDialog)
            .setTitle("发现新版本 ${info.version}")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ -> startDownload(info) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(info: ReleaseInfo) {
        val dir = File(activity.getExternalFilesDir(null), "update").apply { mkdirs() }
        val target = File(dir, "update.apk")
        if (target.exists()) target.delete()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(10))
        }
        val status = TextView(activity).apply {
            text = "准备下载…"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
        }
        val speed = TextView(activity).apply {
            text = ""
            setTextColor(COLOR_SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
        }
        container.addView(status)
        container.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { topMargin = dp(12) })
        container.addView(speed)

        val dialog = AlertDialog.Builder(activity, R.style.DarkDialog)
            .setTitle("正在下载 ${info.version}")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> downloading = false }
            .create()
        dialog.show()
        downloading = true

        Thread {
            val ok = downloadApk(info.apkUrl, target) { percent, bytesPerSec ->
                activity.runOnUiThread {
                    progress.progress = percent
                    status.text = "正在下载… $percent%"
                    if (bytesPerSec > 0) {
                        speed.text = if (bytesPerSec >= 1024 * 1024) {
                            String.format("%.1f MB/s", bytesPerSec / 1024f / 1024f)
                        } else {
                            String.format("%.0f KB/s", bytesPerSec / 1024f)
                        }
                    }
                }
            }
            val cancelled = !downloading
            activity.runOnUiThread {
                dialog.dismiss()
                if (ok && target.exists() && target.length() > 1024 * 100) {
                    install(target)
                } else if (!cancelled && downloading) {
                    Toast.makeText(activity, "下载失败, 请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 下载 APK: 先镜像加速, 失败再用直连 */
    private fun downloadApk(url: String, target: File, onProgress: (Int, Long) -> Unit): Boolean {
        val candidates = listOf(MIRROR_PREFIX + url, url)
        for (candidate in candidates) {
            if (!downloading) return false
            try {
                Log.i(TAG, "download from $candidate")
                val conn = URL(candidate).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                if (conn.responseCode != 200) {
                    Log.w(TAG, "http ${conn.responseCode} from $candidate")
                    conn.disconnect()
                    continue
                }
                val total = conn.contentLengthLong
                var done = 0L
                var lastTick = 0L
                var lastBytes = 0L
                val buffer = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        while (true) {
                            if (!downloading) return false
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
                return target.length() > 1024 * 100
            } catch (e: Exception) {
                Log.w(TAG, "download failed from $candidate: ${e.message}")
                if (target.exists()) target.delete()
            }
        }
        return false
    }

    private fun install(apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "install failed: ${e.message}")
            Toast.makeText(activity, "无法拉起安装, 请允许安装未知来源应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
}