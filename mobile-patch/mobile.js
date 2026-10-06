/**
 * v1.13 新增：Iterator Helpers polyfill。
 * 背景：@deepseek-ai/dsh-client-ui-sidebar-documentpreview 的 client.js 顶层有
 *   "function"!=typeof Iterator.prototype.join&&(Iterator.prototype.join=...)
 * 而 Iterator 是 ES2025（Chrome/WebView 122+ 才有）。老 WebView 上 Iterator 未定义
 * → 这一行直接 ReferenceError → 整个插件 import 失败 → 前端白页显示“Failed to load plugins”。
 * （用户反馈的 GitHub 最新版 bug；在 WebView ≥122 的设备上不复现。）
 * 位置：mobile.js 是 body 末尾的普通脚本，早于所有 module 脚本执行 —— 时机正确。
 * 只补全树实际用到的 Iterator.prototype.join（已扫描：仅此一处用法）。
 */
(function () {
  // 真实 %IteratorPrototype%（数组/字符串/Map/Set/生成器迭代器都继承它）
  var iterProto = null;
  try {
    if (typeof Symbol !== 'undefined' && Symbol.iterator) {
      iterProto = Object.getPrototypeOf(Object.getPrototypeOf([][Symbol.iterator]()));
    }
  } catch (e) { iterProto = null; }

  function joinImpl(sep) {
    sep = (sep === undefined) ? ',' : String(sep);
    if (this == null || typeof this.next !== 'function') {
      throw new TypeError('Iterator.prototype.join called on incompatible receiver');
    }
    var out = '', first = true, step;
    while (!(step = this.next()).done) {
      if (!first) out += sep;
      first = false;
      var v = step.value;
      out += (v === null || v === undefined) ? '' : String(v);   // 与规范一致：null/undefined 当作空串
    }
    return out;
  }

  if (iterProto && typeof iterProto.join !== 'function') iterProto.join = joinImpl;

  // 老 WebView 没有全局 Iterator：补一个占位，让 `typeof Iterator.prototype.join` 不抛
  if (typeof window.Iterator === 'undefined') {
    var It = function Iterator() { throw new TypeError('Iterator is not constructible'); };
    if (iterProto) { It.prototype = iterProto; } else { It.prototype.join = joinImpl; }
    window.Iterator = It;
  } else if (window.Iterator.prototype && typeof window.Iterator.prototype.join !== 'function') {
    window.Iterator.prototype.join = joinImpl;
  }
})();

/**
 * 移动端软键盘适配 v0.3（对应 APK v1.3.1）
 * v0.2（历史）：VisualViewport + translateY 方案，竖屏横屏通用，
 *   rAF 节流 + 异常保护，暴露 --kb-height 供 CSS 使用。
 * v0.3 新增（v1.3.1）：键盘防自动聚焦 —— 用 pointerdown 位置判断焦点来源，
 *   切换话题/新会话自动聚焦输入框时立即 blur（不弹键盘），
 *   只有用户真的点击输入框才弹键盘。
 * 注：窄屏侧栏改造（三条杠 + 浮层）在核心源码 dsh-client-ui-layout，不在此文件。
 */
