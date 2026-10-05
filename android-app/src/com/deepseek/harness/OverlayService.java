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
    /** v1.21：面板里的"任务状态"行 —— 与头像上方气泡同源（气泡被点掉后仍能在这里看）。 */
    private TextView agentText;
    private Button destroyBtn;
    /** v1.21（B）：虚拟屏回程按钮，仅在虚拟屏真的在跑时可见。 */
    private Button vscreenBtn;
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
        @Override public void run() { if (statusBubble != null) statusBubble.setVisibility(View.GONE); }
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
                            if (up && System.currentTimeMillis() - lastAgentStatusAt > IDLE_FISH_MS) {
                                applyBubble("摸鱼中…", true, 0L);
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
        statusBubble.setVisibility(View.GONE);
        LinearLayout.LayoutParams bubLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bubLp.gravity = Gravity.CENTER_HORIZONTAL;
        bubLp.bottomMargin = dp(2);
        statusBubble.setLayoutParams(bubLp);
        // 点一下收起（尤其"任务已完成/会话已结束"这种常驻终态）
        statusBubble.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideBubble(); }
        });
        rootView.addView(statusBubble);           // 先加 = 在小人上方
        rootView.addView(iconRow);

        // ===== 状态面板（紧凑版，默认隐藏）=====
        // v1.21 修复"展开面板后文字一字一行（竖排）"：
        // 根因是窗口为 WRAP_CONTENT，ViewRootImpl 用**当前窗口宽度**当测量约束 ——
        // 收起态只有小人 + 状态气泡（≈60~130dp），展开时面板在这一轮测量里被压到约一个字宽，
        // 文字就竖排了。给面板一个**显式宽度**后，测量结果不再取决于"当时窗口有多宽"。
        panelView = new LinearLayout(this);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setPadding(dp(10), dp(8), dp(10), dp(8));
        panelView.setLayoutParams(new LinearLayout.LayoutParams(
                dp(178), LinearLayout.LayoutParams.WRAP_CONTENT));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(getColor(R.color.panel_bg));              // 深蓝半透明（统一配色资源）
        pbg.setCornerRadius(dp(12));
        panelView.setBackground(pbg);

        // 第一行：引擎 + AI 会话状态合并成一行（省一行高度，信息更集中）
        //   ● 引擎运行中 · AI：空闲 / AI：1 个会话工作中… / AI：会话已完成 ✓
        statusText = new TextView(this);
        statusText.setText("○ 状态：检测中…");
        statusText.setTextColor(getColor(R.color.panel_text_bright));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_caption));
        statusText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        panelView.addView(statusText);

        // 第二行：agent 任务状态（与头像上方气泡同源；气泡被点掉后这里仍看得到）
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
        btnRow.addView(pillButton("打开应用", new Runnable() { @Override public void run() {
            Intent i = new Intent(OverlayService.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }}));
        vscreenBtn = pillButton("虚拟屏", new Runnable() { @Override public void run() {
            // 预览窗被「收起到小鲸鱼」后的回程入口；没有虚拟屏时按钮本身就不可见
            try { VsreenBridgeService.showPreviewFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }});
        vscreenBtn.setVisibility(View.GONE);
        btnRow.addView(vscreenBtn);
        // 销毁屏：替代旧预览窗上的 ✕（用户要求销毁功能收进小鲸鱼面板）
        destroyBtn = pillButton("销毁屏", new Runnable() { @Override public void run() {
            try { VsreenBridgeService.destroyVscreenFromWhale(); } catch (Throwable ignored) {}
            setPanelVisible(false, true);
        }});
        destroyBtn.setVisibility(View.GONE);
        btnRow.addView(destroyBtn);

        // v1.21：**完全退出**按钮。
        // 与「打开应用」并排放（常态下 vscreen 两枚按钮隐藏，所以一行放得下）。
        // 防误触：两步确认 —— 第一次点只进入"待确认"（按钮变红、文案改「确认退出」、
        //   飘一句提示），4 秒内不点第二次自动取消；确认后才真正执行。
        exitBtn = pillButton("退出", new Runnable() { @Override public void run() { onExitButtonTap(); } });
        btnRow.addView(exitBtn);

        panelView.addView(btnRow);
        rootView.addView(panelView);
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
                rootView.setVisibility(show ? View.VISIBLE : View.GONE);
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
            // 只做一次"布局落定后摆正"。
            // ⚠ 不要在这里挂长期的 OnLayoutChangeListener：拖动窗口、以及"半藏"时
            // 系统重测宽度都会触发 layoutChange，长期监听会把 x 每帧拽回贴边位 ——
            // 表现就是"小鲸鱼横向拖不动、半藏也站不住"。而这里要修的只是"面板
            // 展开/收起瞬间 getWidth() 还是旧值"，一次性摆正就够。
            settleAfterLayout();
        } catch (Throwable t) {
            stopSelf();
        }
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
        } catch (Throwable ignored) {}
    }

    /**
     * 贴边坐标：snappedRight 决定靠哪边；tucked 决定是否半藏。
     * 面板收起（静置）→ 半藏：x 让窗口越出屏幕 (1-露出的比例)；展开 → 完整可见 + 留 4dp 边距。
     */
    private int edgeXFor(int viewWidth) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        boolean tucked = !panelVisible;
        if (tucked) {
            int off = Math.round(viewWidth * (1f - TUCK_VISIBLE_FRACTION));
            return snappedRight ? screenW - viewWidth + off : -off;
        }
        return snappedRight ? Math.max(dp(4), screenW - viewWidth - dp(4)) : dp(4);
    }

    /** 面板显示/隐藏；animate=true 时带旋转抖动 + 位置过渡（唤出、收起共用）。 */
    private void setPanelVisible(boolean show, boolean animate) {
        panelVisible = show;
        if (panelView != null) panelView.setVisibility(show ? View.VISIBLE : View.GONE);
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
            setExitArmed(true);
            try {
                android.widget.Toast.makeText(getApplicationContext(),
                        "再点一次「确认退出」将彻底关闭：停引擎、关悬浮窗、结束进程",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
            handler.removeCallbacks(exitDisarm);
            handler.postDelayed(exitDisarm, 4000);
            return;
        }
        handler.removeCallbacks(exitDisarm);
        setExitArmed(false);
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
        boolean same = text.contentEquals(statusBubble.getText()) && statusBubble.getVisibility() == View.VISIBLE;
        lastAgentStatusAt = System.currentTimeMillis();
        if (same) return;
        statusBubble.setText(text);
        statusBubble.setVisibility(View.VISIBLE);
        handler.removeCallbacks(bubbleHide);
        final boolean idle = text.startsWith("摸鱼");
        final boolean terminal = text.startsWith("任务已完成") || text.startsWith("会话已结束");
        // 终态与空闲态常驻（用户点一下收起）；瞬时状态（思考中/调用工具）TTL 后自动收
        if (!sticky && !terminal && !idle) {
            handler.postDelayed(bubbleHide, ttlMs > 0 ? ttlMs : BUBBLE_TTL_MS);
        }
    }

    private void hideBubble() {
        handler.removeCallbacks(bubbleHide);
        if (statusBubble != null) statusBubble.setVisibility(View.GONE);
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
            // v1.21：引擎状态 + AI 会话状态合并成一行（原来两行，省一行高度）
            statusText.setText((engineUp ? "● 引擎运行中" : "○ 引擎未运行") + " · " + aiStatusText());
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
    private String aiStatusText() {
        if (!engineUp) return "AI：—";
        HashSet<String> cur = activeSessions;
        if (cur == null) return "AI：—";       // 还没扫过 / /proc 扫不了
        int n = cur.size();
        long now = System.currentTimeMillis();
        if (n > 0) {
            lastSessionsHadWork = true;   // 边沿触发源：从"有会话工作"变"没有"时报已完成
            finishedAt = 0L;
            return n == 1 ? "AI：1 个会话工作中…" : "AI：" + n + " 个会话工作中…";
        }
        if (finishedAt == 0L && lastSessionsHadWork) {
            finishedAt = now;
        }
        if (finishedAt > 0L && now - finishedAt < FINISHED_TTL_MS) {
            return "AI：会话已完成 ✓";
        }
        lastSessionsHadWork = false;
        return "AI：空闲";
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
