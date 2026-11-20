package com.vasthread.webviewtv.activity

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.preference
import com.vasthread.webviewtv.playlist.Channels
import com.vasthread.webviewtv.widget.ChannelPlayerView
import com.vasthread.webviewtv.widget.ChannelSheetView
import com.vasthread.webviewtv.widget.QualitySheetView
import com.vasthread.webviewtv.widget.UiLayout
import me.jessyan.autosize.AutoSize

class MainActivity : AppCompatActivity() {

 companion object {
  private const val KEY_LAST_CHANNEL = "last_channel"
  private const val KEY_LAST_QUALITY = "last_quality"
  private const val SHEET_TIMEOUT = 6000L
  private const val EXIT_CONFIRM_WINDOW = 2000L

  private enum class UiMode { STANDARD, CHANNELS, QUALITY }

  private val OPERATION_KEYS = arrayOf(
   KeyEvent.KEYCODE_DPAD_UP,
   KeyEvent.KEYCODE_DPAD_DOWN,
   KeyEvent.KEYCODE_DPAD_LEFT,
   KeyEvent.KEYCODE_DPAD_RIGHT,
   KeyEvent.KEYCODE_DPAD_CENTER,
   KeyEvent.KEYCODE_ENTER,
   KeyEvent.KEYCODE_MENU,
   KeyEvent.KEYCODE_BACK
  )
 }

 private lateinit var playerView: ChannelPlayerView
 private lateinit var mainLayout: FrameLayout
 private lateinit var uiLayout: UiLayout
 private lateinit var channelSheet: ChannelSheetView
 private lateinit var qualitySheet: QualitySheetView

 private var lastChannel = Channels.DEFAULT_CHANNEL
 private var lastQuality = Channels.DEFAULT_QUALITY

 private var uiMode = UiMode.STANDARD
  set(value) {
   if (field == value) return
   field = value
   when (value) {
    UiMode.CHANNELS -> {
     qualitySheet.visibility = View.GONE
     channelSheet.open(lastChannel, lastQuality)
     repostCloseAction()
    }
    UiMode.QUALITY -> {
     channelSheet.visibility = View.GONE
     qualitySheet.open(lastQuality)
     repostCloseAction()
    }
    UiMode.STANDARD -> {
     mainLayout.removeCallbacks(closeSheetAction)
     channelSheet.visibility = View.GONE
     qualitySheet.visibility = View.GONE
     playerView.requestFocus()
    }
   }
  }