(function () {
  if (!window.visualViewport) return;
  var vv = window.visualViewport;
  var app = document.getElementById('root') || document.body;
  var lastKb = 0;
  var rafId = 0;
  var raf = window.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };

  function computeKb() {
    // 键盘高度 ≈ 布局视口高度 - 视觉视口高度 - 视觉视口顶部偏移
    var kb = window.innerHeight - vv.height - vv.offsetTop;
    return Math.max(0, Math.round(kb));
  }

  function apply() {
    var kb = computeKb();
    if (Math.abs(kb - lastKb) < 6) return;
    lastKb = kb;
    document.documentElement.style.setProperty('--kb-height', kb + 'px');
    if (!app) return;
    if (kb > 120) {
      // 键盘弹出：把 App 容器向上平移，露出底部输入栏
      app.style.transform = 'translateY(' + (-kb) + 'px)';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.add('kb-open');
    } else {
      // 键盘收起：恢复原位
      app.style.transform = '';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.remove('kb-open');
    }
  }

  function schedule() {
    if (rafId) return;
    rafId = raf(function () {
      rafId = 0;
      apply();
    });
  }

  vv.addEventListener('resize', schedule);
  vv.addEventListener('scroll', schedule);
  window.addEventListener('resize', schedule);
  window.addEventListener('orientationchange', function () {
    // 旋转后等布局稳定再算一次
    setTimeout(schedule, 200);
  });
  // 记录用户最后一次真实点击（pointerdown）位置，用于区分
  // 「用户主动点击输入框」与「程序化聚焦」（如切换新话题后输入框自动 focus）
  var lastPointer = null;
  document.addEventListener('pointerdown', function (e) {
    lastPointer = { x: e.clientX, y: e.clientY, t: Date.now() };
  }, true);

  function isInputLike(t) {
    return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
  }

  document.addEventListener('focusin', function (e) {
    var t = e && e.target;
    if (!isInputLike(t)) return;
    // 判断这次聚焦是否由用户直接点击该输入框产生
    var userTapped = false;
    if (lastPointer && Date.now() - lastPointer.t < 800) {
      var r = t.getBoundingClientRect();
      userTapped = r.left <= lastPointer.x && lastPointer.x <= r.right &&
                   r.top <= lastPointer.y && lastPointer.y <= r.bottom;
    }
    if (!userTapped) {
      // 程序化聚焦（切换话题/新会话自动 focus）：立即失焦，避免键盘自动弹出
      setTimeout(function () {
        if (document.activeElement === t) t.blur();
      }, 0);
      return;
    }
    setTimeout(schedule, 150);
  });
  document.addEventListener('focusout', function (e) {
    if (isInputLike(e && e.target)) setTimeout(schedule, 150);
  });

  // 初始计算（等待首帧布局稳定）
  setTimeout(schedule, 100);
})();

// 注：插件按钮已改为核心实现（dsh-client-ui-cordis 注册到
// conversation.session.header.utilities，单一实例），由核心渲染；
// 位置用 mobile.css 的 position:fixed 挪到三条杠下方（不动 DOM，
// 保证 React 事件委托有效）。

/**
 * 手机端回车行为 v0.4——**回车换行，Ctrl/Cmd+回车 发送**
 *
 * 问题（用户反馈）：手机软键盘上按回车 = 直接把消息发出去，换不了行。
 * 成因：DSH 前端 composer 的键盘映射（dsh-client-ui-conversation 的
 *   registerComposerKeymap）对 Enter 命令只在 event.shiftKey === true 时
 *   放行给编辑器做换行，其余情况一律 handlers.submit()。实体键盘有 Shift，
 *   手机软键盘没有 —— 所以手机上永远触发不了换行。
 *
 * 做法：**不改内核代码**（保持 mobile-patch “只注入、不覆盖原生”的原则）。
 *   在 document 的捕获阶段拦下裸 Enter（仅限聊天输入栏 [data-composer-card]
 *   内、且不是输入法组词中），阻止默认行为与冒泡（=> Lexical 收不到“发送”），
 *   再补发一个 shiftKey=true 的合成 keydown：让编辑器走它自己本来就有的
 *   「Shift+Enter = 换行」路径（不自己拼 DOM / 不直接改 React 状态，
 *   避免受控组件状态脱节）。合成事件打 __dshSynthEnter 标记避免自拦截。
 *   万一 KeyboardEvent 不可用，退化用 execCommand('insertLineBreak')。
 *
 * 保留：Ctrl/Cmd + Enter 仍是原语义（发送/插队加速）；右下角发送按钮照常可用。
 */
(function () {
  var SYNTH_FLAG = '__dshSynthEnter';

  function inComposer(t) {
    try {
      // data-composer-card 是 composer 卡片的稳定标记（占位符 data-composer-placeholder
      // 只在草稿为空时存在，不能用来定位输入栏）
      return !!(t && t.closest && t.closest('[data-composer-card]'));
    } catch (e) { return false; }
  }

  document.addEventListener('keydown', function (e) {
    if (e[SYNTH_FLAG]) return;                                  // 自己补发的事件：放行
    if (e.key !== 'Enter' && e.keyCode !== 13) return;
    if (e.isComposing || e.keyCode === 229) return;              // 输入法组词中的回车：交给输入法
    if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return; // 组合键保持原语义
    var t = e.target;
    if (!t || !t.isContentEditable || !inComposer(t)) return;    // 只管聊天输入栏
    e.preventDefault();
    e.stopPropagation();                                        // 拦住 Lexical 的“回车即发送”
    try {
      var ev = new KeyboardEvent('keydown', {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        shiftKey: true, bubbles: true, cancelable: true
      });
      ev[SYNTH_FLAG] = true;
      t.dispatchEvent(ev);
    } catch (err) {
      try { document.execCommand('insertLineBreak'); } catch (e2) {}
    }
  }, true);
})();

