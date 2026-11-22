package com.vasthread.webviewtv.app

import android.app.Application
import android.util.Log
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import com.vasthread.webviewtv.misc.WebKernel
import com.vasthread.webviewtv.misc.setApplication

class LiveApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setApplication(this)
        // v2.4.6 双内核: 仅当用户在设置中选择"腾讯 X5"时才初始化 X5。
        // 默认(系统内核)完全不触碰 X5, 保持主路径零风险。
        if (WebKernel.isX5(this)) initX5()
    }

    /**
     * X5 静默初始化。
     *
     * QbSdk.initX5Environment 会在后台检查本地 X5 内核, 缺失时自动联网
     * 下载(约 30~60MB, 即"首次使用需下载内核"的来源)。下载完成前
     * X5 WebView 自动回落系统内核; 下载完成后下次启动即走 X5。
     * 全程不阻塞启动、不弹窗; 任何异常仅记录日志, 不影响系统内核路径。
     */
    private fun initX5() {
        try {
            val settings = mapOf<String, Any>(
                TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER to true,
                TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE to true
            )
            QbSdk.initTbsSettings(settings)
            QbSdk.initX5Environment(this, object : QbSdk.PreInitCallback {
                override fun onCoreInitFinished() {
                    Log.i(TAG, "X5 core init finished")
                }

                override fun onViewInitFinished(isX5: Boolean) {
                    Log.i(TAG, "X5 view init finished, isX5=$isX5")
                }
            })
        } catch (t: Throwable) {
            // X5 不可用(无内核/环境异常)时静默失败, 回落系统内核
            Log.w(TAG, "X5 init failed, fallback to system kernel", t)
        }
    }

    companion object {
        private const val TAG = "LiveApplication"
    }
}