// =====================================================================
// 央视频TV 注入脚本 (注入到 https://www.yangshipin.cn/tv/home)
// ---------------------------------------------------------------------
// 核心思想: 只打开一个标签页, 所有"换台/清晰度/全屏"操作都由本脚本
// 以"隐藏鼠标"的方式程序化点击页面上的对应按钮完成。
//
// 页面结构(2026-11 实测):
//   频道按钮:  .tv-main-con-r-list-left > div   (央视 imga / 卫视 imgb)
//   当前频道:  .tvSelect
//   清晰度:    .bei(当前档位) .bei-list .item(蓝光/超清/高清, 隐藏态可直接点击)
//   整体全屏:  .full.full2  (不用 .videoFull 网页窗口全屏)
//
// 对原生暴露 window.main (JavascriptInterface, 见 WebpageAdapterWebView)
// =====================================================================
(function() {
	if (window.__WVT__) { return; }

	function q(sel) { return document.querySelector(sel); }
	function qa(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }
	// 名称归一化: 去空白, 全角括号转半角, 统一大写比较
	function norm(s) {
		return (s || '').replace(/\s+/g, '')
			.replace(/（/g, '(').replace(/）/g, ')')
			.replace(/［/g, '[').replace(/］/g, ']')
			.toUpperCase();
	}
	function log(msg) { console.log('[WVT] ' + msg); }

	// 按名称查找页面上对应的频道按钮
	function findPill(name) {
		var pills = qa('.tv-main-con-r-list-left > div');
		var target = norm(name);
		for (var i = 0; i < pills.length; i++) {
			if (norm(pills[i].textContent).indexOf(target) >= 0) { return pills[i]; }
		}
		return null;
	}

	window.__WVT__ = {

		// 换台: 映射点击页面频道按钮
		switchChannel: function(name) {
			var pill = findPill(name);
			if (!pill) { console.error('[WVT] channel not found: ' + name); return false; }
			var cur = q('.tvSelect');
			if (cur && norm(cur.textContent) === norm(name)) { return true; } // 已是该频道
			pill.click();
			log('switchChannel -> ' + name);
			return true;
		},

		// 设置清晰度: 隐藏态直接点击清晰度菜单项(已实测生效)
		setQuality: function(label) {
			var items = qa('.bei-list .item');
			var target = norm(label);
			for (var i = 0; i < items.length; i++) {
				if (norm(items[i].textContent).indexOf(target) >= 0) {
					if (!/active/.test(items[i].className)) { items[i].click(); }
					log('setQuality -> ' + label);
					return true;
				}
			}
			console.error('[WVT] quality not found: ' + label + ', items=' + items.length);
			return false;
		},

		// 点击页面"整体全屏"按钮(不使用 .videoFull 网页窗口全屏)
		enterFullscreen: function() {
			var btn = q('.full.full2') || q('.y-full-control-btnr .full') || q('.y-full-control-btnr');
			if (btn) { btn.click(); log('enterFullscreen clicked'); return true; }
			return false;
		},

		// 视频是否已铺满视口(页面内全屏 或 Fullscreen API 全屏)
		isPageFullscreen: function() {
			if (document.fullscreenElement || document.webkitFullscreenElement) { return true; }
			var v = q('video');
			if (!v) { return false; }
			var r = v.getBoundingClientRect();
			return r.width >= window.innerWidth * 0.97 && r.height >= window.innerHeight * 0.95;
		},

		// 查询页面当前状态, 返回 JSON 字符串
		getState: function() {
			var v = q('video');
			var sel = q('.tvSelect');
			var bei = q('.bei span');
			return JSON.stringify({
				channel: sel ? (sel.textContent || '').trim() : '',
				quality: bei ? (bei.textContent || '').trim() : '',
				playing: !!(v && !v.paused),
				fullscreen: window.__WVT__.isPageFullscreen()
			});
		},

		// 启动恢复: 等视频起播后, 切换到上次频道 + 上次清晰度
		restore: function(channel, quality) {
			var tries = 0;
			function tryRestore() {
				var v = q('video');
				if (v && !v.paused) {
					if (channel) { window.__WVT__.switchChannel(channel); }
					if (quality) {
						setTimeout(function() { window.__WVT__.setQuality(quality); }, 1500);
					}
					return;
				}
				if (++tries < 45) { setTimeout(tryRestore, 500); } // 最多等待 ~22 秒
				else { log('restore timeout, keep default channel'); }
			}
			tryRestore();
		},

		// JS 控制播放/暂停(供 App 生命周期使用)
		play: function() { var v = q('video'); if (v && v.paused) { v.play(); } },
		pause: function() { var v = q('video'); if (v && !v.paused) { v.pause(); } }
	};

	// ------------------- 自动化循环 -------------------
	// 1. 守护全屏: 视频在播但未全屏 -> 优先点页面全屏按钮,
	//    失败则请原生注入一次 F 键兜底(video.js 原生快捷键)
	var lastFsAttempt = 0;
	function watchFullscreen() {
		try {
			var v = q('video');
			if (v && !v.paused && !window.__WVT__.isPageFullscreen()) {
				var now = Date.now();
				if (now - lastFsAttempt > 2500) {
					lastFsAttempt = now;
					if (!window.__WVT__.enterFullscreen() && window.main && window.main.requestKeyFullscreen) {
						window.main.requestKeyFullscreen();
					}
				}
			}
		} catch (e) { console.error('[WVT] watchFullscreen: ' + e.message); }
		setTimeout(watchFullscreen, 2000);
	}

	// 2. 确保视频有声 + 向原生上报播放状态
	var lastNotify = 0;
	function watchVideo() {
		try {
			var v = q('video');
			if (v && !v.paused) {
				if (v.muted) { v.muted = false; }
				if (v.volume < 1) { v.volume = 1; }
				var now = Date.now();
				if (window.main && window.main.notifyVideoPlaying && now - lastNotify > 3000) {
					lastNotify = now;
					window.main.notifyVideoPlaying();
				}
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchVideo, 1500);
	}

	log('injected, yangshipin-only edition.');
	watchFullscreen();
	watchVideo();
})();
