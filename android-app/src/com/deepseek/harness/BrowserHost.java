package com.deepseek.harness;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AI 浏览器宿主（v1.19.0 · 阶段 A1）—— **我们自己的实现**。
 *
 * 与参考项目（kelai141/dsh-mobile-apk，MIT）的关系：**借方向，不抄实现**。方向 = 给 AI 的浏览器
 * 应该是"结构化 DOM 快照 + ref 寻址 + 原生触摸派发"，而不是截图 OCR。以下四条是我们自己的：
 *
 *  ① **稳定 ref**：ref = 元素身份指纹短哈希（tag|type|role|名|href host+path|稳定 DOM 路径），
 *     跨快照保持一致（写进 data-dsh-ref 且**不清除**）；同名多元素加 -2/-3 并标 dup。
 *     （参考实现用的是位置编号 bx1..bxN，每次快照重排，AI 上一轮的 ref 下一轮就废。）
 *  ② **真差分快照**：snapshot{since} 回 added/removed/changed/unchangedCount；没变就"零差异"。
 *     （参考实现的 delta 是保留参数、永远回全量。）
 *  ③ **browser_find**：按文本/角色检索定位，只回少量候选，**命中的元素自动打 ref** → 找到即可点。
 *  ④ **动作后置校验**：click/type 之后立刻算一次页面指纹，回 changed=true/false + 最小差异，
 *     让 AI 当场知道"这一下点没点动"，而不是再拍一次全量快照去猜。
 *
 * 边界（阶段 A1，如实写在 caps 里）：
 *  - 单页签；与主界面 WebView **同进程同数据目录** → 不承诺 cookie 隔离（阶段 A2 挪到独立进程 :browser）。
 *  - 导航准入：只允许 http/https/about:blank；**拒绝回环地址**（防 AI 浏览器去打 App 自己的本地服务）。
 *  - 截图只做"给人看的证据"，**不参与任何定位**。
 */
public final class BrowserHost {

    private static final String TAG = "DSHBrowser";

    /** 预算（写死，防止烧穿上下文）。 */
    private static final int MAX_NODES = 400;
    private static final int MAX_NAME = 120;
    private static final int MAX_TEXT = 8000;
    private static final int FIND_DEFAULT = 5;
    private static final int FIND_MAX = 20;

    /** headless 视口（View 像素）：固定非退化尺寸，保证 WebView 真的排版。 */
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;

    /**
     * 窗口宿主（v1.19.6 · A2 独立进程）：BrowserHost 不再绑死 Activity ——
     * 同进程时由 MainActivity 给 content view；独立 :browser 进程里由 BrowserService
     * 给一扇不可见悬浮窗的根视图（WebView 必须挂在一扇真实窗口里才会排版，见 createContainer）。
     */
    public interface Host {
        /** 建 WebView / 取文件目录用的 Context。 */
        Context ctx();
        /** 容器挂到哪个 ViewGroup（**必须主线程**调用）。 */
        ViewGroup windowRoot();
    }

    private final Host host;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout container;
    /** 当前**激活**页签的 WebView（切页签 = 换这个引用 + 换 st）。 */
    private WebView web;

    // v1.19.6 · A2 多页签：页面状态不再挂在宿主上，而是**每个页签一份**。
    // op 代码统一通过 `st.<字段>` 访问"当前激活页签"的状态；切换页签只换 st 指向的引用 ——
    // 于是后台页签的 WebViewClient 回调写自己那份状态，永远不会串到前台来。
    /** 一个页签的全部可变状态（A1 时代这些字段挂在宿主上，A2 起归页签所有）。 */
    private static final class TabState {
        /** 页面代次：任何导航/重载 +1；ref 只在同一代次内保证有效。 */
        long generation = 0;
        /** 上一轮快照（ref → 节点摘要），用于差分。 */
        Map<String, JSONObject> lastNodes = new LinkedHashMap<String, JSONObject>();
        /** 是否已经有过快照（没有快照时 click/type 要被拒）。 */
        boolean hasSnapshot = false;
        String url = "about:blank";
        String title = "";
        volatile boolean pageReady = false;
        String loadState = "idle";     // idle | loading | ready | error
        String loadReason = "";
        /** 实际视口（View 像素）：创建时写入，点击坐标换算用。 */
        volatile int viewW = VIEW_W;
        volatile int viewH = VIEW_H;
    }

    /** 一个页签：稳定 id + 自己的 WebView + 自己那份状态。 */
    private static final class Tab {
        final String id;
        final WebView web;
        final TabState st = new TabState();
        Tab(String id, WebView web) { this.id = id; this.web = web; }
    }

    private final List<Tab> tabs = new ArrayList<Tab>();
    private String activeTabId = "";
    private int tabSeq = 0;
    /** ← 当前激活页签的状态引用（不是拷贝）。 */
    private TabState st = new TabState();

    private volatile String uaString = "";

    public BrowserHost(Host h) {
        this.host = h;
        this.ctx = h.ctx();
    }

    /** 承载窗尺寸（供 :browser 进程建同尺寸的不可见窗；口径必须与同进程时逐像素一致）。 */
    public static int windowWidth() { return VIEW_W; }

    public static int windowHeight() { return VIEW_H; }

    /** 当前页签数（独立进程靠它判断"浏览器已关干净"，好让自己被回收）。 */
    public int tabCount() { return tabs.size(); }

    // ==================== 对外入口（HTTP 线程调用，内部切主线程） ====================

    /**
     * 对外唯一入口。**在调用方线程（HTTP 处理线程）上按顺序编排**：
     * 每一步"碰 WebView 的动作"都 post 到主线程并等它做完，等待/轮询留在本线程 ——
     * 绝不把 sleep/轮询放在主线程（那会卡 UI，甚至 ANR）。
     */
    public String op(String name, JSONObject args, int timeoutMs) {
        try {
            return opSeq(name, args == null ? new JSONObject() : args);
        } catch (Throwable t) {
            Log.w(TAG, "op " + name + " failed", t);
            return err("internal", String.valueOf(t.getMessage()));
        }
    }

