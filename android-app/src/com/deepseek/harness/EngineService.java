package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台保活服务：引擎（node 服务器）运行期间常驻通知栏，
 * 让系统把本应用标记为高优先级进程，挂后台/锁屏不被杀掉，
 * AI 后台任务（对话、工具调用）可持续执行。
 *
 * 生命周期：
 *   - startEngine() 时由 MainActivity 拉起（startForegroundService / startService）
 *   - 用户主动「退出」时由 MainActivity 停止（stopService）
 *   - 按 Home 挂后台不停止（这正是保活的目的）
 */
public class EngineService extends Service {
    private static final String CHANNEL_ID = "dsh_engine";
    private static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        // v1.21：正在「完全退出」→ 立刻自停。否则 START_STICKY 会在进程被杀后重建本服务，
        // 常驻通知又冒出来，用户会觉得"没关干净"。
        if (MainActivity.shutdownPending(this)) { stopSelf(); return; }
        createChannel();
        startForeground(NOTIF_ID, buildNotification("DeepSeek Harness 正在运行", "AI 引擎保活中，后台任务持续执行"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // v1.21：完全退出期间被系统重建 → 不自启动（返回 NOT_STICKY，避免反复重建）
        if (MainActivity.shutdownPending(this)) { stopSelf(); return START_NOT_STICKY; }
        // 每次收到启动/重启意图都刷新通知（系统杀进程后 START_STICKY 重建也会走到这里）
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification("DeepSeek Harness 正在运行", "AI 引擎保活中，后台任务持续执行"));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    private Notification buildNotification(String title, String text) {
        // v1.21（Q1 第 3 批）：常驻通知成为**控制台的唯一人工入口**。
        //   · 点通知 → 进控制台（带 EXTRA_OPEN_CONSOLE）
        //   · 通知上的「打开界面」动作 → 直接进主界面（不带 extra）
        // 之所以要这两个：v1.21 的「秒进」不再把控制台作为冷启动默认页，正常路径直接进主界面，
        // 所以必须给用户留一条随时能进控制台的路（另一条是引擎/WebView 故障时的自动回退）。
        Intent consoleIntent = new Intent(this, MainActivity.class);
        consoleIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        consoleIntent.putExtra(MainActivity.EXTRA_OPEN_CONSOLE, true);
        PendingIntent consolePi = PendingIntent.getActivity(this, 0, consoleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent uiIntent = new Intent(this, MainActivity.class);
        uiIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 注意 requestCode 必须与上面不同，否则两个 PendingIntent 会互相覆盖
        PendingIntent uiPi = PendingIntent.getActivity(this, 1, uiIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(consolePi)
                .setOngoing(true)   // 常驻不可滑动删除
                .setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 20) {
            b.addAction(new Notification.Action.Builder(null, "打开界面", uiPi).build());
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "引擎保活",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("DeepSeek Harness 引擎运行状态");
            nm.createNotificationChannel(ch);
        }
    }
}
