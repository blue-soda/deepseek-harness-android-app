package com.deepseek.harness;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
// v1.19.6：同屏改成"和虚拟屏预览窗同一套"的可拖可缩浮窗（同屏与虚拟屏同一套拖动缩放）
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AI 浏览器宿主服务 —— 跑在**独立进程** `:browser` 里（v1.19.6 · A2 第二件）。
 *
 * 为什么独立进程（设计稿 §0④）：浏览器页面把 WebView 搞崩是常态，同进程时崩的是整个 App
 * （主界面 + 控制台一起没）。独立进程后：页面崩只死 :browser，主界面与引擎（独立 node 进程）
 * 都不受影响；关掉浏览器 = 断开绑定 → 整进程可被回收（"一键整套丢弃"）。
 *
 * 承载方式：独立进程够不到主 Activity 的视图树，而 WebView 必须挂在一扇**真实窗口**里才会排版
 * （脱离视图树的 WebView 不排版、evaluateJavascript 不可靠）。这里用 WindowManager 加一扇
 * TYPE_APPLICATION_OVERLAY 的**不可见窗**（尺寸与同进程时的容器逐像素一致），根视图 INVISIBLE ——
 * 不画任何东西，但参与排版。
 * ⚠ 依赖「悬浮窗」权限（控制台 →「权限」页可授予）；没有时会抛出并回一条明确错误，不静默降级。
 *
 * IPC：Messenger。主进程 bindService；每个 op 一条 Message（data: op/args/timeout_ms）+ replyTo 回信。
 * op 的编排放在**工作线程**（BrowserHost.op 是阻塞式编排，绝不能在子进程主线程上跑）。
 */
public class BrowserService extends Service {
    public static final String TAG = "DSHBrowserSvc";

    /** 一条 op 请求：data{op, args, timeout_ms} + replyTo。 */
    public static final int MSG_OP = 1;
    public static final String KEY_OP = "op";
    public static final String KEY_ARGS = "args";
    public static final String KEY_TIMEOUT = "timeout_ms";
    public static final String KEY_RESULT = "result";

    /** 不可见窗：不接受焦点、不接受触摸（免得挡住用户），也不抢模态。 */
    // v1.19.6：同屏窗现在**可触摸**（与虚拟屏预览窗一致）—— 手指用来拖动/缩放这扇窗；
    // 画面本身仍然只读（你的点击不会传进网页）。原来是 NOT_TOUCHABLE（触摸穿透）。
    private static final int WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread opThread;
    private Messenger messenger;

    private BrowserHost host;
    private WindowManager wm;
    private FrameLayout windowRoot;
    /** 只读同屏（阶段 C）的当前状态；控制台据此显示真实开关，不靠前端猜。 */
    private volatile boolean panelVisible;
    // v1.19.6（同屏与虚拟屏同一套拖动缩放）：与 VsreenBridgeService 预览窗同一套拖动/缩放状态。
    private FrameLayout panelView = null;      // 画面区（BrowserHost 的 container 挂这儿）
    private View panelShell = null;            // 整扇窗的壳（顶栏 + 画面区）
    private volatile boolean panelDragging = false;   // v1.19.6（拖小条搬窗）：本次手势是不是在搬窗
    private float panelDownX, panelDownY;
    private int panelStartX, panelStartY;
    /** 画面适配比例 = 画面区宽 / 1080；container 永远 1080×1920 排版，只靠它等比缩放。 */
    private volatile float panelFit = 1f;

