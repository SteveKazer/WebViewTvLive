// =====================================================================
// 央视频TV 注入脚本 (注入到 https://www.yangshipin.cn/tv/home)
// ---------------------------------------------------------------------
// 核心思想: 只打开一个标签页, 沉浸式播放。(v2.4.0 系统 WebView/Chromium 版)
//
// 沉浸式方案(已实测):
//   页面自身的"全屏"按钮在部分设备上不可靠, 因此改为注入 CSS:
//   - 隐藏网页头部/频道侧栏/节目单/页脚
//   - 把播放器容器(.c-container)固定为铺满整个视口
//   - 隐藏 video.js 大播放按钮(.vjs-big-play-button)
//   切换频道时页面原生的"全力加载中"提示保留在播放器内。
//
// 换台/清晰度: 以"隐藏鼠标"程序化点击页面上的对应按钮完成。
//   频道按钮:  .tv-main-con-r-list-left > div
//   当前频道:  .tvSelect
//   清晰度:    .bei-list .item (蓝光/超清/高清, 隐藏态可直接点击)
//
// 视频隐藏器:
//   视频 paused/无数据或换台/切清晰度瞬间(在动作第一行)直接把 video 元素
//   visibility:hidden —— 切换过程黑幕平滑过渡、不露旧画面/控制条, 
//   恢复播放(playing/canplay)立即恢复显示; 事件驱动 + 200ms 轮询兑底。
//
// 频道检测:
//   每帧扫描页面频道 pill, 集合变化时上报原生 notifyChannels(json),
//   原生据此决定库中哪些频道可见(备用卫视检测到即显示)。
//
// 播放看门狗(播放卡死自愈, 实测页面 loading 层为 .loading.black-bg):
//   - 视频存在但 paused: 持续超 4 秒强制 video.play()
//   - 持续卡住超过 ~14 秒: 自动降一档清晰度(蓝光->超清->高清)重试
//   - 视频已在播但页面 loading 层仍卡住 >2 秒: 强制隐藏该层
//
// 对原生暴露 window.main (JavascriptInterface)
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
	function callNative(fn) {
		try {
			if (window.main && typeof window.main[fn] === 'function') { window.main[fn](); return true; }
		} catch (e) { /* ignore */ }
		return false;
	}

	// 按名称查找页面上对应的频道按钮
	function findPill(name) {
		var pills = qa('.tv-main-con-r-list-left > div');
		var target = norm(name);
		for (var i = 0; i < pills.length; i++) {
			if (norm(pills[i].textContent).indexOf(target) >= 0) { return pills[i]; }
		}
		return null;
	}

	// ---------------- 沉浸式 CSS ----------------
	var IMMERSIVE_CSS = [
		'html, body { margin:0 !important; padding:0 !important; overflow:hidden !important; background:#000 !important; }',
		'header, .header, [class^="header"], [class*=" header"] { display:none !important; }',
		'.tv-main-con-r, .tv-main-con-r-tab, .tv-main-con-r-list { display:none !important; }',
		'.tv-zhan, .tv-zhan-list { display:none !important; }',
		'footer, .footer { display:none !important; }',
		'.tv-main { margin:0 !important; padding:0 !important; width:100vw !important; max-width:none !important; }',
		'.tv-main-con, .tv-main-con-l, .tv-main-con-l-vid, .video-con { margin:0 !important; padding:0 !important; width:100% !important; height:100% !important; max-width:none !important; max-height:none !important; border:none !important; background:#000 !important; }',
		'.c-container { position:fixed !important; left:0 !important; top:0 !important; width:100vw !important; height:100vh !important; z-index:2147483000 !important; margin:0 !important; padding:0 !important; }',
		'.c-container video { width:100% !important; height:100% !important; object-fit:contain !important; }',
		'.y-full { width:100% !important; height:100% !important; }',
		'.y-full-control, .y-control-outside, .volume-muted-tip-container { display:none !important; }',
		'[class^="winG-mask"], [class^="winY-"], [class^="winA-mask"] { display:none !important; }',
		// video.js 大播放按钮: X5 下换清晰度/换台瞬间视频进入 paused 态会渲染巨大播放图标, 隐藏之
		'.vjs-big-play-button { display:none !important; }'
	].join('\n');

	function injectImmersiveCss() {
		if (document.getElementById('wvt-immersive-style')) { return; }
		var style = document.createElement('style');
		style.id = 'wvt-immersive-style';
		style.type = 'text/css';
		style.appendChild(document.createTextNode(IMMERSIVE_CSS));
		(document.head || document.documentElement).appendChild(style);
		log('immersive css injected');
	}

	window.__WVT__ = {

		// 换台: 映射点击页面频道按钮
		switchChannel: function(name) {
			var pill = findPill(name);
			if (!pill) { console.error('[WVT] channel not found: ' + name); return false; }
			var cur = q('.tvSelect');
			if (cur && norm(cur.textContent) === norm(name)) { return true; } // 已是该频道
			setVideoSurface(q('video'), true); // 动作瞬间即隐藏视频, 切换过程黑幕平滑过渡
			pill.click();
			stallSince = Date.now(); // 换台后重置卡住计时
			log('switchChannel -> ' + name);
			return true;
		},

		// 设置清晰度: 隐藏态直接点击清晰度菜单项(已实测生效)
		setQuality: function(label) {
			var items = qa('.bei-list .item');
			var target = norm(label);
			for (var i = 0; i < items.length; i++) {
				if (norm(items[i].textContent).indexOf(target) >= 0) {
					if (!/active/.test(items[i].className)) {
						setVideoSurface(q('video'), true); // 切流瞬间即隐藏, 平滑过渡
						items[i].click();
					}
					log('setQuality -> ' + label);
					return true;
				}
			}
			console.error('[WVT] quality not found: ' + label + ', items=' + items.length);
			return false;
		},

		// 沉浸式样式是否已生效
		isImmersive: function() {
			return !!document.getElementById('wvt-immersive-style');
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
				immersive: window.__WVT__.isImmersive()
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
	// 1. 保持沉浸式样式(页面可能重渲染清除)
	function watchImmersive() {
		try {
			injectImmersiveCss();
			var v = q('video');
			if (v && v.muted) { v.muted = false; }
			if (v && v.volume < 1) { v.volume = 1; }
		} catch (e) { console.error('[WVT] watchImmersive: ' + e.message); }
		setTimeout(watchImmersive, 2000);
	}

	// 1.5 视频隐藏器: X5 在视频 paused/无数据时会在视频表面渲染原生大播放图标,
	//     不在 DOM 内, CSS 与 DOM 盖层均无法遮盖。方案: 直接把 video 元素
	//     visibility:hidden —— 视频层整体不渲染, 原生图标随之消失;
	//     事件驱动(pause/emptied 立即隐藏, playing 立即恢复), 轮询兑底。
	//     页面"全力加载中"提示层(z:99)位于视频之上, 不受影响。
	var surfaceEmptySince = 0;
	var boundVideo = null;
	function ensurePauseCover() {
		var c = document.getElementById('wvt-pause-cover');
		if (c) { return c; }
		var container = q('.c-container');
		if (!container) { return null; }
		c = document.createElement('div');
		c.id = 'wvt-pause-cover';
		c.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;background:#000;z-index:50;display:none;pointer-events:none;';
		container.appendChild(c);
		log('pause cover installed');
		return c;
	}
	function setVideoSurface(v, hide) {
		try {
			var target = hide ? 'hidden' : '';
			if (v.style.visibility !== target) { v.style.visibility = target; }
			var cover = ensurePauseCover();
			if (cover) { cover.style.display = hide ? 'block' : 'none'; }
		} catch (e) { /* ignore */ }
	}
	function bindVideoEvents(v) {
		if (v === boundVideo) { return; }
		boundVideo = v;
		try {
			v.addEventListener('pause', function() { setVideoSurface(v, true); });
			v.addEventListener('emptied', function() { setVideoSurface(v, true); });
			v.addEventListener('playing', function() { setVideoSurface(v, false); });
			v.addEventListener('canplay', function() { if (!v.paused) { setVideoSurface(v, false); } });
		} catch (e) { /* ignore */ }
	}
	function watchVideoSurface() {
		try {
			var v = q('video');
			if (v) {
				bindVideoEvents(v);
				var hide = v.paused && !v.ended;
				if (!hide && v.readyState <= 1) {
					// 持续无数据(切清晰度/换台后)超过 600ms 也隐藏
					if (surfaceEmptySince === 0) { surfaceEmptySince = Date.now(); }
					else if (Date.now() - surfaceEmptySince > 600) { hide = true; }
				} else { surfaceEmptySince = 0; }
				setVideoSurface(v, hide);
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchVideoSurface, 200);
	}

	// 2. 向原生上报播放状态; 首帧就绪后通知揭幕
	var lastNotify = 0;
	var firstFrameSent = false;
	function watchVideo() {
		try {
			var v = q('video');
			if (v && !v.paused) {
				var now = Date.now();
				if (now - lastNotify > 3000) {
					lastNotify = now;
					callNative('notifyVideoPlaying');
				}
				if (!firstFrameSent && window.__WVT__.isImmersive()) {
					firstFrameSent = true;
					callNative('notifyFirstFrame');
					log('first frame ready');
				}
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchVideo, 800);
	}

	// 3. 播放看门狗: 视频卡住(电视上页面 loading 永不消失)时自愈
	//    - paused -> 每 2 秒强制 play()
	//    - 卡住超过 14 秒 -> 自动降一档清晰度重试(最多降两档)
	//    - 已播放但页面 loading 层卡住 >2 秒 -> 隐藏该层
	var stallSince = Date.now();      // 本次卡住开始时间
	var qualityDropLevel = 0;         // 本会话已自动降档次数(0=未降)
	var playingNotified = 0;          // 播放中且 loading 可见的采样起点(0=未见)
	var DROP_ORDER = ['超清', '高清'];  // 蓝光起依次降级

	function watchStall() {
		try {
			var v = q('video');
			var loading = q('.loading.black-bg');
			var loadingVisible = !!(loading && getComputedStyle(loading).display !== 'none');

			if (v && v.paused) {
				// 视频暂停: 强制恢复播放(X5 下换清晰度/加载失败可能停在 paused)
				if (Date.now() - stallSince > 4000) { // 只在卡住较久后干预, 避免影响页面自身逻辑
					var p = v.play();
					if (p && typeof p.catch === 'function') { p.catch(function() { /* autoplay 被拒, 等下次 */ }); }
					log('stall watchdog: force play()');
				}
				// 卡住超过 14 秒 -> 自动降一档清晰度重试(含换台后卡死, 与首帧无关)
				if (Date.now() - stallSince > 14000 && qualityDropLevel < DROP_ORDER.length) {
					window.__WVT__.setQuality(DROP_ORDER[qualityDropLevel]);
					qualityDropLevel++;
					stallSince = Date.now(); // 给新清晰度一个完整的观察窗口
					log('stall watchdog: drop quality -> ' + DROP_ORDER[qualityDropLevel - 1]);
				}
			}

			// 已起播但页面 loading 层卡住不消失 -> 强制隐藏
			if (v && !v.paused && loadingVisible) {
				if (playingNotified === 0) { playingNotified = Date.now(); }
				else if (Date.now() - playingNotified > 2000) {
					loading.style.display = 'none';
					log('stall watchdog: hide stuck page loading');
					playingNotified = 0;
				}
			} else {
				playingNotified = 0;
			}

			// 视频真正恢复播放时重置卡住计时
			if (v && !v.paused && Date.now() - stallSince > 2000) {
				stallSince = Date.now();
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchStall, 2000);
	}

	// 4. 频道检测: 页面频道集合变化时上报原生(备用卫视检测到即显示)
	var lastPillSig = '';
	function watchDetect() {
		try {
			var pills = qa('.tv-main-con-r-list-left > div');
			if (pills.length) {
				var names = [];
				for (var i = 0; i < pills.length; i++) {
					names.push((pills[i].textContent || '').replace(/\(限免\)|\(VIP\)|（限免）|（VIP）/g, '').replace(/\s+/g, ''));
				}
				var sig = names.join('|');
				if (sig !== lastPillSig && window.main && typeof window.main.notifyChannels === 'function') {
					lastPillSig = sig; // 只在可上报时记录, 原生桥未就绪则下次重试
					try {
						window.main.notifyChannels(JSON.stringify(names));
						log('channels detected: ' + names.length);
					} catch (e) { lastPillSig = ''; } // 失败重置, 下轮重试
				}
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchDetect, 5000);
	}

	log('injected, yangshipin immersive edition v2.4.0 (chromium)');
	injectImmersiveCss();
	watchImmersive();
	watchVideo();
	watchStall();
	watchVideoSurface();
	watchDetect();
	callNative('notifyScriptReady');
})();
