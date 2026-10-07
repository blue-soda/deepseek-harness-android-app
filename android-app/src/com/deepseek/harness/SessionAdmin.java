package com.deepseek.harness;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话管理（列出 / 删除到回收站 / 恢复 / 彻底删除）。
 *
 * <p>为什么要有：用户 2026-10-04 明确说「这个软件不能删会话，删不了会话，这是个问题，
 * 加一个页面专门删会话」—— App 里原来确实**没有任何删除会话的入口**，话题只能一直堆着。
 *
 * <p>安全边界（与 {@code session-admin.mjs} 一致，两边都写死，谁都不许绕）：
 * <ul>
 *   <li>「删除」= **移到** {@code <dshHome>/sessions-deleted/}（回收站），**不是 rm** —— 随时能恢复；</li>
 *   <li>回收站在 {@code sessions/} **之外**（内核扫不到，不会被当成会话加载）；</li>
 *   <li>只动 {@code sessions/} 与回收站这两棵树；越界请求在 Java 侧与脚本侧**各拦一次**。</li>
 * </ul>
 */
public final class SessionAdmin {

    public static final String SCRIPT = "session-admin.mjs";
    private static final String TAG = "DSHSessionAdmin";

    private SessionAdmin() {}

    // ─────────────────────────── 数据模型 ───────────────────────────

    /** 一条会话。 */
    public static final class SessionInfo {
        public String id = "";
        public String dirName = "";
        public String slug = "";
        public String dir = "";
        public String file = "";
        public String title = "";
        public long bytes;
        public long mtime;
        public int lines;
        public int badFrames;

        public String displayTitle() {
            String t = title == null ? "" : title.trim();
            if (t.length() > 0) return t;
            return "（无标题）会话 " + (id.length() > 12 ? id.substring(0, 12) + "…" : id);
        }

        public String sizeText() { return human(bytes); }

