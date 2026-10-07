/**
 * 内核树「清单式一致性证明」——自修复功能的第 ① 条（本文件只做「只读证明」，绝不修改任何文件）。
 *
 * 为什么单独一类：MainActivity 已经 8000+ 行，而这段逻辑有四条必须写死的安全/正确性约束，
 * 放在一起更容易被后续改动踩坏。约束如下：
 *
 *  1. 「只读」：本类不做任何写入/删除/重命名。修复动作由 App 既有的 extractPayload(全量/增量) 承担。
 *  2. 「只信清单」：判据是"随包生成的 kernel-manifest.tsv"（相对路径/大小/sha256），不猜、不扫全盘找规律。
 *  3. 「先比大小再算哈希」：大小不同已足以判定"内容不符"，省掉一次全文件读取（大树上差别很大）。
 *  4. **分类只影响"能不能自动修"，不影响"报不报"「：用户数据（dshhome/」）与第三方插件
 *     （dshhome/profiles/「）」只报不改**，这是项目红线（v1.17.9 起写死的边界）。
 *
 * 输出是给人在控制台看的：结论 + 计数 + 少量样本 + 每条"下一步该干什么"。
 */
package com.deepseek.harness;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KernelSelfCheck {

    /** 清单资产名（构建期由 tools/manifest-gen.mjs 生成）。 */
    public static final String MANIFEST_ASSET = "kernel-manifest.tsv";

    /** 一次自检的结果。 */
    public static final class Result {
        public String verdict = "unknown";      // ok | warn | fail | skipped
        public String headline = "";
        public int manifestFiles = 0;            // 清单里的文件数
        public int treeFiles = 0;                // 树上实际文件数
        public int hashed = 0;                   // 真算了 sha256 的文件数
        public int missing = 0;                   // 清单有、树上没有
        public int mismatch = 0;                  // 大小或 sha256 不符
        public int extra = 0;                     // 树上有、清单没有（"多余"）
        public int extraKernel = 0;               //   其中属于内核命名空间（可自动修）
        public int extraUser = 0;                 //   其中属于用户数据/第三方插件（只报不改）
        public int nullHash = 0;                  // 哈希算失败（读不了/权限）
        public long elapsedMs = 0;
        public boolean quick = false;
        public final List<String> details = new ArrayList<String>();
        public final List<String> nextSteps = new ArrayList<String>();
        public final List<String> samples = new ArrayList<String>();

        /** 单行摘要（控制台/账本共用）。 */
        public String summary() {
            return verdict + " · 清单 " + manifestFiles + " / 树 " + treeFiles
                    + " · 缺失 " + missing + " · 不符 " + mismatch + " · 多余 " + extra
                    + (extra > 0 ? "（内核 " + extraKernel + " / 用户侧 " + extraUser + "）" : "")
                    + " · 用时 " + (elapsedMs / 1000) + "s" + (quick ? "（快速）" : "（全量校验）");
        }

        /** 账本用的 JSON（自己拼，不引依赖；字段都是数字/短字符串，转义走 esc()）。 */
        public String toLedgerJson(String trigger, String kernelRoot) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"kind\":\"kernel-tree-proof\",");
            sb.append("\"trigger\":\"").append(esc(trigger)).append("\",");
            sb.append("\"verdict\":\"").append(esc(verdict)).append("\",");
            sb.append("\"quick\":").append(quick).append(",");
            sb.append("\"manifestFiles\":").append(manifestFiles).append(",");
            sb.append("\"treeFiles\":").append(treeFiles).append(",");
            sb.append("\"hashed\":").append(hashed).append(",");
            sb.append("\"missing\":").append(missing).append(",");
            sb.append("\"mismatch\":").append(mismatch).append(",");
            sb.append("\"extra\":").append(extra).append(",");
            sb.append("\"extraKernel\":").append(extraKernel).append(",");
            sb.append("\"extraUser\":").append(extraUser).append(",");
            sb.append("\"nullHash\":").append(nullHash).append(",");
            sb.append("\"elapsedMs\":").append(elapsedMs).append(",");
            sb.append("\"kernelRoot\":\"").append(esc(kernelRoot)).append("\",");
            sb.append("\"headline\":\"").append(esc(headline)).append("\",");
            sb.append("\"samples\":[");
            for (int i = 0; i < samples.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(esc(samples.get(i))).append("\"");
            }
            sb.append("],\"nextSteps\":[");
            for (int i = 0; i < nextSteps.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(esc(nextSteps.get(i))).append("\"");
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    private KernelSelfCheck() {}

    /** 进度回调（阶段名 + 已处理 + 总数；total<=0 表示比例未知）。 */
    public interface Progress {
        void onProgress(String phase, int done, int total);
        /** 用户是否已请求取消（默认否，便于老调用点不改）。 */
        default boolean cancelled() { return false; }
    }

    private static void tick(Progress p, String phase, int done, int total) {
        if (p == null) return;
        try { p.onProgress(phase, done, total); } catch (Throwable ignored) {}
    }

    /** 清单是否随包（决定自检页能不能做"清单式证明"）。 */
    public static boolean manifestAvailable(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open(MANIFEST_ASSET, AssetManager.ACCESS_STREAMING);
            in.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 跑一次清单式一致性证明。
     *
     * @param ctx      取 assets 用
     * @param kernelRoot 内核树根（…/files/payload/dshroot）
     * @param quick    true=只比大小（秒级）；false=大小不符就跳过、大小对的全算 sha256（分钟级）
     * @param progress 进度回调（可 null）：[0]=已处理文件数, [1]=总数
     */
    public static Result run(Context ctx, File kernelRoot, boolean quick, int[] progress) {
        return run(ctx, kernelRoot, quick, (Progress) null);
    }

    /**
     * 跑一次清单式一致性证明（带进度回调）。
     * 进度分三段：读清单（预扫总数）→ 遍历+校验 → 收尾统计。total 为清单条目数。
     */
    public static Result run(Context ctx, File kernelRoot, boolean quick, Progress progress) {
        Result r = new Result();
        r.quick = quick;
        long t0 = System.currentTimeMillis();

        if (kernelRoot == null || !kernelRoot.isDirectory()) {
            r.verdict = "fail";
            r.headline = "找不到内核树：" + kernelRoot;
            r.nextSteps.add("先在控制台点「解压文件」把内核树解出来，再回来自检。");
            return r;
        }

        // ── 0) 预扫一遍清单，先拿到总数（进度分母）。读不到就当 0（比例未知，界面显示"处理中"）。 ──
        int totalEntries = 0;
        tick(progress, "读取清单", 0, 0);
        BufferedReader pre = null;
        try {
            pre = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open(MANIFEST_ASSET, AssetManager.ACCESS_STREAMING), "UTF-8"), 1 << 16);
            String l0;
            while ((l0 = pre.readLine()) != null) if (l0.length() > 0 && l0.charAt(0) != '#') totalEntries++;
        } catch (Throwable ignored) {
        } finally {
            try { if (pre != null) pre.close(); } catch (Throwable ignored) {}
        }
        tick(progress, "读取清单", 0, totalEntries);

        // ── 1) 读清单 ──
        Map<String, long[]> meta = new HashMap<String, long[]>();   // 相对路径 → [size]
        Map<String, String> hashes = new HashMap<String, String>(); // 相对路径 → sha256
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open(MANIFEST_ASSET, AssetManager.ACCESS_STREAMING), "UTF-8"), 1 << 16);
            String line;
            int readLines = 0;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                if ((++readLines & 0x7FF) == 0) tick(progress, "读取清单", readLines, totalEntries);
                int t1 = line.indexOf('\t');
                if (t1 <= 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 <= t1) continue;
                String rel = line.substring(0, t1);
                long size;
                try { size = Long.parseLong(line.substring(t1 + 1, t2).trim()); } catch (Throwable t) { continue; }
                meta.put(rel, new long[]{size});
                hashes.put(rel, line.substring(t2 + 1).trim());
            }
        } catch (Throwable t) {
            r.verdict = "skipped";
            r.headline = "本包不带内核树清单（assets/" + MANIFEST_ASSET + " 读不到），无法做清单式证明";
            r.nextSteps.add("重新安装带清单的包；或改用「快速自检」做基础项检查。");
            return r;
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        r.manifestFiles = meta.size();
        if (meta.isEmpty()) {
            r.verdict = "skipped";
            r.headline = "清单是空的（0 行），无法比对";
            return r;
        }

        // ── 2) 遍历树 ──
        Set<String> seen = new HashSet<String>();
        List<File> stack = new ArrayList<File>();
        stack.add(kernelRoot);
        int walked = 0;
        while (!stack.isEmpty()) {
            File dir = stack.remove(stack.size() - 1);
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (int i = 0; i < kids.length; i++) {
                File f = kids[i];
                if (f.isDirectory()) { stack.add(f); continue; }
                String rel = "dshroot/" + relativize(kernelRoot, f);
                walked++;
                // 节流：每 200 个文件回调一次（真机上 1 次回调 ≈ 一次跨线程 post，太密会拖慢校验本身）
                if ((walked & 0x7F) == 0) {
                    // 取消：每 128 个文件问一次（够灵敏，又不至于每次文件都问）
                    if (progress != null && progress.cancelled()) {
                        r.verdict = "cancelled";
                        r.treeFiles = walked;
                        r.elapsedMs = System.currentTimeMillis() - t0;
                        r.headline = "已取消（处理了 " + walked + " / " + totalEntries + " 个文件就停了）"
                                + " —— 这不是结论，缺失/不符计数只统计了已处理的部分，请重新跑一次完整的。";
                        r.nextSteps.add("点「快速自检」或「全量校验」重新跑一遍（取消不会改动任何文件）。");
                        return r;
                    }
                    tick(progress, quick ? "比对大小" : "校验内容（sha256）", walked, totalEntries);
                }
                long[] m = meta.get(rel);
                if (m == null) {
                    r.extra++;
                    if (isKernelNamespace(rel)) r.extraKernel++; else r.extraUser++;
                    // 样本只列内核命名空间里的（用户侧的多余不列，免得被误读成"要清理"）
                    if (r.samples.size() < 12 && isKernelNamespace(rel)) r.samples.add("清单外（内核命名空间，仅供参考，不会自动删）：" + shortPath(rel));
                    continue;
                }
                seen.add(rel);
                r.treeFiles++;
                long size = f.length();
                if (size != m[0]) {
                    r.mismatch++;
                    if (r.samples.size() < 12) r.samples.add("大小不符：" + shortPath(rel) + "（清单 " + m[0] + " / 实际 " + size + "）");
                    continue;
                }
                if (quick) continue;
                String got = sha256Of(f);
                r.hashed++;
                if (got == null || got.length() == 0) {
                    r.nullHash++;
                    if (r.samples.size() < 12) r.samples.add("读不了（权限/损坏）：" + shortPath(rel));
                    continue;
                }
                String want = hashes.get(rel);
                if (want != null && !want.equalsIgnoreCase(got)) {
                    r.mismatch++;
                    if (r.samples.size() < 12) r.samples.add("内容不符：" + shortPath(rel) + "（sha256 " + got.substring(0, 8) + "… ≠ " + want.substring(0, 8) + "…）");
                }
            }
        }
        r.missing = meta.size() - seen.size();
        if (r.missing > 0) {
            int shown = 0;
            for (String rel : meta.keySet()) {
                if (seen.contains(rel)) continue;
                if (shown++ >= 6) break;
                r.samples.add("缺失：" + shortPath(rel));
            }
        }

        r.treeFiles = walked;
        r.elapsedMs = System.currentTimeMillis() - t0;
        tick(progress, "完成", walked, totalEntries);

        // ── 3) 结论 ──
        if (r.missing == 0 && r.mismatch == 0 && r.nullHash == 0) {
            r.verdict = "ok";
            r.headline = "内核树与随包清单完全一致（" + r.treeFiles + " 个文件"
                    + (quick ? "，只比大小" : "，逐个 sha256 校验") + "）";
            if (r.extra > 0) {
                // 措辞讲究：清单是构建期在 devhome（开发树，比出货树多东西）上算的，
                // 所以"清单外文件"绝大多数是「正常差异」（第三方依赖、dev 专用文件），不是陈旧包。
                // 只说事实，不给"可自动修 N 个"这种会被当成承诺的计数。
                r.headline += "；另有 " + r.extra + " 个清单外文件（属参考信息：多为开发树多出的第三方依赖，"
                        + "其中 " + r.extraUser + " 个在用户数据/第三方插件区，永远只报不动）";
            }
            r.nextSteps.add("无需处理。若引擎仍然起不来，请点「导出诊断」把证据发出来。");
        } else {
            r.verdict = "fail";
            r.headline = "内核树与清单不一致：缺失 " + r.missing + " · 内容/大小不符 " + r.mismatch
                    + (r.nullHash > 0 ? " · 读不了 " + r.nullHash : "")
                    + (quick ? "（快速模式只比大小，建议再跑一次全量校验确认内容）" : "");
            if (r.extraKernel > 0) {
                r.headline += "；另有 " + r.extraKernel + " 个清单外的内核命名空间文件（仅供参考）";
            }
            r.nextSteps.add("点「全量校验」确认内容层面是否真的不一致（快速模式只比大小）。");
            r.nextSteps.add("确认后点控制台 →「重新解压」：全量解压会按 payload 覆盖并把 payload 已没有的 dsh* 陈旧内核包清掉（用户数据与第三方插件不动）。");
            r.nextSteps.add("修完再点一次「清单自检」，期望变成 缺失 0 / 不符 0 —— 这是自愈成功的硬判据。");
        }
        return r;
    }

    // ==================== 辅助 ====================

    /** 相对内核树根的路径（统一用 '/'）。 */
    static String relativize(File root, File f) {
        String rp = root.getAbsolutePath();
        String fp = f.getAbsolutePath();
        if (fp.startsWith(rp)) {
            String s = fp.substring(rp.length());
            if (s.startsWith(File.separator)) s = s.substring(1);
            return s.replace(File.separatorChar, '/');
        }
        return f.getName();
    }

    /**
     * 是否属于"内核命名空间"（= 自动修允许触碰的范围）。
     * 与 v1.17.9 的自愈边界一致：只认两个 @deepseek-ai 目录，且只认 dsh 开头的条目。
     */
    static boolean isKernelNamespace(String rel) {
        if (!rel.startsWith("dshroot/")) return false;
        String p = rel.substring("dshroot/".length());
        final String FLAT = "lib/node_modules/@deepseek-ai/";
        final String NESTED = "lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/";
        String rest = null;
        if (p.startsWith(NESTED)) rest = p.substring(NESTED.length());
        else if (p.startsWith(FLAT)) rest = p.substring(FLAT.length());
        if (rest == null) return false;
        int slash = rest.indexOf('/');
        String pkg = slash > 0 ? rest.substring(0, slash) : rest;
        return pkg.startsWith("dsh");
    }

    /** 样本里只显示后半段，避免把长路径全打出来（控制台窄）。 */
    static String shortPath(String rel) {
        if (rel.length() <= 72) return rel;
        return "…" + rel.substring(rel.length() - 72);
    }

    /** 文件 sha256（小写 hex）；失败返回 null。 */
    static String sha256Of(File f) {
        FileInputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }
}