    @Override
    public void onCreate() {
        super.onCreate();
        // ⚠ 多进程用 WebView 的硬约束（真机实测踩到）：
        //   同 App 的两个进程**不能共用同一个 WebView 数据目录**，否则第二个进程直接抛
        //   "Using WebView from more than one process at once with the same data directory
        //    is not supported. https://crbug.com/558377"。
        //   这里给 :browser 进程单独命名一份数据目录 —— 主界面（默认目录）完全不动，
        //   用户界面数据零影响，顺带白拿一层 cookie/存储隔离。
        //   必须在**本进程**用任何 WebView API 之前调用，所以放在 onCreate 最前面。
        try {
            WebView.setDataDirectorySuffix("browser");
            Log.i(TAG, "WebView 数据目录后缀 = browser（与主界面分开）");
        } catch (Throwable t) {
            Log.w(TAG, "setDataDirectorySuffix 失败（本进程可能已用过 WebView）", t);
        }
        opThread = new HandlerThread("browser-op");
        opThread.start();
        messenger = new Messenger(new Handler(opThread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                if (msg.what == MSG_OP) onOp(msg);
                return true;
            }
        }));
        Log.i(TAG, "服务已创建（:browser 进程 pid " + Process.myPid() + "）");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        return messenger == null ? null : messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "服务销毁（pid " + Process.myPid() + "）");
        host = null;
        panelVisible = false;
        final WindowManager w = wm;
        final FrameLayout root = windowRoot;
        wm = null;
        windowRoot = null;
        if (w != null && root != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try { w.removeViewImmediate(root); } catch (Throwable ignored) {}
                }
            });
        }
        if (opThread != null) opThread.quitSafely();
        super.onDestroy();
    }

    // ==================== op 处理（工作线程） ====================

    private void onOp(Message msg) {
        String op = msg.getData().getString(KEY_OP, "caps");
        String argsJson = msg.getData().getString(KEY_ARGS, "{}");
        int timeout = msg.getData().getInt(KEY_TIMEOUT, 30000);

        // 「一键整套丢弃」（设计稿 §0④ 的收益之一）：把整个 :browser 进程丢掉。
        // 先回信再自杀（顺序反了调用方只会看到"子进程死了"），250ms 后 killProcess 自己。
        // 主进程完全不受影响；下一次 op 会重新 bindService，系统拉起一个全新的 :browser。
        if ("process.kill".equals(op)) {
            final int pid = Process.myPid();
            Log.i(TAG, "收到 process.kill → 主动丢弃整个 :browser 进程（pid " + pid + "）");
            reply(msg, "{\"ok\":true,\"reason\":\"process-killed\",\"pid\":" + pid + "}");
            if (opThread != null) {
                new Handler(opThread.getLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() { Process.killProcess(pid); }
                }, 250);
            }
            return;
        }

        // 阶段 C「只读同屏」：把承载窗变可见，让人能看见 AI 正在点哪一页。
        // ⚠ 只切可见性 —— **绝不动窗口尺寸**（WebView 尺寸一变，CSS 视口与点击坐标口径全变），
        //    也**保持 NOT_TOUCHABLE**（触摸穿透到下面，用户照样能操作控制台；这是"只读"的定义）。
        //
        // v1.19.6 收口（控制台入口）后补齐三条不变式：
        //   ① `panel.state` = **纯状态查询** —— 绝不 ensureHost()（否则"打开控制台看一眼"
        //      就会拉起一个 :browser 进程 + 一扇窗口）；
        //   ② `panel{show:true}` 在**还没有浏览器**时只回 no-browser，**不建空窗** ——
        //      承载窗是 1080×1920 全屏的，把空窗变可见 = 一块白板盖住整个屏幕；
        //   ③ 任何 panel 操作都不碰尺寸 / 触摸标志 / 焦点标志。
        if ("panel".equals(op) || "panel.state".equals(op)) {
            JSONObject pa;
            try {
                pa = new JSONObject(argsJson == null || argsJson.trim().isEmpty() ? "{}" : argsJson);
            } catch (Throwable ignored) {
                pa = new JSONObject();
            }
            // 纯查询（或没给 show）：只读现状，什么都不建。
            if ("panel.state".equals(op) || !pa.has("show")) {
                reply(msg, panelStateJson(""));
                return;
            }
            final boolean show = pa.optBoolean("show", true);
            if (show && host == null) {
                // 没有浏览器就没有"正在看的那一页"：建空窗只会挡住整个屏幕。
                reply(msg, err("no-browser", "AI 浏览器还没打开页面：先让 AI 打开一个网页，再开同屏"));
                return;
            }
            try {
                if (host != null) {
                    mainSync(new Runnable() {
                        @Override
                        public void run() { setPanelVisible(show); }
                    });
                } else {
                    panelVisible = false;
                }
            } catch (Throwable t) {
                Log.w(TAG, "panel 失败", t);
                reply(msg, err("browser-process-error", String.valueOf(t.getMessage())));
                return;
            }
            reply(msg, panelStateJson("只读同屏：能看不能点（触摸穿透到下面）"));
            return;
        }

        String result;
        try {
            ensureHost();
            JSONObject args = new JSONObject(argsJson == null || argsJson.trim().isEmpty() ? "{}" : argsJson);
            result = host.op(op, args, timeout);
        } catch (Throwable t) {
            Log.w(TAG, "op " + op + " 失败", t);
            result = err("browser-process-error", String.valueOf(t.getMessage()));
        }
        reply(msg, result);
        // 只在**明确关掉浏览器**之后回收：op 失败（比如承载窗没建起来）时 tabCount 也是 0，
        // 早期版本因此把服务拆了 → 后续每个 op 都在全新空 host 上跑（真机踩过）。
        // 关掉浏览器才回收服务：看**结果里的 closed**（插件走的是 nav{op:"close"}，op 名不是 "close"），
        // 而不是猜 op 名。失败/空页签不再误伤（早期版本因此把服务拆了，见交接文档 §3.2）。
        if (host != null && host.tabCount() == 0 && closedFlag(result)) {
            panelVisible = false;   // 浏览器关干净了 → 同屏自然也没了（窗口在 onDestroy 里摘掉）
            Log.i(TAG, "浏览器已关 → stopSelf（主进程松绑后整进程可被回收）");
            stopSelf();
        }
    }

    /**
     * panel / panel.state 的统一回执：`running`（有没有活的 host）、`visible`（同屏开着没）、
     * `tabs`（页签数）、可选 `hint`。
     * 控制台据此渲染「同屏查看」开关与状态行 —— 状态只有这一个来源，前端不猜。
     */
    private String panelStateJson(String hint) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("running", host != null);
            o.put("visible", panelVisible);
            try {
                WindowManager.LayoutParams l = windowRoot == null ? null
                        : (WindowManager.LayoutParams) windowRoot.getLayoutParams();
                if (l != null) { o.put("w", l.width); o.put("h", l.height); o.put("x", l.x); o.put("y", l.y); }
            } catch (Throwable ignored) {}
            o.put("tabs", host == null ? 0 : host.tabCount());
            if (hint != null && hint.length() > 0) o.put("hint", hint);
        } catch (Throwable ignored) {}
        return o.toString();
    }

    /** 建窗口 + BrowserHost（只做一次）。 */
    private void ensureHost() {
        if (host != null) return;
        mainSync(new Runnable() {
            @Override
            public void run() { ensureWindowOnMain(); }
        });
        if (windowRoot == null) {
            throw new IllegalStateException("承载窗没建起来 —— 多半是缺「悬浮窗」权限（控制台 →「权限」页可授予）");
        }
        host = new BrowserHost(new BrowserHost.Host() {
            @Override
            public Context ctx() { return themed(); }

            @Override
            public ViewGroup windowRoot() { return panelView != null ? panelView : windowRoot; }
        });
    }

    /** 区分"点小条"和"拖小条"的位移阈值（8dp，和系统 touch slop 一个量级）。 */
    private int panelDragSlop() { return dp(8); }

    /** dp → px（与虚拟屏预览窗同一套写法；patch88 用到）。 */
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** 承载窗（**必须主线程**）——形态与虚拟屏预览窗一致：顶部小条 + 画面区，可拖可缩。 */
    private void ensureWindowOnMain() {
        if (windowRoot != null) return;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        // ---- shell：圆角深色底，顶栏 24dp（64×6dp 圆角短横），下面画面区 ----
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shellBg = new GradientDrawable();
        shellBg.setColor(0xCC000000);
        shellBg.setCornerRadius(dp(10));
        shell.setBackground(shellBg);
        try { shell.setClipToOutline(true); } catch (Throwable ignored) {}

        FrameLayout barZone = new FrameLayout(this);
        barZone.setClickable(true);
        View pill = new View(this);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xFF4D6BFE);
        pillBg.setCornerRadius(dp(3));
        pill.setBackground(pillBg);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(dp(64), dp(6));
        pillLp.gravity = Gravity.CENTER;
        barZone.addView(pill, pillLp);
        // v1.19.6（拖小条搬窗）：用户 2026-10-05 当场改的方案 ——
        // 「就不要调整大小了，能移动就行，移动的方法就是拖动上面那个小条」。
        // 一个小条上要分清两种手势：
        //   · 位移 < 8dp 抬起 → 算"点" → 关同屏（原来的手感和行为一点没动）
        //   · 位移 ≥ 8dp       → 进入"搬窗"：按位移改 lp.x/lp.y，每帧 clamp 回可见范围；
        //                        抬起后**不关窗**（拖完顺手把窗关了是最讨厌的）
        // 注意：OnTouchListener 在 DOWN 返回 true 之后 OnClickListener 就再也不会触发，
        // 所以"点 = 关同屏"必须在 ACTION_UP 里自己补上。
        // 画面区（小条以下）**一个触摸都不拦**：那里面是活的 WebView，用户要能直接点里面的内容。
        barZone.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        panelDownX = e.getRawX();
                        panelDownY = e.getRawY();
                        WindowManager.LayoutParams l0 = (WindowManager.LayoutParams) windowRoot.getLayoutParams();
                        panelStartX = l0.x;
                        panelStartY = l0.y;
                        panelDragging = false;
                        return true;      // 得吃下 DOWN，后面的 MOVE/UP 才归我们
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - panelDownX;
                        float dy = e.getRawY() - panelDownY;
                        if (!panelDragging
                                && (Math.abs(dx) > panelDragSlop() || Math.abs(dy) > panelDragSlop())) {
                            panelDragging = true;
                            Log.i(TAG, "小条开始搬窗 " + panelStartX + "," + panelStartY);
                        }
                        if (panelDragging) {
                            WindowManager.LayoutParams l1 = (WindowManager.LayoutParams) windowRoot.getLayoutParams();
                            l1.x = Math.round(panelStartX + dx);
                            l1.y = Math.round(panelStartY + dy);
                            clampPanelBounds(l1);
                            updatePanelLayout();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!panelDragging) {
                            Log.i(TAG, "小条点击 → 关同屏");
                            setPanelVisible(false);       // 没挪动 = 这是一次"点"
                        } else {
                            WindowManager.LayoutParams l2 = (WindowManager.LayoutParams) windowRoot.getLayoutParams();
                            Log.i(TAG, "小条搬窗结束 → " + l2.x + "," + l2.y + "（不关窗）");
                        }
                        panelDragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        panelDragging = false;
                        return true;
                }
                return false;
            }
        });
        shell.addView(barZone, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        panelView = new FrameLayout(this);
        shell.addView(panelView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // v1.19.6（拖小条搬窗）：patch93 那条"画面区拦截"**已撤销** ——
        // 用户明确要画面里的内容**能直接点**（"实际上是可以点击的操作里面的内容的"），
        // 所以这里回到普通 FrameLayout：小条以下的触摸原样交给 WebView。
        FrameLayout root = new FrameLayout(this);
        root.addView(shell, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panelShell = shell;
        // 与同进程实现同一套口径：不画，但参与排版（GONE 会让视口塌成 0）
        root.setVisibility(View.INVISIBLE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                BrowserHost.windowWidth(), BrowserHost.windowHeight(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WINDOW_FLAGS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.setTitle("DSH AI Browser");   // dumpsys window 里能认出这扇窗
        // 初始尺寸：和虚拟屏一样**不占满屏**（留出位置让下面的控制台还能操作），最大 70%
        // v1.19.6（同屏尺寸整比与缩放余量）：起始宽度**故意只占屏宽 55%** ——
        // 若像 patch88 那样直接取 round(sw*0.7f)，它会正好等于 clamp 的上限，
        // 双指放大立刻被夹回去 ⇒ 用户眼里"还是不能调整大小"。
        int sw = getResources().getDisplayMetrics().widthPixels;
        int usableH = usableHeight();
        lp.width = panelStartWidth(sw);
        lp.height = panelHeightFor(lp.width);   // 画面区整比 + 顶部小条 24dp（与虚拟屏 fh + dp(24) 同式）
        clampPanelBounds(lp);
        lp.x = Math.max(0, sw - lp.width - dp(24));                        // 初始右上角（与虚拟屏同口径）
        lp.y = Math.min(dp(120), Math.max(0, usableH - lp.height));
        // v1.19.6（拖小条搬窗）：画面区的拖动与双指缩放**整段撤掉** ——
        // 用户改需求："就不要调整大小了，能移动就行"，移动手势也改成落在顶部小条上。
        // 画面区从此不挂任何触摸处理，WebView 想怎么用就怎么用。
        wm.addView(root, lp);
        windowRoot = root;
        Log.i(TAG, "承载窗已加 " + lp.width + "x" + lp.height + "（拖顶部小条搬窗；起始宽度 "
                + Math.round(lp.width * 100f / Math.max(1, sw)) + "% 屏宽）");
    }

    /** 窗口尺寸/位置的**统一**夹回可见范围（与虚拟屏 clampPreviewBounds 同一套规则）。 */
    private void clampPanelBounds(WindowManager.LayoutParams l) {
        try {
            if (l == null) return;
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int usableH = usableHeight();
            int maxW = panelMaxW();
            int maxH = panelMaxH();
            if (l.width > maxW) l.width = maxW;
            if (l.width < dp(120)) l.width = dp(120);
            if (l.height > maxH) l.height = maxH;
            if (l.height < dp(110)) l.height = dp(110);
            if (l.x > screenW - l.width) l.x = screenW - l.width;
            if (l.y > usableH - l.height) l.y = usableH - l.height;
            if (l.x < 0) l.x = 0;
            if (l.y < 0) l.y = 0;
        } catch (Throwable ignored) {}
    }

    // ---- v1.19.6（同屏尺寸整比与缩放余量）：尺寸口径全部收在这三个方法里 ----

    /** 70% 屏宽上限（与虚拟屏 clampPreviewBounds 的 maxW 同一个式子）。 */
    private int panelMaxW() {
        return Math.max(dp(120), Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f));
    }

    /** 70% 可用高度上限（与虚拟屏 clampPreviewBounds 的 maxH 同一个式子）。 */
    private int panelMaxH() {
        return Math.max(dp(24), Math.round(usableHeight() * 0.7f));
    }

    /** 起始宽度 = 屏宽 55%，夹在 [120dp, 70%] 内 —— 给"双指放大"留出余量。 */
    private int panelStartWidth(int screenW) {
        return Math.min(Math.max(Math.round(screenW * 0.55f), dp(120)), panelMaxW());
    }

    /**
     * 由宽度推窗口高度：**画面区整比（1080×1920）+ 顶部小条 24dp**。
     * 与虚拟屏 `previewLp.height = fh + dp(24)` 是同一个式子；
     * 少了这 24dp，画面区就比整比矮一条 ⇒ 页面底部被裁（真机实测裁掉约 5%）。
     */
    private int panelHeightFor(int w) {
        int content = Math.round(w * BrowserHost.windowHeight() / (float) BrowserHost.windowWidth());
        return Math.min(Math.max(content + dp(24), dp(110)), panelMaxH());
    }

    /** 去掉状态栏占位后的可用高度（与虚拟屏同口径）。 */
    private int usableHeight() {
        int h = getResources().getDisplayMetrics().heightPixels - statusBarInset();
        return h > 0 ? h : getResources().getDisplayMetrics().heightPixels;
    }

    private int statusBarInset() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 安全更新窗口布局（View 已 detach 时静默跳过，与虚拟屏同一套防崩）。 */
    private void updatePanelLayout() {
        try {
            if (windowRoot == null || wm == null) return;
            if (!windowRoot.isAttachedToWindow()) return;
            wm.updateViewLayout(windowRoot, windowRoot.getLayoutParams());
            applyPanelFit();
        } catch (Throwable ignored) {}
    }

    /**
     * 把 1080×1920 的画面**等比适配**到画面区 —— 只改缩放，不改排版尺寸，
     * 所以 WebView 的 CSS 视口与 AI 的坐标口径始终是 540×960。
     */
    private void applyPanelFit() {
        try {
            View content = panelContent();
            if (content == null || panelView == null) return;
            int vw = panelView.getWidth();
            int vh = panelView.getHeight();
            if (vw <= 0) return;
            float fitW = vw / (float) BrowserHost.windowWidth();
            float fitH = vh > 0 ? vh / (float) BrowserHost.windowHeight() : fitW;
            // v1.19.6（同屏尺寸整比与缩放余量）：两个方向都装得下才叫"整幅可见"。
            // 正常几何下 fitW == fitH（高度就是按整比算的）；只有被 70% 高度上限夹住时
            // fitH 更小，这时取小的那个 ⇒ 宁可底下留一条边，也不把页面裁掉。
            panelFit = Math.min(fitW, fitH);
            content.setPivotX(0f);
            content.setPivotY(0f);
            content.setScaleX(panelFit);
            content.setScaleY(panelFit);
        } catch (Throwable ignored) {}
    }

    /** 画面区里那个 1080×1920 的 container（BrowserHost 挂进来的）。 */
    private View panelContent() {
        try {
            if (panelView == null || panelView.getChildCount() == 0) return null;
            return panelView.getChildAt(0);
        } catch (Throwable t) { return null; }
    }

    /**
     * 只读同屏开关（**必须主线程**）：只切可见性 —— 尺寸、触摸标志、焦点标志全部不动。
     * 之所以这么严：窗口尺寸会决定 WebView 的 CSS 视口，AI 的 ref/点击坐标口径全建立在它上面。
     */
    private void setPanelVisible(boolean show) {
        if (windowRoot == null) { panelVisible = false; return; }
        windowRoot.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        panelVisible = show;      // 控制台要读真实状态（别让前端自己猜）
        if (show) {
            // 显示后再算一次适配比例（隐藏期间画面区宽高为 0，量不到）
            windowRoot.post(new Runnable() { @Override public void run() { applyPanelFit(); } });
        }
        Log.i(TAG, "同屏面板 " + (show ? "已显示" : "已隐藏") + "（同屏与虚拟屏同一套拖动缩放）");
    }

    /** 结果里是否 closed=true（"浏览器已经关干净了"的唯一可信判据）。 */
    private static boolean closedFlag(String result) {
        try {
            return new JSONObject(result).optBoolean("closed", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** WebView / 取目录用的 Context：套上 App 主题，尽量与同进程时一致。 */
    private Context themed() {
        try {
            return new ContextThemeWrapper(this, R.style.AppTheme);
        } catch (Throwable t) {
            return this;
        }
    }

    private void mainSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "main task failed", t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reply(Message msg, String result) {
        Messenger back = msg.replyTo;
        if (back == null) return;
        Message r = Message.obtain(null, MSG_OP);
        Bundle b = new Bundle();
        b.putString(KEY_RESULT, result);
        r.setData(b);
        try {
            back.send(r);
        } catch (RemoteException e) {
            Log.w(TAG, "回信失败（主进程可能已退出）", e);
        }
    }

    private static String err(String reason, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("reason", reason);
            o.put("error", message == null ? "" : message);
        } catch (Throwable ignored) {}
        return o.toString();
    }
}
