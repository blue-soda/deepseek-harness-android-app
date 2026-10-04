package com.deepseek.harness;

import android.graphics.Color;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 控制台主题包 · 配置模型（解析 / 校验 / 兜底）—— v1.17.4「控制台自定义」第 1~3 步。
 *
 * <p>读的是 {@code /sdcard/<包名目录>/console/console.json}（外部存储，文件管理器可直接改，
 * 三种变体互不干扰）。三条铁律：
 * <ol>
 *   <li><b>永不因为配置打不开 App</b>：任何异常都在 load() 里被吃掉，坏配置一律回退内置默认；</li>
 *   <li><b>语法错 / schema 过高</b> → 整体回退（fatal=true）；<b>单项非法</b> → 只回退那一项（reverted++）；</li>
 *   <li><b>没有配置文件</b>（absent=true）= 全默认，视觉与加这个功能之前完全一致。</li>
 * </ol>
 *
 * <p>⚠ 规则表与 {@code release-src/console-theme/theme_pack_check.py} 是<b>同一套</b>：
 * 改这里的颜色正则 / 取值范围 / enum，必须同步改那边（否则会出现"校验器说合法、App 说非法"）。
 * 已知的<b>有意差异</b>（写在这里免得被当成 bug）：
 * <ul>
 *   <li>未知字段在离线校验器里算 error，在 App 里只算 warning（向前兼容：新版主题不该让旧 App 整体不生效）；</li>
 *   <li>{@code radius} 只在<b>显式写了</b>时才生效 —— schema 里的 default=14 是给主题作者看的参考值，
 *       App 里"没写"= 沿用每个控件自己的内置圆角，保证不写配置时旧视觉不变。</li>
 * </ul>
 *
 * <p>本类刻意不引用任何 UI 组件（只有 Color/JSON/文件），方便四份源码逐字节相同。
 */
final class ConsoleTheme {

