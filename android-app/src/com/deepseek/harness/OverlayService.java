package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.HashSet;

import org.json.JSONObject;

/**
 * 小鲸鱼悬浮窗服务：
 *  - 常驻悬浮小鲸鱼图标（可拖动；松手自动贴边，静置时半藏在屏幕边缘）
 *  - 点击展开紧凑状态面板：引擎状态 / AI 会话状态 / 打开应用 / 虚拟屏 / 销毁屏 / 收起
 *  - 拖到屏幕底部区域松手 = 隐藏小鲸鱼（通知栏「显示小鲸鱼」可恢复）
 *  - 每 2 秒探测引擎端口；每 6 秒扫一次 /proc 统计"正在写入的会话"刷新 AI 状态
 *  - 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限；前台服务保活
 *
 * v1.13.12 大改（用户 15 条反馈里的悬浮窗部分）：
 *  ① 前台隐藏优先级最高 —— 之前虚拟屏预览「收起到小鲸鱼」的钉住状态会盖过前台隐藏，
 *     导致 App 里偶尔冒出小鲸鱼；现在 App 前台一律隐藏（预览仍由钉住状态保持拉帧）。
 *  ② 静置半藏：面板收起时小鲸鱼滑到屏幕边缘、只露一半（FLAG_LAYOUT_NO_LIMITS 允许越界）；
 *     点开面板/拖动时完整露出。收起与唤出都带旋转抖动动画。
 *  ③ 拖到底部隐藏：悬浮窗没有系统级的"拖底消失"（那是通知气泡的特权），
 *     这里自实现同款手势 + 通知栏恢复入口。
 *  ④ AI 状态不再走 HTTP：0.1.5 起接口要认证 cookie，悬浮窗拿不到（401 → 永远"空闲"）。
 *     改为扫 /proc/<同uid进程>/fd 里持着 dshhome/sessions/**​/session.lock 的文件描述符 ——
 *     内核的会话写入器持锁多久，fd 就开多久（dsh-session-persistence-jsonl 的 SessionWriteLease），
 *     这是"会话正在工作"的一手证据，无需任何认证。
 */
public class OverlayService extends Service {
    /**
     * 引擎端口的**兜底默认值**：v1.18（B11）起由变体表决定（BuildVariant.ENGINE_PORT），
     * 不再按包名 contains() 猜 —— 否则社区版等新变体会落回 3080，抢正式版的端口。
     * 通知端口 = 引擎端口 + 1，无障碍端口 = 引擎端口 + 101（均按变体错开）。
     */
    private static int defaultEnginePort(Context ctx) {
        return BuildVariant.ENGINE_PORT;
    }

    private static final String PREFS = "dsh_prefs";
    private static final String KEY_PORT = "engine_port";
    private static final String CHANNEL_ID = "dsh_overlay";
    private static final int NOTIF_ID = 9002;
    private static final long PROBE_MS = 2000L;
    /**
     * 静置贴边时露在外面的比例（其余越界到屏幕外）。
     * v1.19：悬浮窗图标从"小鲸鱼剪影"换成**人物立绘**（res/drawable-nodpi/overlay_avatar.png）。
     * 原来 0.45 的半藏会把人物裁掉一半、认不出是谁；改成 0.8 = 基本完整、又贴着屏幕边缘
     * （留 20% 越界，视觉上"贴边站着"）。想完全露出改 1.0，想恢复半藏改 0.45。
     */
    private static final float TUCK_VISIBLE_FRACTION = 0.8f;
    /**
     * v1.21：静置（半藏）时，整体再朝**屏幕内侧**平移的比例（按屏幕宽度算）。
     * 用户要求"静止小人整体右移 5% 左右" —— 贴左边时就是往右挪，贴右边时对称地往左挪。
     * 想挪更多/更少改这个值即可；设为 0 则回到纯半藏。
     */
    private static final float TUCK_INSET_SHIFT_FRACTION = 0.05f;
    /** 拖到距底部多少 dp 内松手 = 隐藏。 */
    private static final int DISMISS_ZONE_DP = 84;
    /** AI "已完成"提示在状态切换后保留的时长（毫秒）。 */
    private static final long FINISHED_TTL_MS = 60000L;

    /** 当前运行的 OverlayService 实例（供 MainActivity 前后台联动控制视图可见性）。 */
    private static OverlayService instance = null;

    /** 是否正在运行（供 MainActivity / HTTP 端点查询） */
    public static volatile boolean isRunning = false;
    /** 最近一次引擎探测结果 */
    public static volatile boolean engineUp = false;
    public static volatile long lastProbeAt = 0L;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout rootView;
    private ImageView iconView;
    private LinearLayout panelView;
    private TextView statusText;
    /** v1.21：会话数行（`N 个会话运行中…` / 暂无会话运行 / 会话已完成 ✓）。 */
    private TextView sessionText;
    /** v1.21：面板里的"任务状态"行 —— 与头像上方气泡同源（气泡被点掉后仍能在这里看）。 */
    private TextView agentText;
    private Button destroyBtn;
    /** v1.21（B）：虚拟屏回程按钮，仅在虚拟屏真的在跑时可见。 */
    private Button vscreenBtn;
    /** v1.21：虚拟屏那两枚按钮所在的行，整行只在真的有虚拟屏时出现。 */
    private LinearLayout btnRowVs;
    /** v1.21：「完全退出」按钮（两步确认防误触）。 */
    private Button exitBtn;
    /** 退出按钮是否处于"待确认"状态。 */
    private boolean exitArmed = false;
    /** 待确认超时自动取消（4 秒）。 */
    private final Runnable exitDisarm = new Runnable() {
        @Override public void run() { setExitArmed(false); }
    };
    // v1.9 虚拟屏预览：悬浮窗实时显示虚拟屏画面（用户可看 AI 操作）
    private ImageView vscreenImageView = null;
    private volatile boolean vscreenPreviewRunning = false;
    private final Handler vscreenHandler = new Handler(Looper.getMainLooper());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int enginePort = 3080;

    // ============ v1.21（需求 2）：头像上方的 agent 状态气泡 ============
    /**
     * 小气泡：透明底、小字，贴在头像上方，显示 agent 当前在做什么 ——
     * 「思考中…」「正在调用 web_fetch…」以及最重要的「任务已完成 / 会话已结束」，
     * 空闲几分钟后显示「摸鱼中…」。目的：让用户做屏幕控制类任务时不必靠猜判断是否结束。
     * 状态由内核侧插件 POST 到 App 本地服务（/overlay?action=bubble）后转到这里。
     */
    private TextView statusBubble = null;
    /** 最近一次收到状态的时间：空闲计时用（超时 → 摸鱼中…）。 */
    private volatile long lastAgentStatusAt = 0L;
    /** 空闲多久开始显示"摸鱼中…" */
    private static final long IDLE_FISH_MS = 3 * 60 * 1000L;
    /** 瞬时状态（思考中/调用工具）气泡停留时长 */
    private static final long BUBBLE_TTL_MS = 12000L;
    /** 气泡自动收起（瞬时状态用；终态/空闲态不排这个）。 */
    private final Runnable bubbleHide = new Runnable() {
        // v1.21：用 INVISIBLE 而不是 GONE —— 见 buildOverlay() 里气泡占位的说明：
        // 气泡槽位必须**恒定保留**，否则气泡出现/消失会把小人上下挤动。
        @Override public void run() { hideBubble(); }
    };

    /** 最近一次气泡状态：服务被重启后据此恢复（只恢复 10 分钟内的）。 */
    private static volatile String lastText = "";
    private static volatile boolean lastSticky = false;
    private static volatile long lastStatusAt = 0L;

