// =====================================================================
// 央视频TV 注入脚本 (注入到 https://www.yangshipin.cn/tv/home)
// ---------------------------------------------------------------------
// 核心思想: 只打开一个标签页, 沉浸式播放。
//
// 沉浸式方案(已实测):
//   页面自身的"全屏"按钮在部分设备上不可靠, 因此改为注入 CSS:
//   - 隐藏网页头部/频道侧栏/节目单/页脚
//   - 把播放器容器(.c-container)固定为铺满整个视口
//   切换频道时页面原生的"切换努力加载中"提示保留在播放器内。
//
// 换台/清晰度: 以"隐藏鼠标"程序化点击页面上的对应按钮完成。
//   频道按钮:  .tv-main-con-r-list-left > div
//   当前频道:  .tvSelect
//   清晰度:    .bei-list .item (蓝光/超清/高清, 隐藏态可直接点击)
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
		'[class^="winG-mask"], [class^="winY-"], [class^="winA-mask"] { display:none !important; }'
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

	// 2. 向原生上报播放状态
	var lastNotify = 0;
	function watchVideo() {
		try {
			var v = q('video');
			if (v && !v.paused) {
				var now = Date.now();
				if (now - lastNotify > 3000) {
					lastNotify = now;
					callNative('notifyVideoPlaying');
				}
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchVideo, 1500);
	}

	log('injected, yangshipin immersive edition v2.1');
	injectImmersiveCss();
	watchImmersive();
	watchVideo();
	callNative('notifyScriptReady');
})();