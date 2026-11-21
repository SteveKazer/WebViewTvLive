package com.vasthread.webviewtv.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vasthread.webviewtv.R
import com.vasthread.webviewtv.misc.preference
import com.vasthread.webviewtv.playlist.Channels
import com.vasthread.webviewtv.playlist.Channels.Channel
import com.vasthread.webviewtv.update.UpdateManager
import com.vasthread.webviewtv.widget.ChannelPlayerView
import com.vasthread.webviewtv.widget.ChannelSheetView
import com.vasthread.webviewtv.widget.QualitySheetView
import com.vasthread.webviewtv.widget.UiLayout
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "MainActivity"
		private const val KEY_LAST_CHANNEL = "last_channel"
		private const val KEY_LAST_QUALITY = "last_quality"
		private const val KEY_ACTIVE_TAB = "active_tab"
		private const val KEY_FAVORITES = "favorites"
		private const val KEY_PROVINCE = "province"
		private const val SHEET_TIMEOUT = 8000L
		private const val EXIT_CONFIRM_WINDOW = 2000L
		private const val DIGIT_INPUT_TIMEOUT = 1200L
		private const val LONG_PRESS_OK_DELAY = 650L

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
	private lateinit var updateManager: UpdateManager

	private var lastChannel = Channels.DEFAULT_CHANNEL
	private var lastQuality = Channels.DEFAULT_QUALITY
	private var activeTab = Channels.TAB_ALL
	private var province: String? = null
	private var scriptToastShown = false
	private var lastBackAt = 0L

	private val digitBuffer = StringBuilder()
	private val digitApplyAction = Runnable { applyDigitBuffer() }

	private var longPressOkFired = false
	private val longPressOkAction = Runnable {
		longPressOkFired = true
		val name = channelSheet.focusedChannel()
		if (name.isNotEmpty()) toggleFavorite(name)
	}

	private var uiMode = UiMode.STANDARD
		set(value) {
			if (field == value) return
			field = value
			when (value) {
				UiMode.CHANNELS -> {
					qualitySheet.visibility = View.GONE
					channelSheet.open(activeTab, lastChannel, favorites(), province)
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

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		loadState()
		setupUi()
		setupListeners()
		updateManager = UpdateManager(this)
		loadProvince()
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
		playerView.leftTapCallback = {
			if (uiMode == UiMode.STANDARD) uiMode = UiMode.CHANNELS
		}
		playerView.rightTapCallback = {
			if (uiMode == UiMode.STANDARD) uiMode = UiMode.QUALITY
		}
		playerView.swipeNextCallback = {
			if (uiMode == UiMode.STANDARD) switchChannelByStep(forward = true)
		}
		playerView.swipePrevCallback = {
			if (uiMode == UiMode.STANDARD) switchChannelByStep(forward = false)
		}
		playerView.onPageReady = {
			restoreIfNeeded()
		}
		playerView.onScriptReady = {
			if (!scriptToastShown) {
				scriptToastShown = true
				toast("已连接央视频")
			}
		}
		playerView.onChannelsDetected = { json ->
			// 页面频道检测: 更新库中频道可见性(备用卫视检测到即显示)
			runCatching {
				val arr = JSONArray(json)
				val names = (0 until arr.length()).map { arr.optString(it) }
				Channels.setDetected(names)
				channelSheet.refresh()
			}
		}
		playerView.onFirstFrame = {
			// 首帧就绪后延迟检查更新, 不打断观赏
			mainLayout.postDelayed({ updateManager.checkAndPrompt() }, 5000)
		}
		channelSheet.callback = object : ChannelSheetView.Callback {
			override fun onChannelSelected(name: String, tab: Int) {
				activeTab = tab
				switchTo(name)
				uiMode = UiMode.STANDARD
			}

			override fun onFavoriteToggle(name: String) {
				toggleFavorite(name)
			}
		}
		channelSheet.onDismissCallback = { uiMode = UiMode.STANDARD }
	qualitySheet.callback = object : QualitySheetView.Callback {
		override fun onQualitySelected(label: String) {
			lastQuality = label
			saveState()
			playerView.setQuality(label)
			channelSheet.setCurrent(lastChannel)
			toast("已切换为 $label")
			uiMode = UiMode.STANDARD
		}

		override fun onSettingsClick() {
			toast("设置功能即将上线")
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

	// ---------------- 状态持久化 ----------------

	private fun saveState() {
		preference.edit()
			.putString(KEY_LAST_CHANNEL, lastChannel)
			.putString(KEY_LAST_QUALITY, lastQuality)
			.putInt(KEY_ACTIVE_TAB, activeTab)
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
			activeTab = preference.getInt(KEY_ACTIVE_TAB, Channels.TAB_ALL)
			if (activeTab == Channels.TAB_FAVORITE && favorites().isEmpty()) activeTab = Channels.TAB_ALL
			province = preference.getString(KEY_PROVINCE, null)
		}
	}

	private fun favorites(): Set<String> =
		preference.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()

	private fun saveFavorites(set: Set<String>) {
		preference.edit().putStringSet(KEY_FAVORITES, set).apply()
	}

	private fun toggleFavorite(name: String) {
		val set = favorites().toMutableSet()
		val added = if (set.contains(name)) {
			set.remove(name)
			false
		} else {
			set.add(name)
			true
		}
		saveFavorites(set)
		channelSheet.setFavorites(set, province)
		toast(if (added) "已收藏 $name" else "已取消收藏 $name")
	}

	/** 根据公网 IP 自动识别省份, 用于卫视地区优先 */
	private fun loadProvince() {
		Thread {
			try {
				val conn = URL("https://myip.ipip.net").openConnection() as HttpURLConnection
				conn.connectTimeout = 4000
				conn.readTimeout = 4000
				conn.setRequestProperty("User-Agent", "curl/8.0")
				val text = conn.inputStream.bufferedReader().use { it.readText() }
				conn.disconnect()
				// 格式: 当前 IP：x.x.x.x  来自于：中国 江苏 南京  电信
				val match = Regex("中国\\s+([\\u4e00-\\u9fa5]{2,8}?)\\s").find(text)
				val p = match?.groupValues?.get(1)
				if (!p.isNullOrBlank() && p != province) {
					province = p
					preference.edit().putString(KEY_PROVINCE, p).apply()
					runOnUiThread {
						channelSheet.setFavorites(favorites(), province)
						Log.i(TAG, "Province detected: $p")
					}
				}
			} catch (e: Exception) {
				Log.w(TAG, "loadProvince failed: ${e.message}")
			}
		}.start()
	}

	// ---------------- 频道切换 ----------------

	private fun switchTo(name: String) {
		if (name == lastChannel) {
			// 同一频道: 只提示
			toastChannel(name)
			return
		}
		lastChannel = name
		saveState()
		playerView.switchChannel(name)
		channelSheet.setCurrent(name)
		toastChannel(name)
	}

	private fun switchChannelByStep(forward: Boolean) {
		val list = channelSheet.channelsOf(activeTab)
		if (list.isEmpty()) {
			toast("当前列表为空, 请先添加收藏")
			return
		}
		val next = Channels.nextInList(list, lastChannel, forward) ?: return
		switchTo(next.name)
	}

	private fun applyDigitBuffer() {
		val buffer = digitBuffer.toString()
		digitBuffer.setLength(0)
		if (buffer.isEmpty()) return
		val number = buffer.toIntOrNull() ?: return
		val channel = Channels.byNumber(number)
		if (channel == null) {
			toast("没有编号 $number 的频道")
			return
		}
		activeTab = channel.category
		switchTo(channel.name)
		uiMode = UiMode.STANDARD
	}

	private fun handleDigit(keyCode: Int): Boolean {
		val digit = keyCode - KeyEvent.KEYCODE_0
		if (digit < 0 || digit > 9) return false
		if (digitBuffer.length >= 3) digitBuffer.setLength(0)
		digitBuffer.append(digit)
		toast("选台: ${digitBuffer}")
		mainLayout.removeCallbacks(digitApplyAction)
		mainLayout.postDelayed(digitApplyAction, DIGIT_INPUT_TIMEOUT)
		return true
	}

	// ---------------- 生命周期 ----------------

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
		// 交由 dispatchKeyEvent 统一处理
	}

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		repostCloseAction()
		return super.dispatchTouchEvent(event)
	}

	@SuppressLint("RestrictedApi")
	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
			repostCloseAction()
		}
		val keyCode = event.keyCode
		val isActionUp = event.action == KeyEvent.ACTION_UP

		// 数字键选台(播放态与换台弹窗内均可用)
		if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
			if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
				return handleDigit(keyCode)
			}
			return true
		}

		// 换台弹窗打开
		if (uiMode == UiMode.CHANNELS && channelSheet.visibility == View.VISIBLE) {
			if (keyCode == KeyEvent.KEYCODE_BACK && isActionUp) {
				uiMode = UiMode.STANDARD
				return true
			}
			// OK 键: 长按收藏, 短按播放
			if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
				if (channelSheet.focusedChannel().isEmpty()) {
					return super.dispatchKeyEvent(event)
				}
				if (event.action == KeyEvent.ACTION_DOWN) {
					if (!isActionUp && event.repeatCount == 0) {
						longPressOkFired = false
						mainLayout.removeCallbacks(longPressOkAction)
						mainLayout.postDelayed(longPressOkAction, LONG_PRESS_OK_DELAY)
					}
					return true
				}
				if (isActionUp) {
					mainLayout.removeCallbacks(longPressOkAction)
					if (!longPressOkFired) channelSheet.selectFocused()
					return true
				}
			}
			return super.dispatchKeyEvent(event)
		}

		// 清晰度弹窗打开
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
			// 下键 = 往下播放(下一个), 上键 = 往上播放(上一个)
			isActionUp && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
				switchChannelByStep(forward = true)
				return true
			}
			isActionUp && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
				switchChannelByStep(forward = false)
				return true
			}
		}
		return if (OPERATION_KEYS.contains(keyCode)) true else super.dispatchKeyEvent(event)
	}

	private fun repostCloseAction() {
		mainLayout.removeCallbacks(closeSheetAction)
		if (uiMode == UiMode.CHANNELS || uiMode == UiMode.QUALITY) {
			mainLayout.postDelayed(closeSheetAction, SHEET_TIMEOUT)
		}
	}

	private fun qualityShort(label: String) = label.substringBefore(' ')

	private fun toastChannel(name: String) {
		val channel = Channels.ALL.firstOrNull { it.name == name }
		toast(if (channel != null) "(${channel.number}) $name" else name)
	}

	private var toast: Toast? = null

	@SuppressLint("ShowToast")
	private fun toast(message: String) {
		runOnUiThread {
			toast?.cancel()
			toast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
		}
	}
}