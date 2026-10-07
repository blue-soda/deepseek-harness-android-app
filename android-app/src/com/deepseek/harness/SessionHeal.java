package com.deepseek.harness;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * 自修复 ③「会话级自愈」的 App 侧执行器（只读体检 + 只降级坏附件那一条消息）。
 *
 * <p>为什么这么做：坏 PNG（文件头合法、IDAT 损坏）一旦作为 tool_result 进历史，**每次请求都复现**，
 * 用户只能放弃整条会话（v1.15.1 记录过两条；根因见 dsh-attachment-local/lib/sharp-shim.js 头注释「症状 A」）。
 * 本类的做法是：定位坏的附件引用 → 把那一处 image part 换成等价的 text part，
 * 会话其余内容（文本、上下文、顺序）一字不动。
 *
 * <p>为什么用 node 而不是纯 Java：会话是多帧 zstd（每帧一次 flush），Java 侧没有现成的
 * zstd 解压/压缩实现，而 payload 自带 node（原生支持 zstd）。脚本 {@code session-heal.mjs}
 * 随包分发（assets），运行时落到私有目录再执行 —— assets 不是真实文件路径，node 读不了。
 *
 * <p>安全边界（与设计稿 selfheal-design.md §3 一致，代码里写死）：
 * <ul>
 *   <li>只处理**用户点选的那一条**会话，没有「扫全目录批量修」的入口；</li>
 *   <li>写盘前先备份（同目录 {@code .corrupt-<ts>}），写盘用「临时文件 + rename」原子替换；</li>
 *   <li>写完立即复检：坏引用必须归零、行数与 JSON 合法性不变，否则如实报 partial；</li>
 *   <li>默认 dry-run，只有显式 apply 才落盘。</li>
 * </ul>
 */
public final class SessionHeal {

    private static final String TAG = "DSHSessionHeal";

    /** 随包脚本名（assets/session-heal.mjs）。 */
    public static final String SCRIPT_ASSET = "session-heal.mjs";
    /** 落盘脚本名（<filesDir>/tools/）。 */
    public static final String SCRIPT_NAME = "session-heal.mjs";

    private SessionHeal() {}

    // ─────────────────────────── 数据模型 ───────────────────────────

    /** 一处坏附件引用。 */
    public static final class BadRef {
        public int frame;
        public int line;
        public long seq;
        public String evType = "";
        public String name = "";
        public String reason = "";
        public String detail = "";

        /** 给界面用的一句话（不含 markdown 记号）。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("第 ").append(seq).append(" 号事件");
            if (name.length() > 0) sb.append(" · ").append(name);
            sb.append(" —— ").append(reasonText()).append("：").append(detail);
            return sb.toString();
        }

        /** 把机器码翻成人话。 */
        public String reasonText() {
            if ("ATTACHMENT_NOT_FOUND".equals(reason)) return "附件实体不在了";
            if ("ATTACHMENT_CORRUPT".equals(reason)) return "附件内容已损坏";
            if ("ATTACHMENT_READ_FAILED".equals(reason)) return "附件读不出来";
            if ("INVALID_ATTACHMENT_REF".equals(reason)) return "附件引用形态非法";
            return reason;
        }
    }

    /** 一条会话的体检结果。 */
    public static final class SessionInfo {
        public String id = "";
        public String file = "";
        public long bytes;
        public long mtime;
        public int frames;
        public int lines;
        public int jsonBad;
        public int attRefs;
        public int badCount;
        public final List<BadRef> bad = new ArrayList<BadRef>();

        public boolean hasProblem() { return badCount > 0; }

        public String sizeText() {
            if (bytes >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
            if (bytes >= 1024L) return (bytes / 1024L) + " KB";
            return bytes + " B";
        }

        public String summary() {
            if (badCount > 0) return "⚠ " + badCount + " 处坏附件引用（共 " + attRefs + " 处附件）";
            return "正常（" + attRefs + " 处附件引用，无损坏）";
        }
    }

