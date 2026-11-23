// =====================================================================
// 央视频TV 注入脚本 (注入到 https://www.yangshipin.cn/tv/home)
// ---------------------------------------------------------------------
// 核心思想: 只打开一个标签页, 沉浸式播放。(v2.4.6 双内核版: 系统 WebView / 腾讯 X5)
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
// 切流黑幕策略(v2.4.5):
//   换台/切清晰度统一全程原生黑幕(实测设备端 WebView 在视频表面
//   未就绪时会立即渲染原生占位图形(黑色大三角), 不留任何空窗)。
//   视频真正恢复播放(rs>=3 且未暂停)后 notifyPlaybackResumed() 揭幕。
//   切清晰度黑幕期间 seek 回切换前位置(避免页面从 ct=0 重播已看内容)。
//
// 切流期间静音(v2.4.5):
//   切流瞬间立即 v.muted=true —— 旧台声音立刻消失(此前旧画面隐藏但
//   声音还在), 画面确认后才解除静音, 声音与画面同时出现(此前先有声后
//   有画)。watchImmersive 的自动解除静音在切流期间被抑制。
//
// 换台清晰度跟随(实测: 页面切台会重置回蓝光):
//   720P/540P 用户切台时页面会先加载蓝光流(电视上慢且易死锁),
//   故切台后新流起播时自动切回目标清晰度, 全程黑幕盖住中间过程。
//
// 切台自愈:
//   切台后 12 秒仍无有效画面时自动重试点击频道
//   (相当于"切其他台再切回来"), 最多两次; 重试时黑幕持续盖住。
//   页面出现版权限制提示时自愈停止(重试无法救活受限节目)。
//
// 版权限制直出(v2.4.6):
//   部分节目因版权限制无法播放, 页面会渲染 .loading1 .txt-b
//   (如"应版权方要求, 暂停提供直播信号")。检测到该结构且文本
//   含 版权/限制/变更 等关键词时, 立即 notifyCopyrightBlocked()
//   让原生揭幕显示版权界面, 不再无限卡在"正在切换…"。
//
// 版权保活(v2.4.6):
//   命中后把页面自带的版权提示克隆一份, 固定居中钉在最上层
//   (页面 DOM 退化成黑屏/残留大播放按钮时仍持续可见), 被页面
//   删除时按快照自动重建; 一直保持到用户换台。
//   版权态下清晰度操作被忽略(页面不会响应, 避免卡"正在切换中"),
//   也不再弹原生底部文字提示。
//
// 卡顿提示(v2.4.6):
//   播放中持续缓冲(readyState<3)超过 4 秒且距上次提示 >60 秒时,
//   notifyStutter() 原生底部提示用户尝试更换清晰度。
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
//   - 视频已在播但页面 loading 层仍卡住 >2 秒: 强制隐藏该层
//   注: 不自动降清晰度(v2.4.2 起按用户要求移除画质兜底,
//   卡顿时由用户自行切换清晰度)。
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

	// 调用原生盖切流黑幕(仅当原生遮罩存在时生效, 桌面环境忽略)
	function notifySwitchBegin() {
		try {
			if (window.main && typeof window.main.notifySwitchBegin === 'function') { window.main.notifySwitchBegin(); }
		} catch (e) { /* ignore */ }
	}

	// 视频真正恢复播放且确实有画面后, 通知原生揭幕
	function notifyPlaybackResumed() {
		try {
			if (window.main && typeof window.main.notifyPlaybackResumed === 'function') { window.main.notifyPlaybackResumed(); }
		} catch (e) { /* ignore */ }
	}

	// 页面出现版权限制提示: 通知原生立即揭幕, 展示页面版权界面
	function notifyCopyrightBlocked() {
		try {
			if (window.main && typeof window.main.notifyCopyrightBlocked === 'function') { window.main.notifyCopyrightBlocked(); }
		} catch (e) { /* ignore */ }
	}

	// 调用原生盖切流黑幕并隐藏/静音视频表面(切流动作统一入口)
	// 静音: 旧台声音立即消失; 揭幕(画面确认)时才解除 —— 声画同步
	function beginStreamSwitch(videoEl) {
		var v = videoEl || q('video');
		try {
			if (v && !v.muted) { v.muted = true; switchMuted = true; }
		} catch (e) { /* ignore */ }
		setVideoSurface(v, true);
		notifySwitchBegin();
	}

	// ---------------- 清晰度切换: 位置恢复 ----------------
	var pendingSeek = 0;          // 切换前位置(0=无需恢复)
	var switchMuted = false;      // 切流期间主动静音(画面确认后解除)

	// 新流起播后位置恢复: 页面换流会从 ct=0 重播, 若明显回退则 seek 回切换前位置
	function finishPendingSeek(v) {
		if (pendingSeek > 0 && v && v.readyState >= 1 &&
			isFinite(v.duration) && v.duration >= pendingSeek &&
			v.currentTime < pendingSeek - 5) {
			try {
				v.currentTime = pendingSeek;
				log('quality switch: resume at ' + Math.round(pendingSeek) + 's');
			} catch (e) { /* ignore */ }
		}
		pendingSeek = 0;
	}

	window.__WVT__ = {

		// 换台: 映射点击页面频道按钮; resumeQuality=换台后跟随恢复的清晰度
		switchChannel: function(name, resumeQuality) {
			var pill = findPill(name);
			if (!pill) { console.error('[WVT] channel not found: ' + name); return false; }
			var cur = q('.tvSelect');
			if (cur && norm(cur.textContent) === norm(name)) {
				// 已是该频道: 仅跟随清晰度(不重复点击)
				if (resumeQuality) { pendingFollowQuality = norm(resumeQuality); }
				return true;
			}
			pendingSeek = 0;             // 换台: 位置恢复无意义
			copyrightSeen = false;       // 换台: 重置版权标记
			removeCopyrightKeeper();     // 换台: 移除版权保活层
			copyrightIgnoreUntil = Date.now() + 3000; // 换台后 3s 内不检测版权
			pendingFollowQuality = resumeQuality ? norm(resumeQuality) : ''; // 页面切台会重置回蓝光
			retryTarget = name;          // 自愈重试目标
			retryAt = Date.now();
			retryCount = 0;
			beginStreamSwitch();          // 动作瞬间: 静音+隐藏视频 + 原生黑幕
			pill.click();
			stallSince = Date.now(); // 换台后重置卡住计时
			// 清晰度跟随提前点击: 新台加载早期即点击目标清晰度,
			// 不等蓝光流完整起播, 省一次完整加载(小内存电视更轻快)
			if (pendingFollowQuality) { armFollowQuality(pendingFollowQuality, name); }
			log('switchChannel -> ' + name + (resumeQuality ? ' (follow ' + resumeQuality + ')' : ''));
			return true;
		},

		// 设置清晰度: 隐藏态直接点击清晰度菜单项(已实测生效)
		// 全程黑幕: 遮设备端占位图形; 记录当前位置,
		// 起播后 seek 回去避免从 ct=0 重播
		// noSeek=true 时不记录位置(换台跟随场景, 新台不应继承旧台位置)
		// 版权受限节目: 清晰度操作无意义(页面不会切流), 直接忽略,
		// 避免盖黑幕后页面无响应卡在"正在切换中"
		setQuality: function(label, noSeek) {
			if (copyrightSeen) {
				log('setQuality ignored (copyright): ' + label);
				return false;
			}
			var items = qa('.bei-list .item');
			var target = norm(label);
			for (var i = 0; i < items.length; i++) {
				if (norm(items[i].textContent).indexOf(target) >= 0) {
					if (!/active/.test(items[i].className)) {
						var v = q('video');
						pendingSeek = (!noSeek && v && isFinite(v.duration) && v.currentTime > 3) ? v.currentTime : 0;
						beginStreamSwitch(); // 立即静音+黑幕 + 隐藏视频
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

		// 启动恢复: 页面频道列表一旦就绪立即切换(不等默认频道起播,
		// 省一次完整加载约 3~8 秒); 清晰度经换台跟随机制自动恢复
		restore: function(channel, quality) {
			var tries = 0;
			function tryRestore() {
				var pill = channel ? findPill(channel) : null;
				if (channel && pill) {
					setTimeout(function() { // 等 Vue 事件绑定稳定
						window.__WVT__.switchChannel(channel, quality || '');
					}, 300);
					return;
				}
				if (++tries < 45) { setTimeout(tryRestore, 400); } // 最多等待 ~18 秒
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
				if (v && v.muted && !switchMuted) { v.muted = false; }
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
			// 新流元数据就绪即恢复位置(早于首帧渲染)
			v.addEventListener('loadedmetadata', function() { if (pendingSeek > 0) { finishPendingSeek(v); } });
			v.addEventListener('playing', function() {
				setVideoSurface(v, false);
				// 换台清晰度跟随兑底: 起播时若仍与目标不符则切回
				// (提前点击路径失败时接管; noSeek: 新台不继承旧台位置)
				if (pendingFollowQuality) {
					var bei = q('.bei span');
					var curQ = bei ? norm(bei.textContent) : '';
					if (curQ && curQ.indexOf(pendingFollowQuality) < 0) {
						log('follow quality after switch -> ' + pendingFollowQuality);
						window.__WVT__.setQuality(pendingFollowQuality, true);
						return; // 等目标清晰度流起播后再揭幕
					}
					pendingFollowQuality = '';
				}
				finishPendingSeek(v); // 恢复到切换前位置(避开页面从 ct=0 重播)
				// 画面确认后解除静音并通知原生揭幕 —— 声音与画面同时出现
				setTimeout(function() {
					if (!v.paused && v.readyState >= 3 && v.videoWidth > 0) {
						try {
							if (v.muted && switchMuted) { v.muted = false; switchMuted = false; }
						} catch (e) { /* ignore */ }
						notifyPlaybackResumed();
					}
				}, 250);
			});
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
					// 持续无数据(切流后)超过 600ms 也隐藏
					if (surfaceEmptySince === 0) { surfaceEmptySince = Date.now(); }
					else if (Date.now() - surfaceEmptySince > 600) { hide = true; }
				} else { surfaceEmptySince = 0; }
				setVideoSurface(v, hide);
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchVideoSurface, 250);
	}

	// 2. 向原生上报播放状态; 首帧就绪后通知揭幕
	var lastNotify = 0;
	var firstFrameSent = false;
	// 换台后需跟随恢复的清晰度(页面切台会重置回蓝光)
	var pendingFollowQuality = '';
	// 切台自愈: 无画面时自动重试点击频道
	var retryTarget = '';
	var retryAt = 0;
	var retryCount = 0;
	// 版权限制: 命中页面版权提示后置 true, 停止自愈重试
	var copyrightSeen = false;
	// 换台后短暂静默版权检测(等新台 DOM 渲染, 避免旧提示误报)
	var copyrightIgnoreUntil = 0;
	// 版权保活: 版权提示元素的 outerHTML 快照(元素被页面删除后用于重建)
	var copyrightSnapshotHtml = '';

	// 版权保活层(v2.4.6): 把页面自带的版权提示克隆一份, 固定居中钉在
	// 最上层(z-index 2147483100)。页面 DOM 退化(黑屏/残留大播放按钮)时
	// 版权提示依然持续可见; 保活层被页面清除时按快照重建。
	// 命中后保持显示直到换台(switchChannel 移除), 不再弹原生文字提示。
	function installCopyrightKeeper(srcEl) {
		try {
			if (srcEl) { copyrightSnapshotHtml = srcEl.outerHTML; }
			if (!copyrightSnapshotHtml) { return; }
			var wrap = document.getElementById('wvt-copyright-keeper');
			if (!wrap) {
				wrap = document.createElement('div');
				wrap.id = 'wvt-copyright-keeper';
				// 纯展示层: 不拦截任何事件
				wrap.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483100;display:flex;align-items:center;justify-content:center;background:rgba(8,8,10,0.94);pointer-events:none;';
				(document.body || document.documentElement).appendChild(wrap);
				log('copyright keeper installed');
			}
			wrap.innerHTML = '<div style="max-width:82%;text-align:center;">' + copyrightSnapshotHtml + '</div>';
		} catch (e) { /* ignore */ }
	}
	function removeCopyrightKeeper() {
		try {
			var wrap = document.getElementById('wvt-copyright-keeper');
			if (wrap && wrap.parentNode) { wrap.parentNode.removeChild(wrap); }
			copyrightSnapshotHtml = '';
		} catch (e) { /* ignore */ }
	}

	// 版权限制检测: 页面 .loading1 .txt-b 文本含 版权/限制/变更 即命中。
	// 命中后: 通知原生揭幕 + 安装版权保活层, 并终止换台自愈重试。
	// 命中即保持(粘性): 即使页面随后删掉版权元素也继续显示, 直到换台。
	function watchCopyright() {
		try {
			if (Date.now() < copyrightIgnoreUntil) {
				setTimeout(watchCopyright, 1000);
				return;
			}
			if (copyrightSeen) {
				// 粘性保持: 保活层被页面清除时按快照重建
				if (!document.getElementById('wvt-copyright-keeper')) { installCopyrightKeeper(null); }
				setTimeout(watchCopyright, 1000);
				return;
			}
			var t = q('.loading1 .txt-b');
			if (t) {
				var txt = (t.textContent || '').trim();
				if (/(版权|限制|变更)/.test(txt)) {
				copyrightSeen = true;
				retryTarget = '';    // 版权节目重试无法救活, 停止自愈
				switchMuted = false; // 版权节目无音源, 清除切流静音标记
				installCopyrightKeeper(t.closest ? (t.closest('.loading1') || t) : t);
					notifyCopyrightBlocked();
					log('copyright blocked: ' + txt);
				setTimeout(watchCopyright, 1000);
				return;
				}
			}
		} catch (e) { /* ignore */ }
		setTimeout(watchCopyright, 1000);
	}

	// 清晰度跟随提前点击: 页面切台会重置回蓝光; 等新台真正生效
	// (tvSelect 变为新台, 避免旧台菜单未刷新的竞态)后比对清晰度,
	// 不符立即点击目标; 兑底由 playing 事件路径接管
	function armFollowQuality(target, channelName) {
		var tries = 0;
		function poll() {
			if (!pendingFollowQuality || pendingFollowQuality !== target) { return; } // 已被处理
			var sel = q('.tvSelect');
			var onNewChannel = !!(sel && norm(sel.textContent).indexOf(norm(channelName)) >= 0);
			if (!onNewChannel) {
				if (++tries < 20) { setTimeout(poll, 300); } // 新台未生效, 等
				return;
			}
			var bei = q('.bei span');
			var curQ = bei ? norm(bei.textContent) : '';
			if (!curQ || curQ.indexOf(target) >= 0) {
				if (++tries < 25) { setTimeout(poll, 300); } // 等页面重置(或已是目标, 交 playing 兑底核查)
				return;
			}
			// 已被重置(蓝光) -> 立即点击目标清晰度(不等蓝光流完整起播)
			pendingFollowQuality = '';
			window.__WVT__.setQuality(target, true);
			log('follow quality early -> ' + target);
		}
		setTimeout(poll, 300);
	}

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
		setTimeout(watchVideo, 1000);
	}

	// 3. 播放看门狗: 视频卡住(电视上页面 loading 永不消失)时自愈
	//    - paused -> 每 2 秒强制 play()
	//    - 已播放但页面 loading 层卡住 >2 秒 -> 隐藏该层
	//    (不自动降清晰度: v2.4.2 起按用户要求移除画质兜底)
	var stallSince = Date.now();      // 本次卡住开始时间
	var playingNotified = 0;          // 播放中且 loading 可见的采样起点(0=未见)
	// 卡顿提示: 持续缓冲计时 + 上次提示时间(60 秒内不重复打扰)
	var stutterSince = 0;
	var lastStutterAt = 0;

	function watchStall() {
		try {
			var v = q('video');
			var loading = q('.loading.black-bg');
			var loadingVisible = !!(loading && getComputedStyle(loading).display !== 'none');

			if (v && v.paused) {
				// 视频暂停: 强制恢复播放(换清晰度/加载失败可能停在 paused)
				if (Date.now() - stallSince > 4000) { // 只在卡住较久后干预, 避免影响页面自身逻辑
					var p = v.play();
					if (p && typeof p.catch === 'function') { p.catch(function() { /* autoplay 被拒, 等下次 */ }); }
					log('stall watchdog: force play()');
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

			// 卡顿提示: 播放中持续缓冲 >4s 且距上次提示 >60s, 提示更换清晰度
			// (切流中/清晰度跟随中/版权节目除外, 避免误报)
			if (v && !v.paused && !switchMuted && !pendingFollowQuality && !copyrightSeen) {
				if (v.readyState < 3) {
					if (stutterSince === 0) { stutterSince = Date.now(); }
					else if (Date.now() - stutterSince > 4000 && Date.now() - lastStutterAt > 60000) {
						lastStutterAt = Date.now();
						stutterSince = 0;
						callNative('notifyStutter');
						log('stutter: suggest lower quality');
					}
				} else { stutterSince = 0; }
			} else { stutterSince = 0; }

			// 换台自愈: 切台后 12s 仍无有效画面(rs<3 或无视频宽)时
			// 自动重试点击频道(相当于"切其他台再切回来"), 最多两次;
			// 重试时重新盖黑幕(App 侧 15s 兑底揭幕计时同步重置)
			if (retryTarget && Date.now() - retryAt > 12000 && retryCount < 2) {
				if (!v || v.paused || v.readyState < 3 || v.videoWidth === 0) {
					var pill = findPill(retryTarget);
					if (pill) {
						beginStreamSwitch(); // 黑幕继续盖住
						pill.click();
						if (pendingFollowQuality) { armFollowQuality(pendingFollowQuality, retryTarget); }
						retryAt = Date.now();
						retryCount++;
						stallSince = Date.now();
						log('channel switch self-retry #' + retryCount + ' -> ' + retryTarget);
					}
				} else {
					retryTarget = ''; // 画面有效, 自愈结束
				}
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

	log('injected, yangshipin immersive edition v2.4.9 (dual-kernel + hw-render)');
	injectImmersiveCss();
	watchImmersive();
	watchVideo();
	watchStall();
	watchVideoSurface();
	watchDetect();
	watchCopyright();
	callNative('notifyScriptReady');
})();