        public String timeText() {
            if (mtime <= 0) return "";
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(mtime));
        }
    }

    /** 回收站里的一条。 */
    public static final class TrashInfo {
        public String entry = "";
        public String dir = "";
        public String title = "";
        public String slug = "";
        public String removedAt = "";
        public long bytes;
        public long mtime;

        public String displayTitle() {
            String t = title == null ? "" : title.trim();
            if (t.length() > 0) return t;
            return "（无标题）" + entry;
        }

        public String sizeText() { return human(bytes); }

        public String timeText() {
            if (mtime <= 0) return "";
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(mtime));
        }
    }

    /** 会话列表结果。 */
    public static final class ListResult {
        public boolean ok;
        public String error = "";
        public String sessionsDir = "";
        public String trashDir = "";
        public int count;
        public long totalBytes;
        public final List<SessionInfo> sessions = new ArrayList<SessionInfo>();

        public String headline() {
            if (!ok) return "读取失败：" + error;
            return "共 " + count + " 条会话 · " + human(totalBytes);
        }
    }

    /** 回收站列表结果。 */
    public static final class TrashResult {
        public boolean ok;
        public String error = "";
        public String trashDir = "";
        public int count;
        public long totalBytes;
        public final List<TrashInfo> entries = new ArrayList<TrashInfo>();

        public String headline() {
            if (!ok) return "读取失败：" + error;
            if (count == 0) return "回收站是空的";
            return "回收站 " + count + " 条 · " + human(totalBytes);
        }
    }

    /** 单个操作（删除/恢复/彻底删）的结果。 */
    public static final class OpResult {
        public boolean ok;
        public String error = "";
        public String mode = "";
        public String moved = "";
        public String restoredTo = "";
        public String slug = "";
        public String dirName = "";
        public long bytes;
        public int entries;

        public String headline() {
            if (!ok) return "失败：" + error;
            if ("trash".equals(mode)) return "已移到回收站（可恢复）";
            if ("restore".equals(mode)) return "已恢复回原位";
            if ("purge".equals(mode)) return "已彻底删除（" + human(bytes) + "）";
            if ("purge-all".equals(mode)) return "已清空回收站（" + entries + " 条 · " + human(bytes) + "）";
            return "完成";
        }
    }

    static String human(long n) {
        if (n >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        if (n >= 1024L) return (n / 1024L) + " KB";
        return n + " B";
    }

    // ─────────────────────────── 路径 ───────────────────────────

    /** 会话根：<dshHome>/sessions。 */
    public static File sessionsDir(File dshHome) { return new File(dshHome, "sessions"); }

    /** 回收站根：<dshHome>/sessions-deleted（在 sessions 之外）。 */
    public static File trashRoot(File dshHome) { return new File(dshHome, "sessions-deleted"); }

    // ─────────────────────────── 操作 ───────────────────────────

    /** 列出全部会话（按时间倒序，脚本侧已排序）。 */
    public static ListResult list(Context ctx, File payload, File dshHome) {
        ListResult r = new ListResult();
        File sd = sessionsDir(dshHome);
        r.sessionsDir = sd.getAbsolutePath();
        r.trashDir = trashRoot(dshHome).getAbsolutePath();
        if (!sd.isDirectory()) {
            r.ok = false;
            r.error = "找不到会话目录：" + sd.getAbsolutePath();
            return r;
        }
        try {
            String out = PayloadScript.run(ctx, payload, SCRIPT, new String[]{
                    "list", "--sessions", sd.getAbsolutePath(), "--json"
            }, 180000L);
            JSONObject o = new JSONObject(out);
            r.ok = o.optBoolean("ok", false);
            if (!r.ok) { r.error = o.optString("error", "未知错误"); return r; }
            r.count = o.optInt("count", 0);
            r.totalBytes = o.optLong("totalBytes", 0);
            JSONArray arr = o.optJSONArray("sessions");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.getJSONObject(i);
                    SessionInfo si = new SessionInfo();
                    si.id = s.optString("id", "");
                    si.dirName = s.optString("dirName", "");
                    si.slug = s.optString("slug", "");
                    si.dir = s.optString("dir", "");
                    si.file = s.optString("file", "");
                    si.title = s.optString("title", "");
                    si.bytes = s.optLong("bytes", 0);
                    si.mtime = s.optLong("mtime", 0);
                    si.lines = s.optInt("lines", 0);
                    si.badFrames = s.optInt("badFrames", 0);
                    r.sessions.add(si);
                }
            }
        } catch (Throwable t) {
            r.ok = false;
            r.error = String.valueOf(t.getMessage() == null ? t.toString() : t.getMessage());
            Log.w(TAG, "list failed", t);
        }
        return r;
    }

    /** 列回收站。 */
    public static TrashResult trashList(Context ctx, File payload, File dshHome) {
        TrashResult r = new TrashResult();
        File sd = sessionsDir(dshHome);
        r.trashDir = trashRoot(dshHome).getAbsolutePath();
        try {
            String out = PayloadScript.run(ctx, payload, SCRIPT, new String[]{
                    "trash-list", "--sessions", sd.getAbsolutePath(), "--json"
            }, 120000L);
            JSONObject o = new JSONObject(out);
            r.ok = o.optBoolean("ok", false);
            if (!r.ok) { r.error = o.optString("error", "未知错误"); return r; }
            r.count = o.optInt("count", 0);
            r.totalBytes = o.optLong("totalBytes", 0);
            JSONArray arr = o.optJSONArray("entries");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.getJSONObject(i);
                    TrashInfo ti = new TrashInfo();
                    ti.entry = e.optString("entry", "");
                    ti.dir = e.optString("dir", "");
                    ti.title = e.optString("title", "");
                    ti.slug = e.optString("slug", "");
                    ti.removedAt = e.optString("removedAt", "");
                    ti.bytes = e.optLong("bytes", 0);
                    ti.mtime = e.optLong("mtime", 0);
                    r.entries.add(ti);
                }
            }
        } catch (Throwable t) {
            r.ok = false;
            r.error = String.valueOf(t.getMessage() == null ? t.toString() : t.getMessage());
            Log.w(TAG, "trashList failed", t);
        }
        return r;
    }

    /** 删一条会话 → 移到回收站。 */
    public static OpResult trash(Context ctx, File payload, File dshHome, String sessionDir) {
        return simpleOp(ctx, payload, dshHome, "trash", "--file", sessionDir);
    }

    /** 从回收站恢复一条。 */
    public static OpResult restore(Context ctx, File payload, File dshHome, String entryDir) {
        return simpleOp(ctx, payload, dshHome, "restore", "--trash", entryDir);
    }

    /** 彻底删掉回收站里的一条。 */
    public static OpResult purge(Context ctx, File payload, File dshHome, String entryDir) {
        return simpleOp(ctx, payload, dshHome, "purge", "--trash", entryDir);
    }

    /** 清空回收站。 */
    public static OpResult purgeAll(Context ctx, File payload, File dshHome) {
        OpResult r = new OpResult();
        r.mode = "purge-all";
        try {
            String out = PayloadScript.run(ctx, payload, SCRIPT, new String[]{
                    "purge-all", "--sessions", sessionsDir(dshHome).getAbsolutePath(), "--json"
            }, 180000L);
            JSONObject o = new JSONObject(out);
            r.ok = o.optBoolean("ok", false);
            r.error = o.optString("error", "");
            r.entries = o.optInt("entries", 0);
            r.bytes = o.optLong("bytes", 0);
        } catch (Throwable t) {
            r.ok = false;
            r.error = String.valueOf(t.getMessage() == null ? t.toString() : t.getMessage());
            Log.w(TAG, "purgeAll failed", t);
        }
        return r;
    }

    private static OpResult simpleOp(Context ctx, File payload, File dshHome, String mode, String flag, String target) {
        OpResult r = new OpResult();
        r.mode = mode;
        if (target == null || target.length() == 0) { r.error = "没有指定目标"; return r; }
        try {
            String out = PayloadScript.run(ctx, payload, SCRIPT, new String[]{
                    mode, "--sessions", sessionsDir(dshHome).getAbsolutePath(), flag, target, "--json"
            }, 180000L);
            JSONObject o = new JSONObject(out);
            r.ok = o.optBoolean("ok", false);
            r.error = o.optString("error", "");
            r.moved = o.optString("moved", "");
            r.restoredTo = o.optString("restoredTo", "");
            r.slug = o.optString("slug", "");
            r.dirName = o.optString("dirName", "");
            r.bytes = o.optLong("bytes", 0);
        } catch (Throwable t) {
            r.ok = false;
            r.error = String.valueOf(t.getMessage() == null ? t.toString() : t.getMessage());
            Log.w(TAG, mode + " failed", t);
        }
        return r;
    }
}