    /** audit 的结果。 */
    public static final class AuditResult {
        public boolean ok;
        public String error = "";
        public String sessionsDir = "";
        public String attRoot = "";
        public int sessionCount;
        public int badSessionCount;
        public int totalBadRefs;
        public final List<SessionInfo> sessions = new ArrayList<SessionInfo>();

        public List<SessionInfo> problemSessions() {
            List<SessionInfo> out = new ArrayList<SessionInfo>();
            for (int i = 0; i < sessions.size(); i++) if (sessions.get(i).hasProblem()) out.add(sessions.get(i));
            return out;
        }

        public String headline() {
            if (!ok) return "体检失败：" + error;
            if (sessionCount == 0) return "没找到会话文件";
            if (badSessionCount == 0) return "会话 " + sessionCount + " 条 · 没有发现坏附件";
            return "会话 " + sessionCount + " 条 · 其中 " + badSessionCount + " 条有坏附件（共 " + totalBadRefs + " 处）";
        }
    }

    /** heal 的结果。 */
    public static final class HealResult {
        public boolean ok;
        public boolean applied;
        public String error = "";
        public String refused = "";
        public String reason = "";
        public String file = "";
        public String backup = "";
        public String verdict = "";
        public int framesRewritten;
        public int actionCount;
        public long bytesBefore;
        public long bytesAfter;
        public int badRefsAfter;
        public int linesBefore;
        public int linesAfter;
        public int jsonBadAfter;
        public final List<String> actions = new ArrayList<String>();

        public String headline() {
            if (refused != null && refused.length() > 0) return "已拒绝：" + refused;
            if (!ok) return "修复失败：" + error;
            if (!applied) return reason.length() > 0 ? reason : "没有需要修复的地方";
            return "已降级 " + actionCount + " 处坏附件 · 复检坏引用 " + badRefsAfter
                    + " · 行数 " + linesBefore + " → " + linesAfter;
        }
    }

    // ─────────────────────────── 路径 ───────────────────────────

    /** 会话根：<dshHome>/sessions。 */
    public static File sessionsDir(File dshHome) {
        return new File(dshHome, "sessions");
    }

    /** 附件对象根：<dshHome>/attachments/v1（见 dsh-attachment-local 的 normalizedImagePath）。 */
    public static File attRoot(File dshHome) {
        return new File(new File(dshHome, "attachments"), "v1");
    }

    // ─────────────────────────── 入口 ───────────────────────────