    // ---- 与 theme_pack_check.py 同一套常量 ----
    static final int DARK_FOLLOW = 0, DARK_LIGHT = 1, DARK_DARK = 2;
    static final int MAX_JSON = 512 * 1024;
    private static final Pattern COLOR_RE =
            Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
    private static final String[] COLOR_KEYS =
            {"bg", "card", "text", "sub", "line", "accent", "green", "red", "track"};
    private static final String[] TOP_KEYS =
            {"schema", "name", "author", "app", "appearance", "layout", "text", "behavior", "actions"};
    private static final String[] AP_KEYS =
            {"dark", "colors", "perScheme", "fontScale", "radius", "mono", "logo", "cardsAlpha", "background"};
    /** 需要用户**逐个确认**才执行的动作类型（能力很强：等于把手机 shell 交给一份 JSON）。 */
    private static final String[] EXEC_ACTIONS = {"shell", "http", "intent", "prompt"};
    /** 全部合法动作类型（与离线校验器 check_action 的白名单一致）。 */
    private static final String[] ACTION_SIMPLE = {
            "engine.start", "engine.restart", "engine.stop", "engine.openUi",
            "extract.run", "extract.verify", "perm.open", "log.share", "log.clear", "log.view",
            "theme.reload", "theme.export", "theme.import", "theme.reset",
            "clipboard", "toast", "settings", "url"};
    private static final String[] ACTION_ALL = {
            "engine.start", "engine.restart", "engine.stop", "engine.openUi",
            "extract.run", "extract.verify", "perm.open", "log.share", "log.clear", "log.view",
            "theme.reload", "theme.export", "theme.import", "theme.reset",
            "clipboard", "toast", "settings", "url", "shell", "http", "intent", "prompt"};
    /** 声明式控件树的节点类型 / 字段（与 SPEC §5、离线校验器 check_node 一致）。 */
    private static final String[] NODE_TYPES =
            {"row", "column", "card", "text", "button", "image", "spacer", "divider", "builtin"};
    private static final String[] NODE_KEYS =
            {"type", "id", "text", "size", "color", "align", "weight", "width", "visible", "style", "action", "children"};
    private static final String[] BUILTIN_IDS = {
            "extract.block", "extract.status", "extract.detail",
            "engine.status", "engine.buttons",
            "rescue.buttons", "rescue.desc", "theme.card",
            "perm.summary", "perm.list", "plugin.summary", "plugin.list",
            "log.actions", "log.view", "update.button", "version.label", "brand.label"};
    private static final String[] SEMANTIC_COLORS = {"text", "sub", "accent", "green", "red"};
    private static final String[] ALIGN_VALUES = {"left", "center", "right"};
    private static final String[] STYLE_VALUES = {"primary", "secondary"};
    private static final int MAX_NODE_DEPTH = 6;
    private static final String[] ACT_KEYS = {
            "id", "label", "icon", "type", "confirm", "continueOnError", "cmd", "privileged",
            "url", "external", "action", "data", "package", "text", "copy", "session", "mode",
            "method", "body", "headers"};
    private static final String[] LAY_KEYS =
            {"order", "hidden", "defaultPage", "detailOpen", "compact", "pages"};
    private static final String[] CARD_IDS =
            {"extract", "engine", "rescue", "actions", "perm", "plugins", "log", "update", "theme"};
    /** 不可移除：救援面（写进 hidden 会被忽略并给 warning）。 */
    private static final String[] KEEP_CARDS = {"rescue", "theme"};
    private static final Pattern TEXT_KEY_RE =
            Pattern.compile("^(card|btn|title|status|desc)\\.[A-Za-z0-9_.]+$");
    private static final int MAX_TEXT = 40;
    private static final String[] BG_KEYS =
            {"image", "fit", "anchor", "opacity", "dim", "blur", "parallax"};
    private static final String[] FIT_VALUES =
            {"cover", "contain", "stretch", "tile", "center"};
    private static final String[] ANCHOR_VALUES =
            {"center", "top", "bottom", "left", "right",
             "topLeft", "topRight", "bottomLeft", "bottomRight"};
    private static final Pattern IMG_REF_RE =
            Pattern.compile("^([^/\\\\]+\\.(png|jpg|jpeg|webp)|/sdcard/.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAR_AT_RE = Pattern.compile("at character (\\d+)");

    /** 声明式控件树的一个节点（`layout.pages[].children[]`）。 */
    static final class Node {
        String type;
        String id;              // 节点 id（可选）或 builtin 的积木 id
        String text;
        Integer size;           // 8~28（文字 sp；spacer 当高度 dp 用）
        String color;           // text|sub|accent|green|red 或 #rrggbb
        String align;           // left|center|right
        Float weight;           // 0~1，行内占比
        Integer width;          // dp，可省
        Boolean visible;
        String style;           // primary|secondary（button）
        Act action;             // button 的动作（复用 actions 那套）
        final java.util.List<Node> children = new ArrayList<Node>();
    }

    /** 一整页（`layout.pages[]`）。pages[0] = 主控台；后面的按顺序变成额外页。 */
    static final class Page {
        String id;
        String title;
        final java.util.List<Node> children = new ArrayList<Node>();
    }

    /** 一条自定义按钮（`actions[]` 里的一项）。 */
    static final class Act {
        String id, label, type;
        String cmd, url, action, data, pkg, text, copy, method, body;
        boolean confirm = true;        // shell 默认要二次确认
        boolean privileged = false;    // shell: 走 Shizuku/root
        boolean external = true;       // url: 用外部浏览器
        boolean executable = false;    // shell/http/intent/prompt → 点击时先确认
    }

    /** 背景图配置（只有 appearance.background 存在且给了 image 时才有意义）。 */
    static final class Bg {
        String image;                 // 文件名（相对 console/）或 /sdcard/… 绝对路径
        String fit = "cover";
        String anchor = "center";
        float opacity = 0.4f;         // schema 默认 0.4（叠在 colors.bg 之上）
        float dim = 0.25f;            // 再压一层暗色，保证文字可读
        int blur = 0;                 // 仅 Android 12+ 生效
        boolean parallax = false;     // true = 滚动时背景跟随（实际按 0.4 倍速位移）
    }

    // ---- 解析结果（全部有内置默认值）----
    String name = null;
    int darkMode = DARK_FOLLOW;
    float fontScale = 1f;
    Integer radius = null;            // null = 没写 → 沿用各控件内置圆角
    boolean mono = false;
    String logo = null;
    Float cardsAlpha = null;          // null = 没写 → 不动底色 alpha
    // ---- layout（第 4 步：内置卡片的显隐·顺序·默认页）----
    java.util.List<String> cardOrder = null;                 // null = 用内置顺序
    final java.util.List<String> hiddenCards = new ArrayList<String>();
    Integer defaultPage = null;                              // 0~9（本版只认 0~3）
    Boolean detailOpen = null;                               // 「已解压」详情默认展开
    boolean compact = false;                                 // 间距减半
    /** 声明式页（layout.pages）：null = 没配（用内置卡片布局）。 */
    java.util.List<Page> pages = null;
    /** 解析出来的自定义按钮（按配置顺序）。 */
    final java.util.List<Act> actions = new ArrayList<Act>();
    /** 其中"可执行动作"的摘要（主题页第④块显示用）：形如 "shell：重启服务器"。 */
    final java.util.List<String> execActions = new ArrayList<String>();
    // ---- text（文案覆盖：键 → 新文案，≤40 字）----
    private final Map<String, String> texts = new HashMap<String, String>();
    Bg background = null;
    int statusBarAny = 0;             // appearance.colors.statusBar（0 = auto）
    int statusBarLight = 0;           // appearance.perScheme.light.statusBar（0 = 未给/auto，优先级更高）
    int statusBarDark = 0;            // appearance.perScheme.dark.statusBar

    private final Map<String, Integer> colors = new HashMap<String, Integer>();
    private final Map<String, Integer> lightColors = new HashMap<String, Integer>();
    private final Map<String, Integer> darkColors = new HashMap<String, Integer>();

    // ---- 诊断 ----
    /** 文件不存在（不是错误，只是没配置）。 */
    boolean absent = false;
    /** 整体回退（语法错 / schema 不支持 / 顶层不是对象）——此时除诊断外所有字段都是默认值。 */
    boolean fatal = false;
    /** 被单项回退的字段数（与 warnings 里 "[已回退]" 的条数对应）。 */
    int reverted = 0;
    final List<String> errors = new ArrayList<String>();     // fatal 的原因（1~3 条）
    final List<String> warnings = new ArrayList<String>();   // 单项回退 + 提示
    final List<String> notes = new ArrayList<String>();       // 本轮未生效的字段等
    String schemaRaw = null;                                  // 读到的 schema 原值（报错用）

    // ==================== 载入 ====================

    /** 读并解析一份 console.json。<b>任何异常都在内部吃掉</b>，永远返回一个可用对象。 */
    static ConsoleTheme load(File f) {
        ConsoleTheme t = new ConsoleTheme();
        if (f == null || !f.exists() || !f.isFile() || f.length() == 0) {
            t.absent = true;
            return t;
        }
        if (f.length() > MAX_JSON) {
            t.fail("配置文件超过 " + (MAX_JSON / 1024) + "KB 上限（" + (f.length() / 1024) + "KB）");
            return t;
        }
        String text;
        try {
            text = readText(f);
        } catch (Throwable e) {
            t.fail("读取失败：" + brief(e));
            return t;
        }
        JSONObject o;
        try {
            o = new JSONObject(stripBom(text));
        } catch (Throwable e) {
            String msg = brief(e);
            Matcher m = CHAR_AT_RE.matcher(msg == null ? "" : msg);
            if (m.find()) {
                try {
                    int at = Integer.parseInt(m.group(1));
                    int[] lc = lineCol(text, at);
                    msg = msg + "（第 " + lc[0] + " 行第 " + lc[1] + " 列附近）";
                } catch (Throwable ignored) {}
            }
            t.fail("JSON 语法错误：" + msg);
            return t;
        }
        try {
            t.parse(o);
        } catch (Throwable e) {
            // parse() 内部已经做了逐项兜底；走到这里说明出了意料之外的问题 → 整体回退，但绝不崩。
            t.fail("解析异常（已回退默认）：" + brief(e));
        }
        return t;
    }

    /**
     * 校验一段 console.json 文本（导入主题包时用：先判能不能用，再决定要不要覆盖现有配置）。
     * 与 load() 的区别：不落盘、不读文件，语法错/超限同样走 fail()（fatal=true）。
     */
    static ConsoleTheme fromText(String text) {
        ConsoleTheme t = new ConsoleTheme();
        if (text == null || text.trim().length() == 0) {
            t.fail("console.json 是空的");
            return t;
        }
        if (text.length() > MAX_JSON) {
            t.fail("console.json 超过 " + (MAX_JSON / 1024) + "KB 上限");
            return t;
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Throwable e) {
            String msg = brief(e);
            Matcher m = CHAR_AT_RE.matcher(msg == null ? "" : msg);
            if (m.find()) {
                try {
                    int[] lc = lineCol(text, Integer.parseInt(m.group(1)));
                    msg = msg + "（第 " + lc[0] + " 行第 " + lc[1] + " 列附近）";
                } catch (Throwable ignored) {}
            }
            t.fail("JSON 语法错误：" + msg);
            return t;
        }
        try {
            t.parse(o);
        } catch (Throwable e) {
            t.fail("解析异常（已回退默认）：" + brief(e));
        }
        return t;
    }

    private void fail(String why) {
        fatal = true;
        errors.add(why);
    }

    // ==================== 解析 + 逐项校验 ====================

    private void parse(JSONObject o) {
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!in(TOP_KEYS, k)) warn("$." + k + " 是未知字段（已忽略）");
        }

