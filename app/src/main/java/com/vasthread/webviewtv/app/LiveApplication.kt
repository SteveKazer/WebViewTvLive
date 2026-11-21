package com.vasthread.webviewtv.app

import android.app.Application
import com.vasthread.webviewtv.misc.setApplication

class LiveApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setApplication(this)
        // v2.4.0 起弃用腾讯 X5, 使用系统 WebView(Chromium), 无需预初始化
    }
}