 private val closeSheetAction = Runnable { uiMode = UiMode.STANDARD }
 private var lastBackAt = 0L

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  loadState()
  setupUi()
  setupListeners()
 }

 @Suppress("DEPRECATION")
 private fun setupUi() {
  setContentView(R.layout.activity_main)
  mainLayout = findViewById(R.id.mainLayout)
  uiLayout = findViewById(R.id.uiLayout)
  playerView = findViewById(R.id.player)
  channelSheet = findViewById(R.id.channelSheet)
  qualitySheet = findViewById(R.id.qualitySheet)

  window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
    View.SYSTEM_UI_FLAG_FULLSCREEN or
    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
  window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
 }

 private fun setupListeners() {
  playerView.clickCallback = {
   if (uiMode == UiMode.STANDARD) uiMode = UiMode.CHANNELS
  }
  playerView.onPageReady = { restoreIfNeeded() }
  channelSheet.callback = object : ChannelSheetView.Callback {
   override fun onChannelSelected(name: String) {
    if (name != lastChannel) {
     lastChannel = name
     saveState()
     playerView.switchChannel(name)
     toastChannel(name)
    }
    uiMode = UiMode.STANDARD
   }

   override fun onQualityRequested() {
    uiMode = UiMode.QUALITY
   }
  }
  channelSheet.onDismissCallback = { uiMode = UiMode.STANDARD }
  qualitySheet.callback = object : QualitySheetView.Callback {
   override fun onQualitySelected(label: String) {
    lastQuality = label
    saveState()
    playerView.setQuality(label)
    channelSheet.setQualityLabel(qualityShort(label))
    toast("已切换为 $label")
    uiMode = UiMode.STANDARD
   }
  }
  qualitySheet.onDismissCallback = { uiMode = UiMode.STANDARD }
 }

 private fun startPlayerIfNeeded() {
  playerView.start()
 }

 /** 页面就绪后恢复上次频道/清晰度(由注入脚本等待视频起播后执行) */
 private fun restoreIfNeeded() {
  playerView.postDelayed({
   if (lastChannel != Channels.DEFAULT_CHANNEL || lastQuality != Channels.DEFAULT_QUALITY) {
    playerView.restore(lastChannel, qualityShort(lastQuality))
   }
  }, 500)
 }

 private fun saveState() {
  preference.edit()
   .putString(KEY_LAST_CHANNEL, lastChannel)
   .putString(KEY_LAST_QUALITY, lastQuality)
   .apply()
 }

 private fun loadState() {
  runCatching {
   preference.getString(KEY_LAST_CHANNEL, null)?.let { name ->
    if (Channels.ALL.any { it.name == name }) lastChannel = name
   }
   preference.getString(KEY_LAST_QUALITY, null)?.let { q ->
    if (Channels.QUALITIES.any { it.startsWith(q.substringBefore(' ')) }) lastQuality = q
   }
  }
 }

 override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
  AutoSize.autoConvertDensityOfGlobal(this)
  return super.onCreateView(parent, name, context, attrs)
 }

 override fun onResume() {
  super.onResume()
  startPlayerIfNeeded()
  playerView.playVideo()
  playerView.requestFocus()
 }

 override fun onPause() {
  super.onPause()
  uiMode = UiMode.STANDARD
  playerView.pauseVideo()
  saveState()
 }

 override fun onWindowFocusChanged(hasFocus: Boolean) {
  super.onWindowFocusChanged(hasFocus)
  if (hasFocus) {
   playerView.requestFocus()
  }
 }

 @Deprecated("Deprecated in Java")
 @Suppress("DEPRECATION")
 override fun onBackPressed() {
  // 交由 dispatchKeyEvent 统一处理(BACK 属于 OPERATION_KEYS)
 }

 @SuppressLint("RestrictedApi")
 override fun dispatchKeyEvent(event: KeyEvent): Boolean {
  if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
   repostCloseAction()
  }
  val keyCode = event.keyCode
  val isActionUp = event.action == KeyEvent.ACTION_UP

  // 弹窗打开时优先交给弹窗/默认处理
  if (uiMode == UiMode.CHANNELS && channelSheet.visibility == View.VISIBLE) {
   if (keyCode == KeyEvent.KEYCODE_BACK && isActionUp) {
    uiMode = UiMode.STANDARD
    return true
   }
   return super.dispatchKeyEvent(event)
  }
  if (uiMode == UiMode.QUALITY && qualitySheet.visibility == View.VISIBLE) {
   if (keyCode == KeyEvent.KEYCODE_BACK && isActionUp) {
    qualitySheet.dismiss()
    return true
   }
   return super.dispatchKeyEvent(event)
  }

  // 播放态(遮罩下)的按键
  when {
   keyCode == KeyEvent.KEYCODE_BACK && isActionUp -> {
    val now = System.currentTimeMillis()
    if (now - lastBackAt < EXIT_CONFIRM_WINDOW) {
     finish()
    } else {
     lastBackAt = now
     toast("再按一次返回键退出")
    }
    return true
   }
   isActionUp && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) -> {
    uiMode = UiMode.CHANNELS
    return true
   }
   isActionUp && keyCode == KeyEvent.KEYCODE_MENU -> {
    uiMode = UiMode.QUALITY
    return true
   }
   isActionUp && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
    switchChannelByStep(forward = false)
    return true
   }
   isActionUp && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
    switchChannelByStep(forward = true)
    return true
   }
  }
  return if (OPERATION_KEYS.contains(keyCode)) true else super.dispatchKeyEvent(event)
 }

 /** 电视: 上/下键直接换台 */
 private fun switchChannelByStep(forward: Boolean) {
  val next = Channels.next(lastChannel, forward)
  lastChannel = next.name
  saveState()
  playerView.switchChannel(next.name)
  toastChannel(next.name)
 }

 private fun repostCloseAction() {
  mainLayout.removeCallbacks(closeSheetAction)
  mainLayout.postDelayed(closeSheetAction, SHEET_TIMEOUT)
 }

 private fun qualityShort(label: String) = label.substringBefore(' ')

 private fun toastChannel(name: String) {
  val index = Channels.indexOf(name)
  val display = if (index >= 0) "(${index + 1}) $name" else name
  toast(display)
 }

 private var toast: Toast? = null

 @SuppressLint("ShowToast")
 private fun toast(message: String) {
  runOnUiThread {
   toast?.cancel()
   toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
      .also { it.show() }
  }
 }
}