/**
 * 禁止网页被双指缩放 v0.5
 *
 * 症状：**预览窗存在时**双指捏合会把整个对话页缩放；
 * 没有预览窗时又缩不动 —— 行为不一致，是 bug。
 *
 * 成因：DSH 的 index.html viewport 只写了 `width=device-width, initial-scale=1`，
 *   **没有** `user-scalable=no` / `maximum-scale`；而 WebView 侧的
 *   `setSupportZoom(false)` 在现代 WebView 上并不能可靠地禁掉 pinch-zoom
 *   （viewport 声明才是权威）。预览窗出现时 WebView 会重排，于是"有时能缩"被暴露出来。
 *
 * 做法（两层，都不改内核代码）：
 *   ① inject.sh 给 viewport 补 `maximum-scale=1.0, user-scalable=no`（权威手段）；
 *   ② 这里再兜一道：捕获阶段拦 ≥2 指的 touchmove / gesture*，preventDefault 掉，
 *      Chrome 就不会把它当翻页缩放。（touchmove 必须 passive:false 才拦得住）
 *
 * 注意：虚拟屏预览窗是**独立原生窗口**，不走网页事件 —— 它的双指缩放不受影响。
 */
(function () {
  function blockMulti(e) {
    if (e && e.touches && e.touches.length > 1 && e.cancelable) e.preventDefault();
  }
  document.addEventListener('touchmove', blockMulti, { passive: false, capture: true });
  ['gesturestart', 'gesturechange', 'gestureend'].forEach(function (n) {
    document.addEventListener(n, function (e) { if (e && e.cancelable) e.preventDefault(); },
      { passive: false, capture: true });
  });
})();

/**
 * v1.21：插件登录授权页**自动**交系统浏览器（用户不必手点"Open the sign-in page"）。
 *
 * 背景（用户实测）：ds-harness-remote 点「DS 登录」后，第一次点击界面没反应；
 * 第二次会显示它自己的 pending 文案 + 一个「Open the sign-in page」链接；
 * 手动点那个链接**确实能**把真实授权地址交给系统浏览器（已验证）。
 * 说明通道是通的，只是插件"自动打开"那一步在 WebView 里不稳（它先 window.open("")、
 * 几秒后才 tab.location=授权地址）。所以这里兜底：只要界面上出现指向授权页的链接
 * （href 含 /dsh/authorize 或 authorize_id=），就自动用系统浏览器打开一次。
 * 同一地址只自动打开一次；native 侧对同一地址还有 10 秒去重。
 */
(function () {
  try {
    var opened = {};
    var busy = false;
    function note(msg) {
      try { if (window.dshshell && window.dshshell.note) window.dshshell.note(String(msg)); } catch (e) {}
    }
    function scan() {
      if (busy) return;
      busy = true;
      try {
        if (!window.dshshell || typeof window.dshshell.openExternal !== 'function') return;
        var as = document.querySelectorAll('a[href]');
        for (var i = 0; i < as.length; i++) {
          var href = as[i].href || '';
          if (href.indexOf('/dsh/authorize') < 0 && href.indexOf('authorize_id=') < 0) continue;
          if (opened[href]) continue;
          opened[href] = 1;
          note('auto-open authorize link: ' + href);
          try { window.dshshell.openExternal(href); } catch (e) {}
        }
      } catch (e) {
      } finally {
        busy = false;
      }
    }
    setInterval(scan, 800);
    try {
      new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
  } catch (e) {}
})();

/**
 * v1.21：把**前端报错**记到壳里，便于真机排查（真机报障"默认工作区建立失败"，但引擎日志里没有）。
 *
 * 抓三类：
 *   · window.onerror / unhandledrejection（页面脚本异常）
 *   · 非 2xx 的 fetch 响应（只记 URL 与状态码）—— workspace / session 相关接口失败最有用
 *   · console.error
 * 通过 dshshell.note(...) 送到 App，App 追加到 files/web-notes.log（控制台「日志→分享」会带上）。
 */
(function () {
  try {
    if (window.__dshErrHooked) return;
    window.__dshErrHooked = true;
    function note(msg) {
      try { if (window.dshshell && window.dshshell.note) window.dshshell.note(String(msg).slice(0, 500)); } catch (e) {}
    }
    window.addEventListener('error', function (e) {
      try { note('[js-error] ' + (e && e.message ? e.message : 'unknown') + ' @ ' + (e && e.filename ? e.filename : '') + ':' + (e && e.lineno ? e.lineno : '')); } catch (x) {}
    });
    window.addEventListener('unhandledrejection', function (e) {
      try {
        var r = e && e.reason;
        note('[js-reject] ' + String((r && (r.message || r.code)) || r).slice(0, 300));
      } catch (x) {}
    });
    try {
      var ce = console.error;
      console.error = function () {
        try {
          note('[console.error] ' + Array.prototype.map.call(arguments, function (a) {
            return (a && a.message) ? a.message : String(a);
          }).join(' ').slice(0, 400));
        } catch (x) {}
        return ce.apply(console, arguments);
      };
    } catch (x) {}
    try {
      var of = window.fetch;
      window.fetch = function (input, init) {
        var url = '';
        try { url = (typeof input === 'string') ? input : ((input && input.url) || ''); } catch (x) {}
        return of.apply(this, arguments).then(function (res) {
          try {
            var u = String(url);
            // workspace 相关请求**无论成功失败都记**（真机排查"默认工作区建立失败"：
            // 需要看到它到底调了哪个接口、返回什么码，光记失败可能什么都看不到）
            if (/workspace/i.test(u)) {
              note('[fetch] ' + (res && res.status) + ' ' + u.slice(0, 220));
              // 关键：HTTP 200 也可能是业务失败（真机实测 initializeDefault 返回 200 却报错）
              // → 用 clone() 读一份响应体（不消费原流），截断后上报
              try {
                res.clone().text().then(function (txt) {
                  var s = String(txt).replace(/\s+/g, ' ');
                  note('[fetch-body] ' + u.slice(0, 120) + ' => ' + s.slice(0, 400));
                }, function () {});
              } catch (x) {}
            } else if (res && !res.ok) {
              note('[fetch] ' + res.status + ' ' + u.slice(0, 200));
            }
          } catch (x) {}
          return res;
        }, function (err) {
          try { note('[fetch-fail] ' + String(url).slice(0, 200) + ' ' + ((err && err.message) || err)); } catch (x) {}
          throw err;
        });
      };
    } catch (x) {}
  } catch (e) {}
})();

/**
 * v1.21：接管 window.open → 用**系统浏览器**打开（插件登录授权页专用）。
 *
 * 背景：ds-harness-remote 点「DS 登录」时是
 *     let tab = window.open("", "_blank");       // 先拿一个空白窗口对象
 *     ... await 服务端拿 authorizeUrl ...         // 几秒后
 *     tab.location = authorizeUrl;               // 再往里塞地址
 * 走 WebView 的 onCreateWindow 通道要拿一个隐形 WebView 兜住这个空白窗口，
 * 而隐形 WebView 会被 Chromium 节流 → **第一次点击经常丢地址**（用户实测：第一次没反应，
 * 关掉插件再进第二次才跳浏览器）。所以这里直接在页面层接管：
 *   · 有地址的 window.open → 立刻交给系统浏览器；
 *   · 空地址的 window.open（就是上面那个空白窗）→ 返回一个"假窗口"，
 *     它的 location 赋值同样转交浏览器，且 closed=false、close() 只是标记 ——
 *     这样插件两条分支（tab 存在 / tab.closed）都走到我们这里，且不会重复开两个标签页。
 * 插件的结果回收本来就靠主页面轮询服务端，不依赖弹窗回调，所以这条路完全成立。
 * 桥方法由 App 侧提供：addJavascriptInterface(..., "dshshell").openExternal(url)。
 */
(function () {
  try {
    if (window.__dshOpenPatched) return;
    var tries = 0;
    function install() {
      try {
        if (window.__dshOpenPatched) return;
        // 桥（addJavascriptInterface）可能比本脚本晚一步就绪 —— 实测 mobile.js 先执行、
        // 那时 dshshell 还没挂上；不重试的话 shim 会静默失效（用户看到"第一次点击没反应"）。
        if (!window.dshshell || typeof window.dshshell.openExternal !== 'function') {
          if (++tries < 60) setTimeout(install, 200);   // 最多等 12 秒
          return;
        }
        window.__dshOpenPatched = true;
        var origOpen = window.open;
        function hand(url) {
          try { window.dshshell.openExternal(String(url)); return true; } catch (e) { return false; }
        }
        window.open = function (url, name, features) {
          try {
            if (url) { hand(url); return null; }
            var target = '';
            var fake = {
              closed: false, opener: null, name: name || '',
              close: function () { fake.closed = true; },
              focus: function () {}, blur: function () {}, postMessage: function () {}
            };
            Object.defineProperty(fake, 'location', {
              get: function () { return target; },
              set: function (v) {
                target = String(v);
                if (target && target !== 'about:blank') hand(target);
              }
            });
            fake.document = { write: function () {}, close: function () {} };
            return fake;
          } catch (e) {
            try { return origOpen ? origOpen.apply(window, arguments) : null; } catch (e2) { return null; }
          }
        };
      } catch (e) { /* 保持原样，App 侧还有 onCreateWindow 兜底 */ }
    }
    install();
  } catch (e) {}
})();

/**
 * v1.21：客户端时区兜底。
 *
 * 背景：DSH 创建会话时要求 `clientTimeZone` 是 "UTC" 或合法的 IANA "Area/Location"
 * （内核报错：session/invalid-time-zone）。而部分 ROM / 模拟器把时区报成 "GMT"、
 * "GMT+08:00" 这类**别名**（实测设备 persist.sys.timezone=GMT → WebView 里
 * Intl.DateTimeFormat().resolvedOptions().timeZone 也是 "GMT"）→ 会话建不起来，
 * 表现就是"消息发出去没反应/报时区错误"。
 *
 * 做法：只包裹 resolvedOptions，且**仅在拿到明显非 IANA 的值时**改写：
 *   GMT / Etc/GMT → UTC；GMT±H[:MM] → 按偏移取等价 IANA 名（+8 → Asia/Shanghai）。
 * 已是 Area/Location 的一律原样返回 —— 正常设备行为完全不变。
 */
(function () {
  try {
    if (typeof Intl === 'undefined' || !Intl.DateTimeFormat) return;
    var orig = Intl.DateTimeFormat.prototype.resolvedOptions;
    if (!orig || orig.__dshTzFixed) return;

    var BY_OFFSET = {
      '-10': 'Pacific/Honolulu', '-9': 'America/Anchorage', '-8': 'America/Los_Angeles',
      '-7': 'America/Denver', '-6': 'America/Chicago', '-5': 'America/New_York',
      '-4': 'America/Halifax', '-3': 'America/Sao_Paulo', '-2': 'Atlantic/South_Georgia',
      '-1': 'Atlantic/Azores', '0': 'UTC', '1': 'Europe/Berlin', '2': 'Europe/Athens',
      '3': 'Europe/Moscow', '4': 'Asia/Dubai', '5': 'Asia/Karachi', '5.5': 'Asia/Kolkata',
      '6': 'Asia/Dhaka', '7': 'Asia/Bangkok', '8': 'Asia/Shanghai', '9': 'Asia/Tokyo',
      '9.5': 'Australia/Adelaide', '10': 'Australia/Sydney', '11': 'Pacific/Guadalcanal',
      '12': 'Pacific/Auckland', '13': 'Pacific/Tongatapu'
    };

    function fixTz(tz) {
      if (!tz || typeof tz !== 'string') return tz;
      if (/^(GMT|UTC|Etc\/GMT|Etc\/UTC|Z)$/.test(tz)) return 'UTC';
      if (/^[A-Za-z]+\/[A-Za-z0-9_+\-]+$/.test(tz)) return tz;   // 已是 IANA 形态
      // 实测：设备时区为 GMT 时，WebView 报的是纯偏移 "+00:00"（不是 "GMT"）——
      // 这种也必须映射，否则 DSH 仍判非法（session/invalid-time-zone）。
      var m = /^(?:GMT|UTC)?\s*([+-])(\d{1,2})(?::?(\d{2}))?$/.exec(tz);
      if (!m) return tz;
      var hours = parseInt(m[2], 10);
      if (m[3] && parseInt(m[3], 10) === 30) hours += 0.5;
      if (m[1] === '-') hours = -hours;
      return BY_OFFSET[String(hours)] || 'UTC';
    }

    var wrapped = function () {
      var r = orig.apply(this, arguments);
      try { if (r && typeof r.timeZone === 'string') r.timeZone = fixTz(r.timeZone); } catch (e) {}
      return r;
    };
    wrapped.__dshTzFixed = true;
    Intl.DateTimeFormat.prototype.resolvedOptions = wrapped;
  } catch (e) { /* 老 WebView 不支持就跳过 */ }
})();

/**
 * v1.20：第三栏（右侧 dockkit 面板）打开状态 → `html[data-dsh-right-panel]`。
 *
 * 用途：小屏下第三栏是**全屏**的，它的标签条（含 "Start"）正好落在左上角三条杠的位置；
 *   用户实测「此时点三条杠，三条杠本身也会消失」——也就是面板打开时三条杠本就不该在那儿。
 *   于是 mobile.css 按 `html[data-dsh-right-panel]` 把三条杠隐去，Start 保持原样。
 *
 * 判定：dockkit 的**活动 pane** 必须「真的占住了屏幕左侧」才算打开。
 *   ⚠ v1.20 修正：不能只看"pane 宽度占视口一半"——面板**关闭**时那一列依然存在
 *   （实测：rect.x = 视口宽度，排在视口右侧之外，且 computed visibility = hidden），
 *   只看宽度会恒为真 → 三条杠在欢迎页上也被隐去（已回归过）。
 *   现在要求 visibility !== 'hidden' 且 rect.x < 视口 25%（实测：打开时 x=0，关闭时 x=视口宽）。
 *   （不用 CSS-module 哈希类名，也不依赖 i18n 文案；面板开关都会触发 DOM 变更 → MutationObserver。）
 */
(function () {
  // 判定"第三栏是否真的占着屏幕左侧"。
  // ⚠ 踩过的两个坑：
  //   ① 只看宽度（占视口一半）→ 面板关着时那列依然存在，恒为真 → 欢迎页三条杠也被隐去；
  //   ② 只取**第一个** [data-dockkit-pane-active=true] → 终端标签激活时同时存在多个 pane，
  //      取到的可能是排在右侧之外的那个 → 判定为"没开" → 三条杠冒出来跟 bash 图标重叠
  //      （用户实测：点 bash 标签右侧的 + 后稳定出现重叠的导航按钮）。
  // 现在：先看标签条（打开时它落在视口左侧 x≈0，关闭时排在视口右侧之外 x≈视口宽），
  //      再兜底扫**所有**可见 pane，任意一个在左侧即算打开。
  function isRightPanelOpen() {
    var vw = window.innerWidth;
    var strips = document.querySelectorAll('[data-dockkit-strip]');
    for (var i = 0; i < strips.length; i++) {
      var s = strips[i], scs = getComputedStyle(s), sr = s.getBoundingClientRect();
      if (scs.visibility === 'hidden' || sr.width === 0) continue;
      if (sr.x < vw * 0.25 && sr.width > vw * 0.25) return true;
    }
    var panes = document.querySelectorAll('[data-dockkit-pane-active="true"]');
    for (var j = 0; j < panes.length; j++) {
      var p = panes[j], pcs = getComputedStyle(p), pr = p.getBoundingClientRect();
      if (pcs.visibility === 'hidden' || pr.width === 0) continue;
      if (pr.x < vw * 0.25) return true;
    }
    return false;
  }
  function update() {
    var open = false;
    try { open = isRightPanelOpen(); } catch (e) { open = false; }
    try {
      if (open) document.documentElement.setAttribute('data-dsh-right-panel', '');
      else document.documentElement.removeAttribute('data-dsh-right-panel');
    } catch (e) { /* 忽略 */ }
  }
  update();
  try {
    new MutationObserver(update).observe(document.documentElement, {
      subtree: true, childList: true, attributes: true,
      attributeFilter: ['style', 'class', 'hidden', 'aria-hidden', 'data-dockkit-pane-active']
    });
  } catch (e) { /* 老 WebView 没 MutationObserver 也不致命 */ }
  // 只用 MutationObserver 会漏：实测面板开着时偶发没触发（标记仍为 false）。
  // 面板开关不频繁，加一个 400ms 轮询兜底，保证"打开第三栏 → 三条杠隐去"是确定行为。
  try { setInterval(update, 400); } catch (e) { /* 忽略 */ }
  window.addEventListener('resize', update);
  window.addEventListener('orientationchange', update);
  setTimeout(update, 400);
  setTimeout(update, 1500);
})();