        Object sv = o.opt("schema");
        schemaRaw = sv == null ? null : String.valueOf(sv);
        if (!(sv instanceof Number) || ((Number) sv).intValue() != 1) {
            fail("schema 必须为 1（读到 " + (schemaRaw == null ? "缺省" : schemaRaw)
                    + "）—— 更高版本的规范需要更新的 App");
            return;
        }
        name = optStr(o, "name", 64);

        Object av = o.opt("appearance");
        if (av == null) return;
        if (!(av instanceof JSONObject)) {
            revert("$.appearance", "应为对象");
            return;
        }
        JSONObject ap = (JSONObject) av;

        for (Iterator<String> it = ap.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!in(AP_KEYS, k)) warn("$.appearance." + k + " 是未知字段（已忽略）");
        }

        // dark
        Object dark = ap.opt("dark");
        if (dark != null) {
            if ("light".equals(dark)) darkMode = DARK_LIGHT;
            else if ("dark".equals(dark)) darkMode = DARK_DARK;
            else if ("follow".equals(dark)) darkMode = DARK_FOLLOW;
            else revert("$.appearance.dark", "应为 follow / light / dark");
        }

        // colors / perScheme
        if (ap.has("colors")) {
            Object c = ap.opt("colors");
            if (c instanceof JSONObject) checkColors((JSONObject) c, "$.appearance.colors", colors);
            else revert("$.appearance.colors", "应为对象");
        }
        if (ap.has("perScheme")) {
            Object ps = ap.opt("perScheme");
            if (!(ps instanceof JSONObject)) {
                revert("$.appearance.perScheme", "应为对象");
            } else {
                JSONObject p = (JSONObject) ps;
                for (Iterator<String> it = p.keys(); it.hasNext(); ) {
                    String sk = it.next();
                    if (!"light".equals(sk) && !"dark".equals(sk)) {
                        revert("$.appearance.perScheme." + sk, "只有 light / dark 两种方案");
                        continue;
                    }
                    Object v = p.opt(sk);
                    if (v instanceof JSONObject) {
                        JSONObject scheme = (JSONObject) v;
                        checkColors(scheme, "$.appearance.perScheme." + sk,
                                "dark".equals(sk) ? darkColors : lightColors);
                        Object sb = scheme.opt("statusBar");
                        if (sb != null && !"auto".equals(sb)) {
                            if (sb instanceof String && COLOR_RE.matcher((String) sb).matches()) {
                                int c = parseColor((String) sb);
                                if ("dark".equals(sk)) statusBarDark = c; else statusBarLight = c;
                            } else {
                                revert("$.appearance.perScheme." + sk + ".statusBar",
                                        "应为 auto 或 #rgb/#rrggbb/#aarrggbb");
                            }
                        }
                    } else {
                        revert("$.appearance.perScheme." + sk, "应为对象");
                    }
                }
            }
        }

        // fontScale / radius / mono / cardsAlpha
        Object fs = ap.opt("fontScale");
        if (fs != null) {
            if (num(fs, 0.8, 1.4)) fontScale = ((Number) fs).floatValue();
            else revert("$.appearance.fontScale", "应在 0.8~1.4（当前 " + String.valueOf(fs) + "）");
        }
        Object rd = ap.opt("radius");
        if (rd != null) {
            if (num(rd, 0, 32)) radius = Integer.valueOf((int) Math.round(((Number) rd).doubleValue()));
            else revert("$.appearance.radius", "应在 0~32（当前 " + String.valueOf(rd) + "）");
        }
        Object mo = ap.opt("mono");
        if (mo != null) {
            if (mo instanceof Boolean) mono = ((Boolean) mo).booleanValue();
            else revert("$.appearance.mono", "应为 true / false");
        }
        Object ca = ap.opt("cardsAlpha");
        if (ca != null) {
            if (num(ca, 0, 1)) cardsAlpha = Float.valueOf(((Number) ca).floatValue());
            else revert("$.appearance.cardsAlpha", "应在 0~1（当前 " + String.valueOf(ca) + "）");
        }
        Object lg = ap.opt("logo");
        if (lg != null) {
            if (lg instanceof String && IMG_REF_RE.matcher((String) lg).matches()) logo = (String) lg;
            else revert("$.appearance.logo", "应为图片文件名（png/jpg/jpeg/webp）或 /sdcard/… 路径");
        }
        Object st = null;
        if (ap.has("colors") && ap.opt("colors") instanceof JSONObject) {
            st = ((JSONObject) ap.opt("colors")).opt("statusBar");
        }
        if (st != null) {
            if ("auto".equals(st)) {
                statusBarAny = 0;
            } else if (st instanceof String && COLOR_RE.matcher((String) st).matches()) {
                try { statusBarAny = parseColor((String) st); }
                catch (Throwable e) { revert("$.appearance.colors.statusBar", "颜色无法解析"); }
            } else {
                revert("$.appearance.colors.statusBar", "应为 auto 或 #rgb/#rrggbb/#aarrggbb");
            }
        }

        // background
        if (ap.has("background")) {
            Object bv = ap.opt("background");
            if (!(bv instanceof JSONObject)) {
                revert("$.appearance.background", "应为对象");
            } else {
                JSONObject b = (JSONObject) bv;
                Bg bg = new Bg();
                boolean hasImage = false;
                for (Iterator<String> it = b.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (!in(BG_KEYS, k)) warn("$.appearance.background." + k + " 是未知字段（已忽略）");
                }
                Object im = b.opt("image");
                if (im != null) {
                    if (im instanceof String && IMG_REF_RE.matcher((String) im).matches()) {
                        bg.image = (String) im;
                        hasImage = true;
                    } else {
                        revert("$.appearance.background.image", "应为图片文件名或 /sdcard/… 路径");
                    }
                }
                Object fit = b.opt("fit");
                if (fit != null) {
                    if (fit instanceof String && in(FIT_VALUES, (String) fit)) bg.fit = (String) fit;
                    else revert("$.appearance.background.fit", "应为 cover/contain/stretch/tile/center");
                }
                Object an = b.opt("anchor");
                if (an != null) {
                    if (an instanceof String && in(ANCHOR_VALUES, (String) an)) bg.anchor = (String) an;
                    else revert("$.appearance.background.anchor", "应为 center/top/bottom/left/right/四个角");
                }
                Object op = b.opt("opacity");
                if (op != null) {
                    if (num(op, 0, 1)) bg.opacity = ((Number) op).floatValue();
                    else revert("$.appearance.background.opacity", "应在 0~1");
                }
                Object dm = b.opt("dim");
                if (dm != null) {
                    if (num(dm, 0, 1)) bg.dim = ((Number) dm).floatValue();
                    else revert("$.appearance.background.dim", "应在 0~1");
                }
                Object bl = b.opt("blur");
                if (bl != null) {
                    if (num(bl, 0, 25)) bg.blur = (int) Math.round(((Number) bl).doubleValue());
                    else revert("$.appearance.background.blur", "应在 0~25");
                }
                Object px = b.opt("parallax");
                if (px != null) {
                    if (px instanceof Boolean) bg.parallax = ((Boolean) px).booleanValue();
                    else revert("$.appearance.background.parallax", "应为 true / false");
                }
                if (hasImage) {
                    background = bg;
                    if (bg.blur > 0 && android.os.Build.VERSION.SDK_INT < 31) {
                        notes.add("background.blur 只在 Android 12+ 生效，本机已忽略（其余项照常生效）");
                    }
                    if (bg.dim == 0f && (cardsAlpha == null || cardsAlpha.floatValue() == 1f)) {
                        warn("背景图 + dim=0 + cardsAlpha=1：文字可能看不清（风格选择，仅提醒）");
                    }
                } else if (b.has("image")) {
                    // image 非法已在上面记过一次，这里不再重复
                } else {
                    notes.add("appearance.background 没给 image，本轮不生效（纯色底）");
                }
            }
        }

        // ---- layout：内置卡片的显隐·顺序·默认页（第 4 步，v1.17.5 起生效）----
        Object lv = o.opt("layout");
        if (lv != null) {
            if (!(lv instanceof JSONObject)) {
                revert("$.layout", "应为对象");
            } else {
                JSONObject lay = (JSONObject) lv;
                for (Iterator<String> it = lay.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (!in(LAY_KEYS, k)) warn("$.layout." + k + " 是未知字段（已忽略）");
                }
                Object ord = lay.opt("order");
                if (ord != null) {
                    if (ord instanceof org.json.JSONArray) {
                        org.json.JSONArray arr = (org.json.JSONArray) ord;
                        java.util.List<String> ids = new ArrayList<String>();
                        for (int i = 0; i < arr.length(); i++) {
                            String id = arr.optString(i, null);
                            if (id != null && in(CARD_IDS, id)) {
                                if (!ids.contains(id)) ids.add(id);
                            } else {
                                revert("$.layout.order[" + i + "]", "未知卡片 id \"" + id + "\"");
                            }
                        }
                        if (!ids.isEmpty()) cardOrder = ids;
                    } else {
                        revert("$.layout.order", "应为数组");
                    }
                }
                Object hid = lay.opt("hidden");
                if (hid != null) {
                    if (hid instanceof org.json.JSONArray) {
                        org.json.JSONArray arr = (org.json.JSONArray) hid;
                        for (int i = 0; i < arr.length(); i++) {
                            String id = arr.optString(i, null);
                            if (id == null || !in(CARD_IDS, id)) {
                                revert("$.layout.hidden[" + i + "]", "未知卡片 id \"" + id + "\"");
                            } else if (in(KEEP_CARDS, id)) {
                                warn("$.layout.hidden[" + i + "]：\"" + id + "\" 是救援面的一部分，不可移除 → 已忽略");
                            } else if (!hiddenCards.contains(id)) {
                                hiddenCards.add(id);
                            }
                        }
                    } else {
                        revert("$.layout.hidden", "应为数组");
                    }
                }
                Object dpg = lay.opt("defaultPage");
                if (dpg != null) {
                    if (num(dpg, 0, 9)) defaultPage = Integer.valueOf(((Number) dpg).intValue());
                    else revert("$.layout.defaultPage", "应为 0~9 的整数（当前 " + String.valueOf(dpg) + "）");
                }
                Object dop = lay.opt("detailOpen");
                if (dop != null) {
                    if (dop instanceof Boolean) detailOpen = (Boolean) dop;
                    else revert("$.layout.detailOpen", "应为 true / false");
                }
                Object cmp = lay.opt("compact");
                if (cmp != null) {
                    if (cmp instanceof Boolean) compact = ((Boolean) cmp).booleanValue();
                    else revert("$.layout.compact", "应为 true / false");
                }
                Object pgv = lay.opt("pages");
                if (pgv != null) {
                    if (!(pgv instanceof org.json.JSONArray)) {
                        revert("$.layout.pages", "应为数组");
                    } else {
                        org.json.JSONArray arr = (org.json.JSONArray) pgv;
                        java.util.List<Page> list = new ArrayList<Page>();
                        for (int i = 0; i < arr.length(); i++) {
                            Object po = arr.opt(i);
                            if (!(po instanceof JSONObject)) {
                                revert("$.layout.pages[" + i + "]", "每页应为对象");
                                continue;
                            }
                            JSONObject pj = (JSONObject) po;
                            Page pg = new Page();
                            pg.id = optStr(pj, "id", 24);
                            pg.title = optStr(pj, "title", 24);
                            if (pg.id == null || pg.id.length() == 0) {
                                revert("$.layout.pages[" + i + "].id", "每页必须有 id");
                                continue;
                            }
                            org.json.JSONArray kids = pj.optJSONArray("children");
                            if (kids == null) {
                                revert("$.layout.pages[" + i + "].children", "每页必须有 children");
                                continue;
                            }
                            int rawKids = kids.length();
                            for (int k = 0; k < rawKids; k++) {
                                Node nd = parseNode(kids.optJSONObject(k),
                                        "$.layout.pages[" + i + "].children[" + k + "]", 0);
                                if (nd != null) pg.children.add(nd);
                            }
                            android.util.Log.i("DSHConsoleTheme", "page[" + i + "] id=" + pg.id
                                    + " 原始节点 " + rawKids + " → 采用 " + pg.children.size());
                            list.add(pg);
                        }
                        if (!list.isEmpty()) {
                            pages = list;
                            notes.add("layout.pages：" + list.size() + " 页自定义布局（第 1 页 = 主控台；"
                                    + "其余页在主控台底部自动生成入口行）");
                            // 救援面安全网：首页里必须有 rescue.buttons 与 theme.card（§7.4 唯一硬约束）
                            if (!nodeHasBuiltin(list.get(0).children, "rescue.buttons")) {
                                warn("layout.pages[0] 里没有 rescue.buttons（救援入口）→ 已自动补在主控台末尾");
                            }
                            if (!nodeHasBuiltin(list.get(0).children, "theme.card")) {
                                warn("layout.pages[0] 里没有 theme.card（换主题入口）→ 已自动补在主控台末尾");
                            }
                        }
                    }
                }
            }
        }

        // ---- text：文案覆盖（第 4 步，v1.17.5 起生效）----
        Object tv = o.opt("text");
        if (tv != null) {
            if (!(tv instanceof JSONObject)) {
                revert("$.text", "应为对象");
            } else {
                JSONObject tx = (JSONObject) tv;
                for (Iterator<String> it = tx.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Object v = tx.opt(k);
                    if (!TEXT_KEY_RE.matcher(k).matches()) {
                        revert("$.text." + k, "键名应形如 card.extract / btn.engine.start / title.brand");
                        continue;
                    }
                    if (!(v instanceof String)) {
                        revert("$.text." + k, "文案应为字符串");
                        continue;
                    }
                    String sval = (String) v;
                    if (sval.length() > MAX_TEXT) {
                        revert("$.text." + k, "文案应 ≤" + MAX_TEXT + " 字（当前 " + sval.length() + "）");
                        continue;
                    }
                    texts.put(k, sval);
                }
            }
        }

        // ---- actions：自定义按钮（v1.17.7 起生效；可执行动作点的时候要逐个确认）----
        Object acv = o.opt("actions");
        if (acv != null) {
            if (!(acv instanceof org.json.JSONArray)) {
                revert("$.actions", "应为数组");
            } else {
                org.json.JSONArray arr = (org.json.JSONArray) acv;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a == null) {
                        revert("$.actions[" + i + "]", "应为对象");
                        continue;
                    }
                    Act act = parseAction(a, "$.actions[" + i + "]");
                    if (act != null) actions.add(act);
                }
                if (!actions.isEmpty()) {
                    notes.add("自定义按钮 " + actions.size() + " 个：主控台会多出一张「自定义按钮」卡片");
                    for (int i = 0; i < actions.size(); i++) {
                        Act a = actions.get(i);
                        if (a.executable) execActions.add(a.type + "：" + a.label);
                    }
                    if (!execActions.isEmpty()) {
                        notes.add("其中 " + execActions.size() + " 个是可执行动作（shell/http/intent/prompt）："
                                + "点按钮时会**先弹确认**，导入时也请自行确认来源");
                    }
                }
            }
        }
        if (o.has("behavior")) notes.add("behavior 尚未生效（只解析）");
    }

    private void checkColors(JSONObject obj, String path, Map<String, Integer> into) {
        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = obj.opt(k);
            if ("statusBar".equals(k)) continue;   // statusBar 只在 appearance.colors 里生效
            if (!in(COLOR_KEYS, k)) {
                warn(path + "." + k + " 是未知字段（已忽略）");
                continue;
            }
            if (v instanceof String && COLOR_RE.matcher((String) v).matches()) {
                try {
                    into.put(k, Integer.valueOf(parseColor((String) v)));
                } catch (Throwable e) {
                    revert(path + "." + k, "不是合法颜色（支持 #rgb/#rrggbb/#aarrggbb）");
                }
            } else {
                revert(path + "." + k, "不是合法颜色（支持 #rgb/#rrggbb/#aarrggbb）");
            }
        }
    }

    /** 递归解析一个控件树节点；非法就记一条并返回 null。 */
    private Node parseNode(JSONObject o, String path, int depth) {
        if (o == null) {
            revert(path, "节点应为对象");
            return null;
        }
        if (depth > MAX_NODE_DEPTH) {
            revert(path, "控件树嵌套过深（>" + MAX_NODE_DEPTH + " 层）");
            return null;
        }
        Node n = new Node();
        n.type = o.optString("type", "");
        if (!in(NODE_TYPES, n.type)) {
            revert(path + ".type", "未知节点类型 \"" + n.type + "\"");
            return null;
        }
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!in(NODE_KEYS, k)) warn(path + "." + k + " 是未知字段（已忽略）");
        }
        n.id = optStr(o, "id", 40);
        n.text = optStr(o, "text", 200);
        n.align = optStr(o, "align", 10);
        n.style = optStr(o, "style", 12);
        n.color = optStr(o, "color", 12);
        Object sz = o.opt("size");
        if (sz != null) {
            // spacer 的 size 是"高度 dp"（1~200）；文字/图片仍按 8~28 —— 与校验器同一套
            if ("spacer".equals(n.type)) {
                if (num(sz, 1, 200)) n.size = Integer.valueOf(((Number) sz).intValue());
                else revert(path + ".size", "spacer 的高度应在 1~200 dp（当前 " + String.valueOf(sz) + "）");
            } else if (num(sz, 8, 28)) {
                n.size = Integer.valueOf(((Number) sz).intValue());
            } else {
                revert(path + ".size", "应在 8~28（当前 " + String.valueOf(sz) + "）");
            }
        }
        Object wt = o.opt("weight");
        if (wt != null) {
            if (num(wt, 0, 1)) n.weight = Float.valueOf(((Number) wt).floatValue());
            else revert(path + ".weight", "应在 0~1（当前 " + String.valueOf(wt) + "）");
        }
        Object wd = o.opt("width");
        if (wd != null) {
            if (num(wd, 1, 2000)) n.width = Integer.valueOf(((Number) wd).intValue());
            else revert(path + ".width", "应为 1~2000 的 dp 值");
        }
        Object vs = o.opt("visible");
        if (vs != null) {
            if (vs instanceof Boolean) n.visible = (Boolean) vs;
            else revert(path + ".visible", "应为 true / false");
        }
        if (n.align != null && !in(ALIGN_VALUES, n.align)) {
            revert(path + ".align", "应为 left / center / right");
            n.align = null;
        }
        if (n.style != null && !in(STYLE_VALUES, n.style)) {
            revert(path + ".style", "应为 primary / secondary");
            n.style = null;
        }
        if (n.color != null && !in(SEMANTIC_COLORS, n.color)
                && !COLOR_RE.matcher(n.color).matches()) {
            revert(path + ".color", "应为 text|sub|accent|green|red 或 #rgb/#rrggbb/#aarrggbb");
            n.color = null;
        }
        if ("builtin".equals(n.type)) {
            if (n.id == null || !in(BUILTIN_IDS, n.id)) {
                revert(path + ".id", "未知积木 id \"" + n.id + "\"");
                return null;
            }
        }
        Object ao = o.opt("action");
        if (ao != null) {
            if (!(ao instanceof JSONObject)) {
                revert(path + ".action", "应为对象（组合动作数组暂不支持）");
            } else {
                n.action = parseAction((JSONObject) ao, path + ".action");
            }
        }
        org.json.JSONArray kids = o.optJSONArray("children");
        if (kids != null) {
            for (int i = 0; i < kids.length(); i++) {
                Node c = parseNode(kids.optJSONObject(i), path + ".children[" + i + "]", depth + 1);
                if (c != null) n.children.add(c);
            }
        }
        return n;
    }

    /** 递归找：这棵树里有没有某个积木（救援面安全网用）。 */
    static boolean nodeHasBuiltin(java.util.List<Node> nodes, String builtinId) {
        if (nodes == null) return false;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if ("builtin".equals(n.type) && builtinId.equals(n.id)) return true;
            if (nodeHasBuiltin(n.children, builtinId)) return true;
        }
        return false;
    }

    /** 解析一条 action；参数不全/类型不认识 → 记一条回退并返回 null（不影响其它按钮）。 */
    private Act parseAction(JSONObject a, String path) {
        Act t = new Act();
        t.type = a.optString("type", "");
        if (!in(ACTION_ALL, t.type)) {
            revert(path + ".type", "未知动作类型 \"" + t.type + "\"");
            return null;
        }
        t.id = optStr(a, "id", 40);
        t.label = optStr(a, "label", 40);
        if (t.label == null || t.label.length() == 0) t.label = actionDefaultLabel(t.type);
        t.cmd = optStr(a, "cmd", 1000);
        t.url = optStr(a, "url", 2000);
        t.action = optStr(a, "action", 200);
        t.data = optStr(a, "data", 2000);
        t.pkg = optStr(a, "package", 200);
        t.text = optStr(a, "text", 4000);
        t.copy = optStr(a, "copy", 40);
        t.method = optStr(a, "method", 10);
        t.body = optStr(a, "body", 4000);
        Object o1 = a.opt("confirm");        if (o1 instanceof Boolean) t.confirm = ((Boolean) o1).booleanValue();
        Object o2 = a.opt("privileged");     if (o2 instanceof Boolean) t.privileged = ((Boolean) o2).booleanValue();
        Object o3 = a.opt("external");       if (o3 instanceof Boolean) t.external = ((Boolean) o3).booleanValue();
        for (Iterator<String> it = a.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!in(ACT_KEYS, k)) warn(path + "." + k + " 是未知字段（已忽略）");
        }
        // 必填参数 —— 与离线校验器 check_action 同一套判据
        if ("shell".equals(t.type) && (t.cmd == null || t.cmd.length() == 0)) {
            revert(path + ".cmd", "shell 动作必须给 cmd");
            return null;
        }
        if ("url".equals(t.type)
                && (t.url == null || !(t.url.startsWith("http://") || t.url.startsWith("https://")))) {
            revert(path + ".url", "url 动作必须是 http(s) 地址");
            return null;
        }
        if ("http".equals(t.type) && (t.url == null || !t.url.startsWith("http"))) {
            revert(path + ".url", "http 动作必须给 url");
            return null;
        }
        if ("intent".equals(t.type)
                && (t.action == null || t.action.length() == 0) && (t.data == null || t.data.length() == 0)) {
            revert(path, "intent 动作至少要给 action 或 data");
            return null;
        }
        if ("prompt".equals(t.type) && (t.text == null || t.text.length() == 0)) {
            revert(path + ".text", "prompt 动作必须给 text");
            return null;
        }
        t.executable = in(EXEC_ACTIONS, t.type);
        return t;
    }

    private static String actionDefaultLabel(String type) {
        if ("engine.start".equals(type)) return "启动引擎";
        if ("engine.restart".equals(type)) return "重启引擎";
        if ("engine.stop".equals(type)) return "停止引擎";
        if ("engine.openUi".equals(type)) return "打开主界面";
        if ("extract.run".equals(type)) return "解压文件";
        if ("extract.verify".equals(type)) return "校验文件";
        if ("perm.open".equals(type)) return "授予权限";
        if ("log.view".equals(type)) return "查看日志";
        if ("log.share".equals(type)) return "分享日志";
        if ("log.clear".equals(type)) return "清空日志";
        if ("theme.reload".equals(type)) return "重新加载主题";
        if ("theme.export".equals(type)) return "导出主题包";
        if ("theme.import".equals(type)) return "导入主题包";
        if ("theme.reset".equals(type)) return "恢复默认主题";
        if ("shell".equals(type)) return "执行命令";
        if ("url".equals(type)) return "打开网址";
        if ("intent".equals(type)) return "打开应用";
        if ("prompt".equals(type)) return "发给 AI";
        if ("clipboard".equals(type)) return "复制";
        if ("toast".equals(type)) return "提示";
        if ("settings".equals(type)) return "应用设置";
        if ("http".equals(type)) return "HTTP 请求";
        return type;
    }

    private void revert(String path, String why) {
        reverted++;
        warnings.add("[已回退] " + path + "：" + why);
    }

    private void warn(String msg) {
        warnings.add(msg);
    }

    // ==================== 查询 ====================

    /** 这一项有没有被**显式写过**（perScheme[方案] 或 colors）——决定要不要按底色推导。 */
    boolean hasColor(String key, boolean darkScheme) {
        Integer v = darkScheme ? darkColors.get(key) : lightColors.get(key);
        return v != null || colors.containsKey(key);
    }

    /** 取一个颜色：perScheme[当前方案] &gt; colors &gt; def（内置默认）。 */
    int color(String key, boolean darkScheme, int def) {
        Integer v = darkScheme ? darkColors.get(key) : lightColors.get(key);
        if (v == null) v = colors.get(key);
        return v == null ? def : v.intValue();
    }

    /** 文案覆盖取值：没配 / 没这个键 → null（调用方用内置中文）。 */
    String text(String key) {
        return texts.get(key);
    }

    /** 生效的文案覆盖条数（主题页显示状态用）。 */
    int textCount() {
        return texts.size();
    }

    /** 状态栏/导航栏底色：perScheme[方案].statusBar > colors.statusBar；0 = auto（由 App 按底色算）。 */
    int statusBarColor(boolean darkScheme) {
        int v = darkScheme ? statusBarDark : statusBarLight;
        return v != 0 ? v : statusBarAny;
    }

    /** 当前方案的深浅：由 appearance.dark 决定（follow → 交给 App 的 isDark()）。 */
    boolean prefersDark(boolean appDark) {
        if (darkMode == DARK_DARK) return true;
        if (darkMode == DARK_LIGHT) return false;
        return appDark;
    }

    /** 顶部黄条的一句话；null = 不需要提示。 */
    String bannerText() {
        if (fatal) return "主题配置有问题，已回退默认：" + (errors.isEmpty() ? "" : errors.get(0));
        if (reverted > 0) return "主题配置有 " + reverted + " 项已回退（详情）";
        return null;
    }

    /** 详情弹窗正文（诊断 + 当前生效值）。 */
    String detail() {
        StringBuilder sb = new StringBuilder();
        if (name != null && name.length() > 0) sb.append("主题：").append(name).append('\n');
        if (absent) sb.append("（没有 console.json，全部使用内置默认）\n");
        if (fatal) {
            sb.append("\n整体回退原因：\n");
            for (int i = 0; i < errors.size(); i++) sb.append("  ✗ ").append(errors.get(i)).append('\n');
        }
        if (!warnings.isEmpty()) {
            sb.append("\n警告 / 已回退的项：\n");
            for (int i = 0; i < warnings.size(); i++) sb.append("  · ").append(warnings.get(i)).append('\n');
        }
        if (!notes.isEmpty()) {
            sb.append("\n说明：\n");
            for (int i = 0; i < notes.size(); i++) sb.append("  · ").append(notes.get(i)).append('\n');
        }
        sb.append("\n当前生效（").append(darkMode == DARK_DARK ? "深色" : darkMode == DARK_LIGHT ? "浅色" : "跟随系统").append("）：")
          .append(" 字号×").append(trim(fontScale))
          .append(" · 圆角").append(radius == null ? "内置" : String.valueOf(radius))
          .append(" · 卡片不透明度").append(cardsAlpha == null ? "内置" : trim(cardsAlpha.floatValue()))
          .append(" · 等宽").append(mono ? "是" : "否");
        if (background != null) {
            sb.append("\n背景图：").append(background.image)
              .append("（fit=").append(background.fit)
              .append(" anchor=").append(background.anchor)
              .append(" opacity=").append(trim(background.opacity))
              .append(" dim=").append(trim(background.dim))
              .append(" blur=").append(background.blur)
              .append(" parallax=").append(background.parallax).append("）");
        }
        if (cardOrder != null || !hiddenCards.isEmpty() || compact
                || defaultPage != null || detailOpen != null) {
            sb.append("\n布局：");
            if (cardOrder != null) sb.append("order=").append(cardOrder);
            if (!hiddenCards.isEmpty()) sb.append(" hidden=").append(hiddenCards);
            if (compact) sb.append(" compact");
            if (defaultPage != null) sb.append(" defaultPage=").append(defaultPage);
            if (detailOpen != null) sb.append(" detailOpen=").append(detailOpen);
        }
        if (pages != null) {
            sb.append("\n自定义布局：").append(pages.size()).append(" 页");
            for (int i = 0; i < pages.size(); i++) {
                Page p = pages.get(i);
                sb.append("\n  · ").append(i == 0 ? "主控台" : ("第 " + (i + 1) + " 页"))
                  .append("「").append(p.title == null ? p.id : p.title).append("」")
                  .append(p.children.size()).append(" 个顶层节点");
            }
        }
        if (!execActions.isEmpty()) {
            sb.append("\n可执行动作（本版不执行）：");
            for (int i = 0; i < execActions.size(); i++) sb.append("\n  · ").append(execActions.get(i));
        }
        if (!texts.isEmpty()) {
            sb.append("\n文案覆盖（").append(texts.size()).append(" 条）：");
            int n = 0;
            for (Map.Entry<String, String> e : texts.entrySet()) {
                if (n++ >= 6) { sb.append(" …"); break; }
                sb.append("\n  · ").append(e.getKey()).append(" = ").append(e.getValue());
            }
        }
        return sb.toString();
    }

    // ==================== 小工具 ====================

    private static boolean in(String[] arr, String v) {
        if (v == null) return false;
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return true;
        return false;
    }

    private static boolean num(Object v, double lo, double hi) {
        if (!(v instanceof Number) || v instanceof Boolean) return false;
        double d = ((Number) v).doubleValue();
        return d >= lo && d <= hi;
    }

    /** JSON 里的字符串字段：超长直接截断（主题名只是显示用）。 */
    private static String optStr(JSONObject o, String key, int max) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return null;
        String s = (String) v;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String trim(float f) {
        String s = String.valueOf(Math.round(f * 100f) / 100f);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }

    private static String stripBom(String s) {
        return (s != null && s.length() > 0 && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
    }

    /**
     * 颜色字符串 → int。
     * ⚠ Android 的 Color.parseColor 只认 #rrggbb 与 #aarrggbb（长度 7/9），
     * 而本规范允许 #rgb —— 真机实测：`"accent": "#0ff"` 会被 parseColor 抛异常、
     * 进而被判成"非法颜色并回退"，与离线校验器的结论矛盾。所以这里先展开 #rgb。
     */
    private static int parseColor(String s) throws IllegalArgumentException {
        String v = s;
        if (v != null && v.length() == 4) {   // #rgb → #rrggbb
            v = new StringBuilder("#")
                    .append(v.charAt(1)).append(v.charAt(1))
                    .append(v.charAt(2)).append(v.charAt(2))
                    .append(v.charAt(3)).append(v.charAt(3)).toString();
        }
        return Color.parseColor(v);
    }

    /** 字符偏移 → {行, 列}（1 基），用于把 JSON 语法错报到人能看懂的位置。 */
    private static int[] lineCol(String text, int offset) {
        int line = 1, col = 1;
        int n = Math.min(Math.max(offset, 0), text.length());
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') { line++; col = 1; } else col++;
        }
        return new int[]{line, col};
    }

    static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