    /** 只读体检：扫描全部会话，报告哪些会话的哪一条消息引用了坏附件。 */
    public static AuditResult audit(Context ctx, File payload, File dshHome) {
        AuditResult r = new AuditResult();
        File sessions = sessionsDir(dshHome);
        File att = attRoot(dshHome);
        r.sessionsDir = sessions.getAbsolutePath();
        r.attRoot = att.getAbsolutePath();
        if (!sessions.isDirectory()) {
            r.ok = false;
            r.error = "找不到会话目录：" + sessions.getAbsolutePath() + "（引擎跑过一次、或先点「解压文件」）";
            return r;
        }
        try {
            String out = PayloadScript.run(ctx, payload, SCRIPT_NAME, new String[]{
                    "audit",
                    "--sessions", sessions.getAbsolutePath(),
                    "--att-root", att.getAbsolutePath(),
                    "--json"
            }, 180000L);
            JSONObject o = new JSONObject(out);
            r.ok = o.optBoolean("ok", false);
            r.sessionCount = o.optInt("sessionCount", 0);
            r.badSessionCount = o.optInt("badSessionCount", 0);
            r.totalBadRefs = o.optInt("totalBadRefs", 0);
            JSONArray arr = o.optJSONArray("sessions");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.getJSONObject(i);
                    SessionInfo si = new SessionInfo();
                    si.id = s.optString("id", "");
                    si.file = s.optString("file", "");
                    si.bytes = s.optLong("bytes", 0);
                    si.frames = s.optInt("frames", 0);
                    si.lines = s.optInt("lines", 0);
                    si.jsonBad = s.optInt("jsonBad", 0);
                    si.attRefs = s.optInt("attRefs", 0);
                    si.badCount = s.optInt("badCount", 0);
                    JSONArray bad = s.optJSONArray("bad");
                    if (bad != null) {
                        for (int k = 0; k < bad.length(); k++) {
                            JSONObject b = bad.getJSONObject(k);
                            BadRef br = new BadRef();
                            br.frame = b.optInt("frame", 0);
                            br.line = b.optInt("line", 0);
                            br.seq = b.optLong("seq", 0);
                            br.evType = b.optString("evType", "");
                            br.name = b.optString("name", "");
                            br.reason = b.optString("reason", "");
                            br.detail = b.optString("detail", "");
                            si.bad.add(br);
                        }
                    }
                    r.sessions.add(si);
                }
            }
        } catch (Throwable t) {
            r.ok = false;
            r.error = String.valueOf(t.getMessage() == null ? t.toString() : t.getMessage());
            Log.w(TAG, "audit failed", t);
        }
        return r;
    }

    /**
     * 对**一条**会话执行修复（dry-run 或落盘）。
     *
     * @param apply true 才真正写盘；false 只回报"将会做什么"
     */
    public static HealResult heal(Context ctx, File payload, File dshHome, String sessionFile, boolean apply) {
        HealResult r = new HealResult();
        r.file = sessionFile;
        File att = attRoot(dshHome);
        if (sessionFile == null || sessionFile.length() == 0) {
            r.error = "没有指定会话文件";
            return r;
        }
        if (!new File(sessionFile).isFile()) {
            r.error = "会话文件不存在：" + sessionFile;
            return r;
        }
        if (!att.isDirectory()) {
            r.error = "找不到附件目录：" + att.getAbsolutePath();
            return r;
        }
        try {
            List<String> args = new ArrayList<String>();
            args.add("heal");
            args.add("--file"); args.add(sessionFile);
            args.add("--att-root"); args.add(att.getAbsolutePath());
            args.add("--json");
            if (apply) args.add("--apply");
            String out = PayloadScript.run(ctx, payload, SCRIPT_NAME, args.toArray(new String[args.size()]), 180000L);
            JSONObject o = new JSONObject(out);
            r.ok = o.optBoolean("ok", false);
            r.applied = o.optBoolean("applied", false);
            r.refused = o.optString("refused", "");
            r.reason = o.optString("reason", "");
            r.backup = o.optString("backup", "");
            r.verdict = o.optString("verdict", "");
            r.framesRewritten = o.optInt("framesRewritten", 0);
            r.bytesBefore = o.optLong("bytesBefore", 0);
            r.bytesAfter = o.optLong("bytesAfter", 0);
            JSONArray acts = o.optJSONArray("actions");
            if (acts != null) {
                r.actionCount = acts.length();
                for (int i = 0; i < acts.length(); i++) {
                    JSONObject a = acts.getJSONObject(i);
                    r.actions.add("第 " + a.optLong("seq", 0) + " 号事件 · " + a.optString("name", "")
                            + " —— " + a.optString("reason", "") + "：" + a.optString("detail", ""));
                }
            }
            JSONObject v = o.optJSONObject("verify");
            if (v != null) {
                r.badRefsAfter = v.optInt("badRefsAfter", -1);
                r.linesBefore = v.optInt("linesBefore", 0);
                r.linesAfter = v.optInt("linesAfter", 0);
                r.jsonBadAfter = v.optInt("jsonBadAfter", 0);
            }
        } catch (Throwable t) {
            r.ok = false;
            r.error = String.valueOf(t.getMessage() == null ? t.toString() : t.getMessage());
            Log.w(TAG, "heal failed", t);
        }
        return r;
    }

}