    private float touchX, touchY, startX, startY;
    private boolean dragging = false;
    private boolean panelVisible = false;
    /** 拖动中进入"拖底删除"暗示区（图标缩小变淡提示松手即隐藏）。 */
    private boolean dismissHint = false;
    /** 用户拖底主动隐藏后为 true；从通知栏「显示小鲸鱼」恢复。 */
    private volatile boolean userHidden = false;
    /** v1.13.11：悬浮图标当前吸附在右边？由拖动松手时的 snapToEdge() 决定。 */
    private boolean snappedRight = false;
    /** v1.21：气泡槽位（停靠方向变化时要改它的对齐方式，见 applyDockAlignment）。 */
    private android.widget.FrameLayout bubbleSlotView = null;
    /** v1.21：图标行（小人所在的那一行）—— 也要显式设 layout_gravity，否则靠右停靠时小人不动。 */
    private LinearLayout iconRowView = null;
    /** v1.21：横向行，装着"面板 + 小人"，小人恒为该行末尾 ⇒ 小人右边 = 行右边（与面板宽度无关）。 */
    private LinearLayout bottomRowView = null;
    /** v1.21：竖向容器，**左侧停靠**时装面板（小人下方）——保证左侧行为不变。 */
    private LinearLayout panelRowView = null;
    /** v1.21：权重留白（插在面板与小人之间，把小人顶到行末端）。 */
    private View rowSpacerView = null;
    /** v1.21：当前横向行是否为"右侧顺序"（面板在前、小人在后），避免每次布局都重排。 */
    private boolean bottomRowOrderIsRight = false;
    /** v1.21：最近一次几何快照（供 /overlay?action=geom 查询，便于用数字验证布局）。 */
    static volatile String lastGeom = "";
    /** v1.21（重构）：气泡独立窗口。 */
    private android.widget.FrameLayout bubbleWindowView = null;
    /** v1.21（重构）：面板独立窗口。 */
    private android.widget.FrameLayout panelWindowView = null;
    /** v1.21（重构）：气泡/面板两个附属窗口的布局参数。 */
    private WindowManager.LayoutParams lpBubble = null;
    private WindowManager.LayoutParams lpPanel = null;
    /** v1.21（重构）：气泡当前是否有内容要显示。 */
    private volatile boolean bubbleShowing = false;

