package com.vasthread.webviewtv.misc

import android.content.Context
import android.content.SharedPreferences

private var _application: Context? = null
val application: Context
 get() {
  return _application!!
 }

fun setApplication(application: Context) {
 _application = application
}

val preference: SharedPreferences by lazy {
 application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
}
