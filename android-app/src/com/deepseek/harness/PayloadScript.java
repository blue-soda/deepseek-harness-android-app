package com.deepseek.harness;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 跑「随包脚本」的公共通道：assets 里的 .mjs 先落到 {@code <filesDir>/tools/}，
 * 再交给 payload 自带的 node 执行，stdout 按 JSON 返回给调用方。
 *
 * <p>为什么要有这个类：会话自愈（{@link SessionHeal}）与会话管理（{@link SessionAdmin}）
 * 都要跑随包脚本，逻辑一模一样 —— 与其复制两份，不如抽成一条通道。
 *
 * <p>两个实现要点（都是踩出来的）：
 * <ul>
 *   <li>assets 里的东西**不是真实文件路径**，node 打不开 → 必须先复制到私有目录；</li>
 *   <li>{@code LD_LIBRARY_PATH} 必须指向 {@code payload/runtime/lib}，
 *       否则 node 找不到 libz.so.1 之类，报 {@code CANNOT LINK EXECUTABLE}。</li>
 * </ul>
 */
public final class PayloadScript {

    private static final String TAG = "DSHPayloadScript";

    private PayloadScript() {}

    /**
     * 把随包脚本落到私有目录，返回可执行的文件路径。
     * 每次覆盖写（脚本很小），避免"改了脚本但设备上还是旧版"这类静默失败。
     */
    public static File ensure(Context ctx, String script) throws IOException {
        File dir = new File(ctx.getFilesDir(), "tools");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录：" + dir.getAbsolutePath());
        File out = new File(dir, script);
        InputStream in = null;
        OutputStream os = null;
        try {
            in = ctx.getAssets().open(script);
            os = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (os != null) os.close(); } catch (Throwable ignored) {}
        }
        if (out.length() <= 0) throw new IOException("随包脚本复制为空：" + script);
        return out;
    }

    /**
     * 跑一次随包脚本并返回它的 stdout（脚本一律以 {@code --json} 输出，所以这里就是一段 JSON）。
     *
     * @param script    脚本名（assets 里叫这个，落到 tools/ 也叫这个）
     * @param args      传给脚本的参数（不含脚本名本身）
     * @param timeoutMs 超时；超时会 destroy 进程并抛异常
     */
    public static String run(Context ctx, File payload, String script, String[] args, long timeoutMs) throws Exception {
        File node = new File(payload, "runtime/bin/node");
        if (!node.isFile()) throw new IOException("payload 里没有 node：" + node.getAbsolutePath());
        if (!node.canExecute()) node.setExecutable(true, false);
        File scriptFile = ensure(ctx, script);
        File lib = new File(payload, "runtime/lib");
        File tmp = new File(ctx.getCacheDir(), "tmp");
        if (!tmp.isDirectory()) tmp.mkdirs();

        List<String> cmd = new ArrayList<String>();
        cmd.add(node.getAbsolutePath());
        cmd.add(scriptFile.getAbsolutePath());
        for (int i = 0; i < args.length; i++) cmd.add(args[i]);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        File osslConf = new File(payload, "runtime/etc/openssl.cnf");
        if (osslConf.exists()) env.put("OPENSSL_CONF", osslConf.getAbsolutePath());
        pb.directory(tmp);
        pb.redirectErrorStream(false);

        Process p = pb.start();
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        final InputStream es = p.getErrorStream();
        Thread errThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(es, "UTF-8"));
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (stderr.length() < 8000) stderr.append(l).append('\n');
                    }
                } catch (Throwable ignored) {}
            }
        }, "dsh-payload-script-stderr");
        errThread.setDaemon(true);
        errThread.start();

        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            if (stdout.length() < 4 * 1024 * 1024) stdout.append(line).append('\n');
        }
        boolean finished = waitFor(p, timeoutMs);
        if (!finished) {
            p.destroy();
            throw new IOException("脚本超时（" + (timeoutMs / 1000) + " 秒）：" + script);
        }
        int code = p.exitValue();
        if (stdout.length() == 0) {
            throw new IOException("脚本没有输出（退出码 " + code + "）"
                    + (stderr.length() > 0 ? "：" + tail(stderr.toString(), 400) : ""));
        }
        if (code == 2) throw new IOException("脚本参数错误（退出码 2）：" + tail(stdout.toString(), 300));
        return stdout.toString();
    }

    private static boolean waitFor(Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException notYet) {
                try { Thread.sleep(120L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= n ? t : t.substring(t.length() - n);
    }

    /** 出错时把 stderr 记一笔（排查用；不抛给用户）。 */
    public static void logWarn(String what, Throwable t) {
        Log.w(TAG, what, t);
    }
}