    /**
     * v1.21（重构核心）：给气泡/面板两个**独立窗口**定位，全部以小人窗口为锚点 ——
     *   · 气泡：靠右停靠 ⇒ 右边界 = 小人右边界（只向左增长）；靠左停靠 ⇒ 左边界 = 小人左边界
     *   · 面板：水平同理；纵向贴在小人下方
     * 独立窗口之间不参与彼此的测量，所以"谁出现/消失"都不会挤动别人 ——
     * 这正是原来"一个窗口装三样东西"时做不到的。
     */
    private void layoutCompanions() {
        try {
            if (lp == null || rootView == null) return;
            int avW = rootView.getWidth() > 0 ? rootView.getWidth() : dp(48);
            int avH = rootView.getHeight() > 0 ? rootView.getHeight() : dp(48);
            int avL = lp.x;
            int avR = lp.x + avW;
            boolean visible = rootView.getVisibility() == View.VISIBLE;

            if (bubbleWindowView != null && lpBubble != null) {
                boolean show = visible && bubbleShowing;
                bubbleWindowView.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    // v1.21 修正：**不要**给气泡窗口强制设宽高 —— 之前用 getMeasuredWidth() 设过，
                    // 而那一刻文字还没布局，窗口被压窄 ⇒ 气泡只剩一个空框、文字被裁掉（用户报障）。
                    // 交回 WRAP_CONTENT（窗口自适应内容），位置由 x/y 决定，布局完成后再重排一次。
                    lpBubble.width = WindowManager.LayoutParams.WRAP_CONTENT;
                    lpBubble.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    int bw = bubbleWindowView.getWidth() > 0 ? bubbleWindowView.getWidth() : dp(60);
                    int bh = bubbleWindowView.getHeight() > 0 ? bubbleWindowView.getHeight() : dp(22);
                    lpBubble.x = snappedRight ? (avR - bw) : avL;
                    lpBubble.y = lp.y - bh - dp(2);
                    wm.updateViewLayout(bubbleWindowView, lpBubble);
                }
            }
            if (panelWindowView != null && lpPanel != null) {
                boolean show = visible && panelVisible;
                panelWindowView.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    lpPanel.width = WindowManager.LayoutParams.WRAP_CONTENT;
                    lpPanel.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    int pw = panelWindowView.getWidth() > 0 ? panelWindowView.getWidth() : dp(200);
                    lpPanel.x = snappedRight ? (avR - pw) : avL;
                    lpPanel.y = lp.y + avH + dp(2);
                    wm.updateViewLayout(panelWindowView, lpPanel);
                }
            }
            updateGeomSnapshot();
        } catch (Throwable ignored) {}
    }

    /** v1.21：让 /overlay?action=geom 能主动刷新一次几何快照（只读，不改变位置）。 */
    static void nudgeGeometry() {
        OverlayService s = instance;
        if (s != null) {
            try { s.updateGeomSnapshot(); } catch (Throwable ignored) {}
        }
    }

    /** 采集一次几何快照（窗口/小人/气泡/面板的屏幕 x 区间）。 */
    private void updateGeomSnapshot() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth();
            int[] aLoc = new int[2];
            int[] bLoc = new int[2];
            int[] pLoc = new int[2];
            if (iconView != null) iconView.getLocationOnScreen(aLoc);
            if (statusBubble != null) statusBubble.getLocationOnScreen(bLoc);
            if (panelView != null) panelView.getLocationOnScreen(pLoc);
            lastGeom = "{\"dock\":\"" + (snappedRight ? "R" : "L") + "\""
                    + ",\"panel\":" + panelVisible
                    + ",\"screenW\":" + getResources().getDisplayMetrics().widthPixels
                    + ",\"win\":[" + lp.x + "," + (lp.x + w) + "]"
                    + ",\"avatar\":[" + aLoc[0] + "," + (aLoc[0] + (iconView != null ? iconView.getWidth() : 0)) + "]"
                    + ",\"bubble\":[" + bLoc[0] + "," + (bLoc[0] + (statusBubble != null ? statusBubble.getWidth() : 0)) + "]"
                    + ",\"bubbleVis\":" + (statusBubble != null ? statusBubble.getVisibility() : -1)
                    + ",\"panelRect\":[" + pLoc[0] + "," + (pLoc[0] + (panelView != null ? panelView.getWidth() : 0)) + "]}";
        } catch (Throwable ignored) {}
    }
    /** v1.13.11：App 在前台 → 悬浮窗应隐藏（由 MainActivity.onStart/onStop 维护）。 */
    private volatile boolean foregroundWantsHidden = true;
    /** v1.13.11：被虚拟屏预览「收起到小鲸鱼」钉住 —— 只负责持续拉预览帧，不再影响可见性。 */
    private volatile boolean vscreenPinned = false;
    /** 探测计数：每 PROBE_MS 探测一次引擎；每 3 次（约 6 秒）顺带扫一次会话写入器 */
    private int probeCount = 0;
    /** 最近一次扫到的"持锁会话"目录集合；null 表示还扫过。 */
    private volatile HashSet<String> activeSessions = new HashSet<String>();
    private volatile long finishedAt = 0L;

    public static int enginePort(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return sp.getInt(KEY_PORT, defaultEnginePort(ctx));
    }

    private final Runnable probeRunnable = new Runnable() {
        @Override public void run() {
            if (!isRunning) return;
            probeCount++;
            final boolean scanSessions = probeCount % 3 == 0;
            // 探测放后台线程：HttpURLConnection / /proc 扫描在主线程会卡界面
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean up = engineAlive(enginePort);
                    if (up && scanSessions) {
                        activeSessions = scanActiveSessions();
                        lastProbeAt = System.currentTimeMillis();
                    }
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (!isRunning) return;
                            engineUp = up;
                            updateEngineStatusUi();
                            // v1.21（需求 2）：空闲超过 IDLE_FISH_MS → 摸鱼中…（只在没有活跃状态时补）
                            // ⚠ 例外：正在等用户回答（"正在向用户提问..."）时不能改成摸鱼 —— 那不是摸鱼，是在等你。
                            if (up && System.currentTimeMillis() - lastAgentStatusAt > IDLE_FISH_MS) {
                                String cur = (statusBubble != null && statusBubble.getText() != null)
                                        ? statusBubble.getText().toString() : "";
                                if (!cur.startsWith("正在向用户提问")) applyBubble("摸鱼中…", true, 0L);
                            }
                        }
                    });
                }
            }, "overlay-probe").start();
            handler.postDelayed(this, PROBE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // v1.21：正在「完全退出」→ 立刻自停。本服务是 START_STICKY，进程被杀后系统会重建它
        // （悬浮窗又冒出来）；退出期间必须挡住，否则用户会觉得"没关干净"。
        if (MainActivity.shutdownPending(this)) { stopSelf(); return; }
        isRunning = true;
        instance = this;
        // v1.21：空闲计时从这里起算（服务刚起来不该立刻显示"摸鱼中…"）
        lastAgentStatusAt = System.currentTimeMillis();
        enginePort = enginePort(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundCompat();
        buildOverlay();
        addToWindow();
        // 前后台联动：App 前台时隐藏悬浮窗（不挡界面），退后台时显示
        foregroundWantsHidden = MainActivity.overlayForeground;   // v1.13.11：改为记状态再统一应用
        applyVisibleNow();
        handler.postDelayed(probeRunnable, 200);
        // v1.21：服务被重启过 → 把最近一次气泡状态补回来（终态"任务已完成/会话已结束"尤其重要）
        try {
            if (lastText != null && !lastText.isEmpty()
                    && System.currentTimeMillis() - lastStatusAt < 10 * 60 * 1000L) {
                final String t = lastText;
                final boolean st = lastSticky;
                handler.postDelayed(new Runnable() {
                    @Override public void run() { applyBubble(t, st, 0L); }
                }, 400);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏「显示小鲸鱼」：解除用户隐藏并抖一下示意
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) {
            userHidden = false;
            applyVisibleNow();
            wiggle();
        }
        // 允许通过 intent 指定端口（如换端口后重启）
        if (intent != null && intent.hasExtra("port")) {
            enginePort = intent.getIntExtra("port", enginePort);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_PORT, enginePort).apply();
        }
        return START_STICKY;
    }

    // v1.18（B12）：action 名随变体，避免同一设备上两个变体的悬浮窗互相唤起/干扰
    private static final String ACTION_SHOW = BuildVariant.APP_ID + ".overlay.SHOW";

    @Override
    public void onDestroy() {
        isRunning = false;
        if (instance == this) instance = null;
        stopVscreenPreview();
        handler.removeCallbacksAndMessages(null);
        if (rootView != null && wm != null) {
            try { wm.removeView(rootView); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** 前台服务保活（引擎运行期间悬浮窗不被系统回收） */
    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "黑鲸鱼悬浮窗",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("黑鲸鱼悬浮窗运行中（引擎状态指示）");
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotification());
    }

    /** 常驻通知（内容随引擎状态更新；常驻「显示小鲸鱼」动作，拖底隐藏后靠它找回）。 */
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(this, OverlayService.class);
        show.setAction(ACTION_SHOW);
        PendingIntent showPi = PendingIntent.getService(this, 1, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { b.addAction(new Notification.Action.Builder(null, "显示小鲸鱼", showPi).build()); }
        catch (Throwable ignored) {
            // 老系统 Action.Builder(null, ...) 不吃图标时退化：不显示动作也不影响主流程
        }
        return b.setContentTitle("🐋 DeepSeek Harness 运行中")
                .setContentText("引擎状态：" + (engineUp ? "运行中（端口 " + enginePort + "）" : "未运行"))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void buildOverlay() {
        // ===== 根布局（竖排：图标行 + 状态面板）=====
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setPadding(dp(10), dp(8), dp(10), dp(8));
        // 收起态无背景（只留小鲸鱼图标）；背景移到展开面板 panelView 上

        // ===== 图标行（小鲸鱼）=====
        LinearLayout iconRow = new LinearLayout(this);
        iconRowView = iconRow;
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(dp(4), dp(2), dp(4), dp(2));

        iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.overlay_avatar); // 悬浮窗头像：icon-src/source.png（全身立绘）
                                                              // 由 tools/gen-icons.py 生成到 res/drawable-nodpi/
        iconView.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        iconRow.addView(iconView);

        // ===== v1.21 需求 2：agent 状态气泡（贴在小人上方；透明底、尽量小，默认隐藏）=====
        statusBubble = new TextView(this);
        statusBubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        statusBubble.setTextColor(0xFFEAF0FF);
        statusBubble.setSingleLine(true);
        statusBubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusBubble.setMaxWidth(dp(132));
        statusBubble.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(0xB31F2733);                 // 半透明深色：尽量不挡视线
        bbg.setCornerRadius(dp(8));
        bbg.setStroke(dp(1), 0x55FFFFFF);
        statusBubble.setBackground(bbg);
        // v1.21（重构）：气泡**不再**和共用同一个窗口 —— 它有自己的窗口（bubbleWindowView），
        // 位置由 layoutCompanions() 从"小人的右边界"算出来。
        // 这样：气泡出现/消失只影响自己那个窗口，永远挤不动小人；
        // 气泡右边界固定在小人右边界、宽度自适应 ⇒ 只向**左**增长。
        statusBubble.setVisibility(View.VISIBLE);   // 自身可见性由窗口整体控制，这里保持可见
        statusBubble.setMaxWidth(dp(240));          // 有自己的窗口后可以放宽，长文案更完整
        statusBubble.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        // 点一下收起（尤其"任务已完成/会话已结束"这种常驻终态）
        statusBubble.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideBubble(); }
        });
        // v1.21：气泡自己（重新）布局完成后，按它的真实尺寸把小窗口摆到"右端齐着小人"的位置。
        // 只重排位置、不改尺寸 ⇒ 不会触发循环。
        statusBubble.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(android.view.View v, int l, int t, int r, int b,
                                                 int ol, int ot, int orr, int ob) {
                if (r - l != orr - ol || b - t != ob - ot || l != ol || t != ot) layoutCompanions();
            }
        });
        bubbleWindowView = new android.widget.FrameLayout(this);
        bubbleWindowView.addView(statusBubble);
        bubbleWindowView.setVisibility(View.GONE);

        // 小人：只装它自己（这个窗口就是"小人窗口"，位置由 lp.x/lp.y 固定，气泡/面板都不影响它）
        rootView.addView(iconRow);
        rootView.setPadding(0, 0, 0, 0);

        // ===== 状态面板（紧凑版，默认隐藏）=====
        // v1.21 修复"展开面板后文字一字一行（竖排）"：
        // 根因是窗口为 WRAP_CONTENT，ViewRootImpl 用**当前窗口宽度**当测量约束 ——
        // 收起态只有小人 + 状态气泡（≈60~130dp），展开时面板在这一轮测量里被压到约一个字宽，
        // 文字就竖排了。给面板一个**显式宽度**后，测量结果不再取决于"当时窗口有多宽"。
        panelView = new LinearLayout(this);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setPadding(dp(10), dp(8), dp(10), dp(8));
        panelView.setLayoutParams(new LinearLayout.LayoutParams(
                dp(200), LinearLayout.LayoutParams.WRAP_CONTENT));   // 200dp：常态三枚按钮（打开应用/控制台/退出）放得下
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(getColor(R.color.panel_bg));              // 深蓝半透明（统一配色资源）
        pbg.setCornerRadius(dp(12));
        panelView.setBackground(pbg);

        // 第一行：引擎状态
        //   ● 引擎运行中… / ○ 引擎未运行
        // v1.21 调整（用户反馈"AI：…"读着别扭）：不再把两件事挤在一行并加"AI："前缀，
        // 改成两行直白的话 —— 引擎一行、会话数一行（见下面 sessionText）。
        statusText = new TextView(this);
        statusText.setText("○ 引擎状态检测中…");
        statusText.setTextColor(getColor(R.color.panel_text_bright));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        statusText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panelView.addView(statusText);

        // 第二行：会话数（N 个会话运行中… / 暂无会话运行 / 会话已完成 ✓）
        sessionText = new TextView(this);
        sessionText.setText("—");
        sessionText.setTextColor(getColor(R.color.panel_text_bright));
        sessionText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        sessionText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panelView.addView(sessionText);

        // 第三行：agent 任务状态（与头像上方气泡同源；气泡被点掉后这里仍看得到）
        agentText = new TextView(this);
        agentText.setText("任务：—");
        agentText.setTextColor(getColor(R.color.panel_text_dim));
        agentText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        agentText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panelView.addView(agentText);

        // v1.9 虚拟屏预览（默认隐藏）：悬浮窗实时显示虚拟屏画面
        vscreenImageView = new ImageView(this);
        LinearLayout.LayoutParams vsp = new LinearLayout.LayoutParams(dp(176), dp(298));
        vsp.topMargin = dp(6);
        vscreenImageView.setLayoutParams(vsp);
        vscreenImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vscreenImageView.setVisibility(View.GONE);
        vscreenImageView.setBackgroundColor(0x88000000);
        panelView.addView(vscreenImageView);

        // v1.21 精简（用户选 A+B）：
        //   A. 去掉「收起」按钮 —— 点面板外、或再点一下小人就能收起，按钮没必要占一行；
        //      三个按钮并成**一行**（常态下只有「打开应用」，不会拥挤）。
        //   B.「虚拟屏／销毁屏」只在**真的有虚拟屏**时才出现（refreshPanelDynamicRows 按
        //      VsreenBridgeService.sVscreenRunning 切换），避免出现"点了没反应"的按钮。
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brp.topMargin = dp(7);
        btnRow.setLayoutParams(brp);
        btnRow.addView(pillButton("打开", new Runnable() { @Override public void run() {
            Intent i = new Intent(OverlayService.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }}));
        // v1.21：控制台入口。v1.21 起冷启动默认进主界面、控制台只留了"常驻通知"和"故障回退"
        // 两条路（见 autoEnterOnBoot / EXTRA_OPEN_CONSOLE），面板上补一条随时可用的路
        // —— 复用通知那套 extra，走的是同一个 showConsole() 分支，不新增第二套逻辑。
        // 引擎没起来也能进控制台（控制台是原生层，本来就用来处理引擎异常）。
        btnRow.addView(pillButton("控制台", new Runnable() { @Override public void run() {
            Intent i = new Intent(OverlayService.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(MainActivity.EXTRA_OPEN_CONSOLE, true);
            try { startActivity(i); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }}));
        vscreenBtn = pillButton("虚拟屏", new Runnable() { @Override public void run() {
            // 预览窗被「收起到小鲸鱼」后的回程入口；没有虚拟屏时按钮本身就不可见
            try { VsreenBridgeService.showPreviewFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }});
        // 销毁屏：替代旧预览窗上的 ✕（用户要求销毁功能收进小鲸鱼面板）
        destroyBtn = pillButton("销毁屏", new Runnable() { @Override public void run() {
            try { VsreenBridgeService.destroyVscreenFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }});

        // v1.21：**完全退出**按钮。
        // 防误触：两步确认 —— 第一次点只进入"待确认"（按钮变红、文案改「确认退出」、
        //   飘一句提示），4 秒内不点第二次自动取消；确认后才真正执行。
        exitBtn = pillButton("退出", new Runnable() { @Override public void run() { onExitButtonTap(); } });
        btnRow.addView(exitBtn);

        // v1.21：虚拟屏那两枚按钮单独一行，整行只在真的有虚拟屏时出现。
        // 原因：常态行已经有「打开应用 / 控制台 / 退出」三枚，再塞两枚会挤到看不清；
        // 而虚拟屏场景本来就少见，多一行可接受（也是"A 并成一行"的例外，用户已知）。
        btnRowVs = new LinearLayout(this);
        btnRowVs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams vrp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vrp.topMargin = dp(5);
        btnRowVs.setLayoutParams(vrp);
        btnRowVs.addView(vscreenBtn);
        btnRowVs.addView(destroyBtn);
        btnRowVs.setVisibility(View.GONE);

        panelView.addView(btnRow);
        panelView.addView(btnRowVs);
        // v1.21（重构）：面板也有自己的窗口，位置由 layoutCompanions() 从小人下方算出。
        panelWindowView = new android.widget.FrameLayout(this);
        panelWindowView.addView(panelView);
        panelWindowView.setVisibility(View.GONE);
        setPanelVisible(false, false);

        // ===== 拖动 + 点击 + 拖底隐藏 =====
        rootView.setOnTouchListener(new View.OnTouchListener() {
            private long downAt = 0;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downAt = System.currentTimeMillis();
                        touchX = ev.getRawX(); touchY = ev.getRawY();
                        startX = lp.x; startY = lp.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(ev.getRawX() - touchX) > dp(8) || Math.abs(ev.getRawY() - touchY) > dp(8)) {
                            dragging = true;
                        }
                        if (dragging) {
                            lp.x = (int) (startX + (ev.getRawX() - touchX));
                            lp.y = (int) (startY + (ev.getRawY() - touchY));
                            try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                            updateDismissHint(ev.getRawY());
                    layoutCompanions();   // v1.21（重构）：拖动时气泡/面板跟随
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging && dismissHint) {
                            hideByDragToBottom();
                        } else if (dragging) {
                            snapToEdge();      // 拖完自动吸到最近的左右边缘（静置态=半藏）
                            if (panelVisible) setPanelVisible(true, false);
                        } else if (System.currentTimeMillis() - downAt < 400) {
                            setPanelVisible(!panelVisible, true);
                        }
                        setDismissHintInternal(false);
                        return true;
                    case MotionEvent.ACTION_OUTSIDE:
                        // 点击悬浮窗外区域：收回面板（回到半藏态）
                        if (panelVisible) setPanelVisible(false, true);
                        return true;
                }
                return false;
            }
        });
    }

    /** 面板里的小胶囊按钮（v1.13.12 重做：小、轻、不抢眼）。 */
    private Button pillButton(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        b.setTextColor(getColor(R.color.accent_brand));
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(11));
        b.setBackground(bg);
        b.setSingleLine(true);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setHeight(dp(25));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(25), 1f);
        lp2.leftMargin = dp(3);
        lp2.rightMargin = dp(3);
        b.setLayoutParams(lp2);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try { action.run(); } catch (Throwable ignored) {}
        }});
        return b;
    }

    // ==================== 可见性 / 贴边 / 动画 ====================

    /** 悬浮窗整体可见性（App 前台隐藏、退后台显示；服务常驻只切视图）。 */
    public static void setOverlayVisible(boolean show) {
        OverlayService s = instance;
        if (s != null) { s.foregroundWantsHidden = !show; s.applyVisibleNow(); }
    }

    /**
     * v1.13.11：虚拟屏预览「收起到小鲸鱼」。
     * v1.13.12 语义收窄：pin 只代表"预览帧继续在小鲸鱼面板里拉"，**不再强制可见** ——
     * 之前它会盖过前台隐藏，用户在 App 里也会看到小鲸鱼（报过"偶尔在 dsh 中也显示小鲸鱼"）。
     * @param pin true=开始拉虚拟屏画面到面板；false=停止
     */
    public static void pinForVscreen(boolean pin) {
        OverlayService s = instance;
        if (s != null) s.applyVscreenPin(pin);
    }

    private void applyVscreenPin(boolean pin) {
        try {
            vscreenPinned = pin;
            if (pin) startVscreenPreview();
            else stopVscreenPreview();
        } catch (Throwable ignored) {}
    }

    /**
     * 实际可见性。v1.13.12 起规则只有两条：
     * ① App 在前台（foregroundWantsHidden）→ 隐藏，无论虚拟屏是否钉住；
     * ② 用户拖底主动隐藏（userHidden）→ 隐藏，直到通知栏「显示小鲸鱼」。
     */
    private void applyVisibleNow() {
        try {
            if (rootView != null) {
                boolean show = !foregroundWantsHidden && !userHidden;
                rootView.setVisibility(show ? View.VISIBLE : View.GONE); layoutCompanions();   // v1.21（重构）：小人隐藏时气泡/面板一起收
            }
        } catch (Throwable ignored) {}
    }

    private void addToWindow() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        // FLAG_LAYOUT_NO_LIMITS：允许窗口越出屏幕边界 —— 静置"半藏"就靠它
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(160);
        try {
            wm.addView(rootView, lp);
            // v1.21（重构）：气泡与面板各自一个窗口，位置由 layoutCompanions() 从小人算出。
            // 三个窗口互不参与对方的测量 ⇒ 谁出现/消失都不会挤动别人。
            try {
                lpBubble = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        // 气泡只用来"看"和"点掉"，不需要焦点，也不抢触摸模态
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lpBubble.gravity = Gravity.TOP | Gravity.START;
                wm.addView(bubbleWindowView, lpBubble);
                lpPanel = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT);
                lpPanel.gravity = Gravity.TOP | Gravity.START;
                wm.addView(panelWindowView, lpPanel);
            } catch (Throwable ignored) {}
            // 只做一次"布局落定后摆正"。
            // ⚠ 不要在这里挂长期的 OnLayoutChangeListener：拖动窗口、以及"半藏"时
            // 系统重测宽度都会触发 layoutChange，长期监听会把 x 每帧拽回贴边位 ——
            // 表现就是"小鲸鱼横向拖不动、半藏也站不住"。而这里要修的只是"面板
            // 展开/收起瞬间 getWidth() 还是旧值"，一次性摆正就够。
            settleAfterLayout();
            // v1.21（用户报障修复）：**只对"停靠右侧"**挂一个右缘钉住监听。
            // 为什么必须挂长期监听：真机实测窗口宽度会随气泡出现/消失而变化（WRAP_CONTENT +
            // 系统按当前窗口宽度测量的鸡生蛋问题）。只设一次 lp.x 的话，宽度一变右缘就跟着动 ——
            // 用户看到的就是"有消息时小人被往左挤，消息消失后停在半空"。
            // 钉住右缘后：气泡出现/消失只改变窗口左边界，小人始终贴着屏幕右缘；面板展开时
            // 窗口变宽也是向左长，小人跟着面板右边界一起动。
            // ⚠ 停靠左侧时**直接 return**，因此原来"半藏/可拖动"的行为完全不受影响。
            installRightEdgePin();
        } catch (Throwable t) {
            stopSelf();
        }
    }

    /** 停靠右侧时把窗口右缘钉在屏幕上（见 buildOverlay 里的说明）。 */
    private void installRightEdgePin() {
        try {
            if (rootView == null) return;
            rootView.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(android.view.View v, int l, int t, int r, int b,
                                                     int ol, int ot, int or, int ob) {
                    try {
                        if (!snappedRight || dragging || lp == null || rootView == null) return;
                        int w = rootView.getWidth();
                        if (w <= 0) return;
                        int screenW = getResources().getDisplayMetrics().widthPixels;
                        int want;
                        if (panelVisible) {
                            want = Math.max(dp(4), screenW - w - dp(4));
                        } else {
                            int avatarW = (iconView != null && iconView.getWidth() > 0)
                                    ? iconView.getWidth() : dp(40);
                            int off = Math.max(0,
                                    Math.round(avatarW * (1f - TUCK_VISIBLE_FRACTION))
                                    - Math.round(screenW * TUCK_INSET_SHIFT_FRACTION));
                            want = screenW + off - w;
                        }
                        if (lp.x != want) {
                            lp.x = want;
                            wm.updateViewLayout(rootView, lp);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * v1.13.12：贴边 = 面板收起时把小鲸鱼**半藏**到屏幕边缘（露出约一半），
     * 面板展开时完整贴边（否则面板会被截掉）。
     */
    private void snapToEdge() {
        try {
            if (lp == null || rootView == null) return;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            int h = rootView.getHeight() > 0 ? rootView.getHeight() : dp(56);
            snappedRight = (lp.x + w / 2) > getResources().getDisplayMetrics().widthPixels / 2;
            lp.x = edgeXFor(w);
            if (lp.y < 0) lp.y = 0;
            if (lp.y > screenH - h) lp.y = Math.max(0, screenH - h);
            wm.updateViewLayout(rootView, lp);
            layoutCompanions();     // v1.21（重构）：气泡/面板跟随小人
            // v1.21 诊断：把停靠方向、窗口几何、小人实际位置、气泡可见性打出来。
            // 真机/模拟器上"小人在半空""气泡被截短"这类问题只能靠这几个数定位（不能再靠猜）。
            try {
                android.view.View av = iconView;
                int aLeft = -1, aRight = -1;
                if (av != null) {
                    int[] loc = new int[2];
                    av.getLocationOnScreen(loc);
                    aLeft = loc[0];
                    aRight = loc[0] + av.getWidth();
                }
                android.util.Log.i("DSHOverlay", "dock=" + (snappedRight ? "R" : "L")
                        + " panel=" + panelVisible
                        + " screenW=" + getResources().getDisplayMetrics().widthPixels
                        + " winX=" + lp.x + " winW=" + w
                        + " winRight=" + (lp.x + w)
                        + " avatar=[" + aLeft + "," + aRight + "]"
                        + " bubbleVis=" + (statusBubble != null ? statusBubble.getVisibility() : -1)
                        + " slotW=" + (bubbleSlotView != null ? bubbleSlotView.getWidth() : -1)
                        // v1.21 诊断（定位"小人没靠右"）：根布局 gravity / 小人行 gravity 与其实际区间
                        + " rootG=" + (rootView != null ? rootView.getGravity() : -99)
                        + " rowG=" + (iconRowView != null && iconRowView.getLayoutParams() instanceof LinearLayout.LayoutParams
                                ? ((LinearLayout.LayoutParams) iconRowView.getLayoutParams()).gravity : -99)
                        + " rowRect=[" + (iconRowView != null ? iconRowView.getLeft() : -1)
                        + "," + (iconRowView != null ? iconRowView.getRight() : -1) + "]"
                        + " slotRect=[" + (bubbleSlotView != null ? bubbleSlotView.getLeft() : -1)
                        + "," + (bubbleSlotView != null ? bubbleSlotView.getRight() : -1) + "]");
                // v1.21：同时刷新几何快照，供 /overlay?action=geom 查询 ——
                // 以后这类"位置不对"的问题可以用数字验证，不必依赖截图（悬浮窗在 App 前台会隐藏）。
                updateGeomSnapshot();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /**
     * v1.21（用户报障修复）：按停靠方向**镜像**内容对齐。
     *
     * 问题：气泡槽位固定 136dp，而小人只有 40dp —— 停靠**右侧**时窗口有约 100dp 落在屏幕外，
     * 气泡若仍按左对齐、从左往右伸展，右侧部分直接被屏幕边缘截掉
     *（用户报障："小人靠屏幕右侧时消息气泡显示的长度太短"）。
     *
     * 做法：停靠右侧时把 rootView 及其内容整体改成右对齐 —— 气泡槽位贴窗口右侧、
     * 气泡在槽位内右对齐，于是它从小人右侧**向左**伸展，长度恢复正常；左侧停靠保持原样。
     */
    private void applyDockAlignment() {
        try {
            int g = snappedRight ? Gravity.END : Gravity.START;
            if (rootView != null) rootView.setGravity(g);
            // v1.21（真机实测）：**逐个子视图显式设 layout_gravity**。
            // 只设 rootView.setGravity() 时，图标行并没有跟着靠右（用户截图：气泡到了右上角、
            // 小人仍在中间），所以这里对每一行都说清楚；左侧停靠时这些值就是默认的 START/4dp 左边距，
            // 与改动前**完全一致**（不动左侧行为）。
            if (bubbleSlotView != null) {
                android.view.ViewGroup.LayoutParams raw = bubbleSlotView.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) raw;
                    slp.gravity = g;
                    slp.leftMargin = snappedRight ? 0 : dp(4);
                    slp.rightMargin = snappedRight ? dp(4) : 0;
                    bubbleSlotView.setLayoutParams(slp);
                }
            }
            if (iconRowView != null) {
                iconRowView.setPadding(dp(4), dp(2), dp(4), dp(2));
            }
            // v1.21（确定性对齐）：靠右停靠时，气泡与面板都用**右外边距**对齐到"小人的右边界"。
            // 因为窗口比内容宽/窄都无所谓 —— 只要右缘位置 = 窗口右缘 − (窗口宽 − 小人右边界)，
            // 三者（小人、气泡、面板）的右边界就完全一致，且都不依赖 gravity 的分配空间。
            int rightGap = 0;
            if (rootView != null && rootView.getWidth() > 0) {
                rightGap = Math.max(0, rootView.getWidth() - avatarRightInWindow());
            }
            if (statusBubble != null) {
                android.view.ViewGroup.LayoutParams bp = statusBubble.getLayoutParams();
                if (bp instanceof android.widget.FrameLayout.LayoutParams) {
                    android.widget.FrameLayout.LayoutParams blp =
                            (android.widget.FrameLayout.LayoutParams) bp;
                    blp.gravity = (snappedRight ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
                    blp.leftMargin = snappedRight ? 0 : dp(4);
                    blp.rightMargin = snappedRight ? rightGap : 0;
                    statusBubble.setLayoutParams(blp);
                }
            }
            if (panelView != null) {
                android.view.ViewGroup.LayoutParams pp = panelView.getLayoutParams();
                if (pp instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) pp;
                    plp.gravity = snappedRight ? Gravity.END : Gravity.START;
                    plp.rightMargin = snappedRight ? rightGap : 0;
                    panelView.setLayoutParams(plp);
                }
            }
            if (panelView != null) {
                android.view.ViewGroup.LayoutParams raw = panelView.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) raw;
                    plp.gravity = g;                       // 面板与小人同一侧 ⇒ 右对齐
                    panelView.setLayoutParams(plp);
                }
            }
            if (statusBubble != null) {
                android.view.ViewGroup.LayoutParams bp = statusBubble.getLayoutParams();
                if (bp instanceof android.widget.FrameLayout.LayoutParams) {
                    android.widget.FrameLayout.LayoutParams blp =
                            (android.widget.FrameLayout.LayoutParams) bp;
                    blp.gravity = g | Gravity.CENTER_VERTICAL;
                    blp.leftMargin = snappedRight ? 0 : dp(4);
                    blp.rightMargin = snappedRight ? dp(4) : 0;
                    statusBubble.setLayoutParams(blp);
                }
            }
            if (rootView != null) rootView.requestLayout();
        } catch (Throwable ignored) {}
    }

    /**
     * 贴边坐标：snappedRight 决定靠哪边；tucked 决定是否半藏。
     * 面板收起（静置）→ 半藏：x 让窗口越出屏幕 (1-露出的比例)；展开 → 完整可见 + 留 4dp 边距。
     *
     * v1.21（用户报障修复）：半藏偏移必须按**小人图标宽度**算，不能按窗口宽度 ——
     * 气泡槽位（固定 136dp）让窗口远宽于小人；按窗口宽算会让偏移放大数倍，小人几乎被推出屏幕
     *（用户报障：静止态只露出一小条；点击/有气泡时才完整）。窗口右侧多出的是透明槽位，不该参与。
     * 同时用运行时量到的"小人相对窗口的左偏移"（根布局 padding + 图标行 padding），不写死数值。
     */
    private int edgeXFor(int viewWidth) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        if (!panelVisible) {
            int avatarW = (iconView != null && iconView.getWidth() > 0) ? iconView.getWidth() : dp(40);
            int inset = avatarInsetInWindow();
            int off = Math.round(avatarW * (1f - TUCK_VISIBLE_FRACTION));
            // v1.21：半藏之后再朝屏幕内侧收一点（用户要求"静止小人整体右移 5% 左右"，
            // 并确认最终这个位置"刚刚好" —— 所以这里保持"从半藏偏移里扣掉 shift、不小于 0"的算法，
            // 别改成叠加平移，否则会再往内挪一段）。
            int shift = Math.round(screenW * TUCK_INSET_SHIFT_FRACTION);
            off = Math.max(0, off - shift);
            // v1.21（用户报障修复·二）：停靠右侧不能再用 "screenW + off - (inset + avatarW)"。
            // 那个公式只保证**小人**的右缘落在屏幕边缘 —— 而窗口比小人大得多（气泡槽位 136dp），
            // 窗口右缘会跑到屏幕外 ~220px；气泡画在窗口里，于是无论怎么对齐都会被屏幕边缘截短
            //（用户复现两次："右侧气泡还是短的"）。
            // 现在内容已右对齐（applyDockAlignment），窗口右缘 = 小人右缘，所以直接把**窗口右缘**
            // 对准 screenW+off：小人和气泡就都贴在这一侧，气泡整条都在屏幕内。
            // v1.21（确定性方案）：按**小人自身的几何**定位窗口 ——
            // 让"小人右边界"落在屏幕右缘（+半藏偏移 off）。这个公式不依赖任何 gravity 语义。
            if (snappedRight) return screenW + off - avatarRightInWindow();
            return -(inset + off);
        }
        return snappedRight ? Math.max(dp(4), screenW - viewWidth - dp(4)) : dp(4);
    }

    /** 小人图标相对 rootView 的左偏移（累加各级 getLeft()）；未布局时按 10dp+4dp 兜底。 */
    private int avatarInsetInWindow() {
        try {
            int sum = 0;
            View v = iconView;
            while (v != null && v != rootView) {
                sum += v.getLeft();
                android.view.ViewParent p = v.getParent();
                if (!(p instanceof View)) break;
                v = (View) p;
            }
            return sum > 0 ? sum : dp(14);
        } catch (Throwable t) {
            return dp(14);
        }
    }

    /** v1.21：小人右边界相对 rootView 左边缘的距离（用于把窗口定位成"小人贴屏幕右缘"）。 */
    private int avatarRightInWindow() {
        int inset = avatarInsetInWindow();
        int w = (iconView != null && iconView.getWidth() > 0) ? iconView.getWidth() : dp(40);
        return inset + w;
    }

    /** 面板显示/隐藏；animate=true 时带旋转抖动 + 位置过渡（唤出、收起共用）。 */
    private void setPanelVisible(boolean show, boolean animate) {
        panelVisible = show;
        if (panelView != null) panelView.setVisibility(show ? View.VISIBLE : View.GONE); layoutCompanions();   // v1.21（重构）：面板窗口显隐/定位
        if (show) refreshPanelDynamicRows();
        if (lp == null) return;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
        if (!animate) {
            snapToEdge();
        } else {
            wiggle();
        }
        // 展开/收起必然改变窗口尺寸，而 getWidth() 在下一次 layout 之前仍是旧值：
        // 用它算贴边坐标 → 展开时按"图标宽度"摆（面板被推出右边）、收起时按"面板宽度"
        // 算半藏位（整只鲸鱼被推出屏幕）。又因为 FLAG_LAYOUT_NO_LIMITS 系统不夹边界，
        // 算错就真的出屏。原实现用 rootView.post() 补救，但 post 只延后一条消息、
        // 常常仍在下一次 layout 之前，等于没夹。改为等布局真正落定后再算。
        settleAfterLayout();
    }

    /**
     * 布局真正落定后，按**真实尺寸**重算贴边位置。
     * 这是"小鲸鱼/面板跑出屏幕"的根因修法 —— 不能再依赖调用时刻的 getWidth()。
     */
    private void settleAfterLayout() {
        try {
            if (rootView == null) return;
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        android.view.ViewTreeObserver vto = rootView.getViewTreeObserver();
                        if (vto.isAlive()) vto.removeOnGlobalLayoutListener(this);
                    } catch (Throwable ignored) {}
                    applyEdgePos(true);
                }
            });
        } catch (Throwable ignored) {
            applyEdgePos(false);
        }
    }

    /**
     * 按真实尺寸把窗口摆到正确的贴边位：
     * 面板展开 → 完整可见（留 4dp 边距）；面板收起 → 半藏（露出 TUCK_VISIBLE_FRACTION）。
     * @param animate 是否平滑过渡（布局刚落定时用 true，观感更顺）
     */
    private void applyEdgePos(boolean animate) {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            int h = rootView.getHeight() > 0 ? rootView.getHeight() : dp(56);
            int targetX = edgeXFor(w);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            if (lp.y < 0) lp.y = 0;
            if (lp.y > screenH - h) lp.y = Math.max(0, screenH - h);
            if (!animate || lp.x == targetX) {
                lp.x = targetX;
                wm.updateViewLayout(rootView, lp);
                return;
            }
            final int fromX = lp.x;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(fromX, targetX);
            va.setDuration(220);
            va.setInterpolator(new OvershootInterpolator(0.6f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (lp == null || rootView == null) return;
                    lp.x = (Integer) a.getAnimatedValue();
                    try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 面板每次展开时刷新"看场景才该出现"的行（如销毁屏按钮）。 */
    private void refreshPanelDynamicRows() {        try {
            // v1.21（B）：虚拟屏相关的两个按钮只在**真的有虚拟屏**时出现。
            // 常态下（没开虚拟屏）面板里只有「打开应用」，不会出现点了没反应的按钮。
            boolean vs = VsreenBridgeService.sVscreenRunning;
            if (destroyBtn != null) destroyBtn.setVisibility(vs ? View.VISIBLE : View.GONE);
            if (vscreenBtn != null) vscreenBtn.setVisibility(vs ? View.VISIBLE : View.GONE);
            // v1.21：虚拟屏那两枚按钮所在的行整体显隐（常态下不占位置）
            if (btnRowVs != null) btnRowVs.setVisibility(vs ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {}
    }

    // ==================== v1.21：「完全退出」按钮 ====================

    /**
     * 「退出」按钮点击：**两步确认**，防误触。
     *
     * 第一步：按钮变红、文案改「确认退出」，并飘一句提示说明会发生什么（此时什么都没做）；
     * 4 秒内没有第二次点击 → 自动回到「退出」；
     * 第二步（4 秒内再点）：真正执行 —— 停引擎 → 停服务 → 清通知 → 结束进程。
     *
     * 之所以不用系统 AlertDialog：悬浮窗是 TYPE_APPLICATION_OVERLAY 窗口，
     * 在其上弹对话框体验差（会被其他覆盖层挡住/焦点行为不一致），两步确认更稳。
     */
    private void onExitButtonTap() {
        if (!exitArmed) {
            // 第一次点「退出」：按钮变红 = 确认退出，并把小人头顶消息换成「下班啦」。
            // v1.21（用户规格）：**气泡与红按钮共用一个 TTL**（与其它气泡消息时长一致，
            // 即 BUBBLE_TTL_MS）；TTL 到 → 气泡自动消失、按钮变回普通「退出」。
            setExitArmed(true);
            applyBubble("下班啦", false, 0L);          // 非 sticky ⇒ 走 BUBBLE_TTL_MS 自动收起
            handler.removeCallbacks(exitDisarm);
            handler.postDelayed(exitDisarm, BUBBLE_TTL_MS);
            try {
                android.widget.Toast.makeText(getApplicationContext(),
                        "再点一次「确认退出」将彻底关闭：停引擎、关悬浮窗、结束进程",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
            return;
        }
        // 点红色「确认退出」：真正退出。
        // v1.21（用户规格）：这里**不**把按钮变回普通态、气泡也**不**消失 ——
        // 气泡改成常驻（sticky，取消 TTL），一直留到 App 整体关闭。
        handler.removeCallbacks(exitDisarm);
        applyBubble("下班啦", true, 0L);
        try {
            android.widget.Toast.makeText(getApplicationContext(),
                    "正在退出…", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
        MainActivity.shutdownEverything(this);
    }

    /** 切换「退出」按钮的待确认外观（红底白字 = 危险动作已上膛）。 */
    private void setExitArmed(boolean armed) {
        exitArmed = armed;
        if (exitBtn == null) return;
        try {
            exitBtn.setText(armed ? "确认退出" : "退出");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(armed ? 0xFFE5484D : 0xFFFFFFFF);
            bg.setCornerRadius(dp(11));
            exitBtn.setBackground(bg);
            exitBtn.setTextColor(armed ? 0xFFFFFFFF : getColor(R.color.accent_brand));
        } catch (Throwable ignored) {}
    }

    /** 位置过渡：从当前 x 平滑滑到目标贴边位（半藏/完整随面板状态）。 */    private void animateToEdge() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth() > 0 ? rootView.getWidth() : dp(60);
            final int targetX = edgeXFor(w);
            final int fromX = lp.x;
            if (fromX == targetX) return;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofInt(fromX, targetX);
            va.setDuration(260);
            va.setInterpolator(new OvershootInterpolator(0.6f));
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (lp == null || rootView == null) return;
                    lp.x = (Integer) a.getAnimatedValue();
                    try { wm.updateViewLayout(rootView, lp); } catch (Throwable ignored) {}
                }
            });
            va.start();
        } catch (Throwable ignored) {}
    }

    /** 旋转抖动动画（唤出/收起时的"小鲸鱼摆尾巴"）。只转图标，不转整块面板。 */
    private void wiggle() {
        try {
            if (iconView == null) return;
            android.animation.ObjectAnimator.ofFloat(
                    iconView, View.ROTATION, 0f, -14f, 11f, -8f, 5f, 0f)
                    .setDuration(420)
                    .start();
        } catch (Throwable ignored) {}
    }

    /** 拖动中：接近屏幕底部时给出"松手即隐藏"的暗示（图标缩小变淡）。 */
    private void updateDismissHint(float rawY) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        boolean inZone = rawY > screenH - dp(DISMISS_ZONE_DP);
        if (inZone != dismissHint) setDismissHintInternal(inZone);
    }

    private void setDismissHintInternal(boolean on) {
        dismissHint = on;
        try {
            if (iconView == null) return;
            iconView.animate().scaleX(on ? 0.62f : 1f).scaleY(on ? 0.62f : 1f)
                    .alpha(on ? 0.7f : 1f).setDuration(140).start();
        } catch (Throwable ignored) {}
    }

    /** 拖到底部松手：隐藏小鲸鱼（通知栏「显示小鲸鱼」可恢复）。 */
    private void hideByDragToBottom() {
        userHidden = true;
        try {
            rootView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180)
                    .withEndAction(new Runnable() { @Override public void run() {
                        try {
                            rootView.setAlpha(1f);
                            setDismissHintInternal(false);
                            applyVisibleNow();
                        } catch (Throwable ignored) {}
                    }}).start();
        } catch (Throwable ignored) {
            applyVisibleNow();
        }
        try {
            android.widget.Toast.makeText(getApplicationContext(),
                    "小鲸鱼已隐藏，可从通知栏「显示小鲸鱼」恢复", android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private void clampPanelOnScreen() {
        try {
            if (lp == null || rootView == null) return;
            int w = rootView.getWidth();
            if (w <= 0) return;
            if (!panelVisible) {
                lp.x = edgeXFor(w);
            } else {
                int maxX = getResources().getDisplayMetrics().widthPixels - w - dp(4);
                if (lp.x > maxX) lp.x = Math.max(dp(4), maxX);
            }
            wm.updateViewLayout(rootView, lp);
        } catch (Throwable ignored) {}
    }

    // ==================== 引擎探测 / AI 会话状态 ====================

    /** 探测引擎是否在跑。
     *  v1.13：0.1.5 起首页需要一次性 token —— 不带 token 返回 401 + 纯文本
     *  “dsh web authentication required…”，带有效 token 返回 303 跳转；两种都说明“引擎在跑”。 */
    private boolean engineAlive(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-overlay-probe");
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 303 || code == 302) return true;
            if (code == 401) return bodyContains(c, "dsh web authentication required");
            if (code < 200 || code >= 500) return false;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int r;
            while ((r = in.read(chunk)) > 0 && total < 262144) {
                body.write(chunk, 0, r);
                total += r;
            }
            try { in.close(); } catch (Throwable ignored) {}
            return body.toString("UTF-8").contains("<title>DeepSeek Harness</title>");
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 读一小段响应正文（401 的正文在错误流里）。 */
    private boolean bodyContains(HttpURLConnection c, String needle) {
        try {
            InputStream in = null;
            try { in = c.getInputStream(); } catch (Throwable t) { in = c.getErrorStream(); }
            if (in == null) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int r;
            while ((r = in.read(buf)) > 0 && out.size() < 8192) out.write(buf, 0, r);
            try { in.close(); } catch (Throwable ignored) {}
            return out.toString("UTF-8").contains(needle);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 扫出"正在被引擎写入"的会话集合（session 目录路径）。
     *
     * 机制依据：dsh-session-persistence-jsonl 的 SessionWriteLease 在会话写入器存活期间
     * 持有 <会话目录>/session.lock 的独占锁，释放 = 关闭 fd。所以
     * 「同 uid 进程的 /proc/<pid>/fd 里存在指向 session.lock 的 fd」⟺ 该会话正在工作。
     * node 是本 App 的子进程（同 uid），/proc 对同 uid 可读 —— findEnginePid 已验证过这条路。
     *
     * 旧实现走 POST /api/session.list：0.1.5 起要认证 cookie，悬浮窗拿不到（永远 401），
     * 表现就是"AI：空闲"永远不变。此扫描不需要任何认证。
     *
     * /proc 完全扫不动（被 SELinux 拦等极端情况）返回 null，调用方保持上次结果。
     */
    private HashSet<String> scanActiveSessions() {
        HashSet<String> out = new HashSet<String>();
        try {
            File[] procs = new File("/proc").listFiles();
            if (procs == null) return null;
            for (File d : procs) {
                String name = d.getName();
                if (name == null || name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
                File fdDir = new File(d, "fd");
                String[] fds;
                try { fds = fdDir.list(); } catch (Throwable t) { fds = null; }
                if (fds == null) continue;   // 别的 uid 的进程：无权读，跳过
                for (String fd : fds) {
                    String target;
                    try { target = new File(fdDir, fd).getCanonicalPath(); } catch (Throwable t) { continue; }
                    int i = target.indexOf("/dshhome/sessions/");
                    if (i < 0) continue;
                    if (!target.endsWith("/session.lock")) continue;
                    // 会话目录 = session.lock 所在目录（…/sessions/<cwd编码>/<session-id>）
                    out.add(target.substring(0, target.lastIndexOf('/')));
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== v1.21（需求 2）：agent 状态气泡 ====================

    /**
     * 更新气泡内容（必须主线程）。
     *
     * @param text  状态文案；空串/ null = 收起
     * @param sticky 是否常驻（内核侧对"任务已完成/会话已结束"会置 true；用户点一下才收）
     * @param ttlMs 瞬时状态的停留时长（0 → 默认 8 秒）
     */
    private void applyBubble(String text, boolean sticky, long ttlMs) {
        if (statusBubble == null) return;
        if (text == null || text.isEmpty()) { hideBubble(); return; }
        // 面板里的"任务"行与气泡同源：气泡被点掉/超时收起后，展开面板仍能看到最近状态。
        if (agentText != null) agentText.setText("任务：" + text);
        // v1.21（ANR 修正）：文案与当前显示完全相同时**不重绘**。
        // 内核侧已按事件节流，这里再兜一道：悬浮窗每次 setText/显隐都会让窗口重排重绘，
        // 模拟器软件渲染下高频重绘会把主线程卡在出帧（实测 ANR：nSyncAndDrawFrame）。
        boolean same = bubbleShowing && text.contentEquals(statusBubble.getText());
        lastAgentStatusAt = System.currentTimeMillis();
        if (same) return;
        statusBubble.setText(text);
        bubbleShowing = true;
        handler.removeCallbacks(bubbleHide);
        // v1.21（重构）：气泡只影响**自己那个窗口**的位置（在小人上方、右边界对齐小人右边界）。
        // 先按"旧尺寸"摆一次，等它测量完再摆一次（尺寸变了位置才准）——不影响小人。
        layoutCompanions();
        if (bubbleWindowView != null) {
            bubbleWindowView.post(new Runnable() { @Override public void run() { layoutCompanions(); } });
        }
        final boolean idle = text.startsWith("摸鱼");
        final boolean terminal = text.startsWith("任务已完成") || text.startsWith("会话已结束");
        // 终态与空闲态常驻（用户点一下收起）；瞬时状态（思考中/调用工具）TTL 后自动收
        if (!sticky && !terminal && !idle) {
            handler.postDelayed(bubbleHide, ttlMs > 0 ? ttlMs : BUBBLE_TTL_MS);
        }
    }

    private void hideBubble() {
        handler.removeCallbacks(bubbleHide);
        // v1.21（重构）：气泡是独立窗口，直接把自己的窗口收起来 —— 不再需要"占位保留"这类技巧。
        bubbleShowing = false;
        layoutCompanions();
    }

    /**
     * 同进程静态入口：MainActivity 的本地服务收到 `action=bubble` 后调用这里。
     * （内核侧插件 → HTTP → App 本地服务 → 气泡。）
     *
     * v1.21：把最近一次状态缓存在静态字段里 —— 悬浮窗服务被系统重启后，
     * onCreate 会用它把"任务已完成/会话已结束"这类终态补回来，
     * 否则用户回到桌面会发现气泡莫名消失（实测踩过：后台期间服务重启）。
     */
    public static void pushStatus(final String text, final boolean sticky, final long ttlMs) {
        lastText = text;
        lastSticky = sticky;
        lastStatusAt = System.currentTimeMillis();
        final OverlayService s = instance;
        if (s == null) return;   // 悬浮窗没在跑：静默忽略（用户没开悬浮窗就不打扰）
        s.handler.post(new Runnable() {
            @Override public void run() { s.applyBubble(text, sticky, ttlMs); }
        });
    }

    /** 更新悬浮窗状态文字 + 常驻通知（在主线程调用）。 */
    private void updateEngineStatusUi() {
        if (statusText != null) {
            // v1.21：引擎一行、会话数一行（原先把两件事挤在一行并加 "AI：" 前缀，用户觉得别扭）
            statusText.setText(engineUp ? "● 引擎运行中…" : "○ 引擎未运行");
        }
        if (sessionText != null) {
            sessionText.setText(sessionStatusText());
        }
        // 面板开着的话顺带刷新销毁屏按钮的可见性
        if (panelVisible) refreshPanelDynamicRows();
        // 更新常驻通知
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    /**
     * AI 状态行：空闲 / 工作中会话（可多个）/ 已完成。
     *  - 有持锁会话 → "工作中 N 个会话"；
     *  - 上次还工作中、现在没了 → 记一个"刚完成"时间点，60 秒内显示"已完成"；
     *  - 其余 → "空闲"。引擎不在跑时显示"—"。
     */
    private String sessionStatusText() {
        if (!engineUp) return "—";
        HashSet<String> cur = activeSessions;
        if (cur == null) return "—";       // 还没扫过 / /proc 扫不了
        int n = cur.size();
        long now = System.currentTimeMillis();
        if (n > 0) {
            lastSessionsHadWork = true;   // 边沿触发源：从"有会话工作"变"没有"时报已完成
            finishedAt = 0L;
            return n + " 个会话运行中…";
        }
        if (finishedAt == 0L && lastSessionsHadWork) {
            finishedAt = now;
        }
        if (finishedAt > 0L && now - finishedAt < FINISHED_TTL_MS) {
            return "会话已完成 ✓";
        }
        lastSessionsHadWork = false;
        return "暂无会话运行";
    }
    /** 上次扫描是否看到过工作中的会话（用于"已完成"的边沿触发）。 */
    private volatile boolean lastSessionsHadWork = false;

    // ==================== v1.9 虚拟屏预览（悬浮窗实时看 AI 操作虚拟屏） ====================

    /** 开始虚拟屏预览：每 ~1s 拉 server /preview（base64 JPEG）并显示到悬浮窗。 */
    public void startVscreenPreview() {
        if (vscreenPreviewRunning) return;
        vscreenPreviewRunning = true;
        vscreenHandler.post(vscreenPreviewRunnable);
    }

    /** 停止虚拟屏预览。 */
    public void stopVscreenPreview() {
        vscreenPreviewRunning = false;
        vscreenHandler.removeCallbacks(vscreenPreviewRunnable);
        if (vscreenImageView != null) {
            vscreenHandler.post(new Runnable() {
                @Override public void run() {
                    vscreenImageView.setVisibility(View.GONE);
                }
            });
        }
    }

    /** 预览帧拉取任务：HTTP GET 127.0.0.1:<变体桥端口>/vscreen/preview → base64 JPEG → ImageView。 */
    private final Runnable vscreenPreviewRunnable = new Runnable() {
        @Override public void run() {
            if (!vscreenPreviewRunning || !isRunning) return;
            try {
                // v1.18（B12）：桥端口随变体（official 8999 / lite 9009 / compat 9019 / community 9029）
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + BuildVariant.VS_BRIDGE_PORT + "/vscreen/preview").openConnection();
                c.setConnectTimeout(2000); c.setReadTimeout(2000);
                String resp = readAll(c.getInputStream());
                c.disconnect();
                JSONObject o = new JSONObject(resp);
                if (o.optBoolean("ok", false)) {
                    String b64 = o.optString("previewB64");
                    if (b64 != null && !b64.isEmpty()) {
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bmp != null) {
                            vscreenHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (vscreenImageView != null) {
                                        vscreenImageView.setImageBitmap(bmp);
                                        vscreenImageView.setVisibility(View.VISIBLE);
                                    }
                                }
                            });
                        }
                    }
                }
            } catch (Throwable ignored) {
                // server 未跑（虚拟屏未创建）时静默，预览保持隐藏
            }
            vscreenHandler.postDelayed(this, 1000);
        }
    };

    private String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toString("UTF-8");
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