    /** 把一段"必须主线程"的代码丢过去并等它执行完。 */
    private void onMainSync(final Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "onMain task failed", t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isOpen() {
        return web != null;
    }

    // ==================== 线程内顺序编排 ====================

    private String opSeq(String name, JSONObject a) throws Exception {
        if ("caps".equals(name)) return caps();
        if ("close".equals(name)) { closeWebView(); return put(ok(), "closed", true).toString(); }
        // v1.19.6 · A2：页签管理（自己一套，不依赖"先有页面"）
        if ("tabs.list".equals(name)) return tabsList();
        if ("tabs.new".equals(name)) return tabsNew(a);
        if ("tabs.switch".equals(name)) return tabsSwitch(a);
        if ("tabs.close".equals(name)) return tabsClose(a);
        ensureWebView();
        // 页签纪律：带 tabId 的动作必须落在**激活页签**上，否则一律拒绝（防止"A 页签的 ref 点到 B 页签"）。
        // 错误码 stale-tab 与设计稿 §6.4 的校验链一致。
        String wantTab = a.optString("tabId", "");
        if (wantTab.length() > 0 && !wantTab.equals(activeTabId)) {
            JSONObject e = errJson("stale-tab", "这个动作带的 tabId=" + wantTab
                    + " 不是当前激活页签（" + activeTabId + "）");
            put(e, "activeTabId", activeTabId);
            put(e, "hint", "先 browser_tabs{op:'switch',tabId} 切过去；或省略 tabId 表示当前页签");
            return e.toString();
        }
        if ("open".equals(name)) return doOpen(a.optString("url", ""), a.optBoolean("newTab", false));
        if ("snapshot".equals(name)) return doSnapshot(a.optLong("since", -1L));
        if ("find".equals(name)) return doFind(a);
        if ("click".equals(name)) return doClick(a);
        if ("type".equals(name)) return doType(a);
        if ("read".equals(name)) return doRead(a);
        if ("scroll".equals(name)) return doScroll(a);
        if ("press".equals(name)) return doPress(a);
        if ("nav".equals(name)) return doNav(a.optString("op", "reload"));
        if ("screenshot".equals(name)) return doScreenshot();
        if ("jsdebug".equals(name)) return doJsDebug();   // 只读阶梯自检（诊断用，不暴露为工具）
        return err("unknown-op", "未知操作：" + name);
    }

    // ==================== WebView 生命周期 ====================

    /** 创建承载所有页签的容器（**只建一次**，主线程）。 */
    private void createContainer() {
        if (container != null) return;
        onMainSync(new Runnable() {
            @Override
            public void run() {
                if (container != null) return;
                container = new FrameLayout(ctx);
                // v1.19.6 修（真缺陷③，「同屏查看」打开了却看不到画面）：
                // 这里原来是 `container.setVisibility(View.INVISIBLE)` ——「不显示但参与排版」，
                // 可是**之后没有任何人把它设回 VISIBLE**，而 setPanelVisible() 只切父窗口 windowRoot。
                // 于是父窗口切可见、子容器永远 INVISIBLE → WebView 一帧都不画 →
                // 窗口标志位全对（mViewVisibility=0x0 / mHasSurface=true / HAS_DRAWN），屏幕上却是空的。
                // 隐藏由父窗口一个人负责就够（它 INVISIBLE 时整扇窗都不显示）；
                // 容器保持 VISIBLE **不影响视口** —— VISIBLE 与 INVISIBLE 的测量结果完全一样，
                // 只有 GONE 才会塌成 0（这正是原来那条注释想防的事）。
                container.setVisibility(View.VISIBLE);     // 同屏画不出来的根因就在这一行
                ViewGroup root = host.windowRoot();
                root.addView(container, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));
                Log.i(TAG, "browser container created " + VIEW_W + "x" + VIEW_H + " (VISIBLE，由 windowRoot 负责隐藏)");
            }
        });
    }

