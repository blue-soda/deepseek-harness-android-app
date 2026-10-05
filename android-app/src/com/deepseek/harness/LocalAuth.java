package com.deepseek.harness;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地 HTTP 服务的调用方鉴权（v1.18.0）。
 *
 * 为什么需要：App 内有 4 个自实现 HTTP 服务都绑 127.0.0.1（无障碍 3181/3183/3185、
 * 通知 3081/3083/3085、虚拟屏桥 8999、特权核心 8998），而 loopback 对**设备上所有应用**
 * 开放，端口又由包名派生、可枚举 —— 没有令牌时本机任意应用都能读屏、截屏、注入手势、
 * 改系统设置、读剪贴板，甚至以 shell 身份拉起核心。
 *
 * 令牌来源与 /shell 完全同源：dsh_prefs 的 local_token（App 私有、跨重启不变），
 * 由 MainActivity.spawnNode 通过环境变量 APP_LOCAL_TOKEN 交给引擎，插件再放进请求头。
 */
public final class LocalAuth {

    /** 请求头名（插件侧同名常量：process.env.APP_LOCAL_TOKEN 放进这个头）。 */
    public static final String HEADER = "X-DSH-Token";

    private static final String PREFS = "dsh_prefs";
    private static final String KEY = "local_token";

    private LocalAuth() {
    }

    /** 令牌：与 MainActivity.localToken() 同一个 prefs 键（缺失即生成）。 */
    public static String token(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String t = p.getString(KEY, "");
            if (t == null || t.length() < 16) {
                byte[] b = new byte[16];
                new java.security.SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < b.length; i++) {
                    sb.append(String.format("%02x", b[i]));
                }
                t = sb.toString();
                p.edit().putString(KEY, t).apply();
            }
            return t;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从请求头原文里取令牌（大小写不敏感）。 */
    private static String headerToken(String rawHead) {
        if (rawHead == null) {
            return "";
        }
        String[] lines = rawHead.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            if (line.substring(0, c).trim().equalsIgnoreCase(HEADER)) {
                return line.substring(c + 1).trim();
            }
        }
        return "";
    }

    /** 从 path 的 query 里取 token=（便于手工 curl 调试；插件一律走请求头）。 */
    private static String queryToken(String path) {
        if (path == null) {
            return "";
        }
        int q = path.indexOf('?');
        if (q < 0) {
            return "";
        }
        String[] pairs = path.substring(q + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            String kv = pairs[i];
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals("token")) {
                return kv.substring(eq + 1);
            }
        }
        return "";
    }

    /** 令牌校验：请求头优先，query 兜底；expected 为空一律拒绝（fail-closed）。 */
    public static boolean ok(String expected, String rawHead, String path) {
        if (expected == null || expected.length() < 16) {
            return false;
        }
        String t = headerToken(rawHead);
        if (t.isEmpty()) {
            t = queryToken(path);
        }
        return expected.equals(t);
    }

    /** 统一拒绝响应体（协议不变：HTTP 200 + body 里 ok=false）。 */
    public static String denied() {
        return "{\"ok\":false,\"error\":\"本地服务鉴权失败：缺少或错误的 X-DSH-Token（该接口仅限本应用引擎调用）\"}";
    }
}
