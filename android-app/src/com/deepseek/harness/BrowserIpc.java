package com.deepseek.harness;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 主进程侧的 IPC 客户端（v1.19.6 · A2 第二件）：把 `/browser` 的每一次 op 转给
 * `:browser` 进程里的 {@link BrowserService}。
 *
 * 纪律：
 *  · **一次调用一条 Message**，回信走 replyTo；调用的编排在调用方线程（本地服务线程），
 *    绝不在主线程等（主线程要负责 onServiceConnected 的投递）。
 *  · 子进程崩了/被系统杀了 → `onBindingDied`/`onServiceDisconnected` 会把连接清掉，
 *    下一次调用自动重连；**主进程不受影响**（这正是搬进程的目的）。
 *  · 浏览器关干净后（结果里 closed=true）主动 `release()` 松绑 —— 不松绑就一直拖着子进程，
 *    也就回收不了（"可整套丢弃"就落空了）。
 */
public class BrowserIpc {
    public static final String TAG = "DSHBrowserIpc";

    /** 建连额外给的余量（bind + onServiceConnected 通常几毫秒，冷启动进程可能上百毫秒）。 */
    private static final int CONNECT_SLACK_MS = 6000;

    private final Context ctx;
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<String>();
    private final Object lock = new Object();

    private HandlerThread rxThread;
    private Messenger rx;
    private Messenger remote;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                remote = new Messenger(binder);
                lock.notifyAll();
            }
            Log.i(TAG, "已连上 :browser 进程");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                remote = null;
                bound = false;
                lock.notifyAll();
            }
            Log.w(TAG, "与 :browser 断开（子进程没了？下次调用会自动重连）");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "绑定已失效（子进程崩溃）→ 解绑，下次调用重连");
            synchronized (lock) {
                remote = null;
                bound = false;
                lock.notifyAll();
            }
            try { ctx.unbindService(this); } catch (Throwable ignored) {}
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "onNullBinding（服务没给出 binder）");
            synchronized (lock) {
                remote = null;
                bound = false;
                lock.notifyAll();
            }
        }
    };

    public BrowserIpc(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /** 主进程侧唯一入口：op 名 + args + 超时，返回子进程回的 JSON 原文。 */
    public String op(String op, JSONObject args, int timeoutMs) {
        ensureRx();
        long deadline = SystemClock.uptimeMillis() + Math.max(2000, timeoutMs) + CONNECT_SLACK_MS;
        Messenger m = connect(deadline);
        if (m == null) {
            return err("browser-ipc-unavailable",
                    "浏览器进程没起来（缺「悬浮窗」权限，或被系统限制后台进程）");
        }
        inbox.clear();
        Message msg = Message.obtain(null, BrowserService.MSG_OP);
        Bundle b = new Bundle();
        b.putString(BrowserService.KEY_OP, op);
        b.putString(BrowserService.KEY_ARGS, args == null ? "{}" : args.toString());
        b.putInt(BrowserService.KEY_TIMEOUT, timeoutMs);
        msg.setData(b);
        msg.replyTo = rx;
        try {
            m.send(msg);
        } catch (Throwable t) {
            Log.w(TAG, "发送失败（子进程可能刚死）", t);
            release();
            return err("browser-ipc-dead", "浏览器进程已退出（连接已重置，重试即可）：" + t.getMessage());
        }
        long left = deadline - SystemClock.uptimeMillis();
        String res = null;
        try {
            res = inbox.poll(Math.max(1000, left), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (res == null) {
            release();
            return err("browser-ipc-timeout", "浏览器进程没有回话（超时 " + timeoutMs + "ms）");
        }
        try {
            JSONObject j = new JSONObject(res);
            if (j.optBoolean("closed", false)) release();   // 浏览器已关 → 松绑，让子进程可被回收
        } catch (Throwable ignored) {}
        return res;
    }

    /** 松绑（MainActivity.onDestroy 与"浏览器已关"时调用）。 */
    public void release() {
        synchronized (lock) {
            remote = null;
            if (bound) {
                try { ctx.unbindService(conn); } catch (Throwable ignored) {}
                bound = false;
            }
            lock.notifyAll();
        }
        inbox.clear();
    }

    // ==================== 内部 ====================

    private void ensureRx() {
        if (rx != null) return;
        rxThread = new HandlerThread("browser-ipc-rx");
        rxThread.start();
        rx = new Messenger(new Handler(rxThread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                String r = msg.getData() == null ? null : msg.getData().getString(BrowserService.KEY_RESULT);
                inbox.offer(r == null ? "" : r);
                return true;
            }
        }));
    }

    /** 连上子进程（必要时 bind）。返回 null = 连不上。 */
    private Messenger connect(long deadline) {
        synchronized (lock) {
            if (remote != null) return remote;
            if (!bound) {
                try {
                    bound = ctx.bindService(new Intent(ctx, BrowserService.class), conn,
                            Context.BIND_AUTO_CREATE);
                } catch (Throwable t) {
                    Log.w(TAG, "bindService 抛错", t);
                    bound = false;
                }
                Log.i(TAG, "bindService → " + bound);
                if (!bound) return null;
            }
            long left = deadline - SystemClock.uptimeMillis();
            while (remote == null && left > 0) {
                try {
                    lock.wait(Math.min(200L, Math.max(1L, left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                left = deadline - SystemClock.uptimeMillis();
            }
            return remote;
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