    /**
     * 给一个页签的 WebView 装设置与回调。
     * ⚠ 回调里一律写 `t.st.*`（它自己那份状态）—— 后台页签加载完成/报错**不得**污染激活页签。
     *   （这正是"把状态拷贝到宿主字段"方案做不到的地方。）
     */
    private void applySettings(final WebView w, final Tab t) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        uaString = s.getUserAgentString() + " DSHBrowser/1.0";
        s.setUserAgentString(uaString);
        w.setBackgroundColor(0xFFFFFFFF);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                String bad = policyReason(url);
                if (bad != null) {
                    t.st.loadState = "error";
                    t.st.loadReason = bad;
                    Log.w(TAG, "导航被拒：" + bad + " ← " + url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                t.st.generation++;
                t.st.hasSnapshot = false;
                t.st.lastNodes.clear();
                t.st.pageReady = false;
                t.st.loadState = "loading";
                t.st.loadReason = "";
                t.st.url = url == null ? "" : url;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                t.st.pageReady = true;
                t.st.loadState = "ready";
                if (url != null) t.st.url = url;
                if (v != null && v.getTitle() != null) t.st.title = v.getTitle();
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                t.st.loadState = "error";
                t.st.loadReason = "net:" + code + " " + (desc == null ? "" : desc);
            }
        });
        w.setWebChromeClient(new WebChromeClient());
    }

    /** 新建页签（内部会切主线程；返回新建的页签，失败返回 null）。 */
    private Tab newTabOnMain(final String startUrl) {
        createContainer();
        final Tab[] made = new Tab[1];
        onMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView w = new WebView(ctx);
                    Tab t = new Tab("t" + (++tabSeq), w);
                    applySettings(w, t);
                    container.addView(w, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));
                    tabs.add(t);
                    if (startUrl != null && startUrl.length() > 0) w.loadUrl(startUrl);
                    made[0] = t;
                    Log.i(TAG, "tab " + t.id + " created"
                            + (startUrl != null && startUrl.length() > 0 ? " → " + startUrl : ""));
                } catch (Throwable e) {
                    Log.w(TAG, "newTab failed", e);
                }
            }
        });
        return made[0];
    }

    /** 激活页签：只换 web / st 两个引用，其余代码（A1 那一千行）一行不用改。 */
    private void activate(Tab t) {
        if (t == null) return;
        web = t.web;
        st = t.st;
        activeTabId = t.id;
    }

    private Tab findTab(String id) {
        if (id == null) return null;
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id.equals(id)) return tabs.get(i);
        return null;
    }

    /**
     * 保证"有容器、有页签、有激活页签"（页面类 op 的入口）。
     * ⚠ 所有页签的 WebView 都**留在同一个容器里**：脱离视图树的 WebView 不排版、
     *   evaluateJavascript 不可靠；容器整体 INVISIBLE，谁也不显示（截图照样 draw 得出来）。
     */
    private void ensureWebView() {
        if (!tabs.isEmpty()) {
            Tab cur = findTab(activeTabId);
            activate(cur == null ? tabs.get(0) : cur);
            return;
        }
        Tab t = newTabOnMain(null);
        if (t == null) throw new IllegalStateException("WebView 创建失败");
        activate(t);
    }

    /** 关掉**全部**页签并释放（"一键整套丢弃"）。 */
    private void closeWebView() {
        onMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < tabs.size(); i++) {
                        try { tabs.get(i).web.destroy(); } catch (Throwable ignored) {}
                    }
                    tabs.clear();
                    if (container != null) {
                        container.removeAllViews();
                        ViewGroup root = host.windowRoot();
                        root.removeView(container);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "close failed", t);
                }
                container = null;
            }
        });
        web = null;
        st = new TabState();
        activeTabId = "";
    }

    /** 关掉单个页签（主线程销毁 WebView + 从列表移除）。 */
    private void destroyTab(final Tab t) {
        if (t == null) return;
        onMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    if (container != null) container.removeView(t.web);
                    t.web.destroy();
                } catch (Throwable e) {
                    Log.w(TAG, "destroy tab failed", e);
                }
            }
        });
        tabs.remove(t);
    }

    // ==================== 导航准入（我们自己的策略） ====================

    static String policyReason(String url) {
        if (url == null || url.length() == 0) return "空地址";
        String u = url.trim();
        String low = u.toLowerCase();
        if (low.startsWith("about:blank")) return null;
        if (!(low.startsWith("http://") || low.startsWith("https://"))) {
            return "只允许 http/https/about:blank（file/content/data/javascript/blob/intent 一律拒绝）";
        }
        String rest = low.substring(low.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        if (authority.indexOf('@') >= 0) return "地址里带 userinfo（@）一律拒绝";
        String host = authority;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) host = authority.substring(0, colon);
        if (host.startsWith("[")) host = host.substring(1, host.length() - (host.endsWith("]") ? 1 : 0));
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || "0.0.0.0".equals(host) || host.endsWith(".localhost")) {
            return "禁止访问本机地址（回环）：那是引擎与 App 本地服务所在处";
        }
        if (host.matches("^\\d+$") || host.matches("^0x[0-9a-f]+$") || host.matches("^\\d+\\.\\d+$")) {
            return "禁止访问本机地址（IPv4 字面量变体）：那是引擎与 App 本地服务所在处";
        }
        return null;
    }

    // ==================== 注入的 JS（ES5 兼容，老 WebView 也能跑） ====================

    /** 语义元素白名单：与"只收能点/能输/有语义的元素"这一原则一致（不收全量 DOM）。 */
    private static final String SEL =
        "a[href],button,input,select,textarea,[role],[onclick],[tabindex],[contenteditable]";

    /**
     * 把选择器包成 **JS 单引号字面量**（注入用）。
     * ⚠ v1.19.0 真机故障的教训：这段选择器曾被双引号包进 querySelectorAll("…")，
     * 而选择器里自带 [contenteditable="true"] → 注入后双引号自杀 → 整段脚本语法错
     * → evaluateJavascript 回 "null" → 表现为"快照脚本没有返回"，而且**不报错、不打日志**。
     * 因此这里把"不许带引号"变成硬断言：谁再往 SEL 里塞引号，立刻抛出来。
     */
    static String selLiteral(String selector) {
        if (selector.indexOf('\'') >= 0 || selector.indexOf('"') >= 0) {
            throw new IllegalStateException("选择器里不允许出现引号（会破坏注入的 JS 字符串字面量）: " + selector);
        }
        return "'" + selector + "'";
    }

    /** 公共工具函数（指纹 / 名字 / 角色 / 稳定 DOM 路径）。 */
    private static final String JS_COMMON =
        "function nm(el){var v=el.getAttribute&&(el.getAttribute('aria-label')||el.getAttribute('placeholder')"
      + "||el.getAttribute('title')||el.getAttribute('alt'));"
      + "if(!v){v=(el.innerText||el.textContent||'');}"
      + "if(!v&&el.value!==undefined&&el.value!==null){v=String(el.value);}"
      + "v=String(v||'').replace(/\\s+/g,' ').replace(/^\\s+|\\s+$/g,'');"
      + "if(v.length>" + MAX_NAME + ")v=v.slice(0," + MAX_NAME + ");return v;}"
      + "function rl(el){var r=el.getAttribute&&el.getAttribute('role');if(r)return r;"
      + "var t=el.tagName?el.tagName.toUpperCase():'';"
      + "if(t==='A')return 'link';if(t==='BUTTON')return 'button';if(t==='SELECT')return 'combobox';"
      + "if(t==='INPUT'||t==='TEXTAREA'){var ty=(el.getAttribute('type')||'').toLowerCase();"
      + "if(ty==='submit'||ty==='button'||ty==='checkbox'||ty==='radio')return ty;return 'textbox';}"
      + "if(el.hasAttribute&&el.hasAttribute('onclick'))return 'clickable';"
      + "if(el.hasAttribute&&el.hasAttribute('tabindex'))return 'focusable';return 'text';}"
      + "function dp(el){var parts=[],n=el,d=0;"
      + "while(n&&n.nodeType===1&&d<6&&n!==document.body){var i=1,s=n;"
      + "while((s=s.previousElementSibling)){if(s.tagName===n.tagName)i++;}"
      + "parts.unshift(n.tagName.toLowerCase()+'['+i+']');n=n.parentElement;d++;}"
      + "return parts.join('>');}"
      + "function fnv(s){var h=0x811c9dc5;for(var i=0;i<s.length;i++){h^=s.charCodeAt(i);h=(h*0x01000193)>>>0;}"
      + "return ('0000000'+h.toString(16)).slice(-7);}"
      + "function fp(el){var tag=(el.tagName||'').toLowerCase();"
      + "var ty=(el.getAttribute&&el.getAttribute('type')||'').toLowerCase();"
      + "var href='';if(tag==='a'&&el.href){try{var u=new URL(el.href);href=u.host+u.pathname;}catch(e){href=String(el.href).slice(0,80);}}"
      + "return tag+'|'+ty+'|'+rl(el)+'|'+nm(el)+'|'+href+'|'+dp(el);}"
      + "function nodesAll(){var all=document.querySelectorAll(" + selLiteral(SEL) + ");var out=[],used={};"
      + "for(var j=0;j<all.length&&out.length<" + MAX_NODES + ";j++){var el=all[j];var r=el.getBoundingClientRect();"
      + "if(r.width<1||r.height<1)continue;"
      + "var st=window.getComputedStyle?window.getComputedStyle(el):null;"
      + "if(st&&(st.visibility==='hidden'||st.display==='none'))continue;"
      + "var base='r'+fnv(fp(el));var ref=base,dup=1;"
      + "while(used[ref]){dup++;ref=base+'-'+dup;}used[ref]=1;"
      + "var prev=el.getAttribute('data-dsh-ref');"
      + "if(prev!==ref){el.setAttribute('data-dsh-ref',ref);}"
      + "var inView=(r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth);"
      + "var dis=!!(el.disabled||(el.getAttribute&&el.getAttribute('aria-disabled')==='true'));"
      + "out.push({ref:ref,role:rl(el),name:nm(el),"
      + "bounds:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)],"
      + "inView:inView,disabled:dis,dup:dup>1});}"
      + "return out;}";

    /** 快照：返回全量节点（差分在 Java 侧算，JS 保持简单）。 */
    private static final String SNAPSHOT_JS =
        "(function(){" + JS_COMMON
      + "var n=nodesAll();"
      + "return JSON.stringify({url:location.href,title:document.title||'',"
      + "viewport:{width:window.innerWidth,height:window.innerHeight,scale:1},nodes:n});})()";

    /** find：同一套打标逻辑，只回匹配项（Java 侧再按 limit/打分裁剪前先拿到全量）。 */
    private static final String FIND_JS = SNAPSHOT_JS;

    /** ref → 视口坐标（ref 已过格式校验，安全注入）。 */
    private static final String RESOLVE_JS_FMT =
        "(function(){var el=document.querySelector('[data-dsh-ref=\"__REF__\"]');"
      + "if(!el)return JSON.stringify({found:false});"
      + "try{el.scrollIntoView({block:'center',inline:'center'});}catch(e){try{el.scrollIntoView();}catch(e2){}}"
      + "var r=el.getBoundingClientRect();"
      + "return JSON.stringify({found:true,x:r.left+r.width/2,y:r.top+r.height/2,"
      + "vw:window.innerWidth||1,vh:window.innerHeight||1,"
      + "disabled:!!(el.disabled||(el.getAttribute&&el.getAttribute('aria-disabled')==='true')),"
      + "inView:(r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth),"
      + "name:" + "nm(el),role:rl(el)});})()";

    private static final String TYPE_JS_FMT =
        "(function(){var el=document.querySelector('[data-dsh-ref=\"__REF__\"]');"
      + "if(!el)return JSON.stringify({ok:false,reason:'stale-ref'});"
      + "var text=__TEXT__,replace=__REPLACE__,done=false;"
      + "try{el.focus();}catch(e){}"
      + "try{if(replace&&el.select)el.select();done=document.execCommand('insertText',false,text);}catch(e){done=false;}"
      + "if(!done){try{var proto=(el.tagName==='TEXTAREA')?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;"
      + "var setter=Object.getOwnPropertyDescriptor(proto,'value');"
      + "var next=replace?text:(String(el.value||'')+text);"
      + "if(setter&&setter.set){setter.set.call(el,next);}else{el.value=next;}"
      + "el.dispatchEvent(new Event('input',{bubbles:true}));"
      + "el.dispatchEvent(new Event('change',{bubbles:true}));done=true;}catch(e2){done=false;}}"
      + "var v=String(el.value===undefined?'':el.value).slice(0,200);"
      + "return JSON.stringify({ok:done,value:v,reason:done?'':'type-failed'});})()";

    private static final String TEXT_JS_FMT =
        "(function(){var el=__REF__?document.querySelector('[data-dsh-ref=\"'+__REF__+'\"]'):null;"
      + "var t=el?(el.innerText||el.textContent||''):(document.body?(document.body.innerText||document.body.textContent||''):'');"
      + "return JSON.stringify({text:String(t).replace(/\\n{3,}/g,'\\n\\n'),ref:__REF__||''});})()";

    private static final String SCROLL_JS_FMT =
        "(function(){window.scrollBy(0,__DY__);return JSON.stringify({scrollY:window.scrollY||0,"
      + "innerHeight:window.innerHeight||0,docHeight:(document.body?document.body.scrollHeight:0)});})()";

    /** 轻量页面指纹：动作后置校验用（不返回节点表，只回"变没变"）。 */
    private static final String PAGE_FP_JS =
        "(function(){var els=document.querySelectorAll('[data-dsh-ref]');var s='',n=0;"
      + "for(var i=0;i<els.length&&n<200;i++){var r=els[i].getBoundingClientRect();"
      + "if(r.bottom>0&&r.top<window.innerHeight){s+=(els[i].getAttribute('data-dsh-ref')||'')+',';n++;}}"
        // v1.19.6（点击不可见时走 JS 兜底）：**正文也算进指纹**。
        // 旧指纹只由"视口内的可交互元素 ref 列表"决定 ⇒ 页面文字变了它也一动不动，
        // 于是"点中了"会被报成 changed=false（AI 会重复点、或以为工具坏了）。
      + "try{s+='|'+(document.body?String(document.body.innerText||'').slice(0,4000):'');}catch(e){}"
      + "var h=0x811c9dc5;for(var j=0;j<s.length;j++){h^=s.charCodeAt(j);h=(h*0x01000193)>>>0;}"
      + "return JSON.stringify({url:location.href,title:document.title||'',inView:n,fp:('0000000'+h.toString(16)).slice(-7)});})()";

    // ==================== JS 调用 ====================

    /** 读一次 JS：**evaluateJavascript 必须在主线程调用**，等待在本线程（HTTP 线程）。 */
    private String evalJs(final String js, int timeoutMs) {
        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (web == null) { latch.countDown(); return; }
                    web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            out[0] = value;
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "evaluateJavascript failed", t);
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return jsUnquote(out[0]);
    }

    static String jsUnquote(String v) {
        if (v == null || "null".equals(v)) return null;
        if (v.length() >= 2 && v.charAt(0) == '"') {
            try {
                return new JSONArray("[" + v + "]").getString(0);
            } catch (Throwable t) {
                return v;
            }
        }
        return v;
    }

    static String jsString(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    static JSONObject ok() {
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        return o;
    }

    static JSONObject put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Throwable ignored) {}
        return o;
    }

    /** 错误对象（需要再补字段时用它，例如 stale-tab 要带 activeTabId 与 hint）。 */
    static JSONObject errJson(String code, String msg) {
        JSONObject o = new JSONObject();
        put(o, "ok", false);
        put(o, "reason", code);
        put(o, "error", msg == null ? code : msg);
        return o;
    }

    static String err(String code, String msg) {
        return errJson(code, msg).toString();
    }

    // ==================== caps ====================

    private String caps() {
        JSONObject o = ok();
        put(o, "surface", "browser");
        put(o, "engine", "system-webview");
        put(o, "webviewUa", uaString.isEmpty() ? "system-webview" : uaString);
        put(o, "maxNodes", MAX_NODES);
        put(o, "maxName", MAX_NAME);
        put(o, "maxText", MAX_TEXT);
        put(o, "viewport", viewportJson());
        put(o, "refScheme", "stable-fingerprint");   // 我们的 ref 是稳定指纹，不是位置编号
        // v1.19.6 · A2：多页签
        put(o, "tabs", true);
        put(o, "tabCount", tabs.size());
        put(o, "activeTabId", activeTabId);
        JSONArray notes = new JSONArray();
        notes.put("主通道 = 结构化 DOM 快照 + 稳定 ref 寻址；截图只做给人看的证据，不参与定位");
        notes.put("多页签：每个页签各有自己的 ref / 快照 / 代次；带 tabId 的动作必须落在激活页签上，否则回 stale-tab");
        notes.put("同进程同数据目录：不承诺 cookie 隔离（阶段 A2 剩余项：挪到独立进程 :browser）");
        notes.put("禁止访问回环地址与引擎端口");
        notes.put("snapshot 支持差分（since）；find 命中的元素会自动打 ref，找到即可点");
        put(o, "notes", notes);
        return o.toString();
    }

    private JSONObject viewportJson() {
        JSONObject v = new JSONObject();
        put(v, "width", st.viewW);
        put(v, "height", st.viewH);
        put(v, "scale", 1);
        return v;
    }

    // ==================== 导航 ====================

    private String doOpen(String url, boolean newTab) {
        String bad = policyReason(url);
        if (bad != null) return err("blocked-url", "导航被拒：" + bad);
        if (newTab) {
            Tab nt = newTabOnMain(null);      // 新页签 → 立刻激活，后面的"等加载"逻辑自然作用在它身上
            if (nt == null) return err("new-tab-failed", "新建页签失败");
            activate(nt);
        }
        final long started = st.generation;
        st.pageReady = false;
        st.loadState = "loading";
        st.loadReason = "";
        onMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    st.viewW = web.getWidth() > 0 ? web.getWidth() : VIEW_W;
                    st.viewH = web.getHeight() > 0 ? web.getHeight() : VIEW_H;
                    web.loadUrl(url);
                } catch (Throwable t) {
                    st.loadState = "error";
                    st.loadReason = "load-failed:" + t.getMessage();
                }
            }
        });
        long deadline = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < deadline) {
            if (st.pageReady && st.generation != started) break;
            if ("error".equals(st.loadState)) break;
            sleep(120);
        }
        JSONObject o = ok();
        put(o, "tabId", activeTabId);
        put(o, "url", st.url);
        put(o, "title", st.title);
        put(o, "pageGeneration", st.generation);
        put(o, "loadState", st.loadState);
        put(o, "ready", st.pageReady);
        if (!st.loadReason.isEmpty()) put(o, "reason", st.loadReason);
        put(o, "hint", "下一步用 browser_snapshot（要省 token 就 browser_find）");
        return o.toString();
    }

    private String doNav(String op) {
        final String o1 = op;
        final String[] navErr = new String[1];
        onMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    if ("back".equals(o1)) {
                        if (web.canGoBack()) web.goBack(); else navErr[0] = "no-history";
                    } else if ("forward".equals(o1)) {
                        if (web.canGoForward()) web.goForward(); else navErr[0] = "no-history";
                    } else if ("reload".equals(o1)) {
                        web.reload();
                    } else if ("close".equals(o1)) {
                        web.destroy();
                        web = null;
                    } else {
                        navErr[0] = "unknown-nav";
                    }
                } catch (Throwable t) {
                    navErr[0] = "nav-failed:" + t.getMessage();
                }
            }
        });
        if ("no-history".equals(navErr[0])) return err("no-history", "没有可" + ("back".equals(op) ? "回退" : "前进") + "的历史");
        if (navErr[0] != null && navErr[0].startsWith("unknown-nav")) return err("unknown-nav", "未知 nav op（back/forward/reload/close）：" + op);
        if (navErr[0] != null) return err("nav-failed", navErr[0]);
        if ("close".equals(op)) { closeWebView(); return put(ok(), "closed", true).toString(); }
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && !st.pageReady) sleep(120);
        JSONObject o = ok();
        put(o, "op", op);
        put(o, "url", st.url);
        put(o, "title", st.title);
        put(o, "pageGeneration", st.generation);
        put(o, "loadState", st.loadState);
        return o.toString();
    }

    // ==================== 只读阶梯自检（诊断用；不暴露为工具） ====================

    /**
     * 逐层回显 WebView 里 evaluateJavascript 的真实返回值，用于定位
     * 「snapshot 秒级失败但 read 正常」这类问题：到底是没回调、返回 null、JS 抛错，还是结果解析失败。
     * 只跑固定查询表达式，不改 DOM、不导航。
     */
    private String doJsDebug() {
        JSONObject o = ok();
        JSONArray rows = new JSONArray();
        String[] probes = new String[] {
            "1",                                   // evaluateJavascript 通不通
            "(function(){return 'x';})()",         // IIFE 通不通
            "document.title",                      // DOM 可读性
            "document.querySelectorAll('a[href]').length",                 // 基础选择器
            "document.querySelectorAll(__SEL__).length"                    // 与快照同一套选择器
        };
        for (int i = 0; i < probes.length; i++) {
            // 用与 nodesAll 完全相同的注入写法（selLiteral），保证探针测的就是真实路径
            String js = probes[i].replace("__SEL__", selLiteral(SEL));
            JSONObject row = new JSONObject();
            put(row, "probe", js.length() > 90 ? js.substring(0, 90) + "…" : js);
            String raw = evalJs(js, 5000);
            put(row, "raw", raw == null ? "(null)" : raw);
            put(row, "rawLen", raw == null ? -1 : raw.length());
            rows.put(row);
        }
        // 分段探针：把 JS_COMMON 一段段加上去，定位是哪一段开始返回 null
        String[] stages = new String[] {
            "function __s1(){return 1;} __s1();",                                   // 只加函数声明
            JS_COMMON + "1;",                                                       // 加公共函数（整段）
            JS_COMMON + "nodesAll().length;",                                        // 调 nodesAll
            SNAPSHOT_JS                                                             // 完整快照脚本
        };
        for (int i = 0; i < stages.length; i++) {
            JSONObject row = new JSONObject();
            put(row, "stage", i + 1);
            put(row, "scriptLen", stages[i].length());
            String raw = evalJs(stages[i], 6000);
            put(row, "raw", raw == null ? "(null)" : (raw.length() > 300 ? raw.substring(0, 300) + "…" : raw));
            put(row, "rawLen", raw == null ? -1 : raw.length());
            rows.put(row);
        }
        put(o, "rows", rows);
        Log.i(TAG, "jsdebug: " + o.toString());
        return o.toString();
    }

    // ==================== 快照 / 差分 ====================

    /** 跑一次 JS 全量节点表（内部用，不直接回给 AI）。null = 失败。 */
    private JSONObject rawSnapshot() {
        String raw = evalJs(SNAPSHOT_JS, 10000);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            Log.w(TAG, "snapshot parse failed", t);
            return null;
        }
    }

    private Map<String, JSONObject> indexNodes(JSONArray nodes) {
        Map<String, JSONObject> m = new LinkedHashMap<String, JSONObject>();
        if (nodes == null) return m;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String r = n.optString("ref", "");
            if (r.length() > 0) m.put(r, n);
        }
        return m;
    }

    private String doSnapshot(long since) {
        long started = st.generation;
        JSONObject snap = rawSnapshot();
        if (snap == null) return err("snapshot-failed", "快照脚本没有返回（页面可能在导航中）");
        if (st.generation != started) return err("stale-page-generation", "快照期间页面导航了，请重新快照");
        JSONArray nodes = snap.optJSONArray("nodes");
        Map<String, JSONObject> now = indexNodes(nodes);
        boolean full = (since < 0) || st.lastNodes.isEmpty();
        JSONObject o = ok();
        put(o, "surface", "browser");
        put(o, "tabId", activeTabId);
        put(o, "pageGeneration", st.generation);
        put(o, "url", snap.optString("url", st.url));
        put(o, "title", snap.optString("title", st.title));
        put(o, "viewport", snap.optJSONObject("viewport") == null ? viewportJson() : snap.optJSONObject("viewport"));
        put(o, "refScheme", "stable-fingerprint");
        if (full) {
            put(o, "full", true);
            put(o, "nodes", nodes == null ? new JSONArray() : nodes);
            put(o, "nodeCount", now.size());
            if (now.size() >= MAX_NODES) put(o, "hint", "节点已达上限，用 browser_find 缩小范围");
        } else {
            JSONArray added = new JSONArray();
            JSONArray changed = new JSONArray();
            List<String> removed = new ArrayList<String>();
            for (Map.Entry<String, JSONObject> e : now.entrySet()) {
                JSONObject old = st.lastNodes.get(e.getKey());
                if (old == null) {
                    added.put(e.getValue());
                } else if (differs(old, e.getValue())) {
                    JSONObject c = new JSONObject();
                    put(c, "ref", e.getKey());
                    put(c, "fields", diffFields(old, e.getValue()));
                    put(c, "node", e.getValue());
                    changed.put(c);
                }
            }
            for (String r : st.lastNodes.keySet()) if (!now.containsKey(r)) removed.add(r);
            put(o, "full", false);
            put(o, "added", added);
            put(o, "removed", new JSONArray(removed));
            put(o, "changed", changed);
            put(o, "unchangedCount", now.size() - added.length() - changed.length());
            put(o, "nodeCount", now.size());
            if (added.length() == 0 && changed.length() == 0 && removed.isEmpty()) {
                put(o, "unchanged", true);   // 零差异：几十 token 就够
            }
        }
        st.lastNodes = now;
        st.hasSnapshot = true;
        Log.i(TAG, "snapshot: gen=" + st.generation + " nodes=" + now.size() + " full=" + full
                + " sample=" + now.keySet().toString());
        return o.toString();
    }

    private static boolean differs(JSONObject a, JSONObject b) {
        return diffFields(a, b).length() > 0;
    }

    private static JSONArray diffFields(JSONObject a, JSONObject b) {
        JSONArray f = new JSONArray();
        String[] keys = new String[]{"role", "name", "bounds", "inView", "disabled"};
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            String va = String.valueOf(a.opt(k));
            String vb = String.valueOf(b.opt(k));
            if (!va.equals(vb)) f.put(k);
        }
        return f;
    }

    // ==================== find：检索式定位 ====================

    private String doFind(JSONObject a) {
        String q = a.optString("query", "").trim();
        if (q.length() == 0) return err("query-required", "需要 query（要查找的文本或角色名）");
        String role = a.optString("role", "").trim().toLowerCase();
        int limit = a.optInt("limit", FIND_DEFAULT);
        if (limit <= 0) limit = FIND_DEFAULT;
        if (limit > FIND_MAX) limit = FIND_MAX;
        String ql = q.toLowerCase();
        JSONObject snap = rawSnapshot();
        if (snap == null) return err("find-failed", "页面快照失败（可能在导航中）");
        JSONArray nodes = snap.optJSONArray("nodes");
        Map<String, JSONObject> now = indexNodes(nodes);
        st.lastNodes = now;
        st.hasSnapshot = true;
        List<Object[]> hits = new ArrayList<Object[]>();
        for (Map.Entry<String, JSONObject> e : now.entrySet()) {
            JSONObject n = e.getValue();
            String nmv = n.optString("name", "");
            String rv = n.optString("role", "");
            if (role.length() > 0 && !role.equals(rv.toLowerCase())) continue;
            String nl = nmv.toLowerCase();
            int score = 0;
            if (nl.equals(ql)) score = 100;
            else if (nl.startsWith(ql)) score = 80;
            else if (nl.indexOf(ql) >= 0) score = 60;
            else if (rl_synonym(rv, ql)) score = 40;
            else if (n.optBoolean("isValue", false)) score = 30;
            if (score == 0) continue;
            if (n.optBoolean("inView", false)) score += 10;
            if (n.optBoolean("disabled", false)) score -= 20;
            hits.add(new Object[]{Integer.valueOf(score), e.getKey(), n});
        }
        java.util.Collections.sort(hits, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                return ((Integer) y[0]).intValue() - ((Integer) x[0]).intValue();
            }
        });
        JSONArray cands = new JSONArray();
        for (int i = 0; i < hits.size() && i < limit; i++) {
            JSONObject n = (JSONObject) hits.get(i)[2];
            JSONObject c = new JSONObject();
            put(c, "ref", hits.get(i)[1]);
            put(c, "role", n.optString("role", ""));
            put(c, "name", n.optString("name", ""));
            put(c, "bounds", n.optJSONArray("bounds"));
            put(c, "inView", n.optBoolean("inView", false));
            put(c, "disabled", n.optBoolean("disabled", false));
            put(c, "score", hits.get(i)[0]);
            cands.put(c);
        }
        JSONObject o = ok();
        put(o, "query", q);
        put(o, "matched", hits.size());
        put(o, "candidates", cands);
        put(o, "pageGeneration", st.generation);
        put(o, "url", snap.optString("url", st.url));
        put(o, "tabId", activeTabId);     // v1.19.6：与 doSnapshot 对齐，插件靠它把 ref 登记到页签上
        if (cands.length() == 0) put(o, "hint", "没找到：换个词、或用 browser_snapshot 看全量节点");
        else put(o, "hint", "命中的元素已打 ref，可直接 browser_click");
        return o.toString();
    }

    /** 角色同义词：让"按钮/button/btn"这类说法都能命中。 */
    private static boolean rl_synonym(String role, String q) {
        String r = role.toLowerCase();
        if (("button".equals(r) || "clickable".equals(r)) && (q.indexOf("按钮") >= 0 || q.indexOf("button") >= 0 || q.indexOf("btn") >= 0)) return true;
        if ("link".equals(r) && (q.indexOf("链接") >= 0 || q.indexOf("link") >= 0)) return true;
        if (("textbox".equals(r) || "combobox".equals(r)) && (q.indexOf("输入") >= 0 || q.indexOf("框") >= 0 || q.indexOf("input") >= 0)) return true;
        return false;
    }

    // ==================== 动作：click / type / press / scroll ====================

    /** ref 校验链（我们自己的版式：稳定 ref 不需要"位置代次"，但要保证有快照且元素还在）。 */
    private String validateRef(JSONObject a) {
        String ref = a.optString("ref", "");
        if (ref.length() == 0) return "ref-required";
        if (!ref.matches("^r[0-9a-f]{7}(-\\d{1,3})?$")) return "invalid-ref";
        if (!st.hasSnapshot) return "snapshot-required";
        if (!st.lastNodes.containsKey(ref)) return "stale-ref";
        return null;
    }

    private JSONObject resolveRef(String ref) {
        // ⚠ v1.19.0 真机故障：这条串用了 nm()/rl()，而 evalJs 每次是**独立一次** evaluateJavascript，
        // 脚本之间不共享作用域 —— 不拼上 JS_COMMON 就是 ReferenceError → 回调收到 null →
        // 被误报成 "ref 已失效（元素不在了）"。点击整条链路因此必挂。
        // 外层再包一个 IIFE，让 JS_COMMON 的函数声明与后面的调用处在同一作用域里（并 return 出结果）。
        String js = "(function(){" + JS_COMMON + "return " + RESOLVE_JS_FMT.replace("__REF__", ref) + ";})()";
        String raw = evalJs(js, 6000);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private String refFailure(String code, String ref) {
        JSONObject o = new JSONObject();
        put(o, "ok", false);
        put(o, "reason", code);
        put(o, "ref", ref);
        if ("stale-ref".equals(code)) {
            put(o, "error", "ref 已失效（元素不在了）：请重新 browser_snapshot 或 browser_find");
        } else if ("snapshot-required".equals(code)) {
            put(o, "error", "还没有快照：先 browser_snapshot（或 browser_find）拿到 ref，再动作；不猜坐标");
        } else if ("invalid-ref".equals(code)) {
            put(o, "error", "ref 格式不对（应为 rXXXXXXX 或 rXXXXXXX-2）");
        } else {
            put(o, "error", code);
        }
        return o.toString();
    }

    private String doClick(JSONObject a) {
        String ref = a.optString("ref", "");
        String bad = validateRef(a);
        if (bad != null) {
            // v1.19.x 诊断：把 stale-ref 的两个来源分开（validateRef 的 lastNodes 判断 vs JS found:false）
            Log.i(TAG, "click 被拒: path=validateRef ref=" + ref + " code=" + bad
                    + " hasSnapshot=" + st.hasSnapshot + " lastNodes=" + st.lastNodes.size()
                    + " inMap=" + st.lastNodes.containsKey(ref) + " gen=" + st.generation);
            return refFailure(bad, ref);
        }
        JSONObject before = pageFp();
        JSONObject rr = resolveRef(ref);
        if (rr == null || !rr.optBoolean("found", false)) {
            Log.i(TAG, "click 被拒: path=resolveRef ref=" + ref + " rr=" + (rr == null ? "null(JS 无返回)" : rr.toString())
                    + " gen=" + st.generation + " loadState=" + st.loadState);
            return refFailure("stale-ref", ref);
        }
        if (rr.optBoolean("disabled", false)) return err("element-disabled", "元素是 disabled，点了也不会有反应");
        double x = rr.optDouble("x", -1), y = rr.optDouble("y", -1);
        double vw = rr.optDouble("vw", 0), vh = rr.optDouble("vh", 0);
        if (vw <= 0 || vh <= 0) return err("viewport-unavailable", "视口不可用（页面没有尺寸）");
        final int[] wh = new int[2];
        onMainSync(new Runnable() {
            @Override
            public void run() {
                wh[0] = web.getWidth();
                wh[1] = web.getHeight();
            }
        });
        if (wh[0] <= 0 || wh[1] <= 0) return err("viewport-unavailable", "视口不可用（WebView 还没有尺寸）");
        st.viewW = wh[0];
        st.viewH = wh[1];
        final float vx = (float) (x * wh[0] / vw);
        final float vy = (float) (y * wh[1] / vh);
        // v1.19.6（点击不可见时走 JS 兜底）：同屏面板关着时承载窗是 INVISIBLE，
        // WebView 作为它的后代 **isShown()=false** —— Chromium 对不可见的自己
        // **直接丢掉输入事件**（坐标算得再对也没用，真机 A/B 实测：面板关=100% 点不动）。
        // 所以：看得见就照旧走真触摸（命中测试语义不变）；看不见就直接 JS 点。
        boolean shown = false;
        try { shown = web.isShown(); } catch (Throwable ignored) {}
        String via;
        if (shown) {
            dispatchTap(vx, vy);
            sleep(700);
            via = "touch";
        } else {
            via = "js";
        }
        JSONObject after = pageFp();
        boolean changed = !sameFp(before, after);
        if (!changed) {
            // 触摸点了却没变（或本来就走 JS）→ 再补一次 JS 点击，双保险
            clickByJs(ref);
            sleep(700);
            after = pageFp();
            changed = !sameFp(before, after);
            if (shown) via = "touch+js";
        }
        JSONObject o = ok();
        put(o, "ref", ref);
        put(o, "via", via);
        put(o, "clickedAt", new JSONArray().put(Math.round(vx)).put(Math.round(vy)));
        put(o, "changed", changed);
        put(o, "url", after == null ? st.url : after.optString("url", st.url));
        put(o, "title", after == null ? st.title : after.optString("title", st.title));
        put(o, "pageGeneration", st.generation);
        put(o, "loadState", st.loadState);
        if (!changed) put(o, "hint", "页面没变化：可能不是可点元素、或点击被遮挡；可用 browser_snapshot{since} 复核");
        else put(o, "delta", minimalDelta());
        return o.toString();
    }

    private String doType(JSONObject a) {
        String ref = a.optString("ref", "");
        String bad = validateRef(a);
        if (bad != null) return refFailure(bad, ref);
        String text = a.optString("text", "");
        boolean replace = a.optBoolean("replace", true);
        if (text.length() == 0 && !a.has("text")) return err("text-required", "需要 text（可以是空串，表示清空）");
        JSONObject before = pageFp();
        // 同 resolveRef：这条串也不能少了公共函数（否则同样变成一个假 stale-ref）
        String js = "(function(){" + JS_COMMON + "return " + TYPE_JS_FMT.replace("__REF__", ref)
                .replace("__TEXT__", jsString(text))
                .replace("__REPLACE__", replace ? "true" : "false") + ";})()";
        String raw = evalJs(js, 8000);
        if (raw == null) return err("type-failed", "写入脚本没有返回");
        JSONObject r;
        try { r = new JSONObject(raw); } catch (Throwable t) { return err("type-parse-failed", "写入结果解析失败"); }
        if (!r.optBoolean("ok", false)) {
            String why = r.optString("reason", "type-failed");
            if ("stale-ref".equals(why)) return refFailure("stale-ref", ref);
            return err(why, "写入失败（元素可能是只读/被禁用）");
        }
        JSONObject after = pageFp();
        JSONObject o = ok();
        put(o, "ref", ref);
        put(o, "value", r.optString("value", ""));
        put(o, "changed", !sameFp(before, after));
        put(o, "pageGeneration", st.generation);
        return o.toString();
    }

    private String doPress(JSONObject a) {
        String key = a.optString("key", "Enter");
        final int code = keyCode(key);
        if (code == 0) return err("unknown-key", "不支持的键：" + key);
        onMainSync(new Runnable() {
            @Override
            public void run() {
                long t = android.os.SystemClock.uptimeMillis();
                web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
                web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
            }
        });
        sleep(600);
        JSONObject after = pageFp();
        JSONObject o = ok();
        put(o, "key", key);
        put(o, "url", after == null ? st.url : after.optString("url", st.url));
        put(o, "pageGeneration", st.generation);
        return o.toString();
    }

    private String doScroll(JSONObject a) {
        String dir = a.optString("direction", "down").toLowerCase();
        int amount = a.optInt("amount", 0);
        String dy;
        if ("top".equals(dir)) dy = "-999999";
        else if ("bottom".equals(dir)) dy = "999999";
        else {
            int step = amount > 0 ? amount : 700;
            dy = ("up".equals(dir) ? "-" : "") + step;
        }
        String raw = evalJs(SCROLL_JS_FMT.replace("__DY__", dy), 6000);
        if (raw == null) return err("scroll-failed", "滚动脚本没有返回");
        JSONObject o = ok();
        try {
            JSONObject r = new JSONObject(raw);
            put(o, "scrollY", r.optInt("scrollY", 0));
            put(o, "innerHeight", r.optInt("innerHeight", 0));
            put(o, "docHeight", r.optInt("docHeight", 0));
        } catch (Throwable ignored) {}
        return o.toString();
    }

    // ==================== read / screenshot ====================

    private String doRead(JSONObject a) {
        String ref = a.optString("ref", "");
        if (ref.length() > 0 && !ref.matches("^r[0-9a-f]{7}(-\\d{1,3})?$")) return refFailure("invalid-ref", ref);
        String js = TEXT_JS_FMT.replace("__REF__", ref.length() > 0 ? jsString(ref) : "null");
        String raw = evalJs(js, 8000);
        if (raw == null) return err("read-failed", "取文脚本没有返回");
        String text = "";
        try {
            text = new JSONObject(raw).optString("text", "");
        } catch (Throwable t) {
            return err("read-parse-failed", "取文结果解析失败");
        }
        boolean truncated = text.length() > MAX_TEXT;
        if (truncated) text = text.substring(0, MAX_TEXT);
        JSONObject o = ok();
        put(o, "ref", ref);
        put(o, "text", text);
        put(o, "chars", text.length());
        put(o, "truncated", truncated);
        if (truncated) put(o, "hint", "已截断；可用 ref 取局部（先用 browser_find 定位）");
        return o.toString();
    }

    private String doScreenshot() {
        final JSONObject[] res = new JSONObject[1];
        final String[] fail = new String[1];
        onMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    if (web.getWidth() <= 0 || web.getHeight() <= 0) { fail[0] = "not-laid-out"; return; }
                    Bitmap bmp = Bitmap.createBitmap(web.getWidth(), web.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    web.draw(c);
                    File dir = new File(ctx.getFilesDir(), "browser");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, "shot-" + System.currentTimeMillis() + ".png");
                    FileOutputStream fos = new FileOutputStream(out);
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
                    fos.close();
                    bmp.recycle();
                    JSONObject o = ok();
                    put(o, "path", out.getAbsolutePath());
                    put(o, "bytes", out.length());
                    put(o, "width", web.getWidth());
                    put(o, "height", web.getHeight());
                    put(o, "purpose", "给人看的证据；不参与定位（定位一律走 ref）");
                    res[0] = o;
                } catch (Throwable t) {
                    fail[0] = String.valueOf(t.getMessage());
                }
            }
        });
        if (fail[0] != null) return err("not-laid-out".equals(fail[0]) ? "not-laid-out" : "screenshot-failed",
                "not-laid-out".equals(fail[0]) ? "WebView 还没有尺寸" : fail[0]);
        return res[0] == null ? err("screenshot-failed", "截图失败") : res[0].toString();
    }

    // ==================== 页签管理（v1.19.6 · 阶段 A2） ====================

    private String tabsList() {
        JSONObject o = ok();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            JSONObject j = new JSONObject();
            put(j, "tabId", t.id);
            put(j, "url", t.st.url);
            put(j, "title", t.st.title);
            put(j, "loadState", t.st.loadState);
            put(j, "pageGeneration", t.st.generation);
            put(j, "hasSnapshot", t.st.hasSnapshot);
            put(j, "active", t.id.equals(activeTabId));
            arr.put(j);
        }
        put(o, "tabs", arr);
        put(o, "count", tabs.size());
        put(o, "activeTabId", activeTabId);
        put(o, "hint", "每个页签的状态（ref/快照/代次）互相独立；切换后如需 ref 请重新 browser_snapshot");
        return o.toString();
    }

    private String tabsNew(JSONObject a) {
        String url = a.optString("url", "");
        if (url.length() > 0) {
            String bad = policyReason(url);
            if (bad != null) return err("blocked-url", "导航被拒：" + bad);
        }
        boolean front = a.optBoolean("front", true);
        Tab t = newTabOnMain(url.length() > 0 ? url : null);
        if (t == null) return err("new-tab-failed", "新建页签失败（WebView 创建不出来）");
        if (front) activate(t);
        JSONObject o = ok();
        put(o, "tabId", t.id);
        put(o, "activeTabId", activeTabId);
        put(o, "count", tabs.size());
        put(o, "url", t.st.url);
        put(o, "loadState", t.st.loadState);
        if (url.length() > 0) {
            put(o, "hint", "正在加载；要「打开并等加载完成」用 browser_open{url,newTab:true}，或直接 browser_snapshot");
        }
        return o.toString();
    }

    private String tabsSwitch(JSONObject a) {
        String id = a.optString("tabId", "");
        if (id.length() == 0) return err("tab-required", "tabs.switch 需要 tabId（先 tabs.list）");
        Tab t = findTab(id);
        if (t == null) return err("no-such-tab", "没有这个页签：" + id + "（先 tabs.list）");
        activate(t);
        JSONObject o = ok();
        put(o, "activeTabId", t.id);
        put(o, "url", t.st.url);
        put(o, "title", t.st.title);
        put(o, "loadState", t.st.loadState);
        put(o, "pageGeneration", t.st.generation);
        put(o, "hasSnapshot", t.st.hasSnapshot);
        put(o, "count", tabs.size());     // v1.19.6 真机缺陷：插件声明并渲染了 count，原生漏了它 → 界面印 undefined
        put(o, "hint", "已切到 " + t.id + "；它有自己的 ref 体系，必要时重新 browser_snapshot");
        return o.toString();
    }

    private String tabsClose(JSONObject a) {
        String id = a.optString("tabId", "");
        if (id.length() == 0) id = activeTabId;
        Tab t = findTab(id);
        if (t == null) return err("no-such-tab", "没有这个页签：" + id + "（先 tabs.list）");
        boolean wasActive = t.id.equals(activeTabId);
        destroyTab(t);
        String madeNew = "";
        if (tabs.isEmpty()) {
            Tab nt = newTabOnMain(null);       // 保持"永远至少一个页签"
            if (nt != null) { activate(nt); madeNew = nt.id; }
        } else if (wasActive) {
            activate(tabs.get(tabs.size() - 1));
        }
        JSONObject o = ok();
        put(o, "closed", id);
        put(o, "count", tabs.size());
        put(o, "activeTabId", activeTabId);
        if (madeNew.length() > 0) put(o, "newTabId", madeNew);
        put(o, "hint", "关掉的页签连同它的页面/内存一起释放（整进程隔离是 A2 的剩余项）");
        return o.toString();
    }

    // ==================== 内部：坐标派发 / 页面指纹 / 最小差分 ====================

    /**
     * JS 兜底点击（v1.19.6）。
     * 只在"WebView 不可见（Chromium 会丢合成触摸）"或"触摸点了但页面没变"时使用 ——
     * 它是唯一能在同屏面板关着时把点击送达的路径，否则 AI 的 browser_click 默认 100% 失效。
     * ref 只允许 [A-Za-z0-9_-]，直接拼进选择器是安全的（validateRef 已经先卡过一道）。
     */
    private String clickByJs(final String ref) {
        try {
            String js = "(function(){try{var e=document.querySelector('[data-dsh-ref=\""
                    + ref + "\"]');if(!e)return 'no-el';e.click();return 'js-clicked';}"
                    + "catch(err){return 'err:'+err;}})()";
            return evalJs(js, 3000);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 原生触摸派发（不用 JS 的 element.click()）：命中测试与坐标口径统一。 */
    private void dispatchTap(final float x, final float y) {
        onMainSync(new Runnable() {
            @Override
            public void run() {
                long t = android.os.SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(t, t + 48, MotionEvent.ACTION_UP, x, y, 0);
                try {
                    web.dispatchTouchEvent(down);
                    web.dispatchTouchEvent(up);
                } finally {
                    down.recycle();
                    up.recycle();
                }
            }
        });
    }

    private JSONObject pageFp() {
        String raw = evalJs(PAGE_FP_JS, 5000);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean sameFp(JSONObject a, JSONObject b) {
        if (a == null || b == null) return false;
        return a.optString("fp", "?").equals(b.optString("fp", "!"))
                && a.optString("url", "").equals(b.optString("url", ""));
    }

    /** 动作后：只回"哪些 ref 新增/消失"，不再回整棵树。 */
    private JSONObject minimalDelta() {
        JSONObject snap = rawSnapshot();
        if (snap == null) return null;
        Map<String, JSONObject> now = indexNodes(snap.optJSONArray("nodes"));
        JSONArray added = new JSONArray();
        List<String> removed = new ArrayList<String>();
        for (String r : now.keySet()) if (!st.lastNodes.containsKey(r)) added.put(r);
        for (String r : st.lastNodes.keySet()) if (!now.containsKey(r)) removed.add(r);
        st.lastNodes = now;
        st.hasSnapshot = true;
        JSONObject d = new JSONObject();
        put(d, "added", added);
        put(d, "removed", new JSONArray(removed));
        put(d, "nowCount", now.size());
        return d;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static int keyCode(String key) {
        String k = key == null ? "" : key.trim();
        if ("Enter".equalsIgnoreCase(k) || "回车".equals(k)) return KeyEvent.KEYCODE_ENTER;
        if ("Tab".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_TAB;
        if ("Escape".equalsIgnoreCase(k) || "Esc".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_ESCAPE;
        if ("Backspace".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_DEL;
        if ("ArrowUp".equalsIgnoreCase(k) || "Up".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("ArrowDown".equalsIgnoreCase(k) || "Down".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("ArrowLeft".equalsIgnoreCase(k) || "Left".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("ArrowRight".equalsIgnoreCase(k) || "Right".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("PageUp".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_PAGE_UP;
        if ("PageDown".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_PAGE_DOWN;
        if ("Home".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_MOVE_HOME;
        if ("End".equalsIgnoreCase(k)) return KeyEvent.KEYCODE_MOVE_END;
        return 0;
    }
}
