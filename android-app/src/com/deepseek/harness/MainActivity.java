package com.deepseek.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import rikka.shizuku.Shizuku;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;

public class MainActivity extends Activity {
    private static final String TAG = "DeepSeekHarness";
    // 引擎端口：固定默认端口（v1.5.4 起移除「端口冲突自动换端口」功能，用于排查慢启动是否与其相关）。
    // 共存版（Lite/抢先版）各用独立默认端口，靠包名隔离，不依赖动态切换。
    private int enginePort = 3080;
    private String homeUrl() { return "http://127.0.0.1:" + enginePort; }
    /** 0.1.5 起 web 首页需要一次性 token：引擎启动时会打印带 token 的 URL，
     *  首次必须用它访问（服务器随即下发签名 cookie，后续可回到普通地址），
     *  否则首页返回 401 "authentication required"。这里保存解析到的带 token URL。 */
    private volatile String engineTokenUrl = null;
    /** WebView 与健康探测应使用的地址：拿到 token 就用带 token 的，否则退回普通地址。 */
    private String webHomeUrl() {
        String u = engineTokenUrl;
        return (u != null && !u.isEmpty()) ? u : homeUrl();
    }
    static {
        // 0.1.5 的浏览器认证靠 cookie：带 token 访问首页会下发 dsh-auth-* cookie。
        // HttpURLConnection 默认不保存 cookie，装上全局 CookieManager 后，
        // App 自身的 HTTP 调用（含健康探测）才能像浏览器一样维持会话。
        try { java.net.CookieHandler.setDefault(new java.net.CookieManager()); } catch (Throwable ignored) {}
    }
    // bin.js 相对 dshroot 目录的路径（dshroot 可能位于外部公共目录或内部 fallback）
    private static final String REL_BINJS = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js";
    // 外部 dshroot 公共目录名（挂在 /sdcard 下，卸载不丢；node 二进制/凭证仍留内部）
    // 外部公共根目录不要用常量：它必须按包名派生（正式版 / Lite / 兼容版共存时互不干扰）。
    // 曾硬编码为 "DeepSeekHarness" → Lite/兼容版会写到正式版的外部目录（vscreen jar、外部回退 dshroot
    // 都会串到别的版本上）。统一用 pkgRoot()（见下）。
    /** v1.23：悬浮窗气泡消息的字数上限 —— 中文与非中文字符**分开计**。
     *  为什么分开：气泡是**单行**（setSingleLine + TruncateAt.END，最宽 240dp），
     *  一个汉字约占一个半角字符两倍的宽度，所以两类的合理上限不同；
     *  超过上限时 App 侧按上限截断（并回真实计数），工具侧也会先行拦截并给出明确报错。 */
    public static final int BUBBLE_MAX_CJK = 20;     // 中日韩字符（含全角标点）上限
    public static final int BUBBLE_MAX_ASCII = 40;   // 其它字符（拉丁 / 数字 / 半角标点 / emoji 等）上限

    /** 是否算"中文"（中日韩字符 + 全角标点 / CJK 符号）。按码点判断。 */
    private static boolean isCjkCodePoint(int cp) {
        try {
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                    || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
                    || s == Character.UnicodeScript.BOPOMOFO) {
                return true;
            }
        } catch (Throwable ignored) {}
        // 全角标点（，。！？：；）与 CJK 符号（「」…）也按"中文"计
        return (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    /**
     * v1.23 第①道闸：按**字数上限**裁剪（中文 ≤ {@link #BUBBLE_MAX_CJK}、其它 ≤ {@link #BUBBLE_MAX_ASCII}，
     * 两类分开计、互不挤占）。这是可预期、且**写进工具说明**的规则。
     * 第②道闸（按真实渲染宽度裁）在 OverlayService.fitBubbleWidth —— 因为气泡是那边渲染的、
     * 字号（fontScale）也是那边生效的（实测：主进程与服务资源字号可能不一致）。
     */
    private static String fitBubbleText(String text, int[] out) {
        if (text == null) text = "";
        int cjk = 0;
        int ascii = 0;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            boolean isCjk = isCjkCodePoint(cp);
            if (isCjk) {
                if (cjk >= BUBBLE_MAX_CJK) break;
                cjk++;
            } else {
                if (ascii >= BUBBLE_MAX_ASCII) break;
                ascii++;
            }
            sb.appendCodePoint(cp);
            i += n;
        }
        countBubble(sb.toString(), out);
        return sb.toString();
    }

    /** 数一遍中/非中文字符数（与 fitBubbleText 同一口径）。 */
    private static void countBubble(String s, int[] out) {
        int cjk = 0;
        int ascii = 0;
        if (s != null) {
            for (int k = 0; k < s.length(); ) {
                int cp = s.codePointAt(k);
                if (isCjkCodePoint(cp)) cjk++; else ascii++;
                k += Character.charCount(cp);
            }
        }
        if (out != null && out.length >= 2) {
            out[0] = cjk;
            out[1] = ascii;
        }
    }

    // 官方维护、需随 APK 更新的路径前缀：即使外部 dshroot 已有同名文件也强制覆盖
    // （避免"保留 AI 修改"策略挡住官方修复，例如 shizuku 插件的三层补丁）。
    private static final String[] FORCE_OVERWRITE_PREFIXES = {
        // v1.21（真机血泪教训）：**运行时的二进制与库必须随 APK 覆盖**。
        // 曾经把 x86_64 的 libz/libssl/libcrypto 打进 payload，真机 arm64 上 node 一启动就崩：
        //   libz.so is for EM_X86_64 (62) instead of EM_AARCH64 (183)
        // 而"已存在文件不覆盖"策略让**换 APK 也修不回来**（只能卸载重装，用户实测又踩了一次）。
        // runtime/ 是我们的运行时（node/库/配置），不是用户数据，列入强制覆盖：
        // 覆盖安装时坏库会被 APK 里的正确版本替换掉。
        "runtime/",
        // v1.21：内置插件 ds-harness-remote（vendor 进 payload 的包）。
        // 必须随 APK 覆盖 —— 实测：改掉默认服务器地址、重新打包、装机后设备上仍是旧文件
        // （payload 同步的"已存在文件永不覆盖"是保护 AI 运行时数据的策略，
        //  但这是我们自己的包；它落后时用户看到的就是"服务器地址没更新"）。
        "dshhome/profiles/web/node_modules/ds-harness-remote/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-shizuku/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-android/",
        // v1.9 虚拟屏插件：必须随 APK 覆盖（否则外部/旧 dshroot 里的旧版插件挡住更新 → "未知错误"）
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-vscreen/",
        // 内核补丁面：以下都是本项目改过或自研的包，必须随 APK 覆盖——
        // 同内核升级走 fast 同步（内核版本号没变）时不会补它们，旧文件会一直挡住修复。
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-bash-local/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/",
        // 本轮（v1.15.2）新增：三个内核补丁包，必须随 APK 覆盖
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-ptc-runtime-node/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-llm-deepseek/",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-accessibility/",
        // v1.19.0：AI 浏览器插件（必须随 APK 覆盖，否则旧副本挡住更新 → 工具看不到）
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-browser/",
        // v1.17.2：目录 fsync 容错补丁（EINVAL/ENOTSUP/EOPNOTSUPP 视为「该目录不支持持久化同步」而跳过）。
        // 必须加白名单——否则同内核版本下 fast 同步不会覆盖它，补丁形同没打
        // → 真机上发图 / read_image / android_see 仍报 `EINVAL: invalid argument, fsync`。
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-storage-json/",
        // v1.20：内置终端的原生依赖 node-pty。这不是上游 npm 包，而是我们塞进 payload 的
        // **Android arm64 预编译版**（@mmmbuto/node-pty-android-arm64，N-API，ABI 无关）。
        // 必须加白名单：否则同内核版本下 dshroot-add 只写"缺失文件"，上一版留下的纯 JS 替身
        // （同名 index.js / package.json）不会被覆盖 → 终端仍然没有真 PTY。
        "dshroot/lib/node_modules/node-pty/",
        // v1.3.x 核心 UI 改动（侧栏改造/插件按钮）必须随 APK 覆盖：
        // 否则旧版升级用户的外部 dshroot 保留旧 client.js → 页面仍是旧 UI（无竖屏适配）
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-layout/lib/client.js",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-cordis/lib/client.js",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/mobile.css",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/mobile.js",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html",
        // v1.20：payload/bin/* 全部由 build.sh 生成（bash 包装、pnpm/python/npm/git 的 wrapper），
        // 属于「官方维护」文件。必须随 APK 覆盖：否则 internal-patch 同步只写缺失文件，
        // 老设备会一直留着旧版 bin/bash（踩过：终端因 mksh 不认 bash 长选项而退出(1)）。
        "bin/",
        // v1.15.9：dsh-app-boot 打了「插件 warn 落盘」补丁（$DSH_HOME/logs/plugins.log）。
        // 必须加白名单，否则同内核版本下 fast 同步不会覆盖它，补丁形同没打。
        "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js",
        "dshroot/lib/node_modules/@deepseek-ai/dsh/package.json"
    };
    // 外部 dshroot 解压完成标记（App 在 dshroot 补齐后写入；清空/重置时随目录删除）。
    // 用于识别「解压中途被打断」：即使 REVISION 一致也强制补齐缺失文件。
    private static final String DSHROOT_COMPLETE = ".complete";
    private static final String PREFS = "dsh_setup";
    /**
     * v1.21（Q1 第 3 批）：常驻通知点按 → 进原生控制台。
     * EngineService 用它构造通知的 contentIntent；MainActivity 在 onCreate/onNewIntent 里消费。
     * （控制台的另一条入口是故障自动回退：引擎起不来 / WebView 加载失败。）
     */
    public static final String EXTRA_OPEN_CONSOLE = "open_console";
    private static final int REQ_STORAGE = 200;
    private static final int REQ_NOTIFICATION = 201;
    private static final int REQ_SHIZUKU = 300;
    /** v1.13：已发出 Shizuku 授权请求、等待结果（用于超时傅底判断）。 */
    private volatile boolean pendingShizukuReq = false;
    private static final int REQ_WORKSPACE_TREE = 400;
    private static final int REQ_FILE_CHOOSER = 500;
    /** 导入备份（SAF 选 zip 文件）。 */
    private static final int REQ_BACKUP_FILE = 600;
    /** 导入控制台主题包（SAF 选 zip 文件）。 */
    private static final int REQ_THEME_FILE = 700;

    // ---------- 安全模式 / 启动失败计数 ----------
    // 背景：引擎启动会解析 profile 的 patch 文件，一旦 YAML 语法坏掉（用户装的插件把配置写坏是常见路径），
    // 内核会直接拒绝启动（实测：failed to parse overlay ...: YAMLException: duplicated mapping key），
    // 而“修”这件事本身需要引擎跑起来让 AI 去改文件 —— 于是形成死结，只能清数据。
    // 安全模式的做法：把 profile 的用户层整体旁置 + 恢复出厂文件，「不动用户数据」（会话/凭证/设置全保留）。
    private static final String KEY_SAFE_MODE = "safe_mode_active";
    private static final String KEY_BOOT_FAILS = "engine_boot_failures";
    /** 连续启动失败多少次要提醒用户可用安全模式。 */
    private static final int BOOT_FAIL_HINT_AT = 2;

    // 悬浮窗前后台联动：App 在前台时隐藏悬浮窗（不挡界面），退后台时显示（随时可查引擎状态）。
    // 由 onStart/onStop 维护；OverlayService 启动时按此标志决定初始可见性。
    public static volatile boolean overlayForeground = true;

    private WebView webView;
    /** v1.13.11：页面实测背景色（0 = 还没取到，壳底色用 cBg() 兜底）。见 refreshPageBackground()。 */
    private volatile int pageBgColor = 0;

    // ---- v1.12 控制台（冷启动首页，原生界面）----
    private FrameLayout engineRoot;                // WebView + 启动浮层 + 控制台的共同根容器
    private ScrollView consoleLayer;               // 控制台覆盖层
    /** 上一次渲染的页号：页号变了就把 ScrollView 归零（换页必须回页首，见 renderConsole）。 */
    private int conLastRenderedPage = Integer.MIN_VALUE;

    // CONSOLE_THEME_PATCH_v1174
    // ---- v1.17.4 控制台主题包（第 1~3 步：资产落地 / 配置地基 / 外观接入）----
    // 配置在 /sdcard/<包名目录>/console/console.json；坏配置一律回退内置默认（见 ConsoleTheme）。
    // 不写配置时 conTheme == null，下面所有 con*() 都返回内置值 → 视觉与本功能之前完全一致。
    private ConsoleTheme conTheme = null;           // 当前生效配置（null = 全默认）
    private boolean conThemeLoaded = false;         // 本进程是否已读过一次
    private String conThemeStamp = null;            // console.json 的 "mtime:size"（热重载判据）
    private boolean conThemeAssetsSeeded = false;   // 四件套本进程已尝试解到 /sdcard
    private FrameLayout consoleLayerBox;            // 控制台外层（背景图 + 压暗层 + 滚动层）
    private ImageView conBgImage;                   // 控制台背景图
    private View conBgDim;                          // 背景压暗层
    private android.graphics.Bitmap conBgBitmap = null;   // 背景图解码缓存（避免每秒重解码）
    private String conBgBitmapRef = null;
    private android.graphics.Bitmap conLogoBitmap = null; // logo 解码缓存
    private String conLogoBitmapRef = null;

    private LinearLayout consoleBody;              // 当前页内容容器
    private int consolePage = 0;                   // 0 控制台 / 1 权限 / 2 插件 / 3 日志
    private boolean consoleVisible = false;
    private boolean consoleDetailOpen = false;      //「已解压」那一行是否展开
    private boolean extracting = false;            // 正在解压（控制台进度）
    private boolean extractOnlyMode = false;       // true：本次只解压，不起引擎
    private boolean filesPreparedThisBoot = false; // 本进程内文件已就绪 → 跳过重复解压
    private volatile boolean starting = false;      // 引擎启动中（跨线程读写：启动流程在后台线程、刷新在主线程）
    private long engineStartTs = 0L;
    private String conExtractMsg = null;
    private String conFilesSummary = null;         //「24,806 个文件 · 214 MB」后台算一次
    private Handler conTick;
    private TextView conExState, conExMeta, conEnState, conEnMeta, conFoot;
    private View conBar, conFill, conSpacer;
    private Button conExBtn, conEnBtn, conEnRestart, conEnStop;
    private View conDetailBox;
    private View dialogOverlay = null;   // 自绘弹窗的遮罩层（替代系统 AlertDialog）
    private final Runnable consoleTick = new Runnable() {
        @Override public void run() {
            if (!consoleVisible) return;
            // v1.19.6 加固：原来 conThemeTick()/refreshConsole() 里任何一个抛异常，
            // 下面的 postDelayed 就永远执行不到 → **整条每秒刷新链永久死掉**，
            // 之后 console.json 再改也不会热重载（界面停在旧样式，只有重启 App 才恢复）。
            // 现在异常吞掉并记日志，链条照常续上；"控制台不可见就停"的原语义保持不变（不额外耗电）。
            try {
                conThemeTick();   // v1.17.4：每秒查一次 console.json 变没变（变了就整页重渲染）
                refreshConsole();
            } catch (Throwable t) {
                Log.w(TAG, "console tick: " + t);
            }
            if (conTick != null) conTick.postDelayed(this, 1000);
        }
    };
    // 网页 <input type="file"> 选完文件后的回调（见 onShowFileChooser）
    private ValueCallback<Uri[]> fileChooserCallback;
    private TextView statusView;
    private ProgressBar progressBar;
    private ImageView splashLogo;
    private TextView splashBrand;
    /** v1.21：启动页副提示（"首次启动需要多等一会儿…"），进主界面时与其它启动页元素一起隐藏。 */
    private TextView splashHint;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 运行时确定的 dshroot 目录（外部公共目录优先，失败回退内部 files/payload/dshroot）
    private File dshrootDir = null;
    private boolean watchdogStarted = false;
    private long lastRespawnAt = 0L;
    private volatile boolean engineStartAborted = false;   // v1.13：「停止」打断在飞的启动等待（否则状态栏一直计时到 90s 超时）
    private volatile boolean engineStoppedByUser = false;  // v1.13：用户主动停止 → 看门狗不得再自动拉起引擎
    // v1.13：引擎存活探测结果缓存。控制台的每秒刷新在主线程跑，一旦直接做 HTTP 探测就会抛
    // NetworkOnMainThreadException（被 catch 吞掉）→ 引擎明明在跑也永远判“未启动”
    // → 用户再点一次「启动引擎」就又拉起一个 node → EADDRINUSE（用户实测的“启动一会又变回去”）。
    private volatile boolean engineAliveCached = false;
    private volatile long engineProbeAt = 0L;
    private volatile boolean engineProbeBusy = false;
    private volatile boolean notifyServerStarted = false;  // v1.13：通知通道幂等启动标记
    // 引擎 node 进程
    private Process nodeProcess = null;
    // v1.5.2 慢启动修复：本次启动走了「快速同步」（同内核升级，只补白名单+REVISION）。
    // 若引擎启动超时，用它触发一次全量补齐（防止快速路径漏掉缺失文件）。
    private volatile boolean fastSyncedThisBoot = false;

    // 权限界面
    private final List<PermRow> permRows = new ArrayList<>();
    private File rishDex;
    private File vscreenDex;
    private boolean pendingVscreenExtract;
    private static volatile IRemoteProcess vscreenProc;
    private long lastVscreenEnsureTs = 0;
    // AI 工作区（可选）：外部共享存储目录，传给引擎作为 bash/文件工具的工作根目录
    private TextView workspaceDescView;
    private TextView conWorkspaceDesc;             // 控制台「权限」页的工作区状态行（与引导完成页共用同一套选择逻辑）


    private interface StatusProvider { boolean granted(); }
    private static class PermRow {
        TextView status;
        StatusProvider provider;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v1.13.11：主题必须先定（且必须在 super.onCreate 之前）。
        // WebView 的 prefers-color-scheme 只认主题的 android:isLightTheme，不看系统 uiMode
        // （详见 res/values/styles.xml 里 AppTheme.Light 的注释）——
        // v1.13.12 起主题可在控制台选「跟随系统/浅色/深色」，不再只能跟系统。
        setTheme(themePrefersDark() ? R.style.AppTheme : R.style.AppTheme_Light);
        super.onCreate(savedInstanceState);
        // v1.21：正在「完全退出」——若被系统/通知重新拉起，直接关掉，别再起界面和引擎。
        // 只有**用户主动点图标**（ACTION_MAIN）才算重新使用：清掉标记正常启动。
        if (shutdownPending(this)) {
            boolean userLaunch = getIntent() != null
                    && Intent.ACTION_MAIN.equals(getIntent().getAction());
            if (!userLaunch) {
                Log.i(TAG, "onCreate: 正在完全退出，忽略本次启动");
                finish();
                return;
            }
            Log.i(TAG, "onCreate: 用户主动启动，清除退出标记");
            clearShutdownPendingOnUserLaunch();
        }
        enginePort = defaultEnginePort(this); // 三版本各自独立端口（见 defaultEnginePort）
        // v1.17.4：控制台主题包配置（/sdcard/<包名目录>/console/console.json）。
        // 放在 applyStatusBar() 之前 —— statusBar 颜色要参与状态栏/导航栏取色；坏配置在 load() 里自动回退。
        conThemeReloadIfChanged(true);
        ensureConsoleThemeAssets();
        ensureAgentSkills(); // D1：把内置「自我定制」技能落到 <filesDir>/.agents/skills（引擎默认扫描目录）
        applyStatusBar(); // 状态栏/导航栏底色跟随 App 主题（浅色模式不再是一条黑条）
        installCrashHandler();
        checkAbiCompat(); // ② ABI 检测：非 arm64 设备引擎可能无法运行，弹提示
        // ④ 电池优化引导：被限制时提示（挂后台可能被杀）
        // v1.21（真机报障）：**首启还没走完向导时不弹** —— 否则会和权限引导页叠在一起
        //（用户看到"第一页就对不上"，而且这里的「去设置」直接进系统电池优化页，
        //  使后面引导页的「忽略电池优化」显示成"已配置"）。向导里的 p7 会专门引导这一项。
        try {
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("setup_done", false)) {
                checkBatteryOptimization();
            }
        } catch (Throwable ignored) {}
        // v1.12：不再在启动时自动检查更新（用户要求）；改为控制台底部的「检查更新」手动触发。

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(100);
        webView.setBackgroundColor(chromeBg()); // v1.13.11：跟随主题（浅色模式下不再是深色闪屏）
        checkWebViewCompat(); // WebView 兼容检测：老内核提示引导（DSH 前端需 Chromium 80+）
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            private int errorRetries = 0;

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                         android.webkit.WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                // v1.21（Q1 第 3 批）：区分"还在等引擎"和"真出不来"。
                //   引擎启动中 → ERR_CONNECTION_REFUSED 属正常，重试到引擎就绪（每次 2.5s）；
                //   连续失败超过预算 → 不是等引擎，而是真加载不出来 → 落控制台（那里能看日志/重启）。
                if (errorRetries < WEBVIEW_RETRY_BUDGET) {
                    errorRetries++;
                    final WebView wv = view;
                    view.postDelayed(new Runnable() {
                        @Override public void run() { wv.loadUrl(webHomeUrl()); }
                    }, 2500L);
                } else {
                    String d = (error == null) ? "unknown" : String.valueOf(error.getDescription());
                    fallbackToConsole("界面加载失败（" + d + "），已打开控制台");
                }
            }

            /**
             * v1.21：渲染进程崩溃/被回收。必须让这个 WebView 实例退场（{@code webViewBroken}），
             * 恢复时走 Activity 重建 —— 直接对已崩溃的 WebView 调 loadUrl 会二次崩溃。
             * 返回 true = "我们处理了"，系统不会因此把整个 App 杀掉。
             */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = detail != null && detail.didCrash();
                webViewBroken = true;
                fallbackToConsole("界面渲染进程" + (crashed ? "崩溃" : "被系统回收") + "，已打开控制台");
                return true;
            }

            /**
             * v1.21：主 WebView 里"跳离本地引擎地址"的导航一律交系统浏览器并取消。
             *
             * 场景：插件（ds-harness-remote 等）在界面里渲染授权链接（例如 pending 状态下的
             * 「Open the sign-in page」）。有些带 target=_blank（走 onCreateWindow ✅），
             * 有些是普通链接（会**把 App 界面导航走**，而插件的登录结果是靠这个页面轮询回收的
             * —— 页面一被顶掉，登录就永远等不到结果）。所以这里：只要目标不是本地引擎地址，
             * 就交给系统浏览器，并保持 App 界面与轮询存活。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                    android.webkit.WebResourceRequest req) {
                try {
                    if (req == null || req.getUrl() == null || !req.isForMainFrame()) return false;
                    String host = req.getUrl().getHost();
                    String scheme = req.getUrl().getScheme();
                    if (scheme != null && scheme.startsWith("http")
                            && (host == null || host.equals("127.0.0.1") || host.equals("localhost"))) {
                        return false;   // 本地引擎页面/本地资源：留在 WebView 里
                    }
                    if (host == null) return false;
                    String url = req.getUrl().toString();
                    Log.i(TAG, "主界面导航到外部地址 → 系统浏览器: " + url);
                    lastPopupUrl = url;
                    lastPopupAt = System.currentTimeMillis();
                    if (openInSystemBrowser(url)) return true;
                    return false;      // 没有浏览器就只能让它在这里加载
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {                errorRetries = 0;
                // v1.21：白页探针 —— "加载完成"不等于"渲染出来了"（老 WebView / 前端插件加载失败
                // 都是加载成功但 #root 空着）。8 秒后探一次，仍空则落控制台。
                final String finishedUrl = url;
                view.postDelayed(new Runnable() {
                    @Override public void run() { probeBlankPage(finishedUrl); }
                }, 8000L);
                // v1.13.11：页面底色决定状态栏/导航栏颜色（前端主题可独立于系统设置），
                // 且主题可能在页面挂载后才被前端插件应用 → 多试几次，取到即刷新。
                final int[] delays = {0, 700, 2000, 5000};
                for (int i = 0; i < delays.length; i++) {
                    final int d = delays[i];
                    view.postDelayed(new Runnable() {
                        @Override public void run() { refreshPageBackground(); }
                    }, d);
                }
                // v1.13.12：让页面把底色变化主动推给壳（用户在前端里切深浅色时状态栏能跟着变，
                // 不再只靠页面加载时的几次采样）。注入 MutationObserver，主题 class/属性一变就上报。
                try {
                    view.evaluateJavascript(
                        "(function(){try{if(window.__dshBgWatch)return;window.__dshBgWatch=1;"
                        + "function opaque(s){return s&&!/rgba?\\([^)]*,\\s*0\\s*\\)/.test(s);}"
                        + "function cur(){var b='';try{b=getComputedStyle(document.body).backgroundColor||''}catch(e){}"
                        + "if(opaque(b))return b;var h='';try{h=getComputedStyle(document.documentElement).backgroundColor||''}catch(e){}"
                        + "return opaque(h)?h:b;}"
                        + "function push(){try{if(window.dshshell&&dshshell.onBg)dshshell.onBg(cur())}catch(e){}}"
                        + "var t=null;function soon(){if(t)return;t=setTimeout(function(){t=null;push()},250);}"
                        + "try{new MutationObserver(soon).observe(document.documentElement,"
                        + "{attributes:true,attributeFilter:['class','style','data-theme','color-scheme']})}catch(e){}"
                        + "document.addEventListener('transitionend',soon,true);"
                        + "push();setTimeout(push,800);setTimeout(push,2500);}catch(e){}})()",
                        null);
                } catch (Throwable ignored) {}
            }
        });

        // v1.13.12：页面 → 壳的底色上报通道（配合上面注入的观察器；只暴露一个只读回调）
        try {
            webView.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void onBg(String css) {
                    final int c = parseCssColor(css);
                    if (c == 0) return;
                    ui.post(new Runnable() { @Override public void run() {
                        if (c == pageBgColor) return;
                        pageBgColor = c;
                        applyStatusBar();
                        if (engineRoot != null) engineRoot.setBackgroundColor(c);
                        if (webView != null) webView.setBackgroundColor(c);
                        applyShellPalette();   // v1.21：页面换色调 → 壳（引导页/控制台/系统栏）跟着换
                    }});
                }

                /**
                 * v1.21：页面请求用**系统浏览器**打开一个地址。
                 *
                 * 用途：插件的登录授权页。ds-harness-remote 是 `window.open("")` 先拿窗口对象、
                 * 之后才 `tab.location = 授权地址` —— 走 WebView 的 onCreateWindow 通道时，
                 * 用来承接的隐形 WebView 会被 Chromium 节流，**第一次点击常常丢地址**（用户实测）。
                 * 所以改成在页面里接管 window.open（见 mobile-patch/mobile.js 的 shim），
                 * 凡是要开新窗口的，直接把 URL 交给系统浏览器，稳定且没有时序问题。
                 */
                @android.webkit.JavascriptInterface
                public void openExternal(String url) {
                    final String u = url == null ? "" : url.trim();
                    if (u.isEmpty()) return;
                    ui.post(new Runnable() { @Override public void run() {
                        Log.i(TAG, "dshshell.openExternal → " + u);
                        if (!openInSystemBrowser(u)) conToast("没有可用的浏览器，无法打开链接");
                    }});
                }

                /** v1.21：页面侧埋点（前端报错/接口失败）。落 logcat，同时**追加到 files/web-notes.log**， */
                /** 该文件列在控制台「日志→分享」里 —— 真机排查前端错误（如"默认工作区建立失败"）靠它。 */
                @android.webkit.JavascriptInterface
                public void note(String msg) {
                    if (msg == null) return;
                    final String line = msg.replace('\n', ' ');
                    Log.i(TAG, "page-note: " + line);
                    try {
                        File f = new File(getFilesDir(), "web-notes.log");
                        // 控制单文件大小（超过 256KB 就重开，避免无限增长）
                        if (f.exists() && f.length() > 256 * 1024) f.delete();
                        String stamped = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(new java.util.Date()) + " " + line + "\n";
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
                        try { fos.write(stamped.getBytes("UTF-8")); } finally { try { fos.close(); } catch (Throwable ignored) {} }
                        // 同时追加到 dsh-web.log —— 用户分享的就是这个文件，这样不必额外找文件
                        //（workspace-diag 也是这样做的，实测能看到）
                        java.io.FileOutputStream lf = new java.io.FileOutputStream(new File(getFilesDir(), "dsh-web.log"), true);
                        try { lf.write(("[app] " + stamped).getBytes("UTF-8")); } finally { try { lf.close(); } catch (Throwable ignored) {} }
                        // 再镜像一份到外部目录 —— /data/user/0/... 是 App 私有目录，文件管理器进不去，
                        // 用户要"自己看"就得有一份在 /sdcard 下（<变体目录>/web-notes.log）
                        try {
                            File extRoot = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                            if (extRoot.exists() || extRoot.mkdirs()) {
                                File ext = new File(extRoot, "web-notes.log");
                                if (ext.exists() && ext.length() > 256 * 1024) ext.delete();
                                java.io.FileOutputStream ef = new java.io.FileOutputStream(ext, true);
                                try { ef.write(stamped.getBytes("UTF-8")); } finally { try { ef.close(); } catch (Throwable ignored) {} }
                            }
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            }, "dshshell");
        } catch (Throwable ignored) {}

        // 附件/文件选择：官方前端用 <input type="file"> 选文件，Android WebView 必须实现
        // onShowFileChooser 才会弹系统文件选择器，否则点「添加附件」没有任何反应。
        //
        // v1.21：**多窗口支持** —— 插件（如 ds-harness-remote 的「DS 账号登录」）用
        // `window.open("", "_blank")` 起授权弹窗（它把 opener 显式置空，授权结果靠插件在
        // 主页面每 3 秒轮询服务端回收），而 WebView 默认**不支持多窗口**、我们也没实现
        // onCreateWindow → 弹窗被拦，主页面会落到 `about:blank#blocked`（用户看到的白屏）。
        // 所以：打开多窗口开关 + 用 onCloseWindow/onCreateWindow 给它一个真正的独立窗口。
        try {
            android.webkit.WebSettings multiWin = webView.getSettings();
            multiWin.setSupportMultipleWindows(true);
            multiWin.setJavaScriptCanOpenWindowsAutomatically(true);
        } catch (Throwable ignored) {}

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "file chooser failed", t);
                    fileChooserCallback = null;
                    return false;
                }
            }

            /**
             * v1.21：插件请求弹窗（window.open）→ **不弹任何卡片，直接交给系统浏览器**。
             *
             * 用户要求：点登录后不要"授权页已交给浏览器"那张卡片。
             * 难点：插件是先 window.open("") 拿到窗口对象，再 tab.location = 授权地址 ——
             * 地址在 onCreateWindow 返回之后才出现。所以这里的做法是：
             *   · 创建一个**1×1 透明**的承载 WebView 挂到内容视图上（必须 attached，
             *     插件 JS 才拿得到窗口对象，它的轮询逻辑才正常）；
             *   · 该 WebView 的首个 http(s) 导航用 Intent.ACTION_VIEW 交给系统浏览器完成授权；
             *   · 承载 WebView 不销毁（否则插件会认为窗口已关、重新 open 一次 → 浏览器开两个标签页），
             *     Activity 销毁时统一清理（hiddenPopups）。
             * 插件的登录结果本来就靠主页面轮询服务端回收，不依赖弹窗回调，所以这条路完全成立。
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                Log.i(TAG, "webview: onCreateWindow → 直接交给系统浏览器（isDialog=" + isDialog
                        + " userGesture=" + isUserGesture + "）");
                try {
                    final WebView pop = new WebView(MainActivity.this);
                    pop.getSettings().setJavaScriptEnabled(true);
                    pop.getSettings().setDomStorageEnabled(true);
                    pop.getSettings().setSupportMultipleWindows(true);
                    pop.setWebViewClient(new WebViewClient() {
                        @Override public boolean shouldOverrideUrlLoading(WebView v,
                                                                         android.webkit.WebResourceRequest req) {
                            if (req == null || req.getUrl() == null) return false;
                            String url = req.getUrl().toString();
                            if (!url.startsWith("http")) return false;
                            // 去重：正常情况下插件只设置一次 tab.location；万一它认为窗口关了再 open 一次，
                            // 同一个地址 10 秒内只交给浏览器一次，避免重复标签页。
                            if (url.equals(lastPopupUrl)
                                    && System.currentTimeMillis() - lastPopupAt < 10000L) return true;
                            lastPopupUrl = url;
                            lastPopupAt = System.currentTimeMillis();
                            boolean ok = openInSystemBrowser(url);
                            if (!ok) {
                                // 没有可用浏览器：至少让用户知道发生了什么（这种设备极少见）
                                conToast("没有可用的浏览器，无法打开授权页");
                            }
                            return true;   // 不在隐形 WebView 里加载
                        }
                    });
                    android.view.ViewGroup contentRoot = findViewById(android.R.id.content);
                    if (contentRoot != null) {
                        // 用**全尺寸 + alpha 0 + 放到最底层**，而不是 1×1：
                        // Chromium 会把 1×1/不可见的小 WebView 当后台页面节流，
                        // 插件"先开空窗、几秒后再 tab.location=授权地址"这一步就可能丢（用户实测第一次点击没反应）。
                        pop.setAlpha(0f);
                        contentRoot.addView(pop, 0, new android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                        hiddenPopups.add(pop);
                    }
                    ((WebView.WebViewTransport) resultMsg.obj).setWebView(pop);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "onCreateWindow failed", t);
                    return false;
                }
            }
        });

        statusView = new TextView(this);
        statusView.setText("正在启动 DeepSeek Harness…");
        statusView.setTextColor(cText());   // v1.21（UI 统一）：主状态用主文字色，副提示才用 cSub()
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_body));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(12), dp(24), dp(12));

        // v1.21：启动页指示器改成小圆环 —— 原来是横向进度条（progressBarStyleHorizontal），
        // 用户反馈"下方有个很丑的类似进度条的东西在动"。圆环是系统标准形态，配主题蓝更耐看。
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyle);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);

        // 提取 rish dex（DSH 的 shizuku_shell 插件执行命令用，与 payload 解压解耦）
        rishDex = extractRishDex();
        vscreenDex = extractVscreenDex();
        // 虚拟屏接入桥：启动 Operit server + HTTP->binder 转发（插件走 8999）
        // 原实现用反射按包名拼类名（getPackageName() + ".VsreenBridgeService"）——
        //   API 包名一改（本项目另有 Lite / 兼容版等只改 manifest 包名的变体），
        //   拼出来的类名就找不到 → ClassNotFoundException → 桥服务根本没起来 → 预览窗永远不出现。
        // 改用类字面量即与包名解耦（manifest 里的组件名仍由变体构建脚本展开成绝对包名）。
        try {
            startService(new Intent(this, VsreenBridgeService.class));
        } catch (Throwable t) {
            Log.w(TAG, "start VsreenBridgeService failed", t);
        }
        // 存储权限：**不再在启动时自动弹系统对话框**（v1.21，真机报障）。
        // 这段老代码是为"写 /sdcard 提取 vscreen jar"服务的，但它会在首启引导刚出现时
        // 叠一个系统权限弹窗 —— 用户看到的是"引导页 + 系统弹窗"两套东西同时来，很乱。
        // 现在改成按需：引导里的「所有文件访问」那一页会主动申请（见 requestPermissions 调用点），
        // 用户点了才弹。缺权限时虚拟屏相关能力后续再申请即可（不影响首次启动）。
        pendingVscreenExtract = false;

        // Shizuku API：监听 binder 与授权结果（实现授权弹窗）
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() { probeShizuku(); }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override public void onBinderDead() { shizukuOk = false; refreshAllStatuses(); }
            });
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    pendingShizukuReq = false;   // v1.13：无论结果都结束“等待授权”状态
                    shizukuOk = grantResult == PackageManager.PERMISSION_GRANTED;
                    Log.i(TAG, "shizuku permission result: " + grantResult);
                    refreshAllStatuses();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku listener init failed", t);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // v1.21（需求 1）：给一个"文件管理器里找得到"的默认工作区，用户不必手动选。
        ensureDefaultWorkspace();
        // v1.21：先消费"从通知进控制台"的 extra（无论走哪个分支）——
        // 否则首启（还没走完向导）时点通知，extra 会留在 Intent 里，
        // 之后任何一次 Activity 重建都会莫名其妙弹回控制台。
        final boolean wantConsole = consumeOpenConsoleExtra();
        if (prefs.getBoolean("setup_done", false)) {
            showEngineScreen();
            // v1.12：切屏回来/任务恢复（savedInstanceState != null）直接进主界面。
            // v1.21（Q1-P1「秒进」）：冷启动**不再先停在控制台** —— 正常路径应当直接进主界面，
            //   控制台只从「常驻通知」或「故障回退」进入（用户定的口径）。
            if (wantConsole) {
                // 从常驻通知点进来的：明确要看控制台
                showConsole();
            } else if (savedInstanceState != null) {
                startEngine();
            } else {
                autoEnterOnBoot();
            }
        } else {
            showPermissionScreen();
        }
    }

    /**
     * v1.21：消费 {@link #EXTRA_OPEN_CONSOLE}。读一次就清掉，避免 Activity 重建/任务恢复时
     * 又莫名其妙弹回控制台（用户可能已经从控制台返回主界面了）。
     */
    private boolean consumeOpenConsoleExtra() {
        try {
            Intent it = getIntent();
            if (it == null || !it.getBooleanExtra(EXTRA_OPEN_CONSOLE, false)) return false;
            it.removeExtra(EXTRA_OPEN_CONSOLE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * v1.21：Activity 已在运行（通知 Intent 带 FLAG_ACTIVITY_SINGLE_TOP）时走这里 ——
     * 不处理的话"点通知"第二次开始就没反应了。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        boolean wantConsole = intent.getBooleanExtra(EXTRA_OPEN_CONSOLE, false);
        intent.removeExtra(EXTRA_OPEN_CONSOLE);
        setIntent(intent);
        if (!wantConsole) return;
        Log.i(TAG, "onNewIntent: EXTRA_OPEN_CONSOLE → 进控制台（来源：常驻通知 / 悬浮窗面板）");
        try {
            showEngineScreenIfNeeded();
            showConsole();
        } catch (Throwable t) {
            Log.w(TAG, "onNewIntent open console failed", t);
        }
    }

    /** 控制台可能被建在 WebView 之上；确保引擎屏（含 WebView 容器）已存在再显示控制台。 */
    private void showEngineScreenIfNeeded() {
        if (webView == null || engineRoot == null || engineRoot.getParent() == null) showEngineScreen();
    }

    /**
     * v1.21（Q1-P1）：冷启动秒进主界面，不再默认停在控制台。
     *
     * 三条分支：
     *  ① 连续启动失败已达阈值（{@link #BOOT_FAIL_HINT_AT}）→ 直接进控制台：
     *     上次就没起来，再让用户对着卡住的启动页没有意义；控制台里有日志/安全模式/重启。
     *  ② 引擎已在跑（App 被杀后 EngineService 保活）→ {@code launchEngine} 内部探测到健康即
     *     {@code loadHome()}，实际是"秒进"；不重复 spawn（有 healthOk/端口两道防线）。
     *  ③ 引擎没在跑 → 正常起引擎：启动页（logo + 状态文字 + 进度条）本身就是骨架屏，
     *     就绪后 {@code waitForServer → loadHome} 自动进主界面。
     *
     * 失败兜底完全沿用既有链路，不改行为：{@code waitForServer} 超时 → {@code bumpBootFailure()}
     * + {@code conEngineTimedOut()}（回控制台 + 后台守望，引擎迟到就绪时自动进主界面）。
     */
    private void autoEnterOnBoot() {
        final int fails = bootFailures();
        if (fails >= BOOT_FAIL_HINT_AT) {
            Log.i(TAG, "autoEnter: 连续失败 " + fails + " 次 → 直接进控制台");
            showConsole();
            conToast("上次引擎未启动成功，已打开控制台（引擎就绪后会自动进入主界面）");
            return;
        }
        // 用户上次在控制台主动点了「停止」→ 尊重意图，冷启动不自动拉起（否则就是"停不掉"）。
        if (engineStoppedByUserPersisted()) {
            Log.i(TAG, "autoEnter: 用户曾主动停止引擎 → 进控制台等用户决定");
            showConsole();
            conToast("引擎处于你上次手动停止的状态，点「启动引擎」即可恢复");
            return;
        }
        // v1.21：上次界面加载失败过（白页/渲染进程崩溃/版本过旧）→ 直接进控制台，
        // 别让用户再对着一个注定失败的 WebView 等 8 秒白页探针。
        if (uiFailures() >= 2) {
            Log.i(TAG, "autoEnter: 界面连续失败 " + uiFailures() + " 次 → 直接进控制台");
            showConsole();
            conToast("上次界面加载失败，已打开控制台（界面恢复后会自动进入）");
            return;
        }
        Log.i(TAG, "autoEnter: 秒进模式（不显示控制台），直接起引擎");
        startEngine();
    }

    /** v1.21：持久化"用户主动停止引擎"的意图（内存标记进程一死就没了）。 */
    private static final String KEY_ENGINE_STOPPED = "engine_user_stopped";
    private boolean engineStoppedByUserPersisted() {
        try { return prefs().getBoolean(KEY_ENGINE_STOPPED, false); } catch (Throwable t) { return false; }
    }

    private void markEngineStoppedByUser(boolean stopped) {
        try { prefs().edit().putBoolean(KEY_ENGINE_STOPPED, stopped).apply(); } catch (Throwable ignored) {}
    }

    /**
     * v1.21（Q1 第 3 批）：权限向导展示期间后台预热 payload（首次解压 1.5 万条目 / ~290MB）。
     *
     * 复用「只解压、不启动」的既有路径（{@code extractOnlyMode} + {@code startEngine(true)}）：
     *   · 文件准备逻辑只有一份，不会和启动路径走偏；
     *   · 幂等：{@code filesPreparedThisBoot} 已为真时直接返回；
     *   · 不重复：正在解压/正在启动时不动手。
     * 预热失败不致命 —— 启动引擎时会再走一遍同样的准备（失败分支会把 filesPreparedThisBoot 复位）。
     */
    private void warmPayloadAsync() {
        if (filesPreparedThisBoot || extracting || starting) return;
        Log.i(TAG, "warmPayload: 向导期间开始后台预热 payload");
        extractOnlyMode = true;
        extracting = true;
        startEngine(true);
    }

    // ================= v1.21（Q1 第 3 批）：界面故障 → 自动落控制台 =================
    /**
     * 主框架加载失败的重试预算（每次 2.5s）。
     * ⚠ 必须 ≥ {@code waitForServer} 的 90 秒等待窗口（40 × 2.5s = 100s）：
     * 否则慢设备上引擎还在正常启动，WebView 就先被判"加载不出来"而落控制台 ——
     * 与"正常路径直接进主界面"相悖。真正"加载完成但前端没起来"由白页/启动壳探针负责。
     */
    private static final int WEBVIEW_RETRY_BUDGET = 40;
    /** 界面故障计数（连续）：与 engine_boot_failures 分开记，便于分别提示与复位。 */
    private static final String KEY_UI_FAILS = "ui_fail_streak";
    /** 渲染进程崩溃后该 WebView 实例不可用（恢复要走 Activity 重建）。 */
    private volatile boolean webViewBroken = false;
    /** 一次启动只报一次故障，避免重试/回退互相刷屏。 */
    private volatile boolean uiFallbackReported = false;

    private int uiFailures() {
        try { return prefs().getInt(KEY_UI_FAILS, 0); } catch (Throwable t) { return 0; }
    }
    private void bumpUiFailure() {
        try { prefs().edit().putInt(KEY_UI_FAILS, uiFailures() + 1).apply(); } catch (Throwable ignored) {}
    }
    private void resetUiFailures() {
        try { if (uiFailures() != 0) prefs().edit().putInt(KEY_UI_FAILS, 0).apply(); } catch (Throwable ignored) {}
    }

    /**
     * v1.21：界面（WebView / 前端）出故障时把用户送到控制台 —— 控制台是原生层，
     * 不依赖引擎也不依赖前端渲染，正好用来恢复（看日志 / 重启引擎 / 重新解压 / 安全模式）。
     */
    private void fallbackToConsole(final String reason) {
        if (uiFallbackReported) return;
        uiFallbackReported = true;
        bumpUiFailure();
        Log.w(TAG, "fallbackToConsole: " + reason);
        ui.post(new Runnable() { @Override public void run() {
            try {
                showEngineScreenIfNeeded();
                showConsole();
                conToast(reason);
            } catch (Throwable t) { Log.w(TAG, "fallbackToConsole failed", t); }
        }});
    }

    /**
     * v1.21：白页 / 前端卡死探针（两段）。
     *
     * 判据来自实测的两种形态：
     *   · 健康：`#root` 有子树、整页元素 ≈150+、正文数百字（SPA 渲染完成）；
     *   · 白页：整页只剩 1~3 个元素、正文为空（HTML 拿到了但什么都没渲染）。
     * 另外 DSH 前端有一层"启动壳"（正文含 "Loading plugins…"），插件加载失败时会**一直停在壳上**
     * —— 这种"加载完成但其实没起来"靠 onReceivedError 是发现不了的，所以 45 秒后再探一次：
     * `#root` 仍无子树且正文还停在 Loading/Failed 文案 → 同样落控制台。
     */
    private void probeBlankPage(String url) {
        final WebView wv = webView;
        if (wv == null || webViewBroken || consoleVisible) return;
        try {
            wv.evaluateJavascript(
                    "(function(){try{var b=document.body||document.documentElement;"
                    + "var e=b?b.getElementsByTagName('*').length:0;"
                    + "var t=(b&&b.innerText?b.innerText:'').trim().length;"
                    + "return e+'|'+t;}catch(x){return '-1|-1'}})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            if (v == null) return;
                            String[] parts = v.replace("\"", "").split("\\|");
                            int e = -1, t = -1;
                            try { e = Integer.parseInt(parts[0]); t = Integer.parseInt(parts[1]); } catch (Throwable ignored) {}
                            if (e >= 0 && e < 8 && t < 20) {
                                fallbackToConsole("界面空白（前端未渲染），已打开控制台");
                            } else if (e >= 8) {
                                resetUiFailures();   // 页面确实渲染出了内容 → 界面故障计数清零
                            }
                        }
                    });
        } catch (Throwable ignored) {}
        // 第二段：45 秒后看 SPA 到底起来没有（启动壳卡住 / 插件加载失败）
        wv.postDelayed(new Runnable() {
            @Override public void run() { probeSpaStuck(); }
        }, 45000L);
    }

    /** v1.21：第二阶段探针 —— `#root` 仍无子树且正文停在 Loading/Failed 文案 → 前端没起来。 */
    private void probeSpaStuck() {
        final WebView wv = webView;
        if (wv == null || webViewBroken || consoleVisible) return;
        try {
            wv.evaluateJavascript(
                    "(function(){try{var r=document.getElementById('root');var n=r?r.children.length:0;"
                    + "var t=document.body?document.body.innerText:'';"
                    + "return n+'|'+(/Loading plugins|Failed to load|Error/i.test(t)?1:0);}catch(x){return '0|0'}})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            if (v == null) return;
                            String[] parts = v.replace("\"", "").split("\\|");
                            int n = 0, flag = 0;
                            try { n = Integer.parseInt(parts[0]); flag = Integer.parseInt(parts[1]); } catch (Throwable ignored) {}
                            if (n == 0 && flag == 1) {
                                fallbackToConsole("前端加载超时（插件未就绪），已打开控制台");
                            } else if (n > 0) {
                                resetUiFailures();
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /**
     * issue #37（社区 @zf-666888 报告）：平板分屏 / 自由窗口拖动分隔条只改窗口尺寸，不该重建界面。
     *
     * manifest 的 configChanges 已补齐 screenLayout|smallestScreenSize（见 AndroidManifest.xml 里
     * 那段注释，含真机 logcat 实证）。此前这两项未声明 → 系统直接销毁重建 MainActivity
     * → 新建 WebView 重新 loadUrl + 启动页盖上来 = 用户看到的「白闪一下 + 重放启动页」
     * （引擎是独立 node 进程 + EngineService 保活，所以会话进度不丢，纯粹是界面闪）。
     *
     * 这里只补做「跟着窗口尺寸走、又不会自动更新」的原生装饰：
     * 状态栏 / 导航栏底色依赖主题与页面实测底色，重建后不会自己变，重新贴一遍。
     * 其余都不用管：WebView 是 MATCH_PARENT，框架会按新尺寸自动重排；
     * 控制台/启动页那套视图是代码建的、px 固定，尺寸变化不影响可用性。
     *
     * 注意：只有「已声明的」配置项变化才会进本方法。uiMode（系统深浅色）/ density（显示大小）
     * / fontScale（字体大小）故意「不」声明 —— 它们继续走重建，由 onCreate 的 setTheme() 重新定主题。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            applyStatusBar();
        } catch (Throwable t) {
            Log.w(TAG, "onConfigurationChanged: applyStatusBar failed", t);
        }
    }

    // ============ WebView 兼容检测（老安卓 WebView 缺失/过旧） ============
    /** DSH 前端是 Vite 构建的现代应用（<script type="module"> + 可选链/nullish），
     *  需要 Chromium 80+ 才能渲染；Android 7/8 出厂 WebView（Chromium 51/59）或长期未更新的
     *  系统 WebView 会白屏，用户误以为「引擎启动失败」。检测到过旧版本时弹提示引导，
     *  不阻断启动（引擎本身与 WebView 无关，node 进程照常拉起）。 */
    /**
     * DSH 前端所需的 Chromium 主版本下限。
     *
     * ⚠️ 以前写的是 80，「那个值是错的」 —— 它只是「能解析 <script type="module">」的底线，
     * 而 Vite 产物里实际用到了更高的语法。v1.17.3 用 `tmp-diag/v1173/scan-syntax.mjs`
     * 对当前入口 bundle 实测：`assets/index-*.js` 里有 2 处 class 静态初始化块 `static{}`
     * → 需要 「Chrome 94」。
     *
     * 阈值偏低的后果：80~93 的设备「静默白屏且不弹任何提示」（issue #38 报告人那台 Chromium 91
     * 就是被这个阈值漏过去的）。
     * ⚠️ 上游重建前端后此值可能变化 —— 出包前用上面那个脚本重测。
     */
    private static final int WEBVIEW_MIN_CHROME = 94;

    private void checkWebViewCompat() {
        try {
            int chrome = parseChromeMajor(webView.getSettings().getUserAgentString());
            // UA 无 Chrome 标记时（部分 ROM 魔改 UA），API 26+ 用 WebView 包版本兜底
            if (chrome <= 0 && Build.VERSION.SDK_INT >= 26) {
                try {
                    android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                    if (pi != null && pi.versionName != null) {
                        chrome = parseChromeMajor(pi.versionName);
                    }
                } catch (Throwable ignored) {}
            }
            if (chrome <= 0 || chrome >= WEBVIEW_MIN_CHROME) return; // 拿不到版本或够新 → 不打扰
            final int ver = chrome;
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("系统 WebView 版本过旧")
                                .setMessage("检测到系统 WebView 内核为 Chromium " + ver
                                        + "（DSH 界面需要 " + WEBVIEW_MIN_CHROME + " 以上）。\n\n"
                                        + "界面可能无法正常显示（白屏/无法交互），引擎本身不受影响。\n\n"
                                        + "建议：① 更新\"Android System WebView\"后重试；"
                                        + "② 安装「DeepSeek Harness 兼容版」（专为老设备优化）。")
                                .setPositiveButton("去更新", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) { openWebViewUpdate(); }
                                })
                                .setNegativeButton("继续尝试", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        // v1.21：老 WebView 大概率白屏 → 直接落到控制台，
                                        // 那里有"兼容版"引导、日志与重新解压等恢复手段。
                                        fallbackToConsole("系统 WebView 版本过旧（Chromium " + ver + "），已打开控制台");
                                    }
                                })
                                .show();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 从 UA（"... Chrome/51.0.2704.81 ..."）或版本名（"80.0.3987.149"）解析主版本号。 */
    private int parseChromeMajor(String s) {
        if (s == null) return -1;
        int i = s.indexOf("Chrome/");
        int base = 0;
        if (i < 0) { i = s.indexOf("Chrome "); if (i < 0) return -1; base = "Chrome ".length(); }
        else { base = "Chrome/".length(); }
        int start = i + base;
        int e = start;
        while (e < s.length() && Character.isDigit(s.charAt(e))) e++;
        if (e == start) return -1;
        try { return Integer.parseInt(s.substring(start, e)); } catch (Throwable t) { return -1; }
    }

    /** 引导更新系统 WebView：优先系统 WebView 设置页，失败兜底应用商店。 */
    private void openWebViewUpdate() {
        try {
            Intent i = new Intent("android.settings.WEBVIEW_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {}
    }

    /**
     * v1.21：为插件弹窗临时挂着的 1×1 隐形 WebView（**不弹卡片**，直接转交系统浏览器）。
     * 不能立刻销毁：插件会检查 `tab.closed`，窗口一关它就再 open 一次（浏览器会开两个标签页）。
     */
    private final java.util.List<WebView> hiddenPopups = new java.util.ArrayList<WebView>();
    /** 最近一次交给浏览器的弹窗地址（去重，避免同一地址开两个标签页）。 */
    private String lastPopupUrl = null;
    private long lastPopupAt = 0L;
    /** v1.21：最近一次把**授权页**交给浏览器的时间（>0 表示"回来后该刷新一次界面"）。 */
    private volatile long authHandoffAt = 0L;

    /** 判断一个 URL 是不是插件登录的授权页（用于决定"回来后刷新界面"）。 */
    private static boolean looksLikeAuthUrl(String url) {
        if (url == null) return false;
        return url.contains("/dsh/authorize") || url.contains("authorize_id=")
                || url.contains("/auth/authorize") || url.contains("sign-in");
    }

    /**
     * v1.21：把授权页交给**系统浏览器**打开（返回是否成功交出去）。
     *
     * 为什么可以这样：插件（ds-harness-remote）的登录结果是它**在主页面轮询服务端**回收的
     * （`requestDeepSeekSignIn()` 每 3 秒一次），并不依赖弹窗 postMessage 回来 ——
     * 所以用系统浏览器完成授权最省事也最稳（真实浏览器、密码管理器、passkey 都在）。
     * 没有可用浏览器时返回 false，调用方给出提示。
     */
    private boolean openInSystemBrowser(String url) {
        if (url == null || url.isEmpty()) return false;
        // 记下"刚把授权页交出去"，用于从浏览器回来时刷新界面（见 maybeReloadAfterAuth）
        if (looksLikeAuthUrl(url)) authHandoffAt = System.currentTimeMillis();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Log.i(TAG, "popup → 系统浏览器: " + url);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "没有可用的系统浏览器，退回内置卡片", t);
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int sp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().scaledDensity);
    }

    // ============ 权限引导界面 ============
    private void detachView(View v) {
        if (v != null && v.getParent() != null) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private void showEngineScreen() {
        if (engineRoot == null) engineRoot = new FrameLayout(this);
        FrameLayout root = engineRoot;
        root.setBackgroundColor(chromeBg()); // v1.13.11：跟随主题/页面底色
        // 成员视图（webView/statusView/progressBar）可能已挂在旧容器上，先全部摘下，避免重复挂载崩溃。
        detachView(webView);
        detachView(statusView);
        detachView(progressBar);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        // 鲸鱼 logo
        splashLogo = new ImageView(this);
        splashLogo.setImageResource(R.drawable.ic_launcher);
        conThemeApplyLogo(splashLogo);   // v1.17.4：主题 logo 覆盖启动页图标（解不出来就保留内置）
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(92), dp(92));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.bottomMargin = dp(22);
        box.addView(splashLogo, llp);

        // 品牌名
        splashBrand = new TextView(this);
        splashBrand.setText("DeepSeek Harness");
        splashBrand.setTextColor(cText());
        splashBrand.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_title));
        splashBrand.setTypeface(null, android.graphics.Typeface.BOLD);
        splashBrand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.bottomMargin = dp(26);
        box.addView(splashBrand, blp);

        // 状态文字
        box.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // v1.21：小圆环（直径 30dp）+ 一行副提示。
        // 原来这里是 260×6dp 的横向进度条（用户："下方还有个很丑的类似进度条的东西在动"）。
        // v1.21（UI 统一）：颜色改走 cAccent() —— 原来写死 #4d6bfe，既不跟主题包也不跟深浅色走。
        progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(cAccent()));
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(30), dp(30));
        pbp.topMargin = dp(20);
        pbp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(progressBar, pbp);

        // 副提示：让"要等一会儿"这件事变得可预期。
        // v1.21（用户反馈）：原文案写的是"首次启动需要多等一会儿" —— 不准确，
        // 事实上**任何时候重新启动引擎都要等**（引擎是独立 node 进程）。改成中性表述。
        splashHint = new TextView(this);
        splashHint.setText("启动引擎需要一点时间，请稍候");
        splashHint.setTextColor(cSub());
        splashHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        splashHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(splashHint, hlp);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER;
        root.addView(box, bp);

        setContentView(root);
        // v1.21（UI 统一）：启动页也把系统栏刷成壳底色 —— 否则从引导页切过来时状态栏会留上一页的颜色。
        applySystemBars(chromeBg());
    }

    /** v1.21：悬浮窗面板「完全退出」进行中 —— 期间不让界面/服务被重新拉起。 */
    public static volatile boolean shuttingDown = false;

    /**
     * v1.21：「完全退出」标记**必须落盘**。
     *
     * 实测踩过：只用一个静态 boolean 挡不住 —— 进程被杀后系统会因 START_STICKY 重建
     * EngineService / OverlayService（重建发生在**新进程**里，静态字段回到 false），
     * 于是常驻通知和悬浮窗又冒出来，用户看到的是"没关干净"。
     * 落盘后，服务/界面在被重建时能读到这个标记并立刻自停。
     * 用户主动点应用图标（ACTION_MAIN）启动时清除它，正常启动不受影响。
     */
    private static final String KEY_SHUTDOWN_PENDING = "shutdown_pending";

    /** 退出标记（静态或落盘任一为真即视为"正在退出"）。 */
    public static boolean shutdownPending(Context ctx) {
        if (shuttingDown) return true;
        try {
            return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(KEY_SHUTDOWN_PENDING, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户主动启动（点图标）时清掉退出标记。 */
    private void clearShutdownPendingOnUserLaunch() {
        try {
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SHUTDOWN_PENDING, false)) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_SHUTDOWN_PENDING, false).apply();
            }
            shuttingDown = false;
        } catch (Throwable ignored) {}
    }

    /**
     * v1.21：**完全退出**（悬浮窗面板的「退出」按钮走的入口）。
     *
     * 与 confirmExit() 的区别：
     *   · confirmExit() 只关界面，引擎 node 是独立子进程、继续在后台跑
     *     （"手机当服务器"的设计意图，不需要停）；
     *   · 本方法是用户明确要求"完全关闭 APP"：停引擎 → 停保活/悬浮窗服务 → 清通知 → 结束进程。
     *
     * 两个实现要点：
     *   ① 必须**显式杀 node**：子进程不会随父进程退出而消失，杀掉 App 进程只会留下孤儿引擎；
     *   ② 必须置 shuttingDown：EngineService/OverlayService 都是 START_STICKY，
     *      进程死掉后系统会重建它们（悬浮窗又冒出来），所以两处 onCreate 都要看这个标志。
     *
     * 防误触由调用方负责（OverlayService 里做了两步确认）。
     */
    public static void shutdownEverything(final Context ctx) {
        if (shuttingDown) return;
        shuttingDown = true;
        // 落盘：进程死后服务被 START_STICKY 重建时，靠它在新进程里挡住（见 shutdownPending）
        try {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_SHUTDOWN_PENDING, true).apply();
        } catch (Throwable ignored) {}
        Log.i(TAG, "shutdown: 完全退出（停引擎 + 停服务 + 清通知 + 结束进程）");
        final int port = OverlayService.enginePort(ctx);
        new Thread(new Runnable() { @Override public void run() {
            // 1) 停引擎：SIGTERM → 轮询 6 秒 → SIGKILL 兜底
            try {
                int pid = findEnginePid(port);
                Log.i(TAG, "shutdown: engine pid=" + pid);
                if (pid > 0) android.os.Process.sendSignal(pid, 15);
                long deadline = System.currentTimeMillis() + 6000;
                while (System.currentTimeMillis() < deadline && findEnginePid(port) > 0) {
                    try { Thread.sleep(200); } catch (Throwable ignored) { break; }
                }
                int still = findEnginePid(port);
                if (still > 0) android.os.Process.killProcess(still);
            } catch (Throwable t) {
                Log.w(TAG, "shutdown: kill engine failed", t);
            }
            // 2) 停前台服务 + 清通知（否则通知栏还留着"运行中"）
            try {
                ctx.stopService(new Intent(ctx, OverlayService.class));
                ctx.stopService(new Intent(ctx, EngineService.class));
                ctx.stopService(new Intent(ctx, VsreenBridgeService.class));   // 也是 STICKY，必须显式停
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancelAll();
            } catch (Throwable ignored) {}
            // 3) 结束进程（shuttingDown 已挡住 START_STICKY 重建）
            try { Thread.sleep(500); } catch (Throwable ignored) {}
            Log.i(TAG, "shutdown: 结束进程");
            android.os.Process.killProcess(android.os.Process.myPid());
        }}, "dsh-shutdown").start();
    }

    // ============ v1.21：默认插件（ds-harness-remote） ============

    /** 默认插件规格：与维护者在 Desktop 上装的 `dsh plugin add github:blue-soda/ds-harness-remote` 一致。 */
    private static final String DEFAULT_PLUGIN_SPEC = "github:blue-soda/ds-harness-remote";
    /** 包名（用于判断是否已装进 profile）。 */
    private static final String DEFAULT_PLUGIN_NAME = "ds-harness-remote";
    private static final String KEY_PLUGIN_TRIES = "default_plugin_tries";
    /** 最多自动尝试几次（跨启动累计）；之后不再自动重试，避免每次开机白等。 */
    private static final int DEFAULT_PLUGIN_MAX_TRIES = 3;
    private volatile boolean defaultPluginBusy = false;

    /**
     * v1.21：内置插件注册（**同步**，必须在引擎启动前完成）。
     *
     * 内置包在 payload 里（构建期 vendor 到 DSH 的 bundle 解析根
     * `dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/ds-harness-remote`），
     * 但还要把它写进 profile 的 `dsh.profile.bundles`，DSH 才会把它组合进插件树
     * （dsh-base / dsh-web-app 也是这样列着的）。这一步只是改一个小 JSON，秒级完成。
     */
    private void ensureDefaultPluginRegisteredSync(File payload) {
        try {
            File vendored = new File(payload,
                    "dshhome/profiles/web/node_modules/" + DEFAULT_PLUGIN_NAME);
            if (!vendored.isDirectory()) return;   // 没有内置包（旧 APK）→ 交给联网安装兜底
            File pkg = new File(payload, "dshhome/profiles/web/package.json");
            String txt = readFileText(pkg);
            if (txt == null || txt.isEmpty()) return;
            if (txt.contains("\"" + DEFAULT_PLUGIN_NAME + "\"")) return;   // 已注册，别重复写
            org.json.JSONObject o = new org.json.JSONObject(txt);
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            if (dsh == null) { dsh = new org.json.JSONObject(); o.put("dsh", dsh); }
            org.json.JSONObject prof = dsh.optJSONObject("profile");
            if (prof == null) { prof = new org.json.JSONObject(); dsh.put("profile", prof); }
            org.json.JSONArray arr = prof.optJSONArray("bundles");
            if (arr == null) { arr = new org.json.JSONArray(); prof.put("bundles", arr); }
            arr.put(DEFAULT_PLUGIN_NAME);
            writeFileText(pkg, o.toString(2) + "\n");
            Log.i(TAG, "default plugin: 已注册到 profile bundles（内置包，无需联网安装）");
        } catch (Throwable t) {
            Log.w(TAG, "default plugin: 注册到 profile 失败", t);
        }
    }

    /**
     * v1.21：把默认插件装进 profile（等价于维护者手敲的
     * `dsh plugin --profile web add github:blue-soda/ds-harness-remote`）。
     *
     * ⚠ 这是**兜底路径**：正常情况下插件已在构建期内置（见 build.sh 的 vendor 段 +
     * {@link #ensureDefaultPluginRegisteredSync}），本方法会直接跳过、不联网。
     * 只有内置包缺失（例如极旧 APK 升上来）才会真的走 pnpm 安装 —— 而实测那条路在 Android 上
     * 有多处坑（git 调用报 realpath、pnpm 默认 ssh、git-hosted 需 allowBuilds），
     * 所以保留但不指望它。
     *
     * 设计：另起线程（安装可能几十秒，绝不挂启动链）；幂等（profile 里已有该包名就跳过）；
     * 失败最多自动重试 3 次（跨启动累计）。输出写 files/plugin-install.log。
     */
    private void ensureDefaultPluginsAsync(final File payload) {
        if (defaultPluginBusy) return;
        // 内置包已就位 → 什么都不用做（避免无谓联网与重试计数）
        try {
            if (new File(payload, "dshhome/profiles/web/node_modules/" + DEFAULT_PLUGIN_NAME).isDirectory()) return;
        } catch (Throwable ignored) {}
        final File profileDir = new File(payload, "dshhome/profiles/web");
        try {
            String txt = readFileText(new File(profileDir, "package.json"));
            if (txt != null && txt.contains(DEFAULT_PLUGIN_NAME)) return;   // 已装
        } catch (Throwable ignored) {}
        final int tries;
        try { tries = prefs().getInt(KEY_PLUGIN_TRIES, 0); } catch (Throwable t) { return; }
        if (tries >= DEFAULT_PLUGIN_MAX_TRIES) {
            Log.i(TAG, "default plugin: 已尝试 " + tries + " 次仍失败，不再自动重试");
            return;
        }
        defaultPluginBusy = true;
        new Thread(new Runnable() { @Override public void run() {
            try {
                prefs().edit().putInt(KEY_PLUGIN_TRIES, tries + 1).apply();
                Log.i(TAG, "default plugin: 开始安装 " + DEFAULT_PLUGIN_SPEC
                        + "（第 " + (tries + 1) + "/" + DEFAULT_PLUGIN_MAX_TRIES + " 次，后台进行）");
                File node = new File(payload, "runtime/bin/node");
                File binjs = new File(dshrootDir, REL_BINJS);
                if (!node.exists() || !binjs.exists()) {
                    Log.w(TAG, "default plugin: node/bin.js 缺失，本次跳过");
                    return;
                }
                File bin = new File(payload, "bin");
                File lib = new File(payload, "runtime/lib");
                File home = new File(payload, "dshhome");
                File gitDir = new File(payload, "git");
                File tmp = new File(getCacheDir(), "tmp");
                if (!tmp.exists()) tmp.mkdirs();

                ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), binjs.getAbsolutePath(),
                        "plugin", "--profile", "web", "add", DEFAULT_PLUGIN_SPEC);
                pb.directory(profileDir);   // dsh plugin 转发 pnpm，工作目录即 profile
                java.util.Map<String, String> env = pb.environment();
                env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
                File osslConf = new File(payload, "runtime/etc/openssl.cnf");
                if (osslConf.exists()) env.put("OPENSSL_CONF", osslConf.getAbsolutePath());
                env.put("GIT_EXEC_PATH", new File(gitDir, "libexec/git-core").getAbsolutePath());
                env.put("GIT_TEMPLATE_DIR", new File(gitDir, "templates").getAbsolutePath());
                env.put("GIT_PAGER", "cat");
                env.put("GIT_TERMINAL_PROMPT", "0");     // 需要凭据时立刻失败，别挂住
                File caBundle = ensureSystemCaBundle(payload);
                if (caBundle != null) {
                    env.put("SSL_CERT_FILE", caBundle.getAbsolutePath());
                    env.put("CURL_CA_BUNDLE", caBundle.getAbsolutePath());
                    env.put("GIT_SSL_CAINFO", caBundle.getAbsolutePath());
                    File gitCfg = ensureGitConfig(payload, caBundle);
                    if (gitCfg != null) env.put("GIT_CONFIG_GLOBAL", gitCfg.getAbsolutePath());
                }
                env.put("PATH", bin.getAbsolutePath() + ":" + new File(payload, "runtime/bin").getAbsolutePath()
                        + ":" + String.valueOf(env.get("PATH")));
                env.put("HOME", getFilesDir().getAbsolutePath());
                env.put("DSH_HOME", home.getAbsolutePath());
                env.put("TMPDIR", tmp.getAbsolutePath());
                env.put("TERM", "xterm");
                env.put("SHELL", new File(bin, "bash").getAbsolutePath());
                env.put("DSH_BASH_PATH", new File(bin, "bash").getAbsolutePath());
                pb.redirectErrorStream(true);

                final Process proc = pb.start();
                // 输出落到文件（控制台/分享用）+ 取尾部进 logcat（排查用）
                File logFile = new File(getFilesDir(), "plugin-install.log");
                final StringBuilder tail = new StringBuilder();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream(), "UTF-8"));
                java.io.FileWriter fw = null;
                try { fw = new java.io.FileWriter(logFile, false); } catch (Throwable ignored) {}
                String line;
                while ((line = r.readLine()) != null) {
                    if (fw != null) { try { fw.write(line + "\n"); } catch (Throwable ignored) {} }
                    if (tail.length() < 4000) tail.append(line).append('\n');
                }
                if (fw != null) { try { fw.close(); } catch (Throwable ignored) {} }
                int code = proc.waitFor();
                if (code == 0) {
                    Log.i(TAG, "default plugin: 安装成功（重启引擎后生效），日志 " + logFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "default plugin: 安装失败 exit=" + code + "，输出尾部："
                            + tail.substring(Math.max(0, tail.length() - 600)));
                }
            } catch (Throwable t) {
                Log.w(TAG, "default plugin: 安装异常", t);
            } finally {
                defaultPluginBusy = false;
            }
        }}, "dsh-plugin-install").start();
    }

    /**
     * v1.21：给 DSH **预置**一个"文件管理器里找得到"的默认工作区。
     *
     * 为什么需要（真机 + 模拟器实测）：DSH 的工作区只能从它**自己的目录空间**里选 ——
     * 工作区存储里那条记录是 `{"path":"<filesDir>","title":"files"}`，那个选择器的根也是
     * `<filesDir>`，用户**选不到** /sdcard/DeepSeekHarness/Workspace。
     * 而用户诉求是"默认就是 /sdcard/DeepSeekHarness/Workspace、不必手动选"。
     * 所以这里直接写 DSH 的工作区存储（`dshhome/storages/workspace.json`，unit version 2）：
     *   · 一条工作区都没有 → 建我们的记录，并设为 global.defaultWorkspaceId（前端就不再让用户选）；
     *   · 已有工作区       → **只追加**我们的记录（已存在则跳过），不动既有记录、不改默认值。
     * 只在**引擎启动前**做（避免与运行中的引擎抢写），并留 `.bak-seed` 备份；失败只记日志。
     */
    private void seedDefaultWorkspaceRecord(File payload, File wsDir) {
        try {
            if (wsDir == null) return;
            String path = wsDir.getAbsolutePath();
            String title = wsDir.getName();
            File store = new File(payload, "dshhome/storages/workspace.json");
            org.json.JSONObject root;
            if (store.exists() && store.length() > 0) {
                try {
                    root = new org.json.JSONObject(readFileText(store));
                } catch (Throwable t) {
                    Log.w(TAG, "workspace.json 解析失败，跳过预置（不冒险改坏它）", t);
                    return;
                }
            } else {
                root = new org.json.JSONObject();
                org.json.JSONObject unit = new org.json.JSONObject();
                unit.put("name", "workspace");
                unit.put("version", 2);
                root.put("unit", unit);
            }
            org.json.JSONObject tables = root.optJSONObject("tables");
            if (tables == null) { tables = new org.json.JSONObject(); root.put("tables", tables); }
            org.json.JSONObject workspaces = tables.optJSONObject("workspaces");
            if (workspaces == null) { workspaces = new org.json.JSONObject(); tables.put("workspaces", workspaces); }
            org.json.JSONObject global = root.optJSONObject("global");
            if (global == null) { global = new org.json.JSONObject(); root.put("global", global); }
            org.json.JSONArray ids = global.optJSONArray("workspaceIds");
            if (ids == null) { ids = new org.json.JSONArray(); global.put("workspaceIds", ids); }
            if (global.optJSONArray("archivedSessionIds") == null) global.put("archivedSessionIds", new org.json.JSONArray());
            if (global.optJSONArray("pinnedSessionIds") == null) global.put("pinnedSessionIds", new org.json.JSONArray());

            // 已有同样路径的工作区？→ 什么都不用做
            String existingId = null;
            java.util.Iterator<String> it = workspaces.keys();
            while (it.hasNext()) {
                String id = it.next();
                org.json.JSONObject w = workspaces.optJSONObject(id);
                if (w != null && path.equals(w.optString("path"))) { existingId = id; break; }
            }
            if (existingId != null) {
                // 顺手把默认值指向它（仅当还没有默认值）
                if (!global.has("defaultWorkspaceId")) {
                    global.put("defaultWorkspaceId", existingId);
                    backupThenWrite(store, root);
                    Log.i(TAG, "default workspace: 已有记录，已设为默认 " + path);
                }
                return;
            }
            boolean hadNone = workspaces.length() == 0;
            String id = java.util.UUID.randomUUID().toString();
            String now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .format(new java.util.Date());
            org.json.JSONObject w = new org.json.JSONObject();
            w.put("path", path);
            w.put("title", title);
            w.put("sessionIds", new org.json.JSONArray());
            w.put("createdAt", now);
            w.put("updatedAt", now);
            workspaces.put(id, w);
            ids.put(id);
            global.put("initialized", true);
            if (hadNone && !global.has("defaultWorkspaceId")) global.put("defaultWorkspaceId", id);
            backupThenWrite(store, root);
            Log.i(TAG, "default workspace: 已预置 " + path + (hadNone ? "（并设为默认）" : "（追加）"));
        } catch (Throwable t) {
            Log.w(TAG, "seedDefaultWorkspaceRecord failed", t);
        }
    }

    /** 先备份再写（备份失败也继续写；只保留一份 .bak-seed）。 */
    private void backupThenWrite(File store, org.json.JSONObject root) throws Exception {
        try {
            File bak = new File(store.getParentFile(), "workspace.json.bak-seed");
            if (store.exists() && !bak.exists()) {
                byte[] buf = new byte[64 * 1024];
                java.io.FileInputStream in = new java.io.FileInputStream(store);
                java.io.FileOutputStream out = new java.io.FileOutputStream(bak);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                    try { out.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        writeFileText(store, root.toString(2) + "\n");
    }

    /** 退出确认对话框（浮动按钮与系统返回键共用） */    private void confirmExit() {
        // v1.13.12 文案纠偏：退出只是关掉本界面，引擎 node 进程是独立子进程，会在后台继续运行
        // （这正是"手机当服务器"的设计意图，不需要停）。旧文案"服务器将停止运行"与实际行为不符。
        conDialog("退出 DeepSeek Harness", "确定要退出吗？界面会关闭，服务器将在后台继续运行。", "退出", new Runnable() {
            @Override public void run() {
                stopKeepAliveService(); // 用户主动退出：停止保活服务
                finish();
            }
        }, "取消");
    }

    /**
     * v1.13.11：修「状态栏不显示」「状态栏没有沉浸」两个问题，实现要点有三：
     * ① 「颜色之前根本没生效」。父主题 Theme.Black.NoTitleBar 是 Holo 时代主题，
     *   不设 windowDrawsSystemBarBackgrounds → Window 上缺 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS，
     *   于是 setStatusBarColor()/setNavigationBarColor() 全是空操作（Holo 主题默认色是纯黑）：
     *   实测顶栏恒为 #000000，而浅色模式下又给了 SYSTEM_UI_FLAG_LIGHT_STATUS_BAR（深色图标）
     *   → 深色图标画在纯黑条上 = 时间/信号/电池全看不见。这就是「状态栏不显示」。
     *   现在显式 addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)，颜色才真正落地。
     * ② 底色不再固定用 cBg()，而优先用「页面实测背景色」（refreshPageBackground()）：
     *   DSH 前端底色实测 #151517，与壳底色 #0b0f1a 并不相同 → 状态栏会与页面割裂成一条色带，
     *   观感上就是「没有沉浸」。取到页面真实底色后，状态栏/导航栏与页面同色，视觉上无缝。
     * ③ 图标深浅按「底色亮度」判定，而不是按系统深浅色判定：用户在前端手动选「浅色/深色」时
     *   系统设置并不跟着变，只有按底色亮度算才不会出现「浅底配白图标」。
     */
    private void applyStatusBar() {
        applySystemBars(chromeBg());
    }

    /** 壳的界面底色：优先用页面实测底色，未取到（启动页/控制台）时用主题底色。 */
    private int chromeBg() {
        if (pageBgColor != 0) return pageBgColor;
        // v1.17.4：主题可指定状态栏/导航栏底色（缺省 auto = 跟随页面或主题底色）
        ConsoleTheme t = conTheme;
        if (t != null) {
            int sb = t.statusBarColor(conDark());
            if (sb != 0) return sb;
        }
        return cBg();
    }

    private void applySystemBars(int barColor) {
        try {
            android.view.Window w = getWindow();
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(barColor);
            w.setNavigationBarColor(barColor);
            android.view.View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            boolean lightBar = isLightColor(barColor);
            if (lightBar) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else          flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                if (lightBar) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else          flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
    }

    /** 底色是否偏亮 —— 决定状态栏/导航栏图标用深色还是浅色。 */
    private boolean isLightColor(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return lum > 140d;
    }

    /**
     * 读页面实测背景色并刷新状态栏/导航栏与壳底色。
     * 页面主题由 DSH 前端自己的偏好决定（light/dark/system），与壳的系统深浅色「不一定一致」，
     * 所以只能从页面实际渲染结果里取，不能靠猜。
     */
    private void refreshPageBackground() {
        if (webView == null) return;
        try {
            // body 透明（全透明底）时退回 <html> 的底色 —— 有些前端把底色画在根元素上
            webView.evaluateJavascript(
                "(function(){try{function op(s){return s&&!/rgba?\\([^)]*,\\s*0\\s*\\)/.test(s);}"
                + "var b=getComputedStyle(document.body).backgroundColor||'';if(op(b))return b;"
                + "var h=getComputedStyle(document.documentElement).backgroundColor||'';return op(h)?h:''}catch(e){return ''}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        final int c = parseCssColor(v);
                        if (c == 0 || c == pageBgColor) return;
                        pageBgColor = c;
                        applyStatusBar();
                        if (engineRoot != null) engineRoot.setBackgroundColor(c);
                        if (webView != null) webView.setBackgroundColor(c);
                        applyShellPalette();   // v1.21：页面换色调 → 壳跟着换
                    }
                });
        } catch (Throwable ignored) {}
    }

    /** 解析 "rgb(r, g, b)" / "rgba(r, g, b, a)"；透明或解析失败返回 0（交给主题底色兜底）。 */
    private int parseCssColor(String css) {
        try {
            if (css == null || css.indexOf("rgb") < 0) return 0;
            int a = css.indexOf('('), b = css.indexOf(')');
            if (a < 0 || b <= a) return 0;
            String[] parts = css.substring(a + 1, b).split(",");
            if (parts.length < 3) return 0;
            int r = (int) Float.parseFloat(parts[0].trim());
            int g = (int) Float.parseFloat(parts[1].trim());
            int bl = (int) Float.parseFloat(parts[2].trim());
            int al = 255;
            if (parts.length >= 4) al = Math.round(Float.parseFloat(parts[3].trim()) * 255f);
            if (al < 8) return 0;   // 全透明取不到底色
            return Color.argb(al, r, g, bl);
        } catch (Throwable t) {
            return 0;
        }
    }


    // ============ 界面主题色（跟随系统深/浅色，权限页与加载页共用）============
    // 取色入口：颜色值统一定义在 res/values/colors.xml（配色/主题统一管理），
    // 这里按主题挑对应的资源；代码其他位置禁止再写死十六进制颜色。
    private boolean isDark() {
        return themePrefersDark();
    }

    // ---- v1.13.12 主题设置（跟随系统 / 浅色 / 深色）----
    // 之前壳只能跟随系统深浅色；前端"跟随系统"又是看 App 主题的 isLightTheme 属性，
    // 用户想白天用深色也没办法。现在主题偏好在控制台可设，onCreate 按偏好选主题，
    // WebView 的 prefers-color-scheme 自然跟着偏好走（机制见 styles.xml 的注释）。
    /** 0=跟随系统 1=浅色 2=深色 */
    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    private int themeMode() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("theme_mode", THEME_SYSTEM);
        } catch (Throwable t) { return THEME_SYSTEM; }
    }

    /** 主题偏好解析出的"是否深色"：浅色偏好恒 false，深色偏好恒 true，跟随系统才看系统 uiMode。 */
    private boolean themePrefersDark() {
        int mode = themeMode();
        if (mode == THEME_LIGHT) return false;
        if (mode == THEME_DARK) return true;
        int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private String themeModeLabel(int mode) {
        if (mode == THEME_LIGHT) return "浅色";
        if (mode == THEME_DARK) return "深色";
        return "跟随系统";
    }

    /** 控制台「主题」行弹出的三选一对话框；选择后重建 Activity 使主题立即生效。 */
    private void conThemeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int cur = themeMode();
        for (int i = 0; i <= 2; i++) {
            final int mode = i;
            TextView row = cText(themeModeLabel(i) + (i == cur ? "  ✓" : ""), 14f, cText(), false);
            row.setPadding(0, dp(12), 0, dp(12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeDialogOverlay();
                    if (mode == themeMode()) return;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("theme_mode", mode).apply();
                    recreate(); // 主题（含 WebView 的 prefers-color-scheme）随 onCreate 重新生效
                }
            });
            box.addView(row);
        }
        // v1.17.4：控制台主题包配置的「恢复默认」（把 console.json 改名留底，不删，可反悔）
        if (conThemeFile().exists()) {
            box.addView(cSep(dp(10)));
            TextView resetRow = cText("恢复默认控制台主题（console.json → .disabled-…）", 12.5f, cRed(), false);
            resetRow.setPadding(0, dp(12), 0, dp(4));
            resetRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeDialogOverlay(); conThemeReset(); }
            });
            box.addView(resetRow);
        }
        // v1.19.6：控制台风格开关**下线**。主控台只有新版（方案 B）一套，
        // 不再提供"回到旧卡片版"的入口（用户 2026-10-05：「就是完整版」= 新版就是完整版）。
        // 旧偏好 console_ui_style 与主题 layout.style 都不再被读取（见 consoleUiStyle()）。
        conDialogView("界面主题", box, null, null, "关闭");
    }
    // v1.17.4：这 9 个 c*() 是控制台配色的「唯一出口」，主题包只在这里覆盖；
    // 没配置 / 该项没写时返回的仍是原来那套内置色（视觉与旧版一致）。
    private int cBg() {
        // v1.21：DSH 页面底色已知时，壳底色**直接跟随它** —— 这是"壳与页面同一色调"的关键；
        // 主题里显式写过的 bg 仍然优先（conColor 内部保证"显式写过的值永远优先"）。
        int def = pageBgColor != 0 ? pageBgColor
                : getColor(conDark() ? R.color.shell_bg_dark : R.color.shell_bg_light);
        return conColor("bg", def);
    }
    private int cCard() { return conCardsAlpha(conColor("card", getColor(conDark() ? R.color.shell_card_dark : R.color.shell_card_light))); }
    private int cText() { return conColor("text", getColor(conDark() ? R.color.text_on_dark : R.color.text_on_light)); }
    private int cSub() { return conColor("sub", getColor(conDark() ? R.color.sub_on_dark : R.color.sub_on_light)); }
    private int cGreen() { return conColor("green", getColor(R.color.status_green)); }
    private int cRed() { return conColor("red", getColor(R.color.status_red)); }

    private long deleteRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        long total = 0;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) total += deleteRecursive(c);
        }
        total += f.length();
        if (!f.delete()) {
            // 删除失败（通常是目录仍非空，因子项删除失败）。再递归扫一遍重试。
            if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) for (File c : children) total += deleteRecursive(c);
            }
            f.delete();
        }
        return total;
    }

    // ② ABI 检测：node 引擎仅 arm64，非 arm64 设备会启动失败——尽早提示用户
    private void checkAbiCompat() {
        try {
            if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) return;
            String abi = Build.SUPPORTED_ABIS[0];
            boolean arm64 = abi.startsWith("arm64") || abi.contains("arm64-v8a");
            if (arm64) return; // 支持，正常继续
            // 非 arm64 设备：引擎（node arm64 二进制）无法原生运行，提示但不阻止。
            // 用 Toast 轻提示且只提示一次（SharedPreferences 记录）：原来是模态 AlertDialog，
            // 真机 arm64 根本不会触发，而 x86_64 模拟器上每次切主题/旋转重建 Activity 都弹一次，
            // 遮住操作还很吵（用户要求改成 Toast 式提示）。
            // 位宽用 Process.is64Bit() 如实描述（API 23+，minSdk 24 可直接用），别猜。
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (sp.getBoolean("abi_warned", false)) return;
            sp.edit().putBoolean("abi_warned", true).apply();
            final String abiDesc = (android.os.Process.is64Bit() ? "64 位 " : "32 位 ") + abi;
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        Toast.makeText(MainActivity.this,
                                "设备架构为 " + abiDesc + "，DSH 引擎仅提供 arm64 版本，AI 引擎可能无法启动",
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "checkAbiCompat error", t);
        }
    }

    // ④ 电池优化引导：App 被系统限制后台时，引擎挂后台可能被杀——提示用户设"不限制"
    private void checkBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return; // 已"不限制"，正常
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        conDialog("建议：允许后台运行",
                                "当前应用被系统限制后台活动，AI 执行任务时挂后台可能被系统杀掉。\n\n建议把本应用设为「不限制」电池优化，确保任务持续运行。",
                                "去设置", new Runnable() { @Override public void run() {
                                    openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                }}, "暂不");
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "checkBatteryOptimization error", t);
        }
    }

    // ⑧ 更新提示：后台查 GitHub Releases 最新 tag，与本地 versionName 比对，有新版弹提示
    private void checkForUpdate(final boolean manual) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL("https://api.github.com/repos/woaiys3/deepseek-harness-android-app/releases/latest");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("User-Agent", "dsh-android");
                    int code = c.getResponseCode();
                    if (code != 200) { c.disconnect(); if (manual) ui.post(new Runnable() { @Override public void run() { conToast("检查更新失败（网络）"); } }); return; }
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    in.close();
                    c.disconnect();
                    String json = new String(out.toByteArray(), "UTF-8");
                    // 解析 "tag_name":"vX.Y.Z"
                    String tag = null;
                    int ti = json.indexOf("\"tag_name\"");
                    if (ti >= 0) {
                        int q1 = json.indexOf('"', ti + 10);
                        int q2 = q1 >= 0 ? json.indexOf('"', q1 + 1) : -1;
                        if (q1 >= 0 && q2 > q1) tag = json.substring(q1 + 1, q2);
                    }
                    if (tag == null || tag.isEmpty()) {
                        if (manual) ui.post(new Runnable() { @Override public void run() { conToast("检查更新失败（响应异常）"); } });
                        return;
                    }
                    String latest = tag.replace("v", "").replace("-lite", "").replace("-beta", "");
                    String local = "";
                    try { local = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
                    // 只比较主版本号（数字部分），忽略后缀
                    final String fLocal = local;
                    if (isNewerVersion(latest, fLocal)) {
                        final String ftag = tag;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    // v1.18（B12）：变体感知——官方 Release 的签名与本变体不同，不能覆盖安装
                                    // （社区版/自签包尤其如此），文案必须说清，别让用户白下载。
                                    String hint = "official".equals(BuildVariant.VARIANT)
                                            ? "前往 GitHub Releases 下载更新（正式版 / Lite 共存版可选）。"
                                            : "注意：当前是「" + BuildVariant.VARIANT + "」变体（" + BuildVariant.APP_ID
                                              + "），签名与官方包不同，官方 APK 无法覆盖安装本变体；\n"
                                              + "请用同变体源码自行构建，或先「导出全部数据」再卸载换装官方版。";
                                    conDialog("发现新版本 " + ftag,
                                            "当前版本 " + fLocal + "，最新 " + ftag + "。\n\n" + hint,
                                            "去下载", new Runnable() { @Override public void run() {
                                                try {
                                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                                            Uri.parse("https://github.com/woaiys3/deepseek-harness-android-app/releases")));
                                                } catch (Throwable ignored) {}
                                            }}, "稍后");
                                } catch (Throwable ignored) {}
                            }
                        });
                    } else if (manual) {
                        ui.post(new Runnable() { @Override public void run() { conToast("已是最新版本（" + fLocal + "）"); } });
                    }
                } catch (final Throwable t) {
                    // 网络失败/离线：启动时的自动检查静默跳过；手动检查给反馈
                    if (manual) ui.post(new Runnable() { @Override public void run() { conToast("检查更新失败：" + t.getMessage()); } });
                }
            }
        }, "update-check").start();
    }

    /** 简单版本号比较："1.4.0" vs "1.3.3" → true（1.4.0 更新）。 */
    private boolean isNewerVersion(String latest, String local) {
        try {
            String[] a = latest.split("\\.");
            String[] b = (local == null ? "" : local).split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? parseIntSafe(a[i]) : 0;
                int y = i < b.length ? parseIntSafe(b[i]) : 0;
                if (x != y) return x > y;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private int parseIntSafe(String s) {
        // 版本段可能带后缀（如 "5-test"/"5-lite"）：提取前导数字，避免 1.6.5-test 被误判为低于 1.6.1
        if (s == null) return 0;
        int i = 0;
        String t = s.trim();
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i == 0) return 0;
        try { return Integer.parseInt(t.substring(0, i)); } catch (Throwable ex) { return 0; }
    }

    // 捕获未处理异常，写到外部崩溃日志（便于无 adb 时排查闪退）
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    File dir = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "crash.log");
                    FileOutputStream fos = new FileOutputStream(f, true);
                    String s = "\n==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + " thread=" + t.getName() + " ====\n";
                    fos.write(s.getBytes("UTF-8"));
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    fos.write(sw.toString().getBytes("UTF-8"));
                    fos.close();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    // 清理外部公共目录下遗留的 .trash-* 垃圾目录（清空数据 rename 后后台删除未完成）。
    private void cleanupTrashDirs(File externalRoot) {
        File[] children = externalRoot.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory() && c.getName().startsWith(".trash-")) {
                deleteRecursive(c);
            }
        }
    }

    // ============ 首次使用 · 翻页式权限引导（v1.13.12 重做） ============
    // 旧版是一屏 9 行权限列表，用户不知道每项是干什么的、也不能跳过某一项。
    // 现在改成翻页式：一页只讲一个权限（它做什么 / 授权后能得到什么 / 当前状态），
    // 每页都可以「跳过」，走完再统一「开始使用」。返回键/重进时按 setup_done 判断不再进入。

    /** 引导页的一页。 */
    private static class GuidePage {
        String title;            // 权限名
        String desc;             // 这个权限是做什么的（页面上显示给用户看）
        String actionLabel;      // 授权按钮文案
        StatusProvider provider; // 是否已授权
        View.OnClickListener action; // 授权动作
    }

    private final java.util.ArrayList<GuidePage> guidePages = new java.util.ArrayList<GuidePage>();
    private int guideIndex = 0;                 // 当前页（== guidePages.size() 时是最后的完成页）
    private LinearLayout guideBody = null;      // 页面内容区（每页重建）
    private LinearLayout guideRoot = null;      // v1.21：引导页最外层容器（深浅色切换时要整页刷新底色）
    // v1.21（UI 统一）：引导页顶部细进度条（轨道 + 强调色填充，跟随壳调色板）
    private LinearLayout guideProgressTrack = null;
    private View guideProgressFill = null, guideProgressSpacer = null;
    private TextView guideDots = null;          // 顶部进度文字（第 X / N 步）
    private Button guidePrevBtn = null, guideNextBtn = null;
    private TextView guideSkipBtn = null;
    private Button guideActionBtn = null;       // 当前页的授权按钮（授权回来后要刷新成"已授权"）

    /** 定义每一页（顺序即翻页顺序，与旧列表一致）。 */
    private void buildGuidePages() {
        guidePages.clear();

        GuidePage p1 = new GuidePage();
        p1.title = "存储权限"; p1.actionLabel = "去授权";
        p1.desc = "读写手机上的文件：导入/导出内容、把 AI 生成的文件存到手机、写日志与运行数据。\n\n不给的话，AI 无法保存任何文件。";
        p1.provider = new StatusProvider() { @Override public boolean granted() {
            return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
        }};
        p1.action = new View.OnClickListener() { @Override public void onClick(View v) {
            requestPermissions(new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
        }};
        guidePages.add(p1);

        GuidePage p2 = new GuidePage();
        p2.title = "所有文件访问"; p2.actionLabel = "去授权";
        p2.desc = "访问手机上所有文件（Android 11 及以上需要单独授权，11 以下由存储权限覆盖）。\n\nAI 读写你的项目文件、整理文档都靠它。";
        p2.provider = new StatusProvider() { @Override public boolean granted() {
            if (Build.VERSION.SDK_INT >= 30) {
                return Environment.isExternalStorageManager();
            } else {
                return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
            }
        }};
        p2.action = new View.OnClickListener() { @Override public void onClick(View v) {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception e2) {
                        Log.w(TAG, "无法打开所有文件访问设置", e2);
                    }
                }
            } else {
                requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
            }
        }};
        guidePages.add(p2);

        GuidePage p3 = new GuidePage();
        p3.title = "悬浮窗"; p3.actionLabel = "去授权";
        p3.desc = "在其它应用之上显示内容：桌面小鲸鱼状态窗、虚拟屏预览窗都靠它。\n\n不给的话没有小鲸鱼，也看不到虚拟屏画面。";
        p3.provider = new StatusProvider() { @Override public boolean granted() {
            return Settings.canDrawOverlays(MainActivity.this);
        }};
        p3.action = new View.OnClickListener() { @Override public void onClick(View v) {
            openSystemSetting(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        }};
        guidePages.add(p3);

        GuidePage p4 = new GuidePage();
        p4.title = "修改系统设置"; p4.actionLabel = "去授权";
        p4.desc = "允许读写系统设置，比如调亮度、音量、保持屏幕常亮。\n\nAI 帮你改设置时需要；不给只是这类操作不可用。";
        p4.provider = new StatusProvider() { @Override public boolean granted() {
            return Settings.System.canWrite(MainActivity.this);
        }};
        p4.action = new View.OnClickListener() { @Override public void onClick(View v) {
            openSystemSetting(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        }};
        guidePages.add(p4);

        GuidePage p5 = new GuidePage();
        p5.title = "使用情况访问"; p5.actionLabel = "去授权";
        p5.desc = "查看应用使用时长与统计信息，AI 才能回答\"我今天用了多久微信\"这类问题。\n\n不给的话应用使用统计相关功能不可用。";
        p5.provider = new StatusProvider() { @Override public boolean granted() {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        }};
        p5.action = new View.OnClickListener() { @Override public void onClick(View v) {
            openSystemSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        }};
        guidePages.add(p5);

        GuidePage p6 = new GuidePage();
        p6.title = "安装未知来源应用"; p6.actionLabel = "去授权";
        p6.desc = "允许安装 APK：侧载应用、AI 帮你下载并安装应用时需要。\n\n不给的话 AI 无法替你安装应用。";
        p6.provider = new StatusProvider() { @Override public boolean granted() {
            if (Build.VERSION.SDK_INT < 26) return true;
            return getPackageManager().canRequestPackageInstalls();
        }};
        p6.action = new View.OnClickListener() { @Override public void onClick(View v) {
            openSystemSetting(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        }};
        guidePages.add(p6);

        GuidePage p7 = new GuidePage();
        p7.title = "忽略电池优化"; p7.actionLabel = "去授权";
        p7.desc = "让本应用在后台常驻、不被系统提前杀掉 —— 引擎要一直在跑，这是稳定在线的前提。\n\n强烈建议授权；不给的话切后台后引擎可能被杀。";
        p7.provider = new StatusProvider() { @Override public boolean granted() {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }};
        p7.action = new View.OnClickListener() { @Override public void onClick(View v) {
            openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        }};
        guidePages.add(p7);

        GuidePage p8 = new GuidePage();
        p8.title = "通知权限"; p8.actionLabel = "去授权";
        p8.desc = "接收 AI 完成任务等通知，人不在应用里也能知道任务跑完了。\n\n不给的话收不到这些提醒（其它功能不受影响）。";
        p8.provider = new StatusProvider() { @Override public boolean granted() {
            if (Build.VERSION.SDK_INT < 33) return true;
            return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        }};
        p8.action = new View.OnClickListener() { @Override public void onClick(View v) {
            // v1.21（真机实测修复）：**直接打开系统「通知设置」页**，不再走运行时申请。
            // 原因：本应用 targetSdk=28，在 Android 13+ 上 POST_NOTIFICATIONS 的运行时申请
            // 经常**什么都不弹**（该权限实际由"通知渠道 / 系统通知开关"控制）→
            // 用户点「去授权」毫无反应（真机报障）。设置页在所有状态下都可用：
            // 开/关通知、看被屏蔽的渠道，都能在那里处理。
            openSystemSetting(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        }};
        guidePages.add(p8);

        GuidePage p9 = new GuidePage();
        p9.title = "无障碍服务"; p9.actionLabel = "去开启";
        p9.desc = "这是 AI 能「看见并操作手机界面」的前提：读屏、点按、输入、截图理解"
                + "（工具 android_screen / tap / type / see），不需要 root，也不需要 Shizuku。\n\n"
                + "系统不允许弹窗授权，只能在系统设置里手动打开本应用的无障碍服务 —— "
                + "点下面的按钮会直接跳到那个页面。\n\n"
                + "想用「帮我点一下」「看看这个界面」这类能力，这一步必须开。";
        p9.provider = new StatusProvider() { @Override public boolean granted() {
            return conA11yEnabled();
        }};
        p9.action = new View.OnClickListener() { @Override public void onClick(View v) {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Throwable t) { openSystemSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS); }
        }};
        guidePages.add(p9);

        GuidePage p10 = new GuidePage();
        p10.title = "读取应用列表"; p10.actionLabel = "去授权";
        p10.desc = "让 AI 知道你装了哪些应用，并帮你启动它们（例如「帮我打开微信」「列出我装的游戏」）。\n\n"
                + "点下面的按钮会触发系统的授权询问，在这里一次性允许掉，"
                + "免得用 AI 的时候突然弹框打断你。";
        p10.provider = new StatusProvider() { @Override public boolean granted() {
            return conAppListOk();
        }};
        p10.action = new View.OnClickListener() { @Override public void onClick(View v) {
            requestAppListAccess();
        }};
        guidePages.add(p10);

        GuidePage p11 = new GuidePage();
        p11.title = "Shizuku / Root 特权（可选）"; p11.actionLabel = "去配置";
        p11.desc = "授权后 AI 可以执行系统级操作：安装/卸载应用、改系统设置、模拟点击等。\n\n不给也完全能用 —— 文件读写、预览、编辑只需要上面的「所有文件访问」。";
        p11.provider = new StatusProvider() { @Override public boolean granted() {
            // 只读缓存：root 探测在后台线程执行（probeShizuku），不在主线程跑 su
            return (shizukuOk != null && shizukuOk) || (rootOk != null && rootOk);
        }};
        p11.action = new View.OnClickListener() { @Override public void onClick(View v) {
            showShizukuDialog();
        }};
        guidePages.add(p11);
    }

    private void showPermissionScreen() {
        buildGuidePages();
        guideIndex = 0;
        // v1.21（Q2-C）：向导展示期间就把 payload 解压跑起来 —— 首次解压 1.5 万条目 / ~290MB，
        // 用户读权限说明的这段时间足够跑完；走完向导点「开始使用」时文件已就绪，无需再等。
        warmPayloadAsync();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg());
        guideRoot = root;   // v1.21：记下来，深浅色切换时整页刷新

        // 顶部：品牌标题 + 进度
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(24), dp(20), dp(24), dp(8));
        guideDots = new TextView(this);
        guideDots.setTextColor(cSub());
        guideDots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.addView(guideDots);
        TextView headTitle = new TextView(this);
        headTitle.setText("首次使用 · 配置手机权限");
        headTitle.setTextColor(cText());
        headTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        headTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        headTitle.setPadding(0, dp(6), 0, 0);
        head.addView(headTitle);

        // v1.21（UI 统一）：细进度条 —— "还要几步"一眼可见；轨道/填充都走壳调色板，
        // 深色浅色自动适配（原来是纯文字"第 X / N 步"，看不出还剩多少）。
        guideProgressTrack = new LinearLayout(this);
        guideProgressTrack.setOrientation(LinearLayout.HORIZONTAL);
        guideProgressTrack.setBackgroundColor(cTrack());
        guideProgressFill = new View(this);
        guideProgressFill.setBackgroundColor(cAccent());
        guideProgressSpacer = new View(this);
        guideProgressTrack.addView(guideProgressFill, new LinearLayout.LayoutParams(0, dp(3), 1f));
        guideProgressTrack.addView(guideProgressSpacer, new LinearLayout.LayoutParams(0, dp(3), 1f));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        trackLp.topMargin = dp(12);
        head.addView(guideProgressTrack, trackLp);
        root.addView(head);

        // 中间：每页内容（滚动）
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        guideBody = new LinearLayout(this);
        guideBody.setOrientation(LinearLayout.VERTICAL);
        guideBody.setPadding(dp(24), dp(8), dp(24), dp(12));
        scroll.addView(guideBody, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部：跳过 / 上一步 / 下一步
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(20), dp(10), dp(20), dp(16));

        guideSkipBtn = new TextView(this);
        guideSkipBtn.setText("跳过");
        guideSkipBtn.setTextColor(cSub());
        guideSkipBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        guideSkipBtn.setPadding(dp(6), dp(10), dp(6), dp(10));
        guideSkipBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            guideGo(+1);
        }});
        nav.addView(guideSkipBtn);

        guidePrevBtn = cButton("上一步", false);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        prevLp.leftMargin = dp(8);
        guidePrevBtn.setLayoutParams(prevLp);
        guidePrevBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            guideGo(-1);
        }});
        nav.addView(guidePrevBtn);

        guideNextBtn = cButton("下一步", true);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nextLp.leftMargin = dp(8);
        guideNextBtn.setLayoutParams(nextLp);
        guideNextBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (guideIndex >= guidePages.size()) {
                guideFinish();          // 完成页上的「下一步」就是开始使用
            } else {
                guideGo(+1);
            }
        }});
        nav.addView(guideNextBtn);
        root.addView(nav);

        setContentView(root);
        renderGuidePage();
        probeShizuku();
    }

    /** 翻一页（dir=+1/-1），越界即停在完成页/首页。 */
    private void guideGo(int dir) {
        int next = guideIndex + dir;
        if (next < 0) next = 0;
        if (next > guidePages.size()) next = guidePages.size();
        if (next == guideIndex) return;
        guideIndex = next;
        renderGuidePage();
    }

    /** 走完引导：记录 setup_done 并进入引擎流程（与旧「开始使用」一致）。 */
    private void guideFinish() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("setup_done", true).apply();
        showEngineScreen();
        startEngine();
    }

    /** 渲染当前页（guideIndex == guidePages.size() 时是完成页）。 */
    private void renderGuidePage() {
        if (guideBody == null) return;
        guideBody.removeAllViews();
        permRows.clear();
        guideActionBtn = null;
        boolean finishPage = guideIndex >= guidePages.size();
        int total = guidePages.size() + 1;

        guideDots.setText(finishPage
                ? "准备完成 · 最后一步"
                : "第 " + (guideIndex + 1) + " / " + total + " 步");
        guidePrevBtn.setEnabled(guideIndex > 0);
        guideNextBtn.setText(finishPage ? "开始使用" : "下一步");
        guideSkipBtn.setVisibility(finishPage ? View.GONE : View.VISIBLE);
        guideSkipBtn.setText(guideIndex == guidePages.size() - 1 ? "跳过" : "跳过这页");

        // v1.21（UI 统一）：细进度条跟着步数走（完成页 = 满格）
        if (guideProgressFill != null && guideProgressSpacer != null) {
            float done = finishPage ? total : (guideIndex + 1f);
            float rest = Math.max(total - done, 0.0001f);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(3));
            flp.weight = Math.max(done, 0.0001f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(3));
            slp.weight = rest;
            guideProgressFill.setLayoutParams(flp);
            guideProgressSpacer.setLayoutParams(slp);
        }

        if (finishPage) {
            TextView t = new TextView(this);
            t.setText("配置完成");
            t.setTextColor(cText());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            guideBody.addView(t, cTop(dp(18)));

            guideBody.addView(cText("权限可以随时在控制台的「权限」页里再改。现在可以进入 DeepSeek Harness 了。",
                    13.5f, cSub(), false), cTop(dp(10)));

            // AI 工作区（可选）：沿用原来的选择逻辑
            LinearLayout wsRow = new LinearLayout(this);
            wsRow.setOrientation(LinearLayout.HORIZONTAL);
            wsRow.setGravity(Gravity.CENTER_VERTICAL);
            wsRow.setPadding(dp(16), dp(14), dp(16), dp(14));
            wsRow.setBackgroundColor(cCard());
            LinearLayout.LayoutParams wslp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            wslp.topMargin = dp(18);
            wsRow.setLayoutParams(wslp);
            LinearLayout wsLeft = new LinearLayout(this);
            wsLeft.setOrientation(LinearLayout.VERTICAL);
            wsLeft.addView(cText("AI 工作区（可选）", 15f, cText(), true));
            workspaceDescView = new TextView(this);
            workspaceDescView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            workspaceDescView.setTextColor(cSub());
            workspaceDescView.setPadding(0, dp(3), 0, 0);
            wsLeft.addView(workspaceDescView);
            wsRow.addView(wsLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            wsRow.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                onWorkspaceRowClick();
            }});
            guideBody.addView(wsRow);

            // v1.21（UI 统一）：完成页给一行「界面外观」—— 首次使用时就能选深浅色，
            // 与 DSH 页面色调对齐（跟随页面 = 默认，跟随实测页面底色）。
            LinearLayout schemeWrap = new LinearLayout(this);
            schemeWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            swLp.topMargin = dp(12);
            schemeWrap.setLayoutParams(swLp);
            schemeWrap.addView(shellSchemeRow());
            guideBody.addView(schemeWrap);

            refreshAllStatuses();
            return;
        }

        final GuidePage pg = guidePages.get(guideIndex);
        boolean granted = false;
        try { granted = pg.provider.granted(); } catch (Throwable ignored) {}

        TextView t = new TextView(this);
        t.setText(uiPlain(pg.title));   // v1.21：引导页文案同样不许出现 markdown 记号
        t.setTextColor(cText());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        guideBody.addView(t, cTop(dp(14)));

        TextView d = new TextView(this);
        d.setText(uiPlain(pg.desc));    // v1.21：同上，去掉 markdown 记号
        d.setTextColor(cSub());
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        d.setLineSpacing(dp(3), 1f);
        guideBody.addView(d, cTop(dp(10)));

        // v1.21（UI 统一）：状态 + 授权按钮收进**一张卡片**（与控制台卡片同款圆角/描边/底色），
        // 页面从"标题 + 散落文字 + 裸按钮"变成"标题 + 卡片"，三个原生页面观感一致。
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(cShape(cCard(), cLine(), 1, 12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(16);
        card.setLayoutParams(cardLp);

        // 状态行（挂进 permRows，onResume / Shizuku 事件回来时 refreshAllStatuses 会统一刷新）
        LinearLayout stRow = new LinearLayout(this);
        stRow.setOrientation(LinearLayout.HORIZONTAL);
        stRow.setGravity(Gravity.CENTER_VERTICAL);
        stRow.addView(cText("当前状态：", 13f, cSub(), false));
        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stRow.addView(status);
        card.addView(stRow);
        PermRow pr = new PermRow();
        pr.status = status;
        pr.provider = pg.provider;
        permRows.add(pr);

        Button act = cButton(pg.actionLabel, true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        alp.topMargin = dp(14);
        act.setLayoutParams(alp);
        act.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try { pg.action.onClick(v); } catch (Throwable ignored) {}
        }});
        act.setEnabled(!granted);
        if (granted) act.setText("已授权 ✓");
        card.addView(act);
        guideBody.addView(card);
        guideActionBtn = act;

        refreshAllStatuses();
    }

    /** refreshAllStatuses 的引导页扩展：授权按钮状态跟着"是否已授权"走。 */
    private void guideSyncButtons() {
        if (guideActionBtn == null || guideBody == null) return;
        if (guideIndex < 0 || guideIndex >= guidePages.size()) return;
        GuidePage pg = guidePages.get(guideIndex);
        boolean granted = false;
        try { granted = pg.provider.granted(); } catch (Throwable ignored) {}
        guideActionBtn.setText(granted ? "已授权 ✓" : pg.actionLabel);
        guideActionBtn.setEnabled(!granted);
    }

    private void refreshAllStatuses() {
        // 线程安全：Shizuku binder 回调、后台线程都可能调用；setText 必须在 UI 线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() {
                @Override public void run() { refreshAllStatuses(); }
            });
            return;
        }
        for (PermRow pr : permRows) {
            boolean g = false;
            try { g = pr.provider.granted(); } catch (Throwable ignored) {}
            pr.status.setText(g ? "已授权" : "未授权");
            pr.status.setTextColor(g ? cGreen() : cRed());
        }
        // 工作区行状态（非权限，显示已设置/未设置）——引导完成页与控制台「权限」页共用同一份状态
        {
            String p = workspacePath();
            boolean unset = (p == null || p.isEmpty());
            if (workspaceDescView != null) {
                workspaceDescView.setText(unset
                        ? "未设置：AI 文件操作在内部目录。点此选择外部文件夹（如 /sdcard/Documents）。"
                        : "已设置：" + p + "（点此更改或恢复默认）");
            }
            if (conWorkspaceDesc != null) {
                conWorkspaceDesc.setText(unset ? "未设置（AI 文件操作在内部目录）" : p);
            }
        }
        // 翻页式引导：当前页的授权按钮状态跟着"是否已授权"走（从系统设置授权回来时刷新）
        guideSyncButtons();
    }

    // ============ AI 工作区（可选） ============
    private static final String KEY_WORKSPACE = "workspace_path";

    /**
     * v1.21（需求 1）：默认工作区 = 安卓用户能在文件管理器里一眼找到的路径。
     *
     * 为什么要有默认值：工作区原本要用户在应用内手动选目录（SAF 选择器），对不熟悉文件系统的
     * 用户是门槛 —— 不选就没有工作区，AI 的文件操作落在内部目录，用户拿文件管理器也找不到产出。
     * 这里给一个固定、可发现的默认值：
     *   /sdcard/DeepSeekHarness/Workspace（文件管理器里就是「全部文件/DeepSeekHarness/Workspace」）
     *
     * 与"变体隔离"的取舍：本 App 有 4 个变体可共存，外部树用的是 /sdcard/&lt;变体目录&gt;；
     * 但工作区按维护者要求用**共用**的 DeepSeekHarness/Workspace（用户视角更简单、更好找）。
     * 若以后要按变体隔离，只改这一处即可。
     */
    /** DSH 自己的默认工作区（与 documentsDirectory 配置配套，见 ensureWorkspaceControllerConfig）。 */
    private File defaultWorkspaceDir() {
        return new File(android.os.Environment.getExternalStorageDirectory(), "deepseek-harness/default-workspace");
    }

    /** 旧默认工作区（v1.21 之前用 /sdcard/DeepSeekHarness/Workspace）；仅用于"把旧偏好升级掉"的判断。 */
    private File legacyWorkspaceDir() {
        return new File(android.os.Environment.getExternalStorageDirectory(), "DeepSeekHarness/Workspace");
    }

    /**
     * v1.21（真机根因修复）：给 workspace-controller 配 `documentsDirectory`。
     *
     * 真机实测：DSH 前端的 `api/workspace/initializeDefault` 返回
     *   {"ok":false,"error":{"code":"gateway/internal",
     *    "message":"system Documents directory is unavailable on android"}}
     * 原因在内核 `dsh-api-workspace-controller/lib/types/default-directory.js`：它只处理
     * darwin/win32/linux 三个平台，其它平台直接 throw —— 而 Node 在 Android 上
     * `process.platform === 'android'`，于是**永远建不出默认工作区**（不是权限问题）。
     * 好在同一文件提供了官方开关：config.documentsDirectory（"explicit deployment override
     * for the system Documents directory"），最终工作区 = <documentsDirectory>/deepseek-harness/default-workspace。
     * 这里保证 profile 补丁里有这条配置（写在 payload 里，App 启动时补齐 → 升级也自动生效）。
     */
    private void ensureWorkspaceControllerConfig(File payload) {
        try {
            File patch = new File(payload, "dshhome/profiles/web/cordis.patch.yml");
            String text = patch.exists() ? readFileText(patch) : "";
            if (text.contains("documentsDirectory")) return;   // 已配好
            String ext = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            StringBuilder sb = new StringBuilder(text);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append("\n# v1.21：Android 上内核解析不出「系统 Documents 目录」，直接导致\n")
              .append("# `api/workspace/initializeDefault` 报 system Documents directory is unavailable on android，\n")
              .append("# 默认工作区永远建不出来。这里用官方提供的 documentsDirectory 覆盖项指定一个可用目录，\n")
              .append("# 最终默认工作区 = <documentsDirectory>/deepseek-harness/default-workspace。\n")
              .append("- id: workspace-controller\n")
              .append("  name: \"@deepseek-ai/dsh-api-workspace-controller\"\n")
              .append("  config:\n")
              .append("    documentsDirectory: ").append(ext).append('\n');
            File parent = patch.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            writeFileText(patch, sb.toString());
            Log.i(TAG, "已写入 workspace-controller.documentsDirectory = " + ext);
        } catch (Throwable t) {
            Log.w(TAG, "ensureWorkspaceControllerConfig failed", t);
        }
    }

    /** 确保默认工作区存在并写进偏好（幂等）。 */
    private void ensureDefaultWorkspace() {
        try {
            String cur = workspacePath();
            File preferred = defaultWorkspaceDir();
            if (cur != null && !cur.isEmpty()) {
                // v1.21（真机报障："默认工作区建立失败"）：之前只在"未设置"时写偏好 ——
                // 首次启动还没拿到存储权限时，只能落到兜底目录；等用户授权后，
                // 偏好已经写着兜底值，再也不会切回"文件管理器里找得到"的那个目录。
                // 这里补上"升级"：当前值如果是**我们自己塞的兜底**（不是用户手选），
                // 且偏好目录现在可写了 → 切过去。
                if (!cur.equals(preferred.getAbsolutePath()) && isOurFallbackWorkspace(cur)
                        && isWritableDir(preferred)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_WORKSPACE, preferred.getAbsolutePath()).apply();
                    Log.i(TAG, "default workspace 升级 -> " + preferred.getAbsolutePath() + "（原：" + cur + "）");
                }
                return;
            }
            File dir = workspaceDirOrFallback();
            if (dir == null) return;   // 极端情况（连应用私有目录都写不了）
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_WORKSPACE, dir.getAbsolutePath()).apply();
            Log.i(TAG, "default workspace -> " + dir.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "ensureDefaultWorkspace failed", t);
        }
    }

    /** 本变体在 /sdcard 下的自带目录（可能与共享工作区目录并存）。 */
    private File variantWorkspaceDir() {
        return new File(new File(android.os.Environment.getExternalStorageDirectory(), pkgRoot()), "Workspace");
    }

    /** 应用专属外部目录（Android 11+ 无需任何存储权限即可写；文件管理器里通常也能看到 Android/data/<pkg>/files）。 */
    private File appExternalWorkspaceDir() {
        File ext = getExternalFilesDir(null);
        return ext == null ? null : new File(ext, "Workspace");
    }

    /** 应用内部目录（永远可写，作为最后兜底；用户看不到，但保证引擎 cwd 有效）。 */
    private File appInternalWorkspaceDir() {
        return new File(getFilesDir(), "Workspace");
    }

    /** 判断某个路径是不是"我们自己算出来的默认/兜底目录"（用于决定能否升级到偏好目录）。 */
    private boolean isOurFallbackWorkspace(String path) {
        if (path == null) return false;
        if (path.equals(legacyWorkspaceDir().getAbsolutePath())) return true;   // 旧默认，一并升级掉
        if (path.equals(variantWorkspaceDir().getAbsolutePath())) return true;
        File ext = appExternalWorkspaceDir();
        if (ext != null && path.equals(ext.getAbsolutePath())) return true;
        return path.equals(appInternalWorkspaceDir().getAbsolutePath());
    }

    /**
     * 选一个**真的能写**的工作区目录，按"用户越容易在文件管理器里找到"排序：
     *   ① /sdcard/DeepSeekHarness/Workspace（共用，最显眼）
     *   ② /sdcard/&lt;pkgRoot&gt;/Workspace（本变体目录；别的变体占用了 ① 的属主时用）
     *   ③ Android/data/&lt;pkg&gt;/files/Workspace（**无需存储权限**即可写 —— 真机首启还没授权时靠它）
     *   ④ &lt;filesDir&gt;/Workspace（最后兜底，保证引擎 cwd 永远有效、不会落回 "/"）
     *
     * ⚠ 为什么必须有 ③④：真机实测（用户报障）没有存储权限时 ①② 都不可写，
     *   于是引擎 cwd 落回 "/" → DSH 在根目录建默认工作区 → 前端报"默认工作区建立失败"。
     */
    private File workspaceDirOrFallback() {
        File preferred = defaultWorkspaceDir();
        if (isWritableDir(preferred)) return preferred;
        File legacy = legacyWorkspaceDir();
        if (isWritableDir(legacy)) {
            Log.i(TAG, "规范工作区不可写，回退到旧的共享目录 " + legacy.getAbsolutePath());
            return legacy;
        }
        File alt = variantWorkspaceDir();
        if (isWritableDir(alt)) {
            Log.i(TAG, "共享目录都不可写，回退到 " + alt.getAbsolutePath());
            return alt;
        }
        File ext = appExternalWorkspaceDir();
        if (ext != null && isWritableDir(ext)) {
            Log.i(TAG, "外部共享目录不可写（可能还没给存储权限），回退到应用外部目录 " + ext.getAbsolutePath());
            return ext;
        }
        File internal = appInternalWorkspaceDir();
        if (isWritableDir(internal)) {
            Log.w(TAG, "外部目录都不可写，回退到应用内部目录 " + internal.getAbsolutePath());
            return internal;
        }
        return null;
    }

    /**
     * POSIX 单引号转义：给 {@code sh -c '…'} 用（工作区路径里可能有空格/撇号）。
     * 规则：' → '\''（结束引号 → 转义撇号 → 重开引号）。
     */
    private static String shq(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 目录能创建且能写入（写一个探针文件再删掉）。 */
    private boolean isWritableDir(File dir) {
        java.io.FileOutputStream fos = null;
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".dsh-write-probe");
            fos = new java.io.FileOutputStream(probe);
            fos.write('1');
            fos.close();
            fos = null;
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    /** 当前配置的工作区路径（外部共享存储目录），未设置返回 null。 */
    private String workspacePath() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WORKSPACE, null);
    }

    /** 工作区行点击：未设置直接选目录；已设置弹菜单（重新选择/恢复默认）。 */
    private void onWorkspaceRowClick() {
        final String cur = workspacePath();
        if (cur == null || cur.isEmpty()) { openWorkspacePicker(); return; }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("AI 工作区")
                    .setMessage("当前工作区：\n" + cur + "\n\n选择其他文件夹，或恢复默认？")
                    .setPositiveButton("重新选择", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { openWorkspacePicker(); }
                    })
                    .setNegativeButton("恢复默认", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            // v1.21：默认值不再是"空"，而是那个容易在文件管理器里找到的目录
                            // （/sdcard/DeepSeekHarness/Workspace）——所以"恢复默认"写它、不是清空。
                            File def = defaultWorkspaceDir();
                            try { if (!def.exists()) def.mkdirs(); } catch (Throwable ignored) {}
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putString(KEY_WORKSPACE, def.getAbsolutePath()).apply();
                            refreshAllStatuses();
                            refreshConsolePermIfShown();
                        }
                    })
                    .setNeutralButton("取消", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** 打开系统文件夹选择器（SAF），选中的目录持久化为工作区。 */
    private void openWorkspacePicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_WORKSPACE_TREE);
        } catch (Throwable t) {
            Log.w(TAG, "open document tree failed", t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                Uri[] picked = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        picked = new Uri[count];
                        for (int i = 0; i < count; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        picked = new Uri[]{data.getData()};
                    }
                }
                fileChooserCallback.onReceiveValue(picked);
                fileChooserCallback = null;
            }
            return;
        }
        if (requestCode == REQ_BACKUP_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                conImportFromUri(data.getData());
            }
            return;
        }
        if (requestCode == REQ_THEME_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                conThemeImportFromUri(data.getData());
            }
            return;
        }
        if (requestCode == REQ_WORKSPACE_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            // 持久化 SAF 授权（重启后仍可访问该目录）
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) {}
            String path = treeUriToPath(tree);
            if (path != null && !path.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_WORKSPACE, path).apply();
                refreshAllStatuses();
                refreshConsolePermIfShown();
            } else {
                try {
                    new AlertDialog.Builder(this)
                            .setTitle("无法使用该目录")
                            .setMessage("无法解析所选文件夹的真实路径，请选择手机存储（内部存储或 SD 卡）内的文件夹。")
                            .setPositiveButton("知道了", null)
                            .show();
                } catch (Throwable ignored) {}
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** SAF 树 URI → 真实路径。
     *  关键：SAF 的 docId 与真实挂载路径不是简单字符串拼接。
     *  - "primary:*"（内部存储）→ /storage/emulated/0/*（Environment.getExternalStorageDirectory 基准）
     *  - "downloads:*"（Downloads 卷）→ /storage/emulated/0/Download/*
     *  - "home:*" → 内部存储根
     *  - "XXXX-XXXX:*"（SD 卡卷）→ 无法可靠映射，回退 /storage/<volume>/*
     *  - "raw:/..."（部分 ROM）→ 直接用 raw: 后的真实路径
     *  解析失败返回 null（调用方提示用户重新选择）。 */
    private String treeUriToPath(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId == null || docId.isEmpty()) return null;
            Log.i(TAG, "SAF docId=" + docId);
            if (docId.startsWith("raw:")) {
                String raw = docId.substring(4);
                return raw.isEmpty() ? null : raw;
            }
            int colon = docId.indexOf(':');
            String volume = colon > 0 ? docId.substring(0, colon) : docId;
            String rest = colon > 0 ? docId.substring(colon + 1) : "";
            File base;
            if ("primary".equals(volume)) {
                base = Environment.getExternalStorageDirectory();
            } else if ("downloads".equals(volume)) {
                // Downloads 卷实际位于内部存储的 Download 目录
                base = new File(Environment.getExternalStorageDirectory(), "Download");
            } else if ("home".equals(volume)) {
                base = Environment.getExternalStorageDirectory();
            } else {
                // 其它卷（如 SD 卡 XXXX-XXXX）：返回 /storage/<volume>/<rest>（可能不准，但极少用）
                String p = "/storage/" + volume + (rest.isEmpty() ? "" : "/" + rest);
                Log.w(TAG, "SAF 非标准卷 -> " + p);
                return p;
            }
            File out;
            if (rest.isEmpty()) out = base;
            else out = new File(base, rest.replace('\\', '/'));
            Log.i(TAG, "SAF 路径 -> " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "treeUriToPath error", t);
            return null;
        }
    }

    private void openSystemSetting(String action) {
        try {
            Intent i = new Intent(action);
            // 通知设置页要带包名，否则部分 ROM 会落到"全部应用通知"列表（用户找不到本应用）
            if (Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action)) {
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            }
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(action);
                if (Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action)) {
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                }
                startActivity(i);
            } catch (Exception e2) {
                Log.w(TAG, "无法打开设置: " + action, e2);
            }
        }
    }

    private void openShizukuApp() {
        String[] pkgs = {"moe.shizuku.privileged.api", "rikka.shizuku"};
        for (String p : pkgs) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) {
                try { startActivity(i); return; } catch (Exception ignored) {}
            }
        }
        Log.w(TAG, "未找到 Shizuku 应用，请手动打开并授权");
    }

    private void showShizukuDialog() {
        boolean installed = false;
        for (String p : new String[]{"moe.shizuku.privileged.api", "rikka.shizuku"}) {
            try { getPackageManager().getPackageInfo(p, 0); installed = true; break; } catch (Exception ignored) {}
        }
        boolean binderOk = false;
        try { binderOk = Shizuku.pingBinder(); } catch (Throwable ignored) {}
        boolean rootOkNow = rootOk != null && rootOk;

        // v1.13：「实时探测」，不信 shizukuOk 缓存。
        // 缓存由 probeShizuku() 异步刷新，而用户“在 Shizuku 里撤销授权 → 回来马上点授权”时缓存
        // 往往还是旧的 true → 会走“已授权”分支而不调 requestPermission → 表现为“点了没任何弹窗”。
        boolean shizukuNow = false;
        int selfPerm = -1, apiVer = -1;
        boolean rationale = false;
        try {
            if (binderOk) {
                selfPerm = Shizuku.checkSelfPermission();
                shizukuNow = selfPerm == PackageManager.PERMISSION_GRANTED;
                try { apiVer = Shizuku.getVersion(); } catch (Throwable ignored) {}
                try { rationale = Shizuku.shouldShowRequestPermissionRationale(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { Log.w(TAG, "Shizuku state probe failed", t); }
        if (binderOk) shizukuOk = shizukuNow;   // 用实时值刷新缓存
        Log.i(TAG, "shizuku state: binder=" + binderOk + " selfPerm=" + selfPerm
                + " apiVer=" + apiVer + " rationale=" + rationale);

        if (rootOkNow || shizukuNow) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("系统特权（可选）");
            b.setMessage((rootOkNow ? "已检测到 Root（su）可用，AI 可以执行系统级操作。\n" : "") +
                    (shizukuNow ? "Shizuku 已授权，AI 可以执行系统级操作。\n" : "") +
                    "\n不授予特权也能正常使用：文件读写、预览、编辑只需「所有文件访问」权限。");
            b.setNegativeButton("关闭", null);
            b.show();
        } else if (binderOk) {
            // 服务在运行但未授权 → 请求 Shizuku 弹授权框；若系统/ROM 没弹出来，8 秒后引导手动授权
            requestShizukuPermission(installed);
        } else {
            fallbackShizukuDialog(installed);
        }
    }

    /**
     * 请求 Shizuku 授权 + 「超时傅底」。
     * v1.13：授权框由 Shizuku 应用弹出；在部分 ROM（ColorOS 等）上它可能被拦截/不弹，
     * 而 requestPermission 本身不报错也不回调 —— 用户看到的就是“点了没任何反应”。
     * 这里过 8 秒仍未拿到结果，就弹一个“怎么手动授权”的引导框（附当前状态供排查）。
     */
    private void requestShizukuPermission(final boolean installed) {
        pendingShizukuReq = true;
        try {
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku requestPermission failed", t);
        }
        // v1.13.3：真机实测本机（Shizuku 13.6.0 + ColorOS 15）上 requestPermission() 不弹框，
        // 而“引擎里 AI 调 rish”能弹 —— 因为 Shizuku 的授权框是 Shizuku 应用在收到「真实请求」时才弹。
        // 所以这里补两条与引擎同款的真实触发；授权框弹出后用户点允许，3 秒后回探一次即可反映到界面。
        triggerShizukuPrompt();
        ui.postDelayed(new Runnable() {
            @Override public void run() { probeShizuku(); }
        }, 3000L);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pendingShizukuReq) return;   // 已经回调过（用户处理了）
                pendingShizukuReq = false;
                showShizukuGuideDialog(installed);
            }
        }, 12000L);
    }

    /**
     * 发两条「真实请求」逼 Shizuku 弹授权框（requestPermission 在本机不弹）：
     *   ① 向 Shizuku 应用要 binder（client provider 路径）；
     *   ② 用 App 自己 spawn 一个 rish（与引擎里 rish 完全同款：同样的 dex、同样的 env 清理、同样的广播）。
     * ② 会超时（5 秒广播预算）也没关系——我们要的是它在 Shizuku 应用侧触发的授权框。
     */
    private void triggerShizukuPrompt() {
        new Thread(new Runnable() {
            @Override public void run() {
                try { Shizuku.getBinder(); } catch (Throwable ignored) {}
            }
        }, "shizuku-binder-warm").start();
        new Thread(new Runnable() {
            @Override public void run() {
                Process p = null;
                try {
                    File dex = (rishDex != null && rishDex.exists()) ? rishDex : extractRishDex();
                    if (dex == null || !dex.exists()) {
                        Log.w(TAG, "triggerShizukuPrompt: rish dex 不可用");
                        return;
                    }
                    try { android.system.Os.chmod(dex.getAbsolutePath(), 0444); } catch (Throwable ignored) {}
                    ProcessBuilder pb = new ProcessBuilder(
                            "/system/bin/app_process",
                            "-Djava.class.path=" + dex.getAbsolutePath(),
                            "/system/bin",
                            "--nice-name=rish",
                            "rikka.shizuku.shell.ShizukuShellLoader",
                            "-c", "id");
                    java.util.Map<String, String> env = pb.environment();
                    env.remove("LD_LIBRARY_PATH");
                    env.remove("LD_PRELOAD");
                    env.remove("LD_DEBUG");
                    env.put("RISH_APPLICATION_ID", getPackageName());
                    pb.redirectErrorStream(true);
                    p = pb.start();
                    try { p.waitFor(9, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    Log.w(TAG, "triggerShizukuPrompt(rish) failed", t);
                } finally {
                    try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
                }
            }
        }, "shizuku-prompt-rish").start();
    }

    /** 授权框没弹出来时的引导（去 Shizuku 里手动授权），并带上当前状态便于定位。 */
    private void showShizukuGuideDialog(boolean installed) {
        String label = getPackageName();
        try {
            CharSequence l = getApplicationInfo().loadLabel(getPackageManager());
            if (l != null && l.length() > 0) label = l + "（" + getPackageName() + "）";
        } catch (Throwable ignored) {}
        String msg = "Shizuku 的授权框没有出现（常见于 ColorOS/OPPO 等系统拦截了它的弹窗）。"
                + "\n\n请手动授权：\n"
                + "1. 打开 Shizuku 应用\n"
                + "2. 进入「已授权的应用」（或首页的「授权应用」）\n"
                + "3. 找到 " + label + " → 打开开关\n\n"
                + "授权后回到本页会自动刷新。";
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Shizuku 授权（需手动）");
        b.setMessage(msg);
        b.setPositiveButton("打开 Shizuku", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { openShizukuApp(); }
        });
        b.setNeutralButton("重新检测", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { probeShizuku(); conToast("已重新检测"); }
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    private void fallbackShizukuDialog(boolean installed) {
        String msg;
        if (installed) {
            msg = "Shizuku 服务未运行。\n\n请先打开 Shizuku 应用并启动服务，然后回来点击「重新检测」；服务启动后本应用会自动弹出授权对话框。";
        } else {
            msg = "未检测到 Shizuku 应用。请先安装 Shizuku（官方版），再回来授权。";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Shizuku 特权");
        b.setMessage(msg);
        if (installed) {
            b.setPositiveButton("去启动 Shizuku", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { openShizukuApp(); }
            });
        }
        b.setNeutralButton("重新检测", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { probeShizuku(); }
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    // Shizuku 检测（Shizuku API，异步）
    private volatile Boolean shizukuOk = null;

    private void probeShizuku() {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = shizukuAvailable();
                shizukuOk = ok;
                // 顺带在后台探测 root（避免在主线程执行 su）
                try { rootAvailable(); } catch (Throwable ignored) {}
                // Shizuku 可用时确保 vscreen server 已启动（特权进程由 App 持有 → 不被命令会话清理）
                if (ok) {
                    // v1.10：虚拟屏服务由 App 进程内嵌启动（VsreenBridgeService → Main.start），
                    // 不再用 Shizuku newProcess 启动旧 jar 的 VirtualScreenServer —— 双 server 抢
                    // 8999 会导致 displayId 状态错乱（153/154）且旧 shell server SIGABRT。
                    // Shizuku 仅保留用于 input 注入 / am start --display（vscreen 交互）。
                    // try { ensureVscreenServer(); } catch (Throwable ignored) {}
                }
                ui.post(new Runnable() { @Override public void run() { refreshAllStatuses(); } });
            }
        }, "shizuku-probe").start();
    }

    /**
     * 用 Shizuku newProcess 启动 vscreen server：
     * 之前用 rish -c 后台 & 启动，Shizuku 命令会话结束会杀子进程（用户 Android 15 实测 server 消失）——
     * newProcess 创建的是独立进程（App 持有 IRemoteProcess），不被会话清理，这是 Operit 验证过的存活方案。
     */
    private void ensureVscreenServer() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastVscreenEnsureTs < 5000) return;  // 5 秒频率限制
            lastVscreenEnsureTs = now;
            if (vscreenAlive()) return;  // server 已在监听
            File src = vscreenDex;
            if (src == null || !src.exists()) return;
            if (!shizukuAvailable()) return;
            android.os.IBinder binder = Shizuku.getBinder();
            if (binder == null) return;
            IShizukuService svc = IShizukuService.Stub.asInterface(binder);
            if (svc == null) return;
            // 1) 特权拷贝 jar 到 /data/local/tmp（App uid 写不了该目录；等待完成）
            IRemoteProcess cp = svc.newProcess(new String[]{ "/system/bin/cp", "-f", src.getAbsolutePath(), "/data/local/tmp/vscreen_shizuku.jar" }, null, null);
            if (cp != null) { cp.waitFor(); }
            // 2) chmod 644（保证可读）
            IRemoteProcess ch = svc.newProcess(new String[]{ "/system/bin/chmod", "644", "/data/local/tmp/vscreen_shizuku.jar" }, null, null);
            if (ch != null) { ch.waitFor(); }
            // 3) 启动 server（长驻；保存引用防 GC）—— Operit 同款启动方式：
            //    CLASSPATH=... app_process / <Main>（cmd-dir 用 /，不用 /system/bin；Operit 实测在这类设备可用）
            // v1.18（B12）：主类与端口随变体（原为硬编码 "…vscreen.VirtualScreenServer"，与
            // vscreen/Main.java 的真实类名 com.deepseek.harness.vscreen.Main 不符，是一条死路径）。
            String[] env = new String[]{ "CLASSPATH=/data/local/tmp/vscreen_shizuku.jar" };
            vscreenProc = svc.newProcess(new String[]{
                    "/system/bin/app_process",
                    "/",
                    BuildVariant.APP_ID + ".vscreen.Main",
                    "--port", String.valueOf(BuildVariant.VS_CORE_PORT)
            }, env, null);
            Log.i(TAG, "vscreen server start issued via Shizuku newProcess");
        } catch (Throwable t) {
            Log.w(TAG, "ensureVscreenServer failed", t);
        }
    }

    private boolean vscreenAlive() {
        try {
            java.net.Socket s = new java.net.Socket();
            // v1.18（B12）：桥端口随变体（official 8999 / lite 9009 / compat 9019 / community 9029）
            s.connect(new java.net.InetSocketAddress("127.0.0.1", BuildVariant.VS_BRIDGE_PORT), 500);
            s.close();
            return true;
        } catch (Throwable t) { return false; }
    }

    private boolean shizukuAvailable() {
        try {
            if (!Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 探测 root（su）是否可用：执行 `su -c id`，输出含 uid=0 即视为可用。结果缓存，onResume 时重置。 */
    private volatile Boolean rootOk = null;
    private boolean rootAvailable() {
        Boolean cached = rootOk;
        if (cached != null) return cached;
        boolean ok = probeRoot();
        rootOk = ok;
        return ok;
    }

    private boolean probeRoot() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            // 等待进程退出，避免僵尸；API 26+ 支持超时，低版本直接等待（su -c id 很快返回）
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
                } else {
                    p.waitFor();
                }
            } catch (Throwable ignored) {}
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private File extractRishDex() {
        try {
            File dir = new File(getFilesDir(), "rish");
            if (!dir.exists()) dir.mkdirs();
            File dex = new File(dir, "rish_shizuku.dex");
            if (dex.exists() && dex.length() > 0) {
                // v1.13.6：升级用户走的就是这一支（旧文件不会被重写），而旧版留下的副本可能是 0666
                // → 同样会被 ART 拒绝加载（静默 SIGABRT），所以“已存在”分支也要收权。
                secureDexPermissions(dex);
                return dex;
            }
            InputStream in = getAssets().open("rish_shizuku.dex");
            FileOutputStream out = new FileOutputStream(dex);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            // v1.13.5：刚写出来的是 rw-rw-rw-，而 Android 14+ 拒绝加载可写 dex（SIGABRT）——
            // 插件侧每次调用会 chmod，但“App 自己刚提取、插件还没跑”的窗口期同样会中招，所以这里就收权。
            secureDexPermissions(dex);
            return dex;
        } catch (Exception e) {
            Log.w(TAG, "extract rish dex failed", e);
            return null;
        }
    }

    private String pkgRoot() {
        // v1.18（B11/B12）：外部目录名随变体（BuildVariant），不再 contains("beta")/("compat") 硬编码。
        return BuildVariant.EXT_DIR_NAME;
    }

    /**
     * 把 APK 内置 payload.zip 里的自定义引擎插件强制覆盖到内部 payload 目录。
     * 只处理 @deepseek-ai/dsh-tool-{vscreen,android,accessibility,shizuku} 与 dsh-bash-local，
     * 均为官方维护、必须随 APK 更新的插件，体积很小（几十 KB）。
     */
    private void refreshEnginePluginsFromPayload(File payloadDir) {
        final String[] MARKS = {
                "/dsh-tool-vscreen/",
                "/dsh-tool-android/",
                "/dsh-tool-accessibility/",
                "/dsh-tool-shizuku/",
                // v1.19.0：AI 浏览器插件（结构化 DOM 快照 + 稳定 ref）
                "/dsh-tool-browser/",
                "/dsh-bash-local/",
                // grep/glob 修复：fs-search 的 resolveRgPath 已改为 Android 走自带的 runtime/bin/rg
                // （@vscode/ripgrep 没有 android 平台包，模块求值即 throw）。不在这张表里，
                // 老版本升上来就仍是旧文件，grep/glob 会继续报 ripgrep launch failed。
                "/dsh-tool-fs-search/"
        };
        int copied = 0;
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(getAssets().open("payload.zip")));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                boolean hit = false;
                for (String m : MARKS) {
                    if (name.contains(m)) { hit = true; break; }
                }
                if (!hit) {
                    continue;
                }
                File out = new File(payloadDir, name);
                File parent = out.getParentFile();
                if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                    continue;
                }
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                copied++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshEnginePluginsFromPayload failed: " + t.getMessage());
        } finally {
            if (zis != null) { try { zis.close(); } catch (Throwable ignored) {} }
        }
        Log.i(TAG, "engine plugins refreshed from payload.zip, files=" + copied);
    }

    /** v1.18.0：读 assets 里的小文本文件（如 vscreen_jar_sha256.txt）；读不到返回空串。 */
    private String assetText(String name) {
        try {
            InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8").trim().toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }

    /** v1.18.0：文件 SHA-256（小写 hex）；失败返回空串（调用方按"不符"处理）。 */
    private String sha256Of(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            InputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            in.close();
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private File extractVscreenDex() {
        // 优先提取到外部共享目录（/sdcard/<EXT_DSHROOT_ROOT>/vscreen/）：
        // shell(uid 2000) 读不到 app 私有目录（SELinux + 权限双重拦截），但能读 /sdcard ——
        // 插件需以特权 shell 把 jar 拷到 /data/local/tmp 供 app_process 加载。
        // 每次启动强制覆盖（旧 jar 残留会导致加载旧版崩溃：NoSuchMethodException / ClassNotFoundException）。
        try {
            File extDir = new File(Environment.getExternalStorageDirectory(), pkgRoot() + "/vscreen");
            if (extDir.exists() || extDir.mkdirs()) {
                File out = new File(extDir, "vscreen_shizuku.jar");
                InputStream in = getAssets().open("vscreen_shizuku.jar");
                FileOutputStream fos = new FileOutputStream(out);
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) fos.write(b, 0, n);
                fos.close();
                in.close();
                // v1.18.0：写出后核对随包分发的 SHA-256（共享存储上的 jar 会被替换 → shell 身份执行任意代码）
                String wantHash = assetText("vscreen_jar_sha256.txt");
                String gotHash = sha256Of(out);
                if (wantHash.isEmpty() || !wantHash.equalsIgnoreCase(gotHash)) {
                    Log.w(TAG, "vscreen jar 哈希不符（期望 " + wantHash + "，实际 " + gotHash + "），重写一次");
                    java.io.FileOutputStream fos2 = new java.io.FileOutputStream(out);
                    InputStream in2 = getAssets().open("vscreen_shizuku.jar");
                    while ((n = in2.read(b)) > 0) fos2.write(b, 0, n);
                    fos2.close();
                    in2.close();
                    gotHash = sha256Of(out);
                    if (!wantHash.isEmpty() && !wantHash.equalsIgnoreCase(gotHash)) {
                        Log.w(TAG, "vscreen jar 重写后仍不符（" + gotHash + "），虚拟屏将被拒绝启动");
                    }
                }
                return out;
            }
        } catch (Exception e) {
            Log.w(TAG, "extract vscreen jar to external failed, fallback internal", e);
        }
        // 降级：私有目录（未授予存储权限时；shell 读不到，但至少 App 自身逻辑可用）
        try {
            File dir = new File(getFilesDir(), "vscreen");
            if (!dir.exists()) dir.mkdirs();
            File dex = new File(dir, "vscreen_shizuku.jar");
            InputStream in = getAssets().open("vscreen_shizuku.jar");
            FileOutputStream out = new FileOutputStream(dex);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            return dex;
        } catch (Exception e) {
            Log.w(TAG, "extract vscreen jar failed", e);
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // 存储权限授予后重新提取 vscreen jar 到外部目录（首次启动时授权在提取之后才完成）
        if (code == REQ_STORAGE && pendingVscreenExtract) {
            pendingVscreenExtract = false;
            for (int i = 0; i < perms.length; i++) {
                if ("android.permission.WRITE_EXTERNAL_STORAGE".equals(perms[i])
                        && results[i] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        File f = extractVscreenDex();
                        if (f != null) {
                            vscreenDex = f;
                            Log.i(TAG, "vscreen jar re-extracted after permission grant: " + f);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "vscreen re-extract failed", t);
                    }
                }
            }
        }
        refreshAllStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        rootOk = null; // 从设置页/Shizuku 返回时重新探测 root
        refreshAllStatuses();
        // v1.21（用户报障）：从系统设置授完权回到 App，控制台「授予权限」页仍显示"未授权"。
        // 原因：那一页每行的状态文字是**构建时**算好的（addPermRow 里 conPermOk 只取一次），
        // 而 onResume 原来只刷新引导页的状态行，没有重绘控制台页面。
        // 补一次重绘 —— 它内部只在"控制台可见且正停在权限页"时才渲染，不影响其它页面。
        refreshConsolePermIfShown();
        // 从 Shizuku/设置页返回时重新检测
        if (permRows != null && !permRows.isEmpty()) probeShizuku();
        // v1.13.12：切回前台时补采样一次页面底色（离开期间前端主题可能被改过）
        refreshPageBackground();
        // v1.21：授权页交给浏览器后，用户回来时刷新一次页面。
        // 为什么：登录是在浏览器 + 引擎侧完成的（日志里能看到 "Host authorized as an owned device"、
        // "server control connection online"），但插件的**网页客户端**仍停在授权前的错误上
        // （用户看到卡片还是"登录失败 / AUTH_INVALID"）。刷新一次，客户端就会重新拉状态。
        maybeReloadAfterAuth();
    }

    /**
     * v1.21：授权跳转后回到前台 → 刷新一次 WebView（见 onResume 注释）。
     * 只在"确实把授权页交给了浏览器"之后触发，且回到前台至少 8 秒（留够用户完成授权的时间）。
     */
    private void maybeReloadAfterAuth() {
        try {
            if (authHandoffAt == 0L || webView == null) return;
            long dt = System.currentTimeMillis() - authHandoffAt;
            if (dt < 8000L) return;                 // 太快了，用户还没在浏览器里点完
            authHandoffAt = 0L;
            Log.i(TAG, "授权跳转后回到前台 → 刷新界面以重新拉取登录状态");
            webView.reload();
        } catch (Throwable t) {
            Log.w(TAG, "maybeReloadAfterAuth failed", t);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        overlayForeground = true;
        OverlayService.setOverlayVisible(false); // 回到前台：隐藏悬浮窗
    }

    @Override
    protected void onStop() {
        overlayForeground = false;
        OverlayService.setOverlayVisible(true);  // 退后台：显示悬浮窗
        super.onStop();
    }

    // ============ 引擎启动（原逻辑）============
    private void startEngine() { startEngine(false); }

    /**
     * @param prepareOnly v1.21（Q2-C）：只把 payload 文件准备好就返回 —— 不启动引擎、不拉保活通知、
     *        不拉悬浮窗。权限向导展示期间用它做后台预热（复用既有的 extractOnlyMode 路径，
     *        文件准备逻辑只有一份），用户走完向导点「开始使用」时 filesPreparedThisBoot 已为真，
     *        启动路径直接跳过整段文件准备（首次解压 1.5 万条目的等待被挪到用户读向导的时间里）。
     */
    private void startEngine(final boolean prepareOnly) {
        engineStartAborted = false;    // v1.13：重新启动 → 清掉「停止」留下的中止/抑制标记
        if (!prepareOnly) {
            // v1.21：只有"真的要启动引擎"才清掉「用户主动停止」的意图。
            // 预热（prepareOnly）不该改写用户意图 —— 那是"准备文件"，不是"开始运行"。
            engineStoppedByUser = false;
            markEngineStoppedByUser(false);
            startKeepAliveService();   // 前台保活：挂后台不被杀（引擎持续运行）
            // 引擎端口持久化（供 OverlayService/其他组件读取）；已授权悬浮窗时自动拉起小鲸鱼
            try {
                getSharedPreferences("dsh_prefs", MODE_PRIVATE)
                        .edit().putInt("engine_port", enginePort).apply();
            } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                startOverlayService();
            }
        }
        // v1.10（Operit 方案）：不再请求 MediaProjection 授权（用户反感弹窗；Operit 主 App 也不用）。
        // 虚拟屏承载外部 App 内容由 PUBLIC|PRESENTATION 建屏实现（真机 Android 15 验证）。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // v1.5.4：已移除「端口冲突自动换端口」（resolveEnginePort/portInUse/saveEnginePort/engine_port 持久化），
                    // 引擎固定默认端口启动，用于排查慢启动是否与端口探测相关。
                    // 通知通道在后台线程启动（端口 = enginePort+1）。
                    startNotifyServer();

                    File payload = payloadDir();
                    // v1.12：解压与启动拆开。「解压文件」把 extractOnlyMode 置 true，跑到下面
                    // ensurePatchConfig 为止就返回；「启动引擎」复用同一入口，但 filesPreparedThisBoot
                    // 已置位 → 整段文件准备被跳过。
                    if (!filesPreparedThisBoot) {
                    File done = new File(payload, ".extracted");

                    // v1.5.3 慢启动根因修复：内核目录改为【内部存储优先】。
                    // 现象：v1.5.x 真机启动 50-60s（v1.4 的 10s），payload/node/启动参数逐字节对比无差异，
                    // 模拟器正常（4s）→ 根因是 node 每次启动从【外部 /sdcard（FUSE）】读取 2 万+ 内核文件，
                    // require() 解析时海量 stat/read 过 FUSE 极慢（真机如此，模拟器宿主机磁盘快测不出）。
                    // 修复：node 恒从内部存储（files/payload/dshroot）读内核（快、可靠）；
                    // 外部目录仅作【内部空间不足】时的回退，以及保留 .nomedia/相册保护等兼容逻辑。
                    File externalRoot = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                    boolean useExternal = externalDshrootWritable(externalRoot);

                    // 后台清理上次「清空」遗留的 .trash-* 目录（rename 后后台删除未完成），不阻塞启动。
                    if (useExternal) {
                        final File extCleanup = externalRoot;
                        new Thread(new Runnable() {
                            @Override public void run() { cleanupTrashDirs(extCleanup); }
                        }, "trash-cleanup").start();
                        // 相册保护：外部 dshroot（历史版本遗留）里 2 万+ 文件会被 MediaStore
                        // 内容嗅探误判为视频。.nomedia 让 MediaStore 忽略整个目录。幂等。
                        File nomedia = new File(externalRoot, ".nomedia");
                        if (!nomedia.exists()) {
                            try { nomedia.createNewFile(); } catch (Throwable ignored) {}
                        }
                    }

                    // v1.21（Q2-A）：本次启动是否换了载荷（assets/payload_manifest.txt 的
                    // payload_zip_sha256 与上次记录不同）。用它当闸门：没换载荷就不必每次冷启动
                    // 都去扫一遍 102MB / 15703 条目的 payload.zip —— 实测这两步合计 ~5.2s
                    // （internal-patch 3.53s + 插件刷新 1.74s），是 App 侧最大的可省项。
                    // 代价：设备上文件若被手删，不会在下次启动自动补回，需在控制台点「重新解压」
                    //（完整性校验仍会报告 drift）。相对每次冷启动省 5 秒，这个取舍值得。
                    final boolean payloadChanged = payloadZipChanged();

                    if (!done.exists()) {
                        // 关键：先解压内部关键运行时（node/.so/dshhome/bin/rish），再解压 dshroot。
                        // 解压中途被打断时，只要内部已就位引擎仍能启动；缺的文件由 dshrootNeedsSync 幂等补齐。
                        extractPayload(payload, null, "internal");
                        done.createNewFile();
                    } else if (payloadChanged) {
                        // 覆盖升级：补齐内部运行时缺失的新增文件（如 runtime/bin/rg），已有文件不动
                        try { extractPayload(payload, null, "internal-patch"); } catch (Throwable ignored) {}
                    } else {
                        Log.i(TAG, "internal-patch 跳过（载荷未变）");
                    }

                    if (payloadChanged) {
                        // 引擎插件强制刷新（见 refreshEnginePluginsFromPayload 注释）
                        refreshEnginePluginsFromPayload(payload);
                    } else {
                        Log.i(TAG, "engine plugins refresh 跳过（载荷未变）");
                    }

                    // 内部 dshroot 同步（node 从此处读内核）：
                    // REVISION 不匹配（重装）或 .complete 缺失（中断）都补。
                    // v1.5.2：REVISION 是构建时间戳每次构建都变——同内核升级走「快速同步」
                    //（只更新 REVISION+白名单文件，秒级）；.complete 缺失或内核版本变化才全量补齐。
                    // v1.13.12：布局标记变化也不再全量重推（老版本升上来常因无标记/标记不同被判
                    // "布局变了"→ 2.5 万文件全部重写，用户看到的就是"更新后又要解压一遍"）。
                    // 改走「增量补齐」：只写缺失文件 + 官方白名单覆盖 + 清理新树里已不存在的顶层插件包。
                    File internalBase = payload; // 内部 dshroot 位于 payload/dshroot
                    File internalDshroot = new File(payload, "dshroot");
                    boolean kernelOnExternal = false;
                    try {
                        if (dshrootNeedsSync(internalBase)) {
                            boolean revisionChanged = dshrootRevisionChanged(internalBase);
                            boolean full = dshrootNeedsFullSync(internalBase);
                            // v1.20：payload.zip 变了也走「增量补齐」——fast 同步只看 REVISION +
                            // 白名单，**不解压本次新增的文件**（踩过：新增的 node-pty 替身包
                            // 在 fast 模式下永远不落地，终端一直报 Cannot find module 'node-pty'）。
                            // v1.21：payloadChanged 提升到本段之前统一判定（见上面的 Q2-A 注释）。
                            boolean layoutOnly = !full && (dshrootLayoutChanged(internalBase) || payloadChanged);
                            fastSyncedThisBoot = !full;
                            String mode = full ? "dshroot" : (layoutOnly ? "dshroot-add" : "dshroot-fast");
                            extractPayload(payload, null, mode);
                            writeDshrootComplete(internalBase);
                            rememberPayloadZipSha();
                            if (revisionChanged) refreshInternalConfig(payload);
                        }
                        // v1.21：本段没走（dshroot 已同步）但载荷换过时，也要记住指纹，
                        // 否则闸门永远为真 → internal-patch/插件刷新每次都做，Q2-A 省不下来。
                        if (payloadChanged) rememberPayloadZipSha();
                        dshrootDir = internalDshroot;
                    } catch (Throwable t) {
                        // 内部解压失败（通常为内部存储空间不足）→ 回退外部（慢但可用）
                        Log.w(TAG, "internal dshroot sync failed, fallback to external", t);
                        // 清理不完整的内部 dshroot，避免双重占空间
                        try { deleteRecursive(internalDshroot); } catch (Throwable ignored) {}
                        if (useExternal) {
                            if (dshrootNeedsSync(externalRoot)) {
                                boolean full = dshrootNeedsFullSync(externalRoot);
                                boolean layoutOnly = !full && (dshrootLayoutChanged(externalRoot) || payloadZipChanged());
                                extractPayload(payload, externalRoot, full ? "dshroot" : (layoutOnly ? "dshroot-add" : "dshroot-fast"));
                                writeDshrootComplete(externalRoot);
                                rememberPayloadZipSha();
                            }
                            dshrootDir = new File(externalRoot, "dshroot");
                            kernelOnExternal = true;
                        } else {
                            throw t;
                        }
                    }

                    // 兜底：确保 dshroot 确实就位（例如首次内部解压被系统打断）。
                    if (!new File(dshrootDir, REL_BINJS).exists()) {
                        Log.w(TAG, "dshroot missing at " + dshrootDir + ", repopulating");
                        extractPayload(payload, kernelOnExternal ? externalRoot : null, "dshroot");
                        if (kernelOnExternal) writeDshrootComplete(externalRoot);
                    }

                    applyLinks(payload);
                    setExecutables(payload);
                    secureDexFiles(payload); // v1.13.5：兜底把旧树里已有的 dex 也收成 0444（覆盖升级不会被重写）
                    ensurePatchConfig(payload); // ③ 补丁启动自检：cordis.patch.yml 缺失/被改则自动补齐
                    filesPreparedThisBoot = true;
                    conMarkPayloadDone();   // 记录“内部这棵树是本次安装解压的”（供控制台/校验判定）
                    checkPayloadIntegrity();  // B9：启动自检 —— 与打包清单核对 runtime/（只记日志+落文件）
                    } // end if (!filesPreparedThisBoot)
                    if (extractOnlyMode || prepareOnly) {
                        // 控制台「解压文件」/ 向导期间的后台预热：到此为止，不碰引擎
                        extractOnlyMode = false;
                        extracting = false;
                        if (prepareOnly) {
                            Log.i(TAG, "warmPayload: 后台预热完成，文件已就绪（向导走完可直接起引擎）");
                            return;
                        }
                        ui.post(new Runnable() { @Override public void run() { conExtractDone(); } });
                        return;
                    }
                    // v1.21：默认插件 —— 先把内置包注册进 profile（同步、秒级，必须在引擎启动前完成），
                    // 再另起线程跑"联网安装"兜底（内置包在时它会直接跳过）。
                    ensureDefaultPluginRegisteredSync(payload);
                    ensureDefaultPluginsAsync(payload);
                    // v1.21：Android 上内核解析不出系统 Documents 目录 → 默认工作区永远建不出来
                    //（真机日志：initializeDefault 返回 "system Documents directory is unavailable on android"）。
                    // 这里补上官方覆盖项，必须**在引擎启动前**写好。
                    ensureWorkspaceControllerConfig(payload);
                    launchEngine(payload);
                } catch (Throwable t) {
                    Log.e(TAG, "engine error", t);
                    String msg = String.valueOf(t.getMessage());
                    filesPreparedThisBoot = false;
                    final boolean wasExtract = extractOnlyMode;
                    extractOnlyMode = false;
                    extracting = false;
                    starting = false;
                    setStatus("引擎启动失败：" + msg);
                    writeStartupDiag(msg);
                    final String m = msg;
                    ui.post(new Runnable() { @Override public void run() {
                        if (wasExtract) conExtractFailed(m); else conEngineFailed(m);
                    } });
                }
            }
        }, "engine-boot").start();
    }

    /** v1.7：启动失败时把引擎日志尾部与状态写进外部目录，用户无需 adb 即可反馈排查。 */
    private void writeStartupDiag(String errorMsg) {
        try {
            String sub = pkgRoot();
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), sub);
            if (!dir.exists()) dir.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("时间: ").append(new java.util.Date()).append('\n');
            sb.append("错误: ").append(errorMsg).append('\n');
            sb.append("enginePort=").append(enginePort).append(" notifyPort=").append(notifyPort()).append('\n');
            sb.append("node存活=").append(nodeProcess != null && nodeProcess.isAlive()).append('\n');
            File log = new File(getFilesDir(), "dsh-web.log");
            if (log.exists()) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r");
                long len = raf.length();
                long start = Math.max(0, len - 65536);
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                raf.close();
                sb.append("--- dsh-web.log 尾部 ---\n").append(new String(buf, "UTF-8"));
            }
            File out = new File(dir, "startup-diag.txt");
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            Log.i(TAG, "启动诊断已写入 " + out.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    // ============ ③ 补丁启动自检 ============
    /** 检查内部 dshhome/cordis.patch.yml 是否完整（含 marker 与禁用的插件），
     *  缺失/被外部改动破坏/版本落后则从 payload.zip 重新提取官方配置（幂等）。
     *  背景：补丁配置被改/删会导致 sandbox/bash-sandbox 启用失败 → 启动崩溃。
     *  v1.12：改用顶部 marker 判定版本。旧实现靠“内容里必须有 llm-pi-ai”判定，
     *        而 v1.12 起 llm-pi-ai 已取消禁用 → 升级用户会被判为“不完整”并自动落地新配置
     *        （正是我们想要的迁移效果）。
     *  ⚠ v1.15.9：本常量必须与 config/cordis.patch.yml 首行的 marker 「逐字相等」。
     *        v1.15.1 把那个文件的 marker 提到 v3（强制迁移 sandbox 修复），而这里的
     *        常量留在 v2 → 上面那句 contains() 恒为 false → 每次启动都判「配置不完整」
     *        并从 payload 刷新官方配置，用户在 dshhome/cordis.patch.yml 上的改动被反复擦掉
     *        （issue #33「引擎 boot 时重写全部配置」）。
     *        今后改 yml 的 marker，必须同步改这一处（共四份源码）。 */
    private static final String PATCH_CONFIG_MARKER = "dsh-android-patch: v4";

    private void ensurePatchConfig(File payload) {
        try {
            File patch = new File(payload, "dshhome/cordis.patch.yml");
            boolean need = !patch.exists();
            if (!need) {
                String content = readFileText(patch);
                // marker 缺失/落后，或关键禁用项缺失 → 视为需重新落地
                need = !(content.contains(PATCH_CONFIG_MARKER) && content.contains("sandbox")
                        && content.contains("bash-sandbox") && content.contains("disabled: true"));
            }
            if (need) {
                Log.w(TAG, "cordis.patch.yml missing or incomplete, restoring from payload.zip");
                refreshInternalConfig(payload); // 重新覆盖 dshhome 官方配置（凭证/会话保留）
            } else {
                conHealPatchConfig();   // v1.13：结构自愈（控制台旧实现关插件会写坏它，见 conHealPatchConfig）
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensurePatchConfig error", t);
        }
    }

    // ============ ① 端口冲突处理 ============
    /** 判断端口上是否真的是 DSH 引擎（而非任意 HTTP 服务/占位页）。
     *  强特征：首页 HTML 含 <title>DeepSeek Harness</title>（占位服务/Termux busy 页不会恰好相同）。
     *  v1.5.1 修复：旧 healthOk() 只认"任意 HTTP 响应(200-499)"，占位服务返回 200 时被误判为
     *  引擎健康 → 不换端口、node 不启动、WebView 显示占位内容。 */
    private boolean isDshEngine(int port) {
        HttpURLConnection c = null;
        try {
            // 0.1.5：首页需要 token（否则 401 authentication required）。
            // ⚠ 探测「绝不能带 token」：token 是一次性的（用过即废），若被探测吃掉，
            // 随后 WebView 拿同一个 token 加载就会 401（用户看到的白屏/黑字就是这个）。
            // 探测只用不带 token 的 /：401 + DSH 专属正文 也足以证明“是本引擎且在跑”。
            String probe = "http://127.0.0.1:" + port + "/";
            c = (HttpURLConnection) new URL(probe).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            c.setRequestProperty("User-Agent", "dsh-probe");
            // 0.1.5：带 token 访问首页会返回 303 + Set-Cookie（浏览器会话 cookie），
            // 而 HttpURLConnection 默认不保存 cookie → 跟随重定向后又变 401 → 探测永远失败
            // （旧实现表现为干等 90 秒超时才进兜底）。这里不跟随重定向，把 303/302
            // 直接当作“引擎已就绪且 token 有效”，正文标题校验只在普通 200 路径上做。
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 303 || code == 302) return true;
            if (code == 401) {
                // v1.12：0.1.5 引擎未带 token 时返回 401 + DSH 专属正文。
                // 旧实现直接当“不是引擎” → 引擎明明在跑，控制台与健康探测却永远判未就绪
                // （用户实测：点完「启动引擎」界面又退回「启动引擎」）。
                String b401 = conReadBody(c, 4096);
                return b401 != null && b401.indexOf("dsh web authentication required") >= 0;
            }
            if (code < 200 || code >= 500) return false;
            InputStream in = c.getInputStream();
            // v1.5.5 修复：首页实际约 14KB（13KB 内联脚本在前，<title> 位于页面末尾第 13.4KB 处），
            // 旧实现只读前 4096 字节 → 永远匹配不到 → waitForServer 干等 90s 超时（慢启动根因）。
            // 改为读完整页（上限 256KB，本地读取 <50ms）。
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

    // ============ 前台保活服务 ============
    /** 启动前台服务（带常驻通知），引擎运行期间挂后台不被系统杀掉。 */
    private void startKeepAliveService() {
        try {
            Intent i = new Intent(this, EngineService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Log.i(TAG, "keep-alive service started");
        } catch (Throwable t) {
            Log.w(TAG, "keep-alive service start failed", t);
        }
    }

    /** 停止前台服务（用户主动退出时调用）。 */
    private void stopKeepAliveService() {
        try {
            stopService(new Intent(this, EngineService.class));
        } catch (Throwable ignored) {}
    }

    // ============ AI 发通知通道（本地端口，只需通知权限） ============
    /** 通知渠道（App 内发通知用，与保活服务的渠道分开）。 */
    private static final String NOTIFY_CHANNEL_ID = "dsh_ai_notify";
    private static final String NOTIFY_CHANNEL_NAME = "AI 通知";
    // 通知端口动态跟随引擎端口（enginePort+1），保证两个 App 共存时不冲突
    /**
     * 三版本共存的默认引擎端口，按包名区分（与 AccessibilityService 的口径一致）：
     * 正式版 3080 / Lite 3082 / 兼容版 3084。通知端口 = 引擎端口 + 1，无障碍端口 = +101。
     * 否则三套 App 同时安装会抢同一个 3080（表现为 EADDRINUSE、工具连到别的版本的服务）。
     */
    // v1.18（B11/B12）：端口不再按包名 contains() 猜，统一由构建期生成的 BuildVariant 提供
    // （真源 android-app/variants.sh）。每个变体一段独立端口，互不重叠。
    private static int defaultEnginePort(Context ctx) {
        return BuildVariant.ENGINE_PORT;
    }

    /** v1.19.0：AI 浏览器宿主（懒建；见 BrowserHost 类注释）。 */
    /** v1.19.6 · A2：AI 浏览器跑在独立 :browser 进程里，主进程只留这条 IPC 客户端。 */
    private BrowserIpc browserIpc;

    private int notifyPort() { return enginePort + 1; }

    /** 启动本地通知监听：AI 通过插件请求 http://127.0.0.1:<notifyPort> 发通知（仅需通知权限）。 */
    private void startNotifyServer() {
        // v1.13：幂等 —— 重复调用（用户连点「启动引擎」、看门狗重试）会对同一端口二次 bind，
        // 真机日志里表现为“notify server stopped + EADDRINUSE”，期间通知通道短暂不可用。
        if (notifyServerStarted) return;
        notifyServerStarted = true;
        final int port = notifyPort();
        new Thread(new Runnable() {
            @Override public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", port));
                    Log.i(TAG, "notify server listening on " + port);
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            final Socket s = ss.accept();
                            handleNotifyConnection(s);
                        } catch (Throwable t) {
                            // accept 异常（连接被重置/中断）不退出监听循环，短暂等待后继续
                            try { Thread.sleep(100); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "notify server stopped", t);
                    notifyServerStarted = false;   // v1.13：bind 失败（端口被占）时允许下次重试
                } finally {
                    try { if (ss != null) ss.close(); } catch (Throwable ignored) {}
                    notifyServerStarted = false;
                }
            }
        }, "notify-server").start();
    }

    /** 处理一条本地请求：按 HTTP 路径分发（/notify 通知、/setting 系统设置、/clipboard 剪贴板）。 */
    private void handleNotifyConnection(final Socket s) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    s.setSoTimeout(5000);
                    InputStream in = s.getInputStream();
                    // 1) 读请求行 + 请求头，解析路径和 Content-Length
                    int contentLength = 0;
                    StringBuilder head = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1) {
                        head.append((char) c);
                        if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
                        if (head.length() > 8192) break; // 防异常大头部
                    }
                    String h = head.toString();
                    // 请求行形如: POST /notify HTTP/1.1
                    String path = "/notify";
                    int sp1 = h.indexOf(' ');
                    int sp2 = sp1 >= 0 ? h.indexOf(' ', sp1 + 1) : -1;
                    if (sp1 >= 0 && sp2 > sp1) path = h.substring(sp1 + 1, sp2);
                    int qIdx = path.indexOf('?');
                    if (qIdx >= 0) path = path.substring(0, qIdx);
                    int clIdx = h.toLowerCase().indexOf("content-length:");
                    if (clIdx >= 0) {
                        int eol = h.indexOf('\r', clIdx);
                        if (eol < 0) eol = h.indexOf('\n', clIdx);
                        if (eol < 0) eol = h.length();
                        try {
                            contentLength = Integer.parseInt(h.substring(clIdx + 15, eol).trim());
                        } catch (Exception ignored) {}
                    }
                    // 2) 读取正文（JSON body）
                    StringBuilder body = new StringBuilder();
                    if (contentLength > 0 && contentLength < 65536) {
                        byte[] buf = new byte[contentLength];
                        int off = 0;
                        while (off < contentLength) {
                            int n = in.read(buf, off, contentLength - off);
                            if (n < 0) break;
                            off += n;
                        }
                        body.append(new String(buf, 0, off, "UTF-8"));
                    } else {
                        // contentLength==0（如 GET 请求 /usage?days=N /overlay /status）：不读 body，
                        // 否则阻塞等 EOF 会 5s 读超时（SocketTimeoutException），所有 GET 路由卡死。
                    }
                    // 3) 分发处理
                    // v1.18.0：本地服务统一鉴权。loopback 不是访问控制（任意应用都能连 127.0.0.1），
                    // 令牌与 /shell 同源（dsh_prefs 的 local_token，随 env 交给引擎里的插件）。
                    String mine = localToken();
                    boolean authed = LocalAuth.ok(mine, h, path)
                            || (path.startsWith("/shell") && mine.length() >= 16
                                && mine.equals(jsonField(body.toString(), "token")));
                    String respBody;
                    if (!authed) {
                        Log.w(TAG, "本地服务请求被拒（令牌缺失或错误）：" + path);
                        respBody = LocalAuth.denied();
                    } else if (path.startsWith("/shell")) {
                        // v1.13.1：App 进程内的特权执行（Shizuku API 通道）——见 handleShellRequest
                        respBody = handleShellRequest(body.toString());
                    } else if (path.startsWith("/setting")) {
                        respBody = handleSettingRequest(body.toString());
                    } else if (path.startsWith("/clipboard")) {
                        respBody = handleClipboardRequest(body.toString());
                    } else if (path.startsWith("/usage")) {
                        respBody = handleUsageRequest(path, body.toString());
                    } else if (path.startsWith("/packages")) {
                        // v1.19：免特权列应用（PackageManager；不走 Su/Shizuku）
                        respBody = handlePackagesRequest(body.toString());
                    } else if (path.startsWith("/app")) {
                        // v1.19：免特权启动应用（launcher Intent；不走 Su/Shizuku）
                        respBody = handleAppRequest(body.toString());
                    } else if (path.startsWith("/openurl")) {
                        // v1.22：免特权打开 URL / 深链 / 通用 Intent（不走 Su/Shizuku）
                        respBody = handleOpenUrlRequest(body.toString());
                    } else if (path.startsWith("/apk")) {
                        // v1.22：免特权查看 / 解包已安装应用的 APK（PackageManager + java.util.zip）
                        respBody = handleApkRequest(body.toString());
                    } else if (path.startsWith("/overlay")) {
                        respBody = handleOverlayRequest(path, body.toString());
                    } else if (path.startsWith("/status")) {
                        respBody = handleStatusRequest();
                    } else if (path.startsWith("/browser")) {
                        // v1.19.0：AI 浏览器（结构化 DOM 快照 + 稳定 ref）——走同一道令牌闸门
                        respBody = handleBrowserRequest(body.toString());
                    } else if (path.equals("/notify") || path.equals("/")) {
                        respBody = handleNotifyRequest(body.toString());
                    } else {
                        // v1.18.0 fail-closed：未匹配路径不再默认当 /notify 处理
                        respBody = "{\"ok\":false,\"error\":\"未知路由: " + jesc(path) + "\"}";
                    }
                    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
                    w.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + respBody.getBytes("UTF-8").length + "\r\nConnection: close\r\n\r\n" + respBody);
                    w.flush();
                    s.close();
                } catch (Throwable t) {
                    Log.w(TAG, "local server connection error", t);
                    try { s.close(); } catch (Throwable ignored) {}
                }
            }
        }, "local-conn").start();
    }

    /**
     * v1.19.0：AI 浏览器路由。请求体 {op, args, timeout_ms?}，op ∈
     * caps | open | snapshot | find | click | type | read | scroll | press | nav | screenshot | close。
     *
     * 设计要点（我们自己的）：ref 是「元素身份指纹」（跨快照稳定），不是位置编号；
     * snapshot 支持差分（since）；find 命中的元素自动打 ref；动作后回 changed + 最小差异。
     * 定位一律走 ref —— 截图只做"给人看的证据"。
     */
    /**
     * ⚠ **必须 synchronized**：BrowserIpc 只有**一个 inbox**，每次 op 前还会 clear() ——
     * 两条 op 并发进来会互相把回信吃掉。原来只有本地服务线程在调（事实上的单线程），
     * v1.19.6 控制台的「AI 浏览器」页也从这里走，串行化就从"碰巧"变成"必须"。
     */
    private synchronized String handleBrowserRequest(String raw) {
        try {
            org.json.JSONObject req = (raw == null || raw.trim().isEmpty())
                    ? new org.json.JSONObject() : new org.json.JSONObject(raw);
            String op = req.optString("op", "caps");
            org.json.JSONObject args = req.optJSONObject("args");
            int timeout = req.optInt("timeout_ms", 30000);
            if (browserIpc == null) browserIpc = new BrowserIpc(this);
            return browserIpc.op(op, args, timeout);
        } catch (Throwable t) {
            return "{\"ok\":false,\"reason\":\"browser-route-error\",\"error\":\""
                    + jesc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    /** 处理 /usage：查询应用使用时长（UsageStats）。参数 days=N（默认 1，上限 30）。 */
    private String handleUsageRequest(String path, String raw) {
        try {
            String days = jsonField(raw, "days");
            if (days.isEmpty()) days = queryField(path, "days");
            int d = 1;
            try { if (!days.isEmpty()) d = Integer.parseInt(days.trim()); } catch (Exception ignored) {}
            return UsageStatsHelper.queryUsageJson(this, d);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 本地路由鉴权：与 /shell 同款 token（只经 env APP_LOCAL_TOKEN 交给本应用自己的引擎）。 */
    private boolean localTokenOk(String raw) {
        String mine = localToken();
        String token = jsonField(raw, "token");
        return !mine.isEmpty() && mine.equals(token);
    }

    private String jsonErr(String msg) {
        return "{\"ok\":false,\"error\":\"" + jesc(msg) + "\"}";
    }

    /**
     * /packages：列出已安装应用（**免特权**，走 PackageManager）。
     *
     * 为什么免特权可行：本应用 targetSdk=28，而 Android 11+ 的**包可见性过滤只对 targetSdk≥30 生效**，
     * 所以 getInstalledApplications / getLaunchIntentForPackage 能看到全部应用，**无需** QUERY_ALL_PACKAGES。
     * ⚠ 若将来把 targetSdk 提到 30+（本项目因 noexec 限制保持 28），必须补
     *   &lt;queries&gt;（MAIN+LAUNCHER）或 QUERY_ALL_PACKAGES，否则这里会静默变空。
     * body: {token, filter?, third_party_only?, launchable_only?, limit?}
     */
    private String handlePackagesRequest(String raw) {
        try {
            if (!localTokenOk(raw)) return jsonErr("token 校验失败（该接口仅限本应用引擎调用）");
            android.content.pm.PackageManager pm = getPackageManager();
            String filter = jsonField(raw, "filter").trim().toLowerCase();
            boolean thirdOnly = "true".equalsIgnoreCase(jsonField(raw, "third_party_only").trim());
            boolean launchableOnly = "true".equalsIgnoreCase(jsonField(raw, "launchable_only").trim());
            int limit = 200;
            try {
                String ls = jsonField(raw, "limit").trim();
                if (!ls.isEmpty()) limit = Math.max(1, Math.min(Integer.parseInt(ls), 1000));
            } catch (Exception ignored) {}
            java.util.List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
            java.util.ArrayList<Object[]> rows = new java.util.ArrayList<Object[]>();
            for (int i = 0; i < apps.size(); i++) {
                android.content.pm.ApplicationInfo ai = apps.get(i);
                boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                if (thirdOnly && system) continue;
                Intent launch = null;
                try { launch = pm.getLaunchIntentForPackage(ai.packageName); } catch (Throwable ignored) {}
                boolean launchable = launch != null;
                if (launchableOnly && !launchable) continue;
                String label = "";
                try { label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Throwable ignored) {}
                if (!filter.isEmpty()
                        && !ai.packageName.toLowerCase().contains(filter)
                        && !label.toLowerCase().contains(filter)) continue;
                String ver = "";
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                    if (pi != null && pi.versionName != null) ver = pi.versionName;
                } catch (Throwable ignored) {}
                rows.add(new Object[]{ai.packageName, label, Boolean.valueOf(system), Boolean.valueOf(launchable), ver});
            }
            // 可启动的排前面，其次按 label 排序（便于模型/人阅读）
            java.util.Collections.sort(rows, new java.util.Comparator<Object[]>() {
                @Override public int compare(Object[] a, Object[] b) {
                    boolean la = ((Boolean) a[3]).booleanValue(), lb = ((Boolean) b[3]).booleanValue();
                    if (la != lb) return la ? -1 : 1;
                    return String.valueOf(a[1]).compareToIgnoreCase(String.valueOf(b[1]));
                }
            });
            org.json.JSONArray arr = new org.json.JSONArray();
            int n = Math.min(rows.size(), limit);
            for (int i = 0; i < n; i++) {
                Object[] r = rows.get(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("package", r[0]);
                o.put("label", r[1]);
                o.put("system", ((Boolean) r[2]).booleanValue());
                o.put("launchable", ((Boolean) r[3]).booleanValue());
                if (!String.valueOf(r[4]).isEmpty()) o.put("versionName", r[4]);
                arr.put(o);
            }
            org.json.JSONObject out = new org.json.JSONObject();
            out.put("ok", true);
            out.put("total", rows.size());
            out.put("count", n);
            out.put("apps", arr);
            return out.toString();
        } catch (Throwable t) {
            return jsonErr("packages error: " + t.getMessage());
        }
    }

    /**
     * /app：启动已安装应用（**免特权**，走 launcher Intent）。
     * ⚠ Android 10+ 有后台启动 Activity 限制（BAL）：本应用通常已授予悬浮窗权限（系统豁免之一），
     *   且"用户刚操作过 App / App 在前台"时最稳。仍失败时如实提示改用特权通道 am start。
     * body: {token, package}
     */
    private String handleAppRequest(String raw) {
        try {
            if (!localTokenOk(raw)) return jsonErr("token 校验失败（该接口仅限本应用引擎调用）");
            String pkg = jsonField(raw, "package").trim();
            if (pkg.isEmpty()) return jsonErr("缺少 package 参数");
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                return jsonErr("该应用没有 launcher 入口（可能未安装，或它是没有界面的系统包）：" + pkg);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ok", true);
            o.put("package", pkg);
            if (i.getComponent() != null) o.put("component", i.getComponent().flattenToShortString());
            return o.toString();
        } catch (Throwable t) {
            return jsonErr("启动失败：" + t.getMessage()
                    + "（Android 10+ 后台启动 Activity 可能被系统拦截：可先把 App 切到前台再试，或授权 Shizuku/root 走 am start）");
        }
    }

    /**
     * POST /apk {"action":"path|list|extract","package":"…","entry":"…","prefix":"…","out_dir":"…","limit":N,"token":"…"}
     * v1.22 新增，**免特权**（PackageManager 给 sourceDir；java.util.zip.ZipFile 读包内条目）。
     *
     * 为什么放 App 侧：① Java 自带 ZipFile，不依赖设备上有没有 unzip；
     * ② 复盘里那位 agent 是手搓 `pm path` + `unzip` 才挖到 APK 里的 JS bundle 与接口表 —— 这是
     *    高价值的侦查入口，值得一等公民化（列条目 / 解单个文件 / 按前缀批量解）。
     * 安全边界：只读已安装应用自己的 APK；解包必须给 out_dir（且限制在我们能写的位置）；
     *          条目数有上限，避免把巨型 APK 全量拉出来。
     */
    private String handleApkRequest(String raw) {
        try {
            if (!localTokenOk(raw)) return jsonErr("token 校验失败（该接口仅限本应用引擎调用）");
            String action = jsonField(raw, "action").trim();
            if (action.isEmpty()) action = "path";
            String pkg = jsonField(raw, "package").trim();
            if (pkg.isEmpty()) return jsonErr("缺少 package 参数（可用 android_apps 查包名）");
            android.content.pm.ApplicationInfo ai;
            try {
                ai = getPackageManager().getApplicationInfo(pkg, 0);
            } catch (Throwable t) {
                return jsonErr("找不到该应用：" + pkg);
            }
            String apk = ai.sourceDir;
            org.json.JSONObject o = new org.json.JSONObject();
            if ("path".equals(action)) {
                o.put("ok", true);
                o.put("package", pkg);
                o.put("apk", apk);
                o.put("sizeBytes", new java.io.File(apk).length());
                o.put("label", String.valueOf(getPackageManager().getApplicationLabel(ai)));
                return o.toString();
            }
            if (!"list".equals(action) && !"extract".equals(action)) {
                return jsonErr("action 需要 path / list / extract");
            }
            int limit = 2000;
            try { String s = jsonField(raw, "limit").trim(); if (!s.isEmpty()) limit = Math.min(20000, Math.max(1, Integer.parseInt(s))); } catch (Throwable ignored) {}
            if ("list".equals(action)) {
                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
                org.json.JSONArray arr = new org.json.JSONArray();
                StringBuilder flat = new StringBuilder();
                int n = 0;
                try {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements() && n < limit) {
                        java.util.zip.ZipEntry e = en.nextElement();
                        n++;
                        org.json.JSONObject it = new org.json.JSONObject();
                        it.put("name", e.getName());
                        it.put("size", e.getSize());
                        if (n <= 300) arr.put(it);           // 结构化最多 300 条，其余用扁平文本给
                        flat.append(e.getName()).append("  ").append(e.getSize()).append('\n');
                    }
                } finally { zf.close(); }
                o.put("ok", true);
                o.put("package", pkg);
                o.put("apk", apk);
                o.put("total", n);
                o.put("truncated", n >= limit);
                o.put("entries", arr);
                String s = flat.toString();
                o.put("entriesText", s.length() > 60000 ? s.substring(0, 60000) : s);
                o.put("hint", "用 action=extract + entry=<包内路径> 取出单个文件；JS bundle / 接口表通常在 assets/ 下。");
                return o.toString();
            }
            // extract
            String entry = jsonField(raw, "entry").trim();
            String prefix = jsonField(raw, "prefix").trim();
            if (entry.isEmpty() && prefix.isEmpty()) return jsonErr("extract 需要 entry（单个包内路径）或 prefix（前缀，如 assets/）");
            String outDir = jsonField(raw, "out_dir").trim();
            if (outDir.isEmpty()) outDir = new java.io.File(getFilesDir(), "apk-extract").getAbsolutePath();
            java.io.File dir = new java.io.File(outDir);
            if (!dir.exists() && !dir.mkdirs()) return jsonErr("无法创建输出目录：" + outDir);
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
            org.json.JSONArray files = new org.json.JSONArray();
            int copied = 0;
            int totalBytes = 0;
            try {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements() && copied < 200) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    boolean match = !entry.isEmpty() ? e.getName().equals(entry) : e.getName().startsWith(prefix);
                    if (!match) continue;
                    // 防目录穿越：条目名里不允许 ..，且目标必须落在 dir 内
                    String name = e.getName().replace("..", "_");
                    java.io.File out = new java.io.File(dir, name);
                    if (!out.getAbsolutePath().startsWith(dir.getAbsolutePath())) continue;
                    java.io.File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    java.io.InputStream in = zf.getInputStream(e);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int r;
                    try {
                        while ((r = in.read(buf)) > 0) { fos.write(buf, 0, r); totalBytes += r; }
                    } finally { try { in.close(); } catch (Throwable ignored) {} try { fos.close(); } catch (Throwable ignored) {} }
                    files.put(out.getAbsolutePath());
                    copied++;
                }
            } finally { zf.close(); }
            o.put("ok", copied > 0);
            o.put("package", pkg);
            o.put("outDir", dir.getAbsolutePath());
            o.put("files", files);
            o.put("copied", copied);
            o.put("bytes", totalBytes);
            if (copied == 0) o.put("error", "APK 里没有匹配「" + (entry.isEmpty() ? prefix : entry) + "」的条目（先用 action=list 看清单）");
            return o.toString();
        } catch (Throwable t) {
            return jsonErr("APK 操作失败：" + t.getMessage());
        }
    }

    /**
     * POST /openurl {"url":"…","package":"…"(可选),"token":"…"} —— 用系统默认应用打开 URL / 深链。
     * v1.22 新增，免特权（ACTION_VIEW 不需要任何权限）。为什么值得单开一个接口：
     * 让 AI「开浏览器 → 点地址栏 → 输入网址 → 提交」实测要 15+ 步，且 Chromium 无障碍下
     * setText 一律 false、粘贴被输入法吃掉、输入法的「确定」只收键盘不导航 —— 一条 Intent 直达即可。
     */
    private String handleOpenUrlRequest(String raw) {
        try {
            if (!localTokenOk(raw)) return jsonErr("token 校验失败（该接口仅限本应用引擎调用）");
            String url = jsonField(raw, "url").trim();
            // v1.22：扩展成通用 Intent 发起器（复盘 §5 的 android_open_intent）：
            //   action 省略或 view → ACTION_VIEW + data=url；否则用给定 action，data/type/extras 均可带。
            String action = jsonField(raw, "action").trim();
            if (url.isEmpty() && action.isEmpty()) return jsonErr("缺少 url 参数（或 action）");
            String pkg = jsonField(raw, "package").trim();
            String type = jsonField(raw, "type").trim();
            Intent i;
            if (action.isEmpty() || "view".equalsIgnoreCase(action) || "android.intent.action.VIEW".equalsIgnoreCase(action)) {
                if (url.isEmpty()) return jsonErr("action=view 需要 url 参数");
                i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            } else {
                i = new Intent(action);
                if (!url.isEmpty()) i.setData(android.net.Uri.parse(url));
            }
            if (!type.isEmpty()) i.setType(type);
            // extras：可选 JSON 对象（值支持字符串 / 整数 / 布尔）
            try {
                org.json.JSONObject body = new org.json.JSONObject(raw.isEmpty() ? "{}" : raw);
                org.json.JSONObject ex = body.optJSONObject("extras");
                if (ex != null) {
                    java.util.Iterator<String> it = ex.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        Object val = ex.get(k);
                        if (val instanceof Integer) i.putExtra(k, (Integer) val);
                        else if (val instanceof Boolean) i.putExtra(k, (Boolean) val);
                        else if (val instanceof Double) i.putExtra(k, (Double) val);
                        else i.putExtra(k, String.valueOf(val));
                    }
                }
            } catch (Throwable ignored) {}
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (!pkg.isEmpty()) i.setPackage(pkg);
            // 先探测接收者：没有就如实报错（否则 startActivity 抛 ActivityNotFoundException，提示不明确）
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
            if (ri == null || ri.activityInfo == null) {
                return jsonErr("没有可处理该 Intent 的应用"
                        + (pkg.isEmpty() ? "" : "（指定的 " + pkg + " 未安装或不支持）")
                        + "：" + (url.isEmpty() ? action : url));
            }
            startActivity(i);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ok", true);
            if (!url.isEmpty()) o.put("url", url);
            if (!action.isEmpty()) o.put("action", action);
            o.put("resolved", ri.activityInfo.packageName);
            if (!pkg.isEmpty()) o.put("package", pkg);
            return o.toString();
        } catch (Throwable t) {
            return jsonErr("打开失败：" + t.getMessage()
                    + "（Android 10+ 后台启动 Activity 可能被系统拦截：可先把 App 切到前台再试）");
        }
    }

    /** 处理 /status：引擎/服务运行状态（供悬浮窗与 AI 查询）。 */
    private String handleStatusRequest() {
        boolean engineOk = isDshEngine(enginePort);
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"enginePort\":").append(enginePort);
        sb.append(",\"engineReady\":").append(engineOk);
        sb.append(",\"nodeAlive\":").append(nodeProcess != null && nodeProcess.isAlive());
        sb.append(",\"overlay\":").append(OverlayService.isRunning);
        sb.append(",\"overlayEngineUp\":").append(OverlayService.engineUp);
        sb.append(",\"usageGranted\":").append(UsageStatsHelper.permissionGranted(this));
        sb.append(",\"overlayGranted\":").append(Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this));
        sb.append('}');
        return sb.toString();
    }

    /**
     * POST /shell {"command":"…","timeout_ms":N,"token":"…"} —— 在 「App 进程内」经 Shizuku API
     * 以 shell 身份执行命令并回收 stdout/stderr/退出码。
     *
     * 为什么不继续用引擎里的 rish：Shizuku 服务端校验「某个包是否被授权」时要回头问 Shizuku 应用本体，
     * 而 ColorOS 会冻结/查杀 Shizuku 应用（真机日志实锤：`OplusHansManager … F exit()`、
     * `NativeFreezeManager … mFgAppPkgname moe.shizuku.privileged.api`、
     * `reason=EXCESSIVE CPU USAGE … excessive binder traffic during cached state`），
     * 于是引擎内 spawn 出来的 rish 子进程等不到响应 → Shizuku 客户端库报 “Request timeout…”。
     * App 进程内的 Shizuku API 通道实测稳定（虚拟屏核心就是用它拉起的），因此特权执行改走这里。
     */
    private String handleShellRequest(String raw) {
        try {
            String token = jsonField(raw, "token");
            String mine = localToken();
            if (mine.isEmpty() || !mine.equals(token)) {
                return "{\"ok\":false,\"error\":\"token 校验失败（该接口仅限本应用引擎调用）\"}";
            }
            String command = jsonField(raw, "command");
            if (command == null || command.isEmpty()) {
                return "{\"ok\":false,\"error\":\"缺少 command 参数\"}";
            }
            int timeoutMs = 30000;
            String tm = jsonField(raw, "timeout_ms");
            if (!tm.isEmpty()) {
                try { timeoutMs = Math.max(1000, Math.min(Integer.parseInt(tm.trim()), 120000)); }
                catch (Exception ignored) {}
            }
            return shellViaShizuku(command, timeoutMs);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"shell 路由异常：" + jesc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    /** 经 Shizuku（App 进程内）以 shell uid 执行一条命令，回收输出，带超时兜底。 */
    private String shellViaShizuku(String command, final int timeoutMs) {
        if (!shizukuAvailable()) {
            return "{\"ok\":false,\"error\":\"Shizuku 未授权或服务未运行（App 内 pingBinder/checkSelfPermission 失败）\"}";
        }
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        try {
            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (svc == null) return "{\"ok\":false,\"error\":\"Shizuku binder 为空\"}";
            final IRemoteProcess p = svc.newProcess(new String[]{"/system/bin/sh", "-c", command}, null, null);
            if (p == null) return "{\"ok\":false,\"error\":\"Shizuku newProcess 返回空\"}";
            Thread to = pumpStream(new android.os.ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream()), out, "priv-out");
            Thread te = pumpStream(new android.os.ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream()), err, "priv-err");
            final int[] code = new int[]{-1};
            Thread waiter = new Thread(new Runnable() {
                @Override public void run() {
                    try { code[0] = p.waitFor(); } catch (Throwable ignored) {}
                }
            }, "priv-wait");
            waiter.start();
            waiter.join(timeoutMs);
            if (waiter.isAlive()) {
                try { p.destroy(); } catch (Throwable ignored) {}
                waiter.join(1500);
                joinQuietly(to); joinQuietly(te);
                return "{\"ok\":false,\"exit_code\":-1,\"stdout\":\"" + jesc(clip(out))
                        + "\",\"stderr\":\"" + jesc(clip(err))
                        + "\",\"error\":\"命令超时（" + timeoutMs + "ms）已终止\"}";
            }
            joinQuietly(to); joinQuietly(te);
            return "{\"ok\":" + (code[0] == 0) + ",\"exit_code\":" + code[0]
                    + ",\"stdout\":\"" + jesc(clip(out)) + "\",\"stderr\":\"" + jesc(clip(err)) + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"exit_code\":-1,\"stdout\":\"" + jesc(clip(out))
                    + "\",\"stderr\":\"" + jesc(clip(err))
                    + "\",\"error\":\"Shizuku 执行失败：" + jesc(String.valueOf(t.getMessage())) + "\"}";
        }
    }

    /** 后台把一条流读到 EOF。 */
    private Thread pumpStream(final InputStream in, final StringBuilder sb, String name) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (sb.length() < 65536) sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try { if (t != null) t.join(800); } catch (Throwable ignored) {}
    }

    private static String clip(StringBuilder sb) {
        String s = sb.toString();
        return s.length() > 8000 ? s.substring(0, 8000) : s;
    }

    /** 最小 JSON 字符串转义。 */
    private static String jesc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.toString();
    }

    /**
     * 本地特权路由的鉴权令牌：持久化在 dsh_prefs（App 私有），随 env 交给引擎。
     * 用 prefs 而不是内存字段，是为了「App 重启但引擎还活着」时令牌不变、插件调用不失效。
     */
    private String localToken() {
        try {
            SharedPreferences p = getSharedPreferences("dsh_prefs", MODE_PRIVATE);
            String t = p.getString("local_token", "");
            if (t == null || t.length() < 16) {
                byte[] b = new byte[16];
                new java.security.SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < b.length; i++) sb.append(String.format("%02x", b[i]));
                t = sb.toString();
                p.edit().putString("local_token", t).apply();
            }
            return t;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 处理 /overlay：控制小鲸鱼悬浮窗。action=show|hide|toggle|status。 */
    private String handleOverlayRequest(String path, String raw) {
        try {
            String action = jsonField(raw, "action");
            if (action.isEmpty()) action = queryField(path, "action");
            if (action.isEmpty()) action = "status";
            if (action.equals("status")) {
                return "{\"ok\":true,\"running\":" + OverlayService.isRunning
                        + ",\"engineUp\":" + OverlayService.engineUp
                        + ",\"granted\":" + (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) + "}";
            }
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                return "{\"ok\":false,\"error\":\"未授予悬浮窗权限，请在权限引导页/系统设置里开启\"}";
            }
            if (action.equals("show") || action.equals("toggle")) {
                if (OverlayService.isRunning) {
                    if (action.equals("toggle")) { stopOverlayService(); return "{\"ok\":true,\"running\":false}"; }
                    return "{\"ok\":true,\"running\":true,\"msg\":\"已在运行\"}";
                }
                startOverlayService();
                return "{\"ok\":true,\"running\":true}";
            }
            if (action.equals("hide")) {
                stopOverlayService();
                return "{\"ok\":true,\"running\":false}";
            }
            // v1.21（需求 2）：agent 状态气泡。内核侧插件把当前状态 POST 到这里，
            // 转给 OverlayService 显示在小人上方（text 空 = 收起）。
            if (action.equals("bubble") || action.equals("agent-status")) {
                String text = jsonField(raw, "text");
                // v1.23：气泡是**单行**（setSingleLine + TruncateAt.END，宽 240dp），太长了会被省略号截断，
                // 所以给出明确的字数上限；中文与非中文**分开计**（一个汉字约占两个半角宽）。
                // 这里**强制执行**（超了按上限截断），并把真实计数回给调用方，好让它如实报告。
                int[] cnt = new int[2];
                String fitted = fitBubbleText(text, cnt);                       // ① 字数上限（写进工具说明的规则）
                String byWidth = OverlayService.fitBubbleWidth(fitted, 400);    // ② 按气泡真实渲染宽度（字号缩放也不出省略号）
                if (!byWidth.equals(fitted)) {
                    fitted = byWidth;
                    countBubble(fitted, cnt);
                }
                boolean truncated = !fitted.equals(text);
                boolean sticky = "true".equalsIgnoreCase(jsonField(raw, "sticky"));
                long ttl = 0L;
                try { ttl = Long.parseLong(jsonField(raw, "ttl")); } catch (Throwable ignored) {}
                OverlayService.pushStatus(fitted, sticky, ttl);
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"bubble\":\"");
                sb.append(fitted.replace("\"", "'"));
                sb.append("\",\"cjk\":").append(cnt[0]);
                sb.append(",\"ascii\":").append(cnt[1]);
                sb.append(",\"maxCjk\":").append(BUBBLE_MAX_CJK);
                sb.append(",\"maxAscii\":").append(BUBBLE_MAX_ASCII);
                sb.append(",\"truncated\":").append(truncated);
                sb.append(",\"running\":").append(OverlayService.isRunning);
                sb.append('}');
                return sb.toString();
            }
            // v1.21：主动拉取一次几何快照（供排版类问题做**数字**验证，不必依赖截图）。
            if (action.equals("geom")) {
                OverlayService.nudgeGeometry();
                return OverlayService.lastGeom.isEmpty()
                        ? "{\"ok\":false,\"error\":\"悬浮窗未运行或还没排过版\"}"
                        : OverlayService.lastGeom;
            }
            return "{\"ok\":false,\"error\":\"未知 action（show/hide/toggle/status/bubble/geom）\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 启动小鲸鱼悬浮窗（需已授予悬浮窗权限；权限引导页里会调用）。 */
    private void startOverlayService() {
        try {
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Log.i(TAG, "overlay service starting");
        } catch (Throwable t) {
            Log.w(TAG, "overlay start failed", t);
        }
    }

    /** 停止小鲸鱼悬浮窗。 */
    private void stopOverlayService() {
        try {
            stopService(new Intent(this, OverlayService.class));
        } catch (Throwable t) {
            Log.w(TAG, "overlay stop failed", t);
        }
    }

    /** 处理 /notify：发系统通知（仅需通知权限）。 */
    private String handleNotifyRequest(String raw) {
        String title = "", text = "";
        int ti = raw.indexOf("\"title\"");
        int tx = raw.indexOf("\"text\"");
        if (ti >= 0 || tx >= 0) {
            title = jsonField(raw, "title");
            text = jsonField(raw, "text");
        } else {
            title = queryField(raw, "title");
            text = queryField(raw, "text");
        }
        if (title.isEmpty()) title = "DeepSeek Harness";
        if (text.isEmpty()) text = "(空消息)";
        boolean granted = checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            postNotification(title, text);
            return "{\"ok\":true}";
        }
        return "{\"ok\":false,\"error\":\"通知权限未授予，无法发送通知\"}";
    }

    /** 处理 /setting：改系统设置（⑤，走 App 的 WRITE_SETTINGS 权限，仅限 System 命名空间，免 Shizuku）。
     *  音量类 key 必须走 AudioManager.setStreamVolume（Settings.System 的记录不生效）；
     *  其余 System 项走 Settings.System.put。 */
    private String handleSettingRequest(String raw) {
        try {
            String key = jsonField(raw, "key");
            String value = jsonField(raw, "value");
            if (key.isEmpty()) {
                key = queryField(raw, "key");
                value = queryField(raw, "value");
            }
            if (key.isEmpty()) return "{\"ok\":false,\"error\":\"缺少 key 参数\"}";
            // 音量：走 AudioManager（真实生效，无需 WRITE_SETTINGS）
            if (key.startsWith("volume_")) {
                return handleVolumeRequest(key, value);
            }
            // 其余 System 设置：需要 WRITE_SETTINGS 权限
            if (Build.VERSION.SDK_INT < 23 || !Settings.System.canWrite(this)) {
                return "{\"ok\":false,\"error\":\"未授予「修改系统设置」权限（WRITE_SETTINGS），无法修改；请先在权限引导页/系统设置里开启\"}";
            }
            boolean ok;
            if (isNumeric(value)) {
                ok = Settings.System.putInt(getContentResolver(), key, Integer.parseInt(value));
            } else {
                ok = Settings.System.putString(getContentResolver(), key, value);
            }
            return ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"写入失败（key 可能不存在或不允许修改）\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 音量调节：走 AudioManager.setStreamVolume（真实改变音量）。 */
    private String handleVolumeRequest(String key, String value) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "{\"ok\":false,\"error\":\"音频服务不可用\"}";
            int stream;
            switch (key) {
                case "volume_music": stream = android.media.AudioManager.STREAM_MUSIC; break;
                case "volume_ring": stream = android.media.AudioManager.STREAM_RING; break;
                case "volume_alarm": stream = android.media.AudioManager.STREAM_ALARM; break;
                case "volume_notification": stream = android.media.AudioManager.STREAM_NOTIFICATION; break;
                case "volume_system": stream = android.media.AudioManager.STREAM_SYSTEM; break;
                case "volume_voice_call": stream = android.media.AudioManager.STREAM_VOICE_CALL; break;
                default: return "{\"ok\":false,\"error\":\"不支持的音量类型: " + key + "\"}";
            }
            int max = am.getStreamMaxVolume(stream);
            int val;
            if (value.endsWith("%")) {
                // 支持百分比：如 "50%"
                val = (int) Math.round(max * Integer.parseInt(value.replace("%", "").trim()) / 100.0);
            } else {
                val = Integer.parseInt(value.trim());
            }
            if (val < 0) val = 0;
            if (val > max) val = max;
            // flags=0：不显示音量条、不播放提示音（静默调整，避免打扰）
            am.setStreamVolume(stream, val, 0);
            return "{\"ok\":true,\"stream\":\"" + key + "\",\"level\":" + val + ",\"max\":" + max + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 处理 /clipboard：读写剪贴板（⑦，无需任何特殊权限）。 */
    private String handleClipboardRequest(String raw) {
        try {
            String action = jsonField(raw, "action");
            if (action.isEmpty()) action = queryField(raw, "action");
            if (action.isEmpty()) action = "read";
            if (action.equals("write")) {
                String content = jsonField(raw, "content");
                if (content.isEmpty()) content = queryField(raw, "content");
                if (content.isEmpty()) return "{\"ok\":false,\"error\":\"缺少 content 参数\"}";
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", content));
                return "{\"ok\":true}";
            }
            // read
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "{\"ok\":true,\"content\":\"\"}";
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            String content = cs == null ? "" : cs.toString();
            // JSON 转义
            content = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
            return "{\"ok\":true,\"content\":\"" + content + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /**
     * 从请求体里取一个字符串字段。
     *
     * v1.13.4：改成「认识转义」的解析。旧实现是“取第一个引号到下一个引号”，不认 `\"` `\\` `\n` 等，
     * 于是命令里带引号/换行会被截断：例如 shizuku_shell 传
     * `pm install -r "/sdcard/Download/my app.apk"` 会被解析成 `pm install -r \`，后半段全丢，
     * 而工具还会“照跑”——表现为莫名其妙的失败。
     */
    private String jsonField(String json, String key) {
        try {
            String k = "\"" + key + "\"";
            int i = json.indexOf(k);
            if (i < 0) return "";
            int c = json.indexOf(':', i + k.length());
            if (c < 0) return "";
            // v1.19：支持裸字面量（true/false/数字）。旧实现只认带引号的字符串，
            // 于是 {"limit":8} 会顺延到**下一个键**的引号上——把 "filter" 这样的键名当成值返回。
            int v0 = c + 1;
            while (v0 < json.length() && Character.isWhitespace(json.charAt(v0))) v0++;
            if (v0 < json.length() && json.charAt(v0) != '"') {
                int e = v0;
                while (e < json.length()) {
                    char ec = json.charAt(e);
                    if (ec == ',' || ec == '}' || ec == ']') break;
                    e++;
                }
                return json.substring(v0, e).trim();
            }
            int q1 = json.indexOf('"', c + 1);
            if (q1 < 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int p = q1 + 1; p < json.length(); p++) {
                char ch = json.charAt(p);
                if (ch == '\\') {
                    if (p + 1 >= json.length()) break;
                    char n = json.charAt(p + 1);
                    if (n == 'n') sb.append('\n');
                    else if (n == 'r') sb.append('\r');
                    else if (n == 't') sb.append('\t');
                    else if (n == 'b') sb.append('\b');
                    else if (n == 'f') sb.append('\f');
                    else if (n == 'u') {
                        if (p + 5 >= json.length()) break;
                        try {
                            sb.append((char) Integer.parseInt(json.substring(p + 2, p + 6), 16));
                            p += 4;
                        } catch (Exception ignored) {}
                    } else {
                        sb.append(n);      // \" \\ \/ 等：取字符本身
                    }
                    p++;
                    continue;
                }
                if (ch == '"') break;
                sb.append(ch);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从 query 字符串里取字段值（title=..&text=..）。 */
    private String queryField(String q, String key) {
        try {
            String k = key + "=";
            int i = q.indexOf(k);
            if (i < 0) return "";
            int e = q.indexOf('&', i + k.length());
            if (e < 0) e = q.length();
            return q.substring(i + k.length(), e).replace("+", " ");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 发一条 AI 通知（仅需 POST_NOTIFICATIONS，无需 Shizuku/root）。 */
    private void postNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(NOTIFY_CHANNEL_ID, NOTIFY_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("AI 任务完成/需要你关注时推送");
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 1, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, NOTIFY_CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            Notification n = b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            int id = (int) (System.currentTimeMillis() & 0x7fffffff);
            nm.notify(id, n);
            Log.i(TAG, "AI notification sent: " + title);
        } catch (Throwable t) {
            Log.w(TAG, "post notification failed", t);
        }
    }

    // 探测外部公共目录是否可写（不需要"所有文件访问"时也能降级内部）
    private boolean externalDshrootWritable(File externalRoot) {
        try {
            if (!externalRoot.exists() && !externalRoot.mkdirs()) return false;
            File probe = new File(externalRoot, ".probe");
            if (!probe.createNewFile()) return false;
            probe.delete();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "external dshroot not writable, fallback to internal", t);
            return false;
        }
    }

    private String readAssetText(String asset) throws IOException {
        InputStream in = getAssets().open(asset);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private String readFileText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    /** v1.21：写文本文件（覆盖）。用于把内置插件注册进 profile/package.json。 */
    private void writeFileText(File f, String text) throws IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private String builtinDshrootRevision() {
        try {
            return readAssetText("dshroot_revision.txt").trim();
        } catch (Throwable t) {
            Log.w(TAG, "read dshroot revision failed", t);
            return "";
        }
    }

    // v1.5.3：dshroot 版本检查统一以「基目录」为单位——外部模式传 /sdcard/DeepSeekHarness，
    // 内部模式传 files/payload（内部 dshroot 位于 payload/dshroot，路径拼接一致）。
    private String dshrootRevisionAt(File dshrootBase) {
        File revFile = new File(dshrootBase, "dshroot/REVISION");
        try {
            return revFile.exists() ? readFileText(revFile).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean dshrootRevisionChanged(File dshrootBase) {
        String builtin = builtinDshrootRevision();
        String external = dshrootRevisionAt(dshrootBase);
        return !builtin.isEmpty() && !builtin.equals(external);
    }

    // dshroot 是否需要补齐：REVISION 不匹配（重装）或缺完成标记（解压被打断）。
    private boolean dshrootNeedsSync(File dshrootBase) {
        if (dshrootRevisionChanged(dshrootBase)) return true;
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        return !complete.exists();
    }

    // v1.5.2 慢启动修复：是否必须走全量补齐（扫描 2 万+ 文件）。
    // 仅两种情况需要：① .complete 缺失（上次解压被打断，缺文件）② 内核版本变化（新内核新增包文件）。
    // 同内核升级（REVISION 变化但内容几乎不变）→ false → 走快速同步，避免真机外部存储 FUSE 上
    // 2 万+ 次 stat 造成的 50-60s 慢启动（模拟器宿主机磁盘快，测不出）。
    // v1.13.12：布局标记变化也不再触发全量 —— 老版本升上来（.complete 里没有/是别的布局标记）
    // 会被判"布局变了"而整棵重写 2.5 万文件；改由 startEngine 走「dshroot-add」增量补齐
    //（只写缺失文件 + 白名单覆盖 + 清理多余顶层插件包），更新安装不再出现"又解压一遍"。
    private boolean dshrootNeedsFullSync(File dshrootBase) {
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        if (!complete.exists()) return true;
        return dshKernelChanged(dshrootBase);
    }

    // 内核树布局标记（build.sh 写入 assets/dshroot_layout.txt）：布局变更时必须全量重推，
    // 否则 fast 同步只覆盖白名单文件，整棵树留着旧结构（补丁包/依赖树换过就再也更新不到）。
    private String builtinDshrootLayout() {
        try {
            return readAssetText("dshroot_layout.txt").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean dshrootLayoutChanged(File dshrootBase) {
        String builtin = builtinDshrootLayout();
        if (builtin.isEmpty()) return false; // 旧 APK 无标记 → 不额外触发
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        try {
            String stored = readFileText(complete).trim();
            int bar = stored.lastIndexOf('|');
            String storedLayout = bar < 0 ? "" : stored.substring(bar + 1);
            return !builtin.equals(storedLayout);
        } catch (Throwable t) {
            return true;
        }
    }

    // 对比 dshroot 的 DSH 内核版本与 APK 内置版本（build.sh 写入 dshroot_kernel_version.txt）。
    // 版本不同 → 内核升级（如 rc.6 → rc.2）→ 新增包文件必须补齐，否则引擎起不来。
    private boolean dshKernelChanged(File dshrootBase) {
        try {
            String builtin = readAssetText("dshroot_kernel_version.txt").trim();
            if (builtin.isEmpty()) return true; // 无版本标记（旧 APK）→ 保守走全量
            File pkg = new File(dshrootBase, "dshroot/lib/node_modules/@deepseek-ai/dsh/package.json");
            if (!pkg.exists()) return true;
            // ⚠ 必须真解析 JSON，不能用 contains("\"version\":\""+builtin+"\"")：
            // dsh/package.json 是带空格的 pretty-print（  "version": "0.1.5-rc.1",  ），
            // 严格拼接的字符串永远匹配不上 → 每次都被判"内核版本变了" →
            // dshrootNeedsFullSync 恒为真 → dshroot-add / dshroot-fast 永远不触发，
            // 每次升级都全量重写 2.5 万文件（"更新后又要解压一遍"的真根因）。
            String v = new org.json.JSONObject(readFileText(pkg)).optString("version", "");
            return !builtin.equals(v);
        } catch (Throwable t) {
            return true; // 读不到 → 保守全量
        }
    }

    private void writeDshrootComplete(File dshrootBase) {
        File complete = new File(dshrootBase, "dshroot/" + DSHROOT_COMPLETE);
        try {
            FileOutputStream fos = new FileOutputStream(complete);
            // 记录 <构建时间戳>|<布局标记>：布局标记用于判断是否需要全量重推（见 dshrootLayoutChanged）。
            String layout = builtinDshrootLayout();
            String marker = layout.isEmpty() ? builtinDshrootRevision() : builtinDshrootRevision() + "|" + layout;
            fos.write(marker.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            Log.w(TAG, "write dshroot complete marker failed", t);
        }
    }

    private void extractPayload(File destInternal, File externalRoot, String mode) throws IOException {
        // mode: "internal" = 只解压内部条目（runtime/bin/dshhome/rish，不含 dshroot）；
        //       "dshroot"  = 只解压 dshroot 条目（外部优先，回退内部）；
        //       "dshroot-fast" = 快速同步：只更新 REVISION + 官方白名单文件，不 stat 已有文件
        //                        （同内核升级用，避免真机 FUSE 2 万+ 次 stat 造成慢启动）；
        //       "dshroot-add"  = 增量补齐（v1.13.12）：缺失文件才写入 + REVISION/白名单总是覆盖，
        //                        结束后清理新树里已不存在的顶层插件包。升级安装不再整棵重写。
        final boolean fast = "dshroot-fast".equals(mode);
        final boolean additive = "dshroot-add".equals(mode);
        final boolean internalPatch = "internal-patch".equals(mode);
        final boolean internalOnly = "internal".equals(mode) || internalPatch;
        final boolean dshrootOnly = "dshroot".equals(mode) || fast || additive;
        if (!destInternal.exists() && !destInternal.mkdirs()) throw new IOException("mkdir failed: " + destInternal);
        // 进度文案按实际动作说：首次解压才叫"解压"，升级同步叫"同步"，别让用户以为又在重装
        final String progressLabel = extractProgressLabel(mode);
        lastExtractLabel = progressLabel;
        final int total = fast ? 0 : countPayloadEntries(mode); // 快速同步无进度条（更新极少文件）
        if (total > 0) setProgress(0, progressLabel + " 0/" + total + " 个文件…");
        // 增量补齐时记录新树包含的顶层插件包（dshroot/lib/node_modules/@deepseek-ai/<pkg>），
        // 结束后把已存在的同名层级里多余的包删掉 —— 这是旧实现"布局变了就整棵重推"要防的
        // 「旧嵌套副本被 Node 优先解析」，现在只清插件层这一小块，不再动整棵树。
        java.util.HashSet<String> addPkgs = additive ? new java.util.HashSet<String>() : null;
        byte[] buf = new byte[128 * 1024];
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int processed = 0;
        int written = 0;
        int failed = 0;
        String firstFail = null;
        // v1.17.9 混装树自愈：全量 dshroot 同步时先把 payload 里的条目名收集起来，
        // 解压完用它清掉目标树里的孤儿（payload 已经没有的文件）。目录也收（不带尾斜杠）。
        final boolean heal = dshrootOnly && !fast && !additive;
        java.util.HashSet<String> payloadNames = heal ? new java.util.HashSet<String>() : null;
        lastHealOrphans = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (payloadNames != null && name.startsWith("dshroot/")) {
                boolean dirEntry = e.isDirectory() || name.endsWith("/");
                payloadNames.add(dirEntry ? name.substring(0, name.length() - 1) : name);
            }
            if (e.isDirectory() || name.startsWith("__MACOSX/") || name.startsWith("META-INF/")) { zis.closeEntry(); continue; }
            boolean isDshroot = name.startsWith("dshroot/");
            if (dshrootOnly && !isDshroot) { zis.closeEntry(); continue; }
            if (internalOnly && isDshroot) { zis.closeEntry(); continue; }
            processed++;

            File target;
            boolean skipIfExists = false;
            if (additive && isDshroot) {
                // 记录新树里的插件包名。⚠ 真实布局是「嵌套」的：
                //     dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/<pkg>/...
                // 顶层 dshroot/lib/node_modules/@deepseek-ai/ 下只有 dsh 本身 ——
                // 只认顶层前缀会得到 rest="dsh/node_modules/..."，取出来的"包名"是 dsh，
                // 于是 addPkgs 恒为 {"dsh"}，下面的清理变成空转。
                final String NESTED = "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/";
                final String FLAT = "dshroot/lib/node_modules/@deepseek-ai/";
                String rest = null;
                if (name.startsWith(NESTED)) rest = name.substring(NESTED.length());
                else if (name.startsWith(FLAT)) rest = name.substring(FLAT.length());
                if (rest != null) {
                    int slash = rest.indexOf('/');
                    if (slash > 0) addPkgs.add(rest.substring(0, slash));
                }
            }
            if (isDshroot && externalRoot != null) {
                target = new File(externalRoot, name);
                // 外部 dshroot：REVISION 与官方白名单路径总是覆盖；其他已有文件跳过（保留 AI 运行时修改）。
                if (fast) {
                    // 快速同步（内外通用）：只处理 REVISION + 白名单文件，其余条目直接跳过（不做 exists() stat）
                    if (!name.equals("dshroot/REVISION") && !isForceOverwrite(name)) { zis.closeEntry(); continue; }
                    skipIfExists = false;
                } else if (additive) {
                    // 增量补齐：已有文件一律保留（保留 AI 运行时修改），缺失文件才落地
                    skipIfExists = !name.equals("dshroot/REVISION") && !isForceOverwrite(name) && target.exists();
                } else {
                    // v1.17.9（混装树自愈）：全量模式「不再跳过已存在的文件」。
                    // 旧行为是"已存在就跳过"，于是上游改过内容的文件永远是旧的 ——
                    // 真机踩过：内核升级后 cosmokit 少了导出，引擎 import 到旧文件直接拒启，
                    // 而且"重新解压"也救不回（全量同样跳过）。用户数据在 dshhome，不在这棵树里。
                    skipIfExists = false;
                }
            } else {
                target = new File(destInternal, name);
                if (fast) {
                    // 快速同步：内部 dshroot 也只更新 REVISION + 白名单文件（同内核升级，避免全量重写）
                    if (!name.equals("dshroot/REVISION") && !isForceOverwrite(name)) { zis.closeEntry(); continue; }
                    skipIfExists = false;
                } else if (additive) {
                    // 增量补齐（内部树同理）：只写缺失文件，REVISION/白名单总是覆盖
                    skipIfExists = !name.equals("dshroot/REVISION") && !isForceOverwrite(name) && target.exists();
                } else if (internalPatch) {
                    // 覆盖升级补齐：内部运行时只写缺失文件（新增文件如 runtime/bin/rg），白名单路径总是覆盖
                    skipIfExists = !isForceOverwrite(name) && target.exists();
                }
            }

            // v1.13.11：用户文件（settings.yaml / .credentials.yaml）任何模式都只在缺失时写入。
            // 「重新解压」走的是 mode="internal"（无 skipIfExists 保护）→ 会把模型配置覆盖回开发机模板，
            // 这就是「每次重新解压丢配置」的直接原因。
            if (!skipIfExists && isDshhomeUserFile(name) && target.exists()) skipIfExists = true;

            if (skipIfExists) {
                zis.closeEntry();
                updateProgress(processed, total, written);
                continue;
            }

            // v1.15.8：profile 清单是插件注册表，覆盖会抹掉已装插件（见 writeMergedProfileManifest）
            if (PROFILE_MANIFEST_PATH.equals(name)) {
                prepareTarget(target);
                writeMergedProfileManifest(target, new String(readZipEntry(zis), "UTF-8"));
                zis.closeEntry();
                written++;
                updateProgress(processed, total, written);
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
            prepareTarget(target);
            try {
                FileOutputStream fos = new FileOutputStream(target);
                int n;
                while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                // v1.13.5：dex 必须「不可写」——Android 14+ 的 ART 拒绝加载可写 dex
                // （logcat: SecurityException: Writable dex file '…' is not allowed → 进程直接
                //  SIGABRT/exit 134，终端上只看到一个 "Aborted"）。payload.zip 内所有条目都不带
                // unix 权限（external_attr=0），文件权限完全由本函数决定，所以这里对 *.dex 收成 0444。
                if (name.endsWith(".dex")) secureDexPermissions(target);
                written++;
            } catch (IOException ioe) {
                // 单个条目失败不中断整体（否则一次 EACCES 就能让整轮解压白干），最后汇总报错
                failed++;
                if (firstFail == null) firstFail = name + "（" + ioe.getMessage() + "）";
                Log.w(TAG, "extract entry failed: " + name, ioe);
            }
            zis.closeEntry();
            updateProgress(processed, total, written);
        }
        zis.close();
        // 增量补齐收尾：清理"新树里已不存在"的顶层插件包，防止旧副本被 Node 优先解析。
        // 只清 @deepseek-ai 插件层（包管理范畴，AI 不会改），不动整棵树。
        if (additive && addPkgs != null && !addPkgs.isEmpty()) {
            // ⚠ 必须同时覆盖「嵌套层」：插件包实际都在
            //   dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/
            // 只扫顶层（里面只有 dsh 自己）等于什么都不清。
            final String NESTED_DIR = "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai";
            File[] scope = {
                    new File(destInternal, NESTED_DIR),
                    new File(destInternal, "dshroot/lib/node_modules/@deepseek-ai") };
            if (externalRoot != null) scope = new File[]{
                    new File(destInternal, NESTED_DIR),
                    new File(destInternal, "dshroot/lib/node_modules/@deepseek-ai"),
                    new File(externalRoot, NESTED_DIR),
                    new File(externalRoot, "dshroot/lib/node_modules/@deepseek-ai") };
            for (File dir : scope) {
                File[] kids = dir.listFiles();
                if (kids == null) continue;
                for (File kid : kids) {
                    if (addPkgs.contains(kid.getName())) continue;
                    Log.i(TAG, "prune stale kernel pkg: " + kid.getName());
                    deleteRecursive(kid);
                }
            }
        }
        // v1.17.9 混装树自愈（第二步）：把目标树里 payload 已不存在的文件/目录清掉。
        // 孤儿文件（上游删掉的包/旧嵌套副本）正是"升级后引擎起不来"的另一半原因。
        if (payloadNames != null && !payloadNames.isEmpty()) {
            File base = (externalRoot != null) ? externalRoot : destInternal;
            // ⚠ 安全边界（用户明确要求：不许删用户自己的东西 / 装的插件）：
            //   只在「内核自己的包命名空间」 @deepseek-ai/* 里清陈旧副本，别处一律不动。
            final String TOP = "dshroot/lib/node_modules/@deepseek-ai";
            final String NESTED = TOP + "/dsh/node_modules/@deepseek-ai";
            int orphans = pruneOrphans(new File(base, TOP), TOP, payloadNames);
            orphans += pruneOrphans(new File(base, NESTED), NESTED, payloadNames);
            lastHealOrphans = orphans;
            if (orphans > 0) Log.i(TAG, "dshroot heal: 覆盖写入 " + written + " 个，清理陈旧内核包 " + orphans + " 个");
        }
        Log.i(TAG, "extracted " + written + " entries (external=" + (externalRoot != null) + ", mode=" + mode + ")");
        if (failed > 0) throw new IOException("有 " + failed + " 个文件写不进去，首个：" + firstFail);
    }

    /**
     * 清陈旧内核包（v1.17.9 混装树自愈）——「安全边界写死在这里」：
     *   · 由调用方限定只传 @deepseek-ai 包目录（顶层 / 嵌套各一个），别处永不进入；
     *   · 只删「目录名以 dsh 开头」且 payload 里没有的条目（内核 dsh 与官方 dsh-tool-* 的陈旧副本）；
     *   · 其他任何名字（第三方包、用户/AI 放的文件）「一律跳过」，只记一条日志；
     *   · `.complete` 例外（App 解压完自己写的标记，不属于 payload，解压后会被重写）。
     * `keep` 里是 payload 条目名（形如 "dshroot/lib/xxx"，目录不带尾斜杠）。
     * 日志最多列 10 条，其余只报总数（避免刷屏）。
     */
    private int pruneOrphans(File dir, String relPrefix, java.util.HashSet<String> keep) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int removed = 0;
        for (int i = 0; i < kids.length; i++) {
            File k = kids[i];
            String rel = relPrefix + "/" + k.getName();
            if (keep.contains(rel)) {
                if (k.isDirectory()) removed += pruneOrphans(k, rel, keep);
                continue;
            }
            // 安全闸门：只处理 dsh* 名下的条目（内核/官方插件），别的名字一律不碰
            if (!k.getName().startsWith("dsh")) {
                Log.i(TAG, "keep non-kernel entry (跳过): " + rel);
                continue;
            }
            if (k.isDirectory()) {
                int n = countTree(k);
                deleteRecursive(k);
                removed += n;
                if (removed - n < 10) Log.i(TAG, "prune stale kernel pkg: " + rel + "（" + n + " 个文件）");
            } else {
                if (DSHROOT_COMPLETE.equals(k.getName())) continue;
                if (k.delete()) {
                    removed++;
                    if (removed <= 10) Log.i(TAG, "prune stale kernel file: " + rel);
                }
            }
        }
        return removed;
    }

    /** 数一棵子树里的文件数（清孤儿前统计用）。 */
    private int countTree(File f) {
        if (f == null || !f.exists()) return 0;
        if (!f.isDirectory()) return 1;
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (int i = 0; i < kids.length; i++) n += countTree(kids[i]);
        return n;
    }

    /**
     * 写入前把目标清干净。覆盖安装时旧版本（v1.10 那棵老树）可能留下**只读文件 / 扭结软链 /
     * 同名目录**，直接 FileOutputStream 会报 EACCES（用户实测：payload/rish/rish_shizuku.dex）；
     * 父目录不可写也要先提权，否则同样是 EACCES。
     */
    private void prepareTarget(File target) {
        try {
            File parent = target.getParentFile();
            if (parent != null && parent.exists() && !parent.canWrite()) parent.setWritable(true, true);
            if (target.isDirectory()) { deleteRecursive(target); return; }
            if (target.exists()) {
                if (target.canWrite()) return;
                // 注意：这里用 owner-only（true）——setWritable(true, false) 会把 group/other 的写位也加上，
                // 0444 的 dex 会因此变成 0666（真机实测的 “Writable dex” 就是这么来的）。
                target.setWritable(true, true);
                if (target.canWrite()) return;
                if (!target.delete()) {
                    // 删不掉就改名避让（改名只需父目录写权限），旧文件内容不再被引用
                    File bak = new File(target.getParentFile(),
                            target.getName() + ".old-" + System.currentTimeMillis());
                    if (target.renameTo(bak)) deleteRecursive(bak);
                }
            } else {
                // File.exists() 对悬空符号链接返回 false，但创建仍会失败 → 用 lstat 判一下再删
                try { if (Os.lstat(target.getAbsolutePath()) != null) target.delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "prepareTarget failed: " + target, t);
        }
    }

    // 判断某条目是否属于官方强制覆盖白名单（外部 dshroot 也随 APK 更新）。
    private boolean isForceOverwrite(String name) {
        for (String p : FORCE_OVERWRITE_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    // dshhome 里随 APK 更新的官方配置文件（凭证 .credentials.yaml、会话数据 storages/ 等不在内）。
    // ⚠ settings.yaml 「不在此列」：它存的是用户自己填的模型/供应商配置
    //   （llm-pi-ai.providers.*、agent-default-model 等），属用户数据。
    //   曾被列在这里 → 每次「重新解压」/覆盖安装都被 APK 里的开发机模板覆盖掉，
    //   表现为「模型配置莫名为空、要重填」（用户实测报障）。
    private static final String[] DSHHOME_CONFIG_PATHS = {
        "dshhome/cordis.patch.yml",
        // ⚠ 0.1.7 起 profiles/<name>/cordis.patch.yml 不再是空壳模板，而是「用户配置文档」：
        //   内核的 dsh-config-editor 把它的 documentPath 指向 profileContext.patchPath，
        //   设置页（模型/供应商等）的保存全部写在这个文件里。
        //   它已改列入 DSHHOME_USER_PATHS，此处不再强制覆盖（否则 issue #20 会以新形式复发）。
        "dshhome/profiles/web/cordis.yml",
        "dshhome/profiles/web/package.json",
        "dshhome/profiles/web/pnpm-workspace.yaml"
    };

    // dshhome 里属于「用户」的文件：只在「不存在」时写入，任何解压模式都不得覆盖。
    // 双保险：extractPayload（写盘）与 refreshInternalConfig（配置刷新）两处都拦。
    private static final String[] DSHHOME_USER_PATHS = {
        "dshhome/settings.yaml",
        "dshhome/.credentials.yaml",
        // 0.1.7：用户配置从此文件读取/写入（见上）。
        "dshhome/profiles/web/cordis.patch.yml"
    };

    private boolean isDshhomeUserFile(String name) {
        for (String p : DSHHOME_USER_PATHS) {
            if (p.equals(name)) return true;
        }
        return false;
    }

    // 重装后把 dshhome 的官方配置文件从 payload.zip 覆盖到内部（凭证/会话保留）。
    private void refreshInternalConfig(File payload) throws IOException {
        byte[] buf = new byte[128 * 1024];
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int updated = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            boolean isConfig = false;
            for (String p : DSHHOME_CONFIG_PATHS) {
                if (name.equals(p)) { isConfig = true; break; }
            }
            if (!isConfig) { zis.closeEntry(); continue; }
            // 用户文件永不覆盖（settings.yaml 等）：只补官方配置文件
            if (isDshhomeUserFile(name)) { zis.closeEntry(); continue; }
            File target = new File(payload, name);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
            // v1.15.8：同上——这里是第二条覆盖路径（内核 revision 变化 / 补丁自检失败时触发）
            if (PROFILE_MANIFEST_PATH.equals(name)) {
                writeMergedProfileManifest(target, new String(readZipEntry(zis), "UTF-8"));
                zis.closeEntry();
                updated++;
                continue;
            }
            FileOutputStream fos = new FileOutputStream(target);
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            zis.closeEntry();
            updated++;
        }
        zis.close();
        Log.i(TAG, "refreshed " + updated + " dshhome config files");
    }

    // 预扫 payload.zip 统计要处理的条目数（只读 entry 头，不写盘），供进度条使用。
    private int countPayloadEntries(String mode) throws IOException {
        final boolean internalOnly = "internal".equals(mode) || "internal-patch".equals(mode);
        final boolean dshrootOnly = "dshroot".equals(mode) || "dshroot-add".equals(mode);
        InputStream in = getAssets().open("payload.zip");
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        int n = 0;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory() || name.startsWith("__MACOSX/") || name.startsWith("META-INF/")) { zis.closeEntry(); continue; }
            boolean isDshroot = name.startsWith("dshroot/");
            if (dshrootOnly && !isDshroot) { zis.closeEntry(); continue; }
            if (internalOnly && isDshroot) { zis.closeEntry(); continue; }
            n++;
            zis.closeEntry();
        }
        zis.close();
        return n;
    }

    /** 当前解压动作的进度文案前缀（updateProgress 用；extractPayload 每次进入时刷新）。 */
    private volatile String lastExtractLabel = "首次启动 · 正在解压运行时";

    private String extractProgressLabel(String mode) {
        if ("internal-patch".equals(mode)) return "正在同步运行时文件";
        if ("dshroot-add".equals(mode)) return "正在同步内核文件（增量）";
        return "首次启动 · 正在解压运行时";
    }

    private void updateProgress(int processed, int total, int written) {
        if (total <= 0) return;
        if (processed != total && processed % 200 != 0) return;
        int pct = (int)(processed * 100L / total);
        setProgress(pct, lastExtractLabel + " " + processed + "/" + total + " 个文件…");
    }

    private void applyLinks(File payload) throws IOException {
        File lib = new File(payload, "runtime/lib");
        File linksFile = new File(lib, "LINKS.txt");
        if (!linksFile.exists()) return;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(linksFile), "UTF-8"));
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\t+");
            if (parts.length < 2) continue;
            String linkName = parts[0].trim();
            String target = parts[1].trim();
            File link = new File(lib, linkName);
            File src = new File(lib, target);
            if (!src.exists()) continue;
            if (link.exists()) {
                // v1.18（B9 自愈）：别名若与目标**不是同一个 inode**，说明它是旧版打包"实体化"出来的
                // 独立副本（历史上 ICU 因此占 3 份、升级用户的 runtime/lib 会一直多背几十 MB）。
                // 删掉它，交给下面按硬链→软链→复制重建；stat() 会跟随软链，所以软链/硬链都会判为同一 inode 而跳过。
                boolean sameInode = false;
                try {
                    android.system.StructStat a = Os.stat(link.getAbsolutePath());
                    android.system.StructStat b = Os.stat(src.getAbsolutePath());
                    sameInode = (a.st_dev == b.st_dev && a.st_ino == b.st_ino);
                } catch (Throwable ignored) {}
                if (sameInode) continue;
                if (!link.delete()) continue;   // 删不掉就保持原样（宁可多占，也不要冒风险）
            }
            if (!link.exists() && src.exists()) {
                try {
                    Os.link(src.getAbsolutePath(), link.getAbsolutePath());
                    n++;
                } catch (ErrnoException e1) {
                    try {
                        Os.symlink(target, link.getAbsolutePath());
                        n++;
                    } catch (ErrnoException e2) {
                        try { copyFile(src, link); n++; } catch (IOException e3) {
                            Log.w(TAG, "link failed " + linkName, e3);
                        }
                    }
                }
            }
        }
        r.close();
    }

    private void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] b = new byte[128 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.close();
        in.close();
    }

    private void setExecutables(File payload) {
        String[] execs = {"runtime/bin/node", "bin/bash", "runtime/bin/rg", "runtime/bin/curl",
                // v1.15.3 内置 pnpm 的 wrapper：插件管理「添加插件」靠它解析 pnpm；
                // 解压不保留执行位，必须每次启动 chmod，否则子进程报「找不到命令」(127)
                "bin/pnpm",
                // v1.15.5 内置 git：插件管理「添加 GitHub 地址」要先 git ls-remote 探活；
                // 解压同样不保留执行位，不加就没有 x 位 → 子进程报「找不到命令」
                "bin/git",
                // v1.15.8 内置 git 的远程助手：与 bin/git 同因（zip 不保留执行位）。
                // 缺了它 → git 能找到 exec-path 却执行不了助手，报
                // "git: 'remote-https' is not a git command"（真机 A/B 实测复现）。
                "git/libexec/git-core/git-remote-https",
                "git/libexec/git-core/git-remote-http",
                // v1.16.0 内置 python / npm：与 pnpm、git 同因（解压不保留执行位，不加就 127）。
                // python 的两个 wrapper 是 sh 脚本，真正要可执行的是 python/bin/python3.14（ELF）。
                "bin/python3",
                "bin/python",
                "python/bin/python3.14",
                // v1.16.0 内置 npm / npx：node 本体早在 payload 里（引擎就跑在它上面），
                // 缺的只是这两个 JS 入口 —— 不加 x 位则 AI 的 `npm install` 报 127。
                "bin/npm",
                "bin/npx",
                // v1.16.1 内置 pip：python 自带的 ensurepip 在 Termux deb 里没带 wheel，
                // 所以 pip 是「直接解进 site-packages」 的（同因：解压不保留执行位）。
                "bin/pip",
                "bin/pip3"};
        for (String p : execs) {
            File f = new File(payload, p);
            if (f.exists()) f.setExecutable(true, false);
        }
    }

    /**
     * 把 payload 里已知的 dex 统一收成 0444（读取不报错，但任何 uid 都写不了）。
     * 用于兜底：覆盖升级时旧树里的文件不会被重写，只能在解压后扫一遍。
     */
    private void secureDexFiles(File payload) {
        String[] dexs = {"rish/rish_shizuku.dex", "vscreen/vscreen_shizuku.dex"};
        for (String p : dexs) {
            File f = new File(payload, p);
            if (f.exists()) secureDexPermissions(f);
        }
    }

    /**
     * dex 文件强制不可写（0444）。
     *
     * 为什么必须这么做：Android 14+ 的 ART 「拒绝加载可写 dex」，报
     * `java.lang.SecurityException: Writable dex file '<path>' is not allowed`，
     * 进程直接 SIGABRT（exit=134）——而终端上只看得到一句 “Aborted”，
     * 极易被误判成 “Shizuku 没运行 / 未授权”，把排查方向带偏。
     * payload.zip 内所有条目的 unix 权限都是 0（external_attr=0），权限完全由解压时决定，
     * 而 Java 写文件在本机 umask 下默认就是 rw-rw-rw-（0666）→ 所以必须显式收权。
     */
    private void secureDexPermissions(File f) {
        try { android.system.Os.chmod(f.getAbsolutePath(), 0444); } catch (Throwable ignored) {}
        try { f.setWritable(false, false); } catch (Throwable ignored) {}   // chmod 失败（如 FUSE）时的兜底
    }

    /**
     * v1.15.5：把 Android 系统 CA（/system/etc/security/cacerts/*.0，PEM）拼成一个 bundle 文件。
     *
     * 为什么必须自己做：内置 curl/git 依赖的 libcrypto 把 OPENSSLDIR 编译死指向 Termux 的
     * /data/data/com.termux/files/usr/etc/tls/cert.pem，而本机通常没装 Termux → 一个 CA 都拿不到，
     * 所有 HTTPS 请求失败（实测 curl 报 (77) error adding trust anchors from file，git 报同一句）。
     *
     * 为什么不直接指向系统目录：该目录本身是 c_rehash 哈希目录（OpenSSL 认这种格式），但对 CAfile
     * OpenSSL 是「文件不存在即硬报错」，不会回退到目录 —— 实测只设 SSL_CERT_DIR 仍然失败。合成为一个
     * 真实存在的文件最稳，顺带把用户自己装的证书也带上。
     *
     * 缓存：证书数 + 最新 mtime 未变时复用，避免每次启动重写 ~750KB。
     */
    /**
     * v1.15.7：生成 git 的配置文件（ssh→https 改写 + git 用的 CA + 沿用用户自己的全局配置）。
     *
     * 为什么用文件而不是环境变量：内核给子进程的是「洗过的」父环境
     * （dsh-subprocess 的 scrubbedParentEnv，SENSITIVE_ENV_PATTERN = /KEY|PASSWORD|SECRET|TOKEN/i），
     * 而 git 用环境变量传配置的键名是 GIT_CONFIG_KEY_<n> —— 含 "KEY"，会被洗掉，
     * 于是 GIT_CONFIG_COUNT 还在、KEY 没了 → "error: missing config key GIT_CONFIG_KEY_0"。
     * 配置文件完全不受这套清洗影响。
     *
     * GIT_CONFIG_GLOBAL 指向本文件（它会替换默认的 ~/.gitconfig，所以把用户那份 include 进来）。
     */
    private File ensureGitConfig(File payload, File caBundle) {
        File out = new File(new File(payload, "runtime/etc"), "gitconfig");
        try {
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("# DSH 手机版自动生成。每次启动引擎都会重写，改这里会被覆盖。\n");
            File userCfg = new File(getFilesDir(), ".gitconfig");
            if (userCfg.isFile() && !userCfg.getAbsolutePath().equals(out.getAbsolutePath())) {
                sb.append("[include]\n\tpath = ").append(userCfg.getAbsolutePath()).append("\n");
            }
            sb.append("[url \"https://github.com/\"]\n");
            sb.append("\tinsteadOf = git+ssh://git@github.com/\n");
            sb.append("\tinsteadOf = ssh://git@github.com/\n");
            sb.append("\tinsteadOf = git@github.com:\n");
            sb.append("[url \"https://gitee.com/\"]\n");
            sb.append("\tinsteadOf = git+ssh://git@gitee.com/\n");
            sb.append("\tinsteadOf = ssh://git@gitee.com/\n");
            sb.append("\tinsteadOf = git@gitee.com:\n");
            if (caBundle != null) {
                sb.append("[http]\n\tsslCAInfo = ").append(caBundle.getAbsolutePath()).append("\n");
            }
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            try {
                os.write(sb.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            Log.i(TAG, "git config -> " + out.getAbsolutePath());
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "git config generation failed: " + t);
            return null;
        }
    }

    // v1.15.8：profile 的插件注册表。dsh-plugin-manager 装插件时把依赖与 bundle 名写在这里
    // （saveManifest → <profile>/package.json 的 dependencies + dsh.profile.bundles），
    // 所以它「不是」可以整文件覆盖的官方配置，必须合并（见 writeMergedProfileManifest）。
    private static final String PROFILE_MANIFEST_PATH = "dshhome/profiles/web/package.json";

    /** 把一个 zip 条目整个读出来（用于需要「先读后合并」的条目）。 */
    private static byte[] readZipEntry(java.util.zip.ZipInputStream zis) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = zis.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    /**
     * 写 profile 清单：以 payload 那份为基准，「合并」已有文件里的用户/插件内容。
     *
     * 为什么不能直接覆盖：这个文件是插件注册表 —— dsh-plugin-manager 把已装插件写进它的
     * dependencies 与 dsh.profile.bundles。整文件覆盖会让「装完插件→重启引擎→插件消失」
     * （真机实测报障）。合并后：内置 bundle 仍随 APK 更新，用户装的插件保留。
     * 解析失败时退回「直接写 payload 默认值」，不因为一个坏文件卡住整轮解压。
     */
    private void writeMergedProfileManifest(File target, String shipJson) throws IOException {
        String out = shipJson;
        try {
            if (target.isFile()) {
                String mineJson = readFileText(target);
                if (mineJson != null && mineJson.trim().length() > 0) {
                    org.json.JSONObject ship = new org.json.JSONObject(shipJson);
                    org.json.JSONObject mine = new org.json.JSONObject(mineJson);

                    // dependencies：ship 同名优先，用户的额外项保留
                    org.json.JSONObject shipDeps = ship.optJSONObject("dependencies");
                    org.json.JSONObject mineDeps = mine.optJSONObject("dependencies");
                    if (mineDeps != null) {
                        if (shipDeps == null) {
                            shipDeps = new org.json.JSONObject();
                            ship.put("dependencies", shipDeps);
                        }
                        java.util.Iterator<String> it = mineDeps.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            if (!shipDeps.has(k)) shipDeps.put(k, mineDeps.get(k));
                        }
                    }

                    // dsh.profile.bundles：并集（ship 的在前，顺序稳定）
                    org.json.JSONObject shipDsh = ship.optJSONObject("dsh");
                    org.json.JSONObject mineDsh = mine.optJSONObject("dsh");
                    org.json.JSONObject shipProf = shipDsh == null ? null : shipDsh.optJSONObject("profile");
                    org.json.JSONObject mineProf = mineDsh == null ? null : mineDsh.optJSONObject("profile");
                    org.json.JSONArray shipB = shipProf == null ? null : shipProf.optJSONArray("bundles");
                    org.json.JSONArray mineB = mineProf == null ? null : mineProf.optJSONArray("bundles");
                    java.util.List<String> names = new java.util.ArrayList<>();
                    if (shipB != null) for (int i = 0; i < shipB.length(); i++) {
                        String v = shipB.optString(i, "");
                        if (v.length() > 0) names.add(v);
                    }
                    if (mineB != null) for (int i = 0; i < mineB.length(); i++) {
                        String v = mineB.optString(i, "");
                        if (v.length() > 0 && !names.contains(v)) names.add(v);
                    }
                    if (!names.isEmpty()) {
                        if (shipDsh == null) {
                            shipDsh = new org.json.JSONObject();
                            ship.put("dsh", shipDsh);
                        }
                        if (shipProf == null) {
                            shipProf = new org.json.JSONObject();
                            shipDsh.put("profile", shipProf);
                        }
                        org.json.JSONArray arr = new org.json.JSONArray();
                        for (String v : names) arr.put(v);
                        shipProf.put("bundles", arr);
                    }
                    out = ship.toString(2) + "\n";
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "profile manifest merge failed, writing payload default", t);
            out = shipJson;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
        FileOutputStream fos = new FileOutputStream(target);
        try {
            fos.write(out.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
    }

    private File ensureSystemCaBundle(File payload) {
        File out = new File(new File(payload, "runtime/etc"), "cacert.pem");
        try {
            File dir = new File("/system/etc/security/cacerts");
            File[] certs = dir.listFiles();
            if (certs == null || certs.length == 0) return out.isFile() ? out : null;
            int count = 0;
            long newest = 0L;
            for (File c : certs) {
                if (c.isFile() && c.length() > 0) {
                    count++;
                    if (c.lastModified() > newest) newest = c.lastModified();
                }
            }
            if (count == 0) return out.isFile() ? out : null;
            String stamp = count + ":" + newest;
            android.content.SharedPreferences sp = getSharedPreferences("dsh_prefs", MODE_PRIVATE);
            if (out.isFile() && out.length() > 1024L && stamp.equals(sp.getString("ca_bundle_stamp", ""))) {
                return out;
            }
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                for (File c : certs) {
                    if (!c.isFile()) continue;
                    java.io.FileInputStream in = new java.io.FileInputStream(c);
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        in.close();
                    }
                    os.write('\n');
                }
            } finally {
                os.close();
            }
            out.setReadable(true, false);
            sp.edit().putString("ca_bundle_stamp", stamp).apply();
            Log.i(TAG, "system CA bundle -> " + out.getName() + " (" + out.length() + " bytes, " + count + " certs)");
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "system CA bundle failed: " + t);
            return out.isFile() ? out : null;
        }
    }

    private void spawnNode(File payload) throws IOException {
        File node = new File(payload, "runtime/bin/node");
        File binjs = new File(dshrootDir, REL_BINJS);
        File lib = new File(payload, "runtime/lib");
        File home = new File(payload, "dshhome");
        File bin = new File(payload, "bin");
        File tmp = new File(getCacheDir(), "tmp");
        if (!tmp.exists()) tmp.mkdirs();

        if (!node.exists()) throw new IOException("node binary missing");
        if (!binjs.exists()) throw new IOException("dsh bin.js missing");
        if (!node.canExecute()) node.setExecutable(true, false);

        // v1.13：起引擎前先自愈 cordis.patch.yml —— 旧版控制台关插件会把它写成非法 YAML，
        // 引擎每次启动都崩在解析、App 反复重拉（表现：界面一直闪、一直计时）。
        conHealPatchConfig();

        // 注意：Android 兼容补丁（禁用 llm-pi-ai/sandbox/bash-sandbox 的 cordis.patch.yml）
        // 位于 $DSH_HOME/cordis.patch.yml，由 dsh profile-boot 的 homePatches 自动加载，
        // 无需 --patch 参数（重复传入会导致 duplicate loader entry 崩溃）。

        // v1.21（需求 1 修复）：**先用 shell cd 再 exec node**，保证引擎进程的 cwd 真的是工作区。
        // 为什么不能只靠 ProcessBuilder.directory()：模拟器上 arm64 node 由
        // ndk_translation_program_runner_binfmt_misc_arm64（binfmt 解释器）拉起，
        // 解释器会把 cwd 重置成 "/" —— 实测 pb.directory(工作区) 之后 /proc/<pid>/cwd 仍是 /。
        // 后果：内核 dsh-workspace 的默认工作区 = host cwd = "/" → DSH 在根目录建工作区失败，
        // 界面弹"默认工作区创建失败"。经 `sh -c 'cd <ws> && exec node …'` 后 cwd 一定正确；
        // exec 让 node 顶替 shell（PID 不变），看门狗/「停止」按 PID 杀依然有效。
        ensureDefaultWorkspace();
        String wsRaw = workspacePath();
        File wsDir = (wsRaw == null || wsRaw.isEmpty()) ? null : new File(wsRaw);
        if (wsDir != null && !isWritableDir(wsDir)) {
            Log.w(TAG, "工作区不可写，重新选择: " + wsRaw);
            File alt = workspaceDirOrFallback();
            if (alt != null) {
                wsDir = alt;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_WORKSPACE, alt.getAbsolutePath()).apply();
            }
        }
        final String nodeArgs = " --expose-internals " + shq(binjs.getAbsolutePath())
                + " web --host 127.0.0.1 --port " + enginePort;
        ProcessBuilder pb;
        if (wsDir != null) {
            Log.i(TAG, "engine cwd -> " + wsDir.getAbsolutePath() + "（经 shell cd 保证）");
            pb = new ProcessBuilder("/system/bin/sh", "-c",
                    "cd " + shq(wsDir.getAbsolutePath()) + " && exec " + shq(node.getAbsolutePath()) + nodeArgs);
        } else {
            Log.i(TAG, "engine cwd -> (未设置工作区，保持默认)");
            pb = new ProcessBuilder(node.getAbsolutePath(), "--expose-internals",
                    binjs.getAbsolutePath(), "web", "--host", "127.0.0.1", "--port", String.valueOf(enginePort));
        }
        // v1.21：把这次的判定事实落盘（控制台「日志→分享」会带上），真机出问题时一眼可见
        writeWorkspaceDiag(wsDir);
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        // Termux 共存修复（v1.7.4）：内置 node 在 Termux 环境编译，OPENSSLDIR 被编译死为
        // /data/data/com.termux/files/usr。装了 Termux 的设备读其 openssl.cnf 触发 EACCES，
        // node 启动即崩；没装 Termux 时靠 ENOENT 静默才碰巧正常。注入 OPENSSL_CONF 指向
        // payload 自带的可读 openssl.cnf（build.sh 生成），有无 Termux 都稳定。
        File osslConf = new File(payload, "runtime/etc/openssl.cnf");
        if (osslConf.exists()) env.put("OPENSSL_CONF", osslConf.getAbsolutePath());
        // v1.15.5：内置 git 的 Android 适配（与 pnpm 同批；插件管理「GitHub 地址」输入要靠它）。
        //  · GIT_EXEC_PATH / GIT_TEMPLATE_DIR：Termux 构建把这些路径写死为 /data/data/com.termux/files/usr，
        //    必须改指 payload 内 —— 否则 git 找不到远程助手（实测报 "unable to find remote helper for 'https'"）。
        //    注意 git 还要靠 PATH 里的 `git` 去拉起该助手，而 payload/bin 本来就在 PATH 上。
        //  · GIT_PAGER=cat：payload 没带 less，避免 git 去拉分页器。
        //  · 证书：内置 curl/git 依赖的 libcrypto 把 OPENSSLDIR 编译死指向 Termux 的
        //    /data/data/com.termux/files/usr/etc/tls/cert.pem；本机没装 Termux → 一个 CA 都拿不到，
        //    HTTPS 一律失败（实测 curl 报 (77) error adding trust anchors，git 报同一句）。
        //    把系统 CA 合成一个 bundle，再用各自认的变量指过去：curl 认 SSL_CERT_FILE / CURL_CA_BUNDLE，
        //    git 认 GIT_SSL_CAINFO（实测三者在真机上都生效）。
        File gitDir = new File(payload, "git");
        env.put("GIT_EXEC_PATH", new File(gitDir, "libexec/git-core").getAbsolutePath());
        env.put("GIT_TEMPLATE_DIR", new File(gitDir, "templates").getAbsolutePath());
        env.put("GIT_PAGER", "cat");
        // v1.15.7：ssh 形式的 git URL 在 Android 上永远不可用 —— payload 没带 ssh 客户端、也没有密钥，
        // 用户填 git@github.com:user/repo.git / git+ssh://… / ssh://… 时报
        // "error: cannot run ssh: No such file or directory"（真机实测）。
        //
        // 做法：把 ssh 形式用 git 的 url.<base>.insteadOf 改写成 https —— 但「不能靠环境变量传」
        // （v1.15.6 的错误做法）：内核给 pnpm 的是「洗过的父环境」（dsh-subprocess 的
        // scrubbedParentEnv：SENSITIVE_ENV_PATTERN = /KEY|PASSWORD|SECRET|TOKEN/i），而 git 用环境变量
        // 传配置的键名恰好叫 「GIT_CONFIG_KEY_<n>」 —— 含 "KEY" 被当凭据洗掉，GIT_CONFIG_COUNT 却活下来
        // → 真机报 "error: missing config key GIT_CONFIG_KEY_0"。
        // 改成写配置文件（见 ensureGitConfig），用 GIT_CONFIG_GLOBAL 指过去：该名字不含
        // KEY/PASSWORD/SECRET/TOKEN，能活过清洗（已按该正则逐项自查）。
        env.put("GIT_TERMINAL_PROMPT", "0");   // 需要凭据时立刻失败，别挂住等 tty 输入（AI 的 bash 没有 tty）
        File caBundle = ensureSystemCaBundle(payload);
        if (caBundle != null) {
            env.put("SSL_CERT_FILE", caBundle.getAbsolutePath());
            env.put("CURL_CA_BUNDLE", caBundle.getAbsolutePath());
            env.put("GIT_SSL_CAINFO", caBundle.getAbsolutePath());
        }
        File gitCfg = ensureGitConfig(payload, caBundle);
        if (gitCfg != null) env.put("GIT_CONFIG_GLOBAL", gitCfg.getAbsolutePath());
        // v1.16.1：npm 全局安装落点（$HOME/.npm-global/bin）也要在 PATH 上。
        // npm 的默认前缀是 <payload>/runtime（payload 内，升级会被覆盖），所以内置 npm 的 wrapper
        // 把它改到 $HOME/.npm-global（HOME = filesDir，可写且跨版本升级保留，见下一行）；
        // 但装出来的命令落在 <HOME>/.npm-global/bin，不显式加进 PATH 则 AI 找不到。
        env.put("PATH", bin.getAbsolutePath() + ":" +
                new File(payload, "runtime/bin").getAbsolutePath() + ":" +
                new File(getFilesDir(), ".npm-global/bin").getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("TERM", "xterm");
        // v1.20：终端/子进程的默认 shell。dsh-subprocess-local 取
        //   `process.env.SHELL || os.userInfo().shell`
        // 而我们的 node 是 **Termux 构建**：Android 上 os.userInfo().shell 直接返回
        // "/data/data/com.termux/files/usr/bin/bash"（本机没装 Termux，路径不存在）→
        // 界面里「新建终端」必报
        //   Terminal error: subprocess-local: command ".../com.termux/.../bash" is not an executable file
        // （DSH 官方 Android 形态是"装在 Termux 里跑"，所以那个默认值对它是对的。）
        // v1.20：payload 自带的 bash 已换成**真 bash**（GNU bash 5.3.20，Termux 构建，
        //        见 tools/fetch-bash.py）——DSH 终端会用
        //        `bash --rcfile <生成的 bashrc> -i` 起 shell，那份 rc 是纯 bash 语法，
        //        mksh 跑不了（旧版就是 /system/bin/sh 的包装，会报 --rcfile 未知选项）。
        env.put("SHELL", new File(bin, "bash").getAbsolutePath());
        // v1.20：agent 的 bash 工具（dsh-bash-local）执行 `bash -c <cmd>`，argv 里是裸名 "bash"，
        // 靠 PATH 查找。这里显式给出绝对路径（补丁读 DSH_BASH_PATH），避免 PATH 顺序被人改动后
        // 又落回 /system/bin/sh(mksh) —— 那样 bash 语法（[[ ]]、数组、BASH_VERSINFO）会失败。
        env.put("DSH_BASH_PATH", new File(bin, "bash").getAbsolutePath());
        env.put("SHIZUKU_DEX", rishDex != null ? rishDex.getAbsolutePath() : "");
        // v1.9 虚拟屏 server dex：app_process 特权加载 VirtualScreenServer
        env.put("VS_DEX", vscreenDex != null ? vscreenDex.getAbsolutePath() : "");
        // v1.13 修正：这里原来「硬编码」 "com.deepseek.harness.beta"，而三版共用同一份源码 —— 正式版跑起来
        // 也在自称 beta，而 rish 要拿这个 appId 去 Shizuku 要授权，Shizuku 比对实际调用者的包名/uid
        // （正式版 uid ≠ beta uid）→ 门卫不认（用户回报：“SHIZUKU_APP_ID=…beta，但真正在跑的是 com.deepseek.harness”）。
        // 按实际包名派生；并写回 dsh_prefs，供无障碍服务等其它组件复用（同样不能信旧值）。
        final String selfAppId = getPackageName();
        env.put("SHIZUKU_APP_ID", selfAppId);
        try {
            getSharedPreferences("dsh_prefs", MODE_PRIVATE).edit().putString("shizuku_app_id", selfAppId).apply();
        } catch (Throwable ignored) {}
        // 特权通道可用性：root(su) 或 Shizuku。两者都未授予时，DSH 插件不注册特权工具，
        // AI 不会反复尝试系统操作；文件读写仍可用 DSH 自带的 fs/bash 工具（只需存储权限）。
        env.put("SHIZUKU_AVAILABLE", shizukuAvailable() ? "1" : "0");
        env.put("ROOT_AVAILABLE", rootAvailable() ? "1" : "0");
        // v1.13.1：本地特权路由 /shell 的鉴权令牌——只经 env 交给本应用自己的引擎，本机其它应用猜不到，
        // 避免任意应用通过 127.0.0.1:<notifyPort>/shell 拿到 shell 权限。
        env.put("APP_LOCAL_TOKEN", localToken());
        env.put("APP_NOTIFY_PORT", String.valueOf(notifyPort()));
        // v1.7 无障碍服务端口（通知端口 + 100，三版本共存不冲突）：插件 dsh-tool-accessibility 经此端口
        // 调用 App 的无障碍服务（读屏/点击/输入/截图）。端口同时写入 dsh_prefs，供无障碍服务读取。
        final int a11yPort = notifyPort() + 100;
        env.put("APP_A11Y_PORT", String.valueOf(a11yPort));
        getSharedPreferences("dsh_prefs", MODE_PRIVATE).edit().putInt("a11y_port", a11yPort).apply();
        // AI 工作区（v1.21 需求 1）：默认 /sdcard/DeepSeekHarness/Workspace。
        // 这里的 wsDir 已在上面的 spawn 段算好（含"不可写就回退"逻辑），进程 cwd 也由
        // `sh -c 'cd … && exec node …'` 保证；此处只把路径注入环境变量供插件/脚本使用
        // （我们的 dsh-bash-local 补丁 config.cwd = DSH_WORKSPACE || process.cwd()，据此生效）。
        if (wsDir != null) env.put("DSH_WORKSPACE", wsDir.getAbsolutePath());
        // v1.18（B12）：把变体事实注入引擎，供插件使用（插件不再硬编码包名/端口/截图目录）。
        //   DSH_APP_ID          —— 本变体包名（rish 兜底 appId、vscreen 核心类名前缀）
        //   DSH_ENGINE_PORT     —— 引擎端口
        //   DSH_EXT_DIR         —— 外部目录绝对路径（/sdcard/<变体目录>）
        //   DSH_VS_BRIDGE/CORE  —— 虚拟屏桥/核心端口（每个变体一段，互不重叠）
        //   DSH_EXT_ROOT_NAME   —— 目录名（历史字段，供需要拼路径的插件用）
        env.put("DSH_APP_ID", BuildVariant.APP_ID);
        env.put("DSH_ENGINE_PORT", String.valueOf(enginePort));
        File extRootDir = new File(android.os.Environment.getExternalStorageDirectory(), pkgRoot());
        env.put("DSH_EXT_DIR", extRootDir.getAbsolutePath());
        env.put("DSH_EXT_ROOT_NAME", pkgRoot());
        env.put("DSH_VS_BRIDGE_PORT", String.valueOf(BuildVariant.VS_BRIDGE_PORT));
        env.put("DSH_VS_CORE_PORT", String.valueOf(BuildVariant.VS_CORE_PORT));
        pb.redirectErrorStream(true);

        final Process proc = pb.start();
        nodeProcess = proc;
        final File logFile = new File(getFilesDir(), "dsh-web.log");
        // v1.7.1：同时镜像一份引擎日志到外部目录（无需 root/adb 可读），
        // 覆盖「node 反复崩溃但 waitForServer 未抛异常」时不产生 startup-diag.txt 的场景。
        final File extLogFile = new File(android.os.Environment.getExternalStorageDirectory(),
                pkgRoot() + "/dsh-web.log");
        new Thread(new Runnable() {
            @Override public void run() {
                FileOutputStream fos = null;
                FileOutputStream extFos = null;
                try {
                    fos = new FileOutputStream(logFile, true);
                    try {
                        File extParent = extLogFile.getParentFile();
                        if (extParent != null && !extParent.exists()) extParent.mkdirs();
                        extFos = new FileOutputStream(extLogFile, true);
                    } catch (Throwable ignored) {
                    }
                    InputStream is = proc.getInputStream();
                    byte[] b = new byte[4096];
                    int n;
                    String carry = "";
                    while ((n = is.read(b)) > 0) {
                        fos.write(b, 0, n);
                        fos.flush();
                        if (extFos != null) {
                            try { extFos.write(b, 0, n); extFos.flush(); } catch (Throwable ignored) {}
                        }
                        // v1.12：一次 read 可能在行中间断开，token URL 会被截成两半 → 拼接后再切行
                        String s = carry + new String(b, 0, n, "UTF-8");
                        int cut = s.lastIndexOf('\n');
                        carry = cut >= 0 ? s.substring(cut + 1) : s;
                        String parse = cut >= 0 ? s.substring(0, cut) : "";
                        for (String line : parse.split("\n")) {
                            String t = line.trim();
                            if (!t.isEmpty()) Log.i(TAG, "node: " + t);
                            // 0.1.5 认证：引擎打印 "dsh web: http://127.0.0.1:3080/?token=..."，
                            // 解析出来供健康探测与 WebView 首次加载使用。
                            int at = t.indexOf("http://127.0.0.1");
                            int tk = t.indexOf("?token=");
                            if (at >= 0 && tk > at) {
                                String u = t.substring(at);
                                int sp = u.indexOf(' ');
                                if (sp > 0) u = u.substring(0, sp);
                                engineTokenUrl = u;
                                Log.i(TAG, "engine token url captured");
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, "log reader error", e);
                } finally {
                    try { if (fos != null) fos.close(); } catch (IOException ignored) {}
                    try { if (extFos != null) extFos.close(); } catch (IOException ignored) {}
                }
            }
        }, "node-log").start();
    }

    private boolean healthOk() {
        return isDshEngine(enginePort);
    }

    /** 读 HTTP 正文（容忍错误流；仅供探测用，上限 max 字节）。 */
    private String conReadBody(HttpURLConnection c, int max) {
        try {
            InputStream in = null;
            try { in = c.getInputStream(); } catch (Throwable t) { in = c.getErrorStream(); }
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int r;
            while ((r = in.read(chunk)) > 0 && total < max) { out.write(chunk, 0, r); total += r; }
            try { in.close(); } catch (Throwable ignored) {}
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private void waitForServer() {
        long start = System.currentTimeMillis();
        int extraRounds = 0;
        boolean engineAlive = false;
        // v1.21：外层循环 —— 只要**引擎进程还活着**就继续等（最多再等 4 轮 × 90 秒）。
        // 背景（模拟器/慢设备实测）：引擎最终确实起来了（3086 在听、日志有启动 URL），
        // 但 App 90 秒就判超时 → 用户看到的就是"DSH 起不来"（其实只是慢）。
        while (true) {
        long deadline = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < deadline) {
            if (engineStartAborted) return;   // v1.13：用户点了「停止」→ 立即收手，别再刷“已等待 N 秒”
            if (healthOk()) { loadHome(); return; }
            long waited = (System.currentTimeMillis() - start) / 1000;
            setStatus("正在启动 DeepSeek Harness…（已等待 " + waited + " 秒）");
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        if (engineStartAborted) return;   // v1.13：停止后不再走超时兜底（否则会重新 spawn + 重新加载页面）
        // 超时：带端口提示便于排查（node 日志已写入 files/dsh-web.log）
        Log.e(TAG, "engine start timeout on port " + enginePort + ", check dsh-web.log");
        // v1.21：**进程还活着就别急着全量补齐**。
        // 实测踩过（用户报障）：点了「DS 账号登录」后引擎卡在 /auth-api 的网络请求上，
        // 进程健在、端口没起；此时全量重推 1.2 万文件既没用，又让用户看到"进度条一直跳"，
        // 误以为在解压（且修完再起仍卡同一处，形成循环）。
        // 只有在**进程真的死了**时才做文件层修复（那才是"缺文件"的场景）。
        engineAlive = findEnginePid() > 0;
        if (engineAlive) {
            Log.w(TAG, "engine process alive but port " + enginePort
                    + " not ready —— 判定为插件/网络请求卡住，跳过全量修复");
            if (extraRounds < 4) {
                // v1.21：进程健在 ⇒ 多半只是慢（模拟器转译、首启解压、网络慢）→ 继续等，别报失败。
                extraRounds++;
                Log.w(TAG, "engine alive —— 继续等待（第 " + extraRounds + " 轮）");
                setStatus("引擎正在启动（较慢），继续等待…（已等待 "
                        + ((System.currentTimeMillis() - start) / 1000) + " 秒）");
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                continue;
            }
            Log.w(TAG, "engine alive but not ready after extra rounds —— 交回原有兜底流程");
        }
        break;
        }
        // v1.5.2 慢启动修复兜底：本次走了「快速同步」（同内核升级），若引擎仍起不来，
        // 可能外部 dshroot 有缺失文件（快速路径不 stat 已有文件）→ 全量补齐后重启引擎再等一轮。
        if (fastSyncedThisBoot && !engineAlive) {
            fastSyncedThisBoot = false;
            Log.w(TAG, "fast sync may have missed files, forcing full dshroot repair");
            setStatus("引擎启动超时，正在补齐引擎文件后重试…");
            try {
                File externalRoot = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                extractPayload(new File(getFilesDir(), "payload"), externalRoot, "dshroot");
                writeDshrootComplete(externalRoot);
            } catch (Throwable t) {
                Log.w(TAG, "full repair failed", t);
            }
            try {
                spawnNode(new File(getFilesDir(), "payload"));
            } catch (Throwable t) {
                Log.e(TAG, "respawn after repair failed", t);
            }
            waitForServer();
            return;
        }
        // v1.13.12：超时不再 loadHome()（引擎没起来，WebView 只会对着死端口反复重试，
        // 用户看到的就是"权限引导走完进不了应用"，只能杀掉重开）。改为：
        // ① 回控制台（那里有真实状态与「启动引擎/日志」入口）；② 后台继续等引擎"迟到"——
        // 首启在真机上（首次建 profiles/冷启动）可能超过 90 秒，引擎一旦就绪自动进入主界面。
        // v1.21：区分"进程死了"与"进程活着但被卡住"，后者直接点破原因并给出出路（安全模式）。
        setStatus(engineAlive
                ? "引擎进程在跑但迟迟未就绪（多为账号登录/网络请求卡住）—— 可试「安全模式启动」"
                : "引擎启动超时（端口 " + enginePort + "），已回到控制台，引擎就绪后会自动进入");
        // 累计连续失败：让控制台能把「引擎起不来 → 可用安全模式」这条出路推到用户面前。
        bumpBootFailure();
        conEngineTimedOut(engineAlive);
    }

    /** 引擎启动超时后的兜底：回控制台 + 后台守望，引擎迟到就绪时自动进入主界面。 */
    private void conEngineTimedOut(final boolean engineAlive) {
        ui.post(new Runnable() { @Override public void run() {
            try {
                conToast(engineAlive
                        ? "引擎进程在跑但一直没就绪（常见于账号登录/网络卡住）；可试「安全模式启动」"
                        : "引擎启动超时，已回到控制台；就绪后会自动进入");
                showConsole();
                refreshConsole();
            } catch (Throwable ignored) {}
        }});
        final long deadline = System.currentTimeMillis() + 600000L; // 最多再守望 10 分钟
        new Thread(new Runnable() { @Override public void run() {
            while (System.currentTimeMillis() < deadline) {
                if (engineStoppedByUser || engineStartAborted) return; // 用户主动停止 → 不再打扰
                if (engineStartTs == 0L) return;                       // 期间被重启流程接管 → 收手
                try {
                    if (healthOk()) {
                        starting = false;
                        ui.post(new Runnable() { @Override public void run() {
                            try {
                                if (consoleVisible) { conToast("引擎已就绪"); enterMainUi(); refreshConsole(); }
                            } catch (Throwable ignored) {}
                        }});
                        return;
                    }
                } catch (Throwable ignored) {}
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }}, "engine-late-bloom").start();
    }

    /** node 看门狗：node 进程死亡且服务不可用时自动重启引擎并刷新页面 */
    private void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    try {
                        if (nodeProcess == null) continue;
                        if (engineStoppedByUser) continue;   // v1.13：用户点了「停止」→ 不自动拉起
                        boolean serverUp = healthOk();
                        boolean nodeAlive = nodeProcess.isAlive();
                        if (!serverUp && !nodeAlive) {
                            long now = System.currentTimeMillis();
                            if (now - lastRespawnAt < 20000) continue; // 避免风车重启
                            lastRespawnAt = now;
                            Log.w(TAG, "node died, respawning engine");
                            spawnNode(new File(getFilesDir(), "payload"));
                            final WebView wv = webView;
                            ui.post(new Runnable() {
                                @Override public void run() { wv.loadUrl(webHomeUrl()); }
                            });
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "watchdog error", t);
                    }
                }
            }
        }, "node-watchdog").start();
    }

    private void loadHome() {
        // v1.12：控制台还开着时不抢界面（引擎就绪后由用户点「打开主界面」进来）
        if (consoleVisible) return;
        startWatchdog();
        ui.post(new Runnable() {
            @Override public void run() {
                statusView.setVisibility(View.GONE);
                if (splashLogo != null) splashLogo.setVisibility(View.GONE);
                if (splashBrand != null) splashBrand.setVisibility(View.GONE);
                if (splashHint != null) splashHint.setVisibility(View.GONE);
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
                webView.loadUrl(webHomeUrl());
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                statusView.setText(s);
                if (consoleVisible) conSay(s);
            }
        });
    }

    private void setProgress(final int percent, final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(percent);
                }
                if (s != null) statusView.setText(s);
                if (consoleVisible) {
                    if (conBar != null) conBar.setVisibility(View.VISIBLE);
                    conSetProgress(percent);
                    conSay(s);
                }
            }
        });
    }

    private void showIndeterminate(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(true);
                    progressBar.setVisibility(View.VISIBLE);
                }
                if (s != null) statusView.setText(s);
                if (consoleVisible) conSay(s);
            }
        });
    }

    private void hideProgress() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        // v1.21：清理为插件弹窗临时挂着的隐形 WebView（见 onCreateWindow）
        try {
            for (WebView w : hiddenPopups) {
                try { w.destroy(); } catch (Throwable ignored) {}
            }
            hiddenPopups.clear();
        } catch (Throwable ignored) {}
        // v1.19.6 · A2：松绑 :browser 进程（不松绑会一直拖着它，也回收不了）
        if (browserIpc != null) { browserIpc.release(); browserIpc = null; }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // ==================== v1.12 控制台（冷启动首页 · 原生界面） ====================
    // 控制台是盖在 WebView 之上的一层原生视图（同一个 FrameLayout 根），冷启动时显示；
    // 切屏回来 / 任务恢复（savedInstanceState != null）不进它，直接回 DSH 主界面。
    // 四块：解压文件（完成后收起成一行）、启动引擎、授予权限（含 root）、插件开关、日志。

    private volatile boolean conRootOk = false;
    private long conRootProbeTs = 0L;

    private int cLine() { return conColor("line", Color.parseColor(conDark() ? "#232a38" : "#e5e7eb")); }
    private int cAccent() { return conColor("accent", getColor(R.color.accent_brand)); }

    private File payloadDir() { return new File(getFilesDir(), "payload"); }

    /** 当前安装包的 versionCode（用于判断“内部那棵树是不是本次安装解压的”）。 */
    private int conBuildCode() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (Throwable t) { return 0; }
    }

    /** 关键文件缺失检查（只查标记文件不够：解压中途失败、文件被杀都不算就绪）。 */
    private String conMissingKey() {
        File p = payloadDir();
        String[] keys = {".extracted", "dshroot/REVISION",
                "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "runtime/bin/node", "rish/rish_shizuku.dex"};
        for (int i = 0; i < keys.length; i++) if (!new File(p, keys[i]).exists()) return keys[i];
        return null;
    }

    /**
     * 运行时与内核树是否已就绪。
     * v1.12：加两道判定 —— ① 关键文件必须在；② 内部那棵树必须是「当前这次安装」解压出来的
     * （payload_build_code == 当前 versionCode）。否则升级安装后拿着旧树（例：v1.10 留下的）
     * 会显示“已解压”、校验也“通过”（用户实测就是这个问题）。
     */
    private boolean conFilesReady() {
        if (conMissingKey() != null) return false;
        int done = 0;
        try { done = getSharedPreferences("dsh_prefs", MODE_PRIVATE).getInt("payload_build_code", 0); } catch (Throwable ignored) {}
        return done != 0 && done == conBuildCode();
    }

    private void conMarkPayloadDone() {
        try {
            getSharedPreferences("dsh_prefs", MODE_PRIVATE).edit()
                    .putInt("payload_build_code", conBuildCode()).apply();
        } catch (Throwable ignored) {}
    }

    private String conKernelVer() {
        try {
            String s = readFileText(new File(payloadDir(), "dshroot_kernel_version.txt"));
            if (s != null && s.trim().length() > 0) return s.trim();
        } catch (Throwable ignored) {}
        return null;
    }

    private String conVersionLabel() {
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        String k = conKernelVer();
        return v + (k != null ? " · 内核 " + k : "");
    }

    /**
     * 控制台用的“引擎就绪”判定：「只认真实端口探测」，不再回退看进程句柄。
     * v1.13 修正：真机实测 node 从拉起→开始监听要 17~25 秒，若把“进程活着”当成“已就绪”，
     * 控制台会过早点亮「打开主界面」，用户点进去时 3080 还没监听 → WebView 连不上，
     * 看起来就是“点了没反应”；同时底部还在刷“正在启动…（已等待 N 秒）” → 两套文案交替闪。
     * 现在：就绪=探测通过；进程活着但未就绪 → 由 conEngineBooting() 归入“启动中”。
     */
    private boolean conEngineRunning() {
        long now = System.currentTimeMillis();
        if (now - engineProbeAt > 1500L && !engineProbeBusy) {
            engineProbeBusy = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    conProbeEngineNow();
                    engineProbeBusy = false;
                    if (consoleVisible) ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
                }
            }, "engine-probe").start();
        }
        return engineAliveCached;
    }

    /** 已拉起但还没监听端口（node 启动中）；控制台据此显示“启动中…”并禁用入口。 */
    private boolean conEngineBooting() {
        if (engineAliveCached) return false;
        Process p = nodeProcess;
        return starting || (p != null && p.isAlive());
    }

    /** 端口是否已被监听（TCP 连接得通即算；后台线程调用）。
     *  比 healthOk() 更早为真：node 还在启动时端口已经 listen，用它避免重复拉起引擎。 */
    private boolean portListening(int port) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 真探一次引擎端口（必须在后台线程调用：主线程做网络 IO 会被系统直接抛异常）。 */
    private boolean conProbeEngineNow() {
        boolean up = false;
        try { up = healthOk(); } catch (Throwable ignored) {}
        engineAliveCached = up;
        engineProbeAt = System.currentTimeMillis();
        // 引擎真的就绪 → 漬零连续失败计数（供安全模式提示用）。
        if (up) resetBootFailures();
        return up;
    }

    /**
     * 从引擎日志尾部把最新一条 token URL 捞回来。
     * 适用：引擎已经在跑、但本次 App 进程没抓到那条启动输出（例：App 被杀后重开、
     * 引擎此前已被拉起）。不捞的话 WebView 只能不带 token 加载 → 401 认证页。
     */
    private String conTokenFromLog() {
        try {
            File f = conLogFile();
            if (!f.exists()) return null;
            String tail = conTailOf(f, 300);
            int at = tail.lastIndexOf("http://127.0.0.1");
            if (at < 0) return null;
            int tk = tail.indexOf("?token=", at);
            if (tk < 0) return null;
            int end = tk + 7;
            while (end < tail.length() && !" \r\n\t".contains(String.valueOf(tail.charAt(end)))) end++;
            return tail.substring(at, end);
        } catch (Throwable t) { return null; }
    }

    private void showConsole() {
        if (consoleLayer == null) buildConsoleLayer();
        if (consoleLayer == null) { Log.w(TAG, "showConsole: 控制台层构建失败，回退 startEngine"); startEngine(); return; }
        consoleVisible = true;
        if (consoleLayerBox != null) consoleLayerBox.setVisibility(View.VISIBLE);
        consoleLayer.setVisibility(View.VISIBLE);
        if (engineRoot != null) engineRoot.bringChildToFront(consoleLayerBox != null ? consoleLayerBox : consoleLayer);
        Log.i(TAG, "showConsole: 控制台已显示");
        ensureConsoleThemeAssets();      // v1.17.4：四件套（规范/schema/示例/校验器）解到 /sdcard
        conThemeReloadIfChanged(false);  // v1.17.4：读 console.json（坏配置自动回退，绝不阻塞控制台）
        renderConsole();
        startConsoleTick();
        computeFilesSummaryAsync();
    }

    private void buildConsoleLayer() {
        if (engineRoot == null) return;
        // v1.17.4：外面套一层 FrameLayout —— 最底下是主题背景图 + 压暗层，上面才是滚动内容。
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(cBg());
        conBgImage = new ImageView(this);
        conBgImage.setVisibility(View.GONE);
        box.addView(conBgImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        conBgDim = new View(this);
        conBgDim.setVisibility(View.GONE);
        box.addView(conBgDim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(cBg());
        sc.setFillViewport(true);
        sc.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                conBgParallax(sy);
            }
        });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // v1.19.x：上边距必须**避让状态栏**（原来固定 24dp，真机上品牌字被状态栏压住）
        int topInset = 0;
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) topInset = getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        col.setPadding(dp(18), Math.max(cGap(24), topInset + dp(10)), dp(18), cGap(24));
        sc.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(sc, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        consoleBody = col;
        consoleLayer = sc;
        consoleLayerBox = box;
        engineRoot.addView(box, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void startConsoleTick() {
        if (conTick == null) conTick = new Handler(Looper.getMainLooper());
        conTick.removeCallbacks(consoleTick);
        conTick.postDelayed(consoleTick, 900);
    }

    /** 控制台里显示一句状态：解压阶段进解压块，否则进引擎块。 */
    private void conSay(String s) {
        if (s == null) return;
        if (extracting) { conExtractMsg = s; if (conExMeta != null) conExMeta.setText(s); }
        else if (conEnMeta != null) conEnMeta.setText(s);
    }

    // ---------- 通用控件 ----------
    private TextView cText(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        // v1.21（用户要求）：界面文案里**不允许出现 markdown 记号**（`**加粗**`、反引号等会原样显示）。
        // 这里统一过一道 uiPlain()，主题包 text 层写进来的文案也一并兜住。
        t.setText(uiPlain(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * conFontScale());   // v1.17.4：fontScale
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        // v1.17.4：mono=true 时日志页整页等宽（日志正文本来就是等宽，这里覆盖页内其余文字）
        if (consolePage == 3 && conMono()) t.setTypeface(android.graphics.Typeface.MONOSPACE);
        return t;
    }

    /** v1.21（UI 统一）：两色线性混合（a=0 取 bg，a=1 取 fg）。用于按主题色推导"淡色填充"。 */
    private static int blendColor(int fg, int bg, float a) {
        float k = Math.max(0f, Math.min(1f, a));
        int r = (int) (Color.red(fg) * k + Color.red(bg) * (1 - k));
        int g = (int) (Color.green(fg) * k + Color.green(bg) * (1 - k));
        int bl = (int) (Color.blue(fg) * k + Color.blue(bg) * (1 - k));
        return Color.argb(255, r, g, bl);
    }

    /** v1.21（用户反馈）：浅色下原来用「强调色混白」得到的淡色偏紫；改成以浅蓝为基色、只叠一点点强调色。 */
    private int shellTintFill() {
        if (conDark()) return blendColor(cAccent(), cCard(), 0.24f);
        return blendColor(cAccent(), getColor(R.color.shell_tint_light), 0.10f);
    }

    /** v1.21：与 {@link #shellTintFill()} 搭配的描边色。 */
    private int shellTintStroke() {
        if (conDark()) return blendColor(cAccent(), cCard(), 0.38f);
        return getColor(R.color.shell_tint_stroke_light);
    }

    /** 圆角形状（替代系统 Button/ProgressBar 自带背景，避免 ColorOS 上灰底、裁字、颜色不对）。 */
    private android.graphics.drawable.GradientDrawable cShape(int fill, int stroke, int strokeW, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(conRadius(radius)));   // v1.17.4：radius 主题项（没写则用调用方内置值）
        if (strokeW > 0) g.setStroke(dp(strokeW), stroke);
        return g;
    }

    private int cTrack() { return conColor("track", Color.parseColor(conDark() ? "#141b2b" : "#eef1f6")); }

    // ==================== v1.17.4 控制台主题包 · 运行时 ====================
    // 设计稿：tmp-diag/v1173/console-theme-design.md（§7.6 第 1 轮）
    // 规范/校验器：release-src/console-theme/（同一套规则，改一处必须同步另一处）

    /**
     * 壳的配色方案：`appearance.dark` 显式指定时以它为准；缺省（follow）跟随**壳的深浅色**
     * （见 {@link #shellDark()}：用户选择 > DSH 页面实测底色 > 系统）。
     */
    private boolean conDark() {
        ConsoleTheme t = conTheme;
        return t == null ? shellDark() : t.prefersDark(shellDark());
    }

    // ==================== v1.21：壳级界面外观（控制台 / 引导页 / 启动等待页共用）====================

    /** v1.21：用户在控制台或引导页选的界面外观（follow / light / dark）。 */
    private static final String KEY_UI_SCHEME = "ui_scheme";

    /** 读界面外观偏好；未设置或异常一律 follow。 */
    private String uiSchemePref() {
        try {
            String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UI_SCHEME, "follow");
            return (s == null || s.isEmpty()) ? "follow" : s;
        } catch (Throwable t) {
            return "follow";
        }
    }

    /** 写界面外观偏好，并立刻把壳（状态栏 / 当前页面 / 控制台）刷成新方案。 */
    private void setUiScheme(String scheme) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_UI_SCHEME, scheme).apply();
        } catch (Throwable ignored) {}
        applyShellPalette();
        refreshConsole();
    }

    /**
     * v1.21：**壳的深浅色判定** —— 三源合一，优先级从高到低：
     *
     * ① 用户显式选择（light / dark）—— 用户意图最高；
     * ② DSH 页面**实测底色**（{@link #pageBgColor}）—— 让壳与网页"同一个色调"：
     *    网页是深色主题时壳也深色，哪怕系统是浅色；反之亦然；
     * ③ 系统深浅色（{@link #isDark()}）—— 页面底色还没测到时的兜底。
     *
     * 修掉的割裂：以前只看系统，于是"系统浅色 + DSH 用深色主题"时，
     * 启动页/引导页/控制台是亮色，一切到主界面就是深色页面，观感断层。
     */
    private boolean shellDark() {
        String p = uiSchemePref();
        if ("dark".equals(p)) return true;
        if ("light".equals(p)) return false;
        int bg = pageBgColor;
        if (bg != 0) return conIsDarkColor(bg);
        return isDark();
    }

    /** 把壳配色应用到"当前可见的那一层"（启动页 / 引导页 / 控制台）+ 系统栏。 */
    private void applyShellPalette() {
        try {
            int bg = chromeBg();
            applySystemBars(bg);
            // 启动等待页：文字/圆环/底色都按新方案重刷（换色随时可能发生在启动过程中）
            if (splashBrand != null) splashBrand.setTextColor(cText());
            if (statusView != null) statusView.setTextColor(cText());
            if (splashHint != null) splashHint.setTextColor(cSub());
            if (progressBar != null) {
                progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(cAccent()));
            }
            if (engineRoot != null && webView != null && engineRoot.getParent() != null) {
                // 主界面：底色跟随 DSH 页面（pageBgColor 已知时 = 页面色；否则主题底色）
                engineRoot.setBackgroundColor(pageBgColor != 0 ? pageBgColor : cBg());
            }
            // 引导页：整页重建 —— 头部的标题/进度条/底部按钮都是创建时定色的，
            // 只刷 body 会留下"浅色时创建的灰标题挂在深色底上"这种残留（实测踩到）。
            if (guideRoot != null && guideRoot.getParent() != null) refreshGuideUi();
        } catch (Throwable ignored) {}
    }

    /** 保持当前步数不变，按新配色重建引导页整页。 */
    private void refreshGuideUi() {
        int keep = guideIndex;
        showPermissionScreen();                       // 内部会把 guideIndex 归零
        try {
            guideIndex = Math.max(0, Math.min(keep, guidePages.size()));
            renderGuidePage();
        } catch (Throwable ignored) {}
    }

    /**
     * 取一个主题色：没配置 / 该项没写 → 内置默认色。
     *
     * v1.17.7（真机反馈）：「只改了底色」（colors.bg / perScheme.*.bg 或加了背景图）而没写其它项时，
     * 分割线等仍会取"浅色方案的内置默认 #e5e7eb" → 深底上一条白线非常突兀。现在这类项按底色推导：
     * line/track/card 跟着底色走，text/sub 在"底色深浅与方案不一致"时自动取对比色。
     * 「显式写过的值永远优先」（含 perScheme 里写的那一份）。
     */
    private int conColor(String key, int def) {
        ConsoleTheme t = conTheme;
        if (t == null) return def;
        boolean dark = conDark();
        if (t.hasColor("bg", dark) && !t.hasColor(key, dark)) {
            int bg = t.color("bg", dark, cBgBuiltin(dark));
            boolean bgDark = conIsDarkColor(bg);
            if ("line".equals(key))  return conShadeToward(bg, bgDark ? 0.14f : 0.10f, bgDark);
            if ("track".equals(key)) return conShadeToward(bg, bgDark ? 0.06f : 0.05f, bgDark);
            if ("card".equals(key) && bgDark) return conShadeToward(bg, 0.06f, true);
            // 底色深浅与当前方案不一致 → 文字/次要文字会撞色，按底色取对比色
            if (bgDark != dark) {
                if ("text".equals(key)) return conShadeToward(bg, 0.92f, bgDark);
                if ("sub".equals(key))  return conShadeToward(bg, 0.55f, bgDark);
            }
        }
        return t.color(key, dark, def);
    }

    /** 内置底色（通知/权限页那套资源色）。 */
    private int cBgBuiltin(boolean dark) {
        return getColor(dark ? R.color.shell_bg_dark : R.color.shell_bg_light);
    }

    /** 这个颜色偏深还是偏浅（与 isLightColor 同一套亮度公式，阈值一致）。 */
    private static boolean conIsDarkColor(int c) {
        double lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return lum <= 140d;
    }

    /** 把颜色往对比色方向混 k（深色底往白混、浅色底往黑混）；k=0 原样返回。 */
    private static int conShadeToward(int c, float k, boolean dark) {
        int target = dark ? 255 : 0;
        int a = Color.alpha(c);
        int r = Math.round(Color.red(c) + (target - Color.red(c)) * k);
        int g = Math.round(Color.green(c) + (target - Color.green(c)) * k);
        int b = Math.round(Color.blue(c) + (target - Color.blue(c)) * k);
        return Color.argb(a, r, g, b);
    }

    private float conFontScale() {
        ConsoleTheme t = conTheme;
        return t == null ? 1f : t.fontScale;
    }

    /** 主题指定的圆角（没写就用调用方自己的内置圆角 —— schema 里的 14 只是给主题作者的参考值）。 */
    private int conRadius(int fallback) {
        ConsoleTheme t = conTheme;
        return (t == null || t.radius == null) ? fallback : t.radius.intValue();
    }

    /** cardsAlpha：给底色乘一个 alpha 系数（放背景图时调低才看得见图）。 */
    private int conCardsAlpha(int color) {
        ConsoleTheme t = conTheme;
        if (t == null || t.cardsAlpha == null) return color;
        float a = Math.max(0f, Math.min(1f, t.cardsAlpha.floatValue()));
        int alpha = Math.round(Color.alpha(color) * a);
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private boolean conMono() {
        ConsoleTheme t = conTheme;
        return t != null && t.mono;
    }

    /** 控制台主题目录：/sdcard/<包名目录>/console（三种变体各自独立）。 */
    private File consoleDir() {
        return new File(Environment.getExternalStorageDirectory(), pkgRoot() + "/console");
    }

    private File conThemeFile() { return new File(consoleDir(), "console.json"); }

    private String conThemeStampOf() {
        File f = conThemeFile();
        return f.exists() ? (f.lastModified() + ":" + f.length()) : "-";
    }

    /**
     * 热重载：console.json 的 mtime/size 变了才重新解析（force=无条件重读）。
     * 控制台每次渲染前调用，开销 = 一次 stat；解析失败/取值非法一律走 ConsoleTheme 的兜底。
     */
    private boolean conThemeReloadIfChanged(boolean force) {
        try {
            // 逃生口（长按品牌字 / 主题页）：忽略主题配置，用内置默认样式打开控制台。文件不删。
            if (conThemeIgnored()) {
                conTheme = null;
                conThemeLoaded = true;
                conThemeStamp = conThemeStampOf();
                return false;
            }
            String stamp = conThemeStampOf();
            if (!force && conThemeLoaded && stamp.equals(conThemeStamp)) return false;
            File f = conThemeFile();
            ConsoleTheme t = ConsoleTheme.load(f);
            conThemeStamp = stamp;
            conThemeLoaded = true;
            conTheme = t.absent ? null : t;
            conThemeApplyLayout();                                // layout.defaultPage / detailOpen
            if (!t.absent && !t.fatal) backupThemeFile(f);        // 成功加载 → 留一份 .bak
            conThemeCheckImages(t);                               // 图片引用了但不在 → 如实回退那一项
            if (t.fatal || t.reverted > 0) writeThemeError(t);    // 回退原因落盘，便于排查/反馈
            Log.i(TAG, "console theme: " + (t.absent ? "无配置（内置默认）" : t.detail().split("\n")[0]));
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "conThemeReloadIfChanged: " + e.getMessage());
        }
        return false;
    }

    /**
     * 每秒一次的「热重载」检查（控制台可见时由 consoleTick 调用）。
     * 这是「放一份 console.json → 控制台外观立刻变」的实现点：「配置真变了才整页重渲染」；
     * 没变时只有一次 stat 的开销（不重建视图）。
     */
    private void conThemeTick() {
        if (conThemeReloadIfChanged(false)) {
            conBgApply();
            renderConsole();
        }
    }

    /**
     * 背景图 / logo 引用了但磁盘上没有 → 视为该项回退（纯色底 / 内置图标）并记一条诊断。
     * 不静默是刻意的：AI 生成的主题包常常只带 console.json、图片没一起拷进来，
     * 用户得能从顶部提示条上看出「为什么背景没出来」。
     */
    private void conThemeCheckImages(ConsoleTheme t) {
        try {
            if (t.background != null) {
                File img = conImageFile(t.background.image);
                if (img == null || !img.exists()) {
                    t.reverted++;
                    t.warnings.add("[已回退] $.appearance.background.image：找不到图片 "
                            + t.background.image + "（应放在 " + consoleDir().getAbsolutePath() + "/）");
                    t.background = null;
                }
            }
            if (t.logo != null) {
                File lg = conImageFile(t.logo);
                if (lg == null || !lg.exists()) {
                    t.reverted++;
                    t.warnings.add("[已回退] $.appearance.logo：找不到图片 " + t.logo);
                    t.logo = null;
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 成功解析后留一份 console.json.bak（内容没变就不重写）。 */
    private void backupThemeFile(File f) {
        try {
            if (!f.exists()) return;
            File bak = new File(consoleDir(), "console.json.bak");
            String now = ConsoleTheme.readText(f);
            if (bak.exists() && now.equals(ConsoleTheme.readText(bak))) return;
            java.io.FileOutputStream fos = new java.io.FileOutputStream(bak);
            fos.write(now.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    /** 回退原因写 console/last-error.txt（只写诊断，不含用户配置内容）。 */
    private void writeThemeError(ConsoleTheme t) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date())).append('\n');
            sb.append("文件：").append(conThemeFile().getAbsolutePath()).append('\n');
            sb.append("结果：").append(t.fatal ? "整体回退默认" : (t.reverted + " 项回退")).append('\n');
            for (int i = 0; i < t.errors.size(); i++) sb.append("错误：").append(t.errors.get(i)).append('\n');
            for (int i = 0; i < t.warnings.size(); i++) sb.append("警告：").append(t.warnings.get(i)).append('\n');
            java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(consoleDir(), "last-error.txt"));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }

    /** 「恢复默认」= 把 console.json 改名留底（不删，可反悔），然后回到内置默认。 */
    private void conThemeReset() {
        try {
            File f = conThemeFile();
            if (f.exists()) {
                String ts = new SimpleDateFormat("yyMMdd-HHmmss").format(new java.util.Date());
                File to = new File(consoleDir(), "console.json.disabled-" + ts);
                // v1.19.x：改名之外**再留一份可恢复副本** —— 用户之后用文件管理器改动/误删也还能找回来。
                // （主题页新增的「我保存过的主题」会把这两种都列出来，点一条即可恢复。）
                try { copyFileShallow(f, new File(consoleDir(), "console.json.replaced-" + ts)); } catch (Throwable ignored) {}
                if (!f.renameTo(to)) { conToast("改名失败：" + to.getName()); return; }
            }
        } catch (Throwable t) {
            conToast("恢复默认失败：" + t.getMessage());
            return;
        }
        conTheme = null;
        conThemeLoaded = false;
        conThemeStamp = null;
        conThemeReloadIfChanged(true);
        conBgApply();
        renderConsole();
        conToast("已恢复内置默认（原文件已改名留底）");
    }

    // ---------- 随包分发的四件套（规范 / schema / 示例 / 校验器） ----------

    private static final String[] CON_THEME_ASSETS = {
            "THEME-PACK-SPEC.md", "console.schema.json", "console.example.json", "theme_pack_check.py"};

    /**
     * 把 assets/console-theme/ 里的四件套解到 /sdcard/<包名目录>/console/，
     * 让「跑在同一台设备上的 AI」 不联网也能读到规范（设计稿 §6.1 第 4 条）。
     * 内容长度不同才重写（升级/改版自动更新）；没存储权限时静默失败，下次再试。
     */
    private void ensureConsoleThemeAssets() {
        if (conThemeAssetsSeeded) return;
        try {
            File dir = consoleDir();
            if (!dir.exists() && !dir.mkdirs()) return;   // 还没拿到存储权限 → 留给下次
            boolean all = true;
            for (int i = 0; i < CON_THEME_ASSETS.length; i++) {
                String n = CON_THEME_ASSETS[i];
                InputStream in = null;
                try {
                    in = getAssets().open("console-theme/" + n);
                    byte[] data = readAllBytes(in);
                    File out = new File(dir, n);
                    if (!(out.exists() && out.length() == data.length)) {
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                        fos.write(data);
                        fos.close();
                    }
                    if (!out.exists() || out.length() != data.length) all = false;
                } catch (Throwable e) {
                    all = false;
                    Log.w(TAG, "console-theme asset " + n + ": " + e.getMessage());
                } finally {
                    if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
                }
            }
            conThemeAssetsSeeded = all;   // 四个都到位才不再重试（没权限时下次渲染还会试）
            Log.i(TAG, "console-theme assets -> " + dir.getAbsolutePath() + " ok=" + all);
        } catch (Throwable t) {
            Log.w(TAG, "ensureConsoleThemeAssets: " + t.getMessage());
        }
    }

    // ============ D1：设备端「自我定制」技能 ============
    /** 技能根目录：<filesDir>/.agents/skills —— 引擎默认扫描（DSH_AGENTS_HOME 缺省 = ~/.agents）。
     *  刻意放在 filesDir 而不是 payload/dshhome：payload 会被解压/同步重写，这里永远不会。 */
    private static final String AGENT_SKILLS_DIR = ".agents/skills";
    /** 本壳管理的技能会在正文里带这个标记；只有带标记的文件才允许被覆盖（用户自己写的技能不动）。 */
    private static final String SKILL_MANAGED_MARKER = "dsh-android-managed";
    private boolean agentSkillsSeeded = false;

    /**
     * 把 assets/skills/&lt;name&gt;/SKILL.md 落到 &lt;filesDir&gt;/.agents/skills/&lt;name&gt;/SKILL.md。
     * 写入规则：文件不存在 → 写；文件存在且**含本壳管理标记**且长度变了 → 覆盖；否则一律不动。
     * （技能内容真源在仓库 skills/，由 build.sh 打进 assets；改内容只需改仓库 + 重打包。）
     */
    private void ensureAgentSkills() {
        if (agentSkillsSeeded) return;
        try {
            String[] names = getAssets().list("skills");
            if (names == null || names.length == 0) { agentSkillsSeeded = true; return; }
            File root = new File(getFilesDir(), AGENT_SKILLS_DIR);
            boolean all = true;
            for (int i = 0; i < names.length; i++) {
                String n = names[i];
                byte[] data = null;
                InputStream in = null;
                try {
                    in = getAssets().open("skills/" + n + "/SKILL.md");
                    data = readAllBytes(in);
                } catch (Throwable e) {
                    continue;   // 不是"目录 + SKILL.md"形态（例如将来的扁平技能），本轮跳过
                } finally {
                    if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
                }
                try {
                    File dir = new File(root, n);
                    File out = new File(dir, "SKILL.md");
                    String old = out.exists() ? readFileText(out) : null;
                    boolean managed = old != null && old.indexOf(SKILL_MANAGED_MARKER) >= 0;
                    boolean need = old == null || (managed && old.length() != data.length);
                    if (need) {
                        if (!dir.exists() && !dir.mkdirs()) { all = false; continue; }
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                        fos.write(data);
                        fos.close();
                    }
                } catch (Throwable e) {
                    all = false;
                    Log.w(TAG, "agent skill write " + n + ": " + e.getMessage());
                }
            }
            agentSkillsSeeded = all;
            Log.i(TAG, "agent skills -> " + root.getAbsolutePath() + " ok=" + all);
        } catch (Throwable t) {
            Log.w(TAG, "ensureAgentSkills: " + t.getMessage());
        }
    }

    private static byte[] readAllBytes(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ---------- 背景图 / logo ----------

    private File conImageFile(String ref) {
        if (ref == null || ref.length() == 0) return null;
        if (ref.startsWith("/sdcard/")) {
            return new File(Environment.getExternalStorageDirectory(), ref.substring("/sdcard/".length()));
        }
        return new File(consoleDir(), ref);
    }

    /** 解码图片并限制最大边（超大照片按 2 的幂降采样，避免 OOM）。 */
    private android.graphics.Bitmap conDecodeFile(File f, int maxDim) {
        try {
            if (f == null || !f.exists()) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1, big = Math.max(o.outWidth, o.outHeight);
            while (big / sample > maxDim) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // 背景/图标不需要 alpha
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable e) {
            Log.w(TAG, "theme image decode: " + e.getMessage());
            return null;
        }
    }

    private android.graphics.Bitmap conLoadBg(String ref) {
        File f = conImageFile(ref);
        if (f == null) return null;
        String key = ref + ":" + f.lastModified() + ":" + f.length();   // 换图（同名）也能热更新
        if (conBgBitmap != null && key.equals(conBgBitmapRef)) return conBgBitmap;
        conBgBitmap = conDecodeFile(f, 2048);
        conBgBitmapRef = key;
        return conBgBitmap;
    }

    private android.graphics.Bitmap conLoadLogo(String ref) {
        File f = conImageFile(ref);
        if (f == null) return null;
        String key = ref + ":" + f.lastModified() + ":" + f.length();   // 换图（同名）也能热更新
        if (conLogoBitmap != null && key.equals(conLogoBitmapRef)) return conLogoBitmap;
        conLogoBitmap = conDecodeFile(f, 512);
        conLogoBitmapRef = key;
        return conLogoBitmap;
    }

    /** cover/contain + 九宫格锚点 → 图片矩阵（ImageView 原生 scaleType 不支持锚点）。 */
    private android.graphics.Matrix conBgMatrix(android.graphics.Bitmap bm, String fit, String anchor,
                                                int vw, int vh) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        float bw = bm.getWidth(), bh = bm.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return m;
        float scale = "contain".equals(fit) ? Math.min(vw / bw, vh / bh) : Math.max(vw / bw, vh / bh);
        float sw = bw * scale, sh = bh * scale;
        String a = anchor == null ? "center" : anchor.toLowerCase(java.util.Locale.US);
        float dx = a.indexOf("left") >= 0 ? 0f : a.indexOf("right") >= 0 ? vw - sw : (vw - sw) / 2f;
        float dy = a.startsWith("top") ? 0f : a.startsWith("bottom") ? vh - sh : (vh - sh) / 2f;
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        return m;
    }

    /** 应用主题背景：底色 + 图片（opacity）+ 压暗层（dim）+ 模糊（Android 12+）。位图有缓存，可每秒调。 */
    private void conBgApply() {
        if (consoleLayerBox == null || consoleLayer == null || conBgImage == null) return;
        consoleLayerBox.setBackgroundColor(cBg());
        ConsoleTheme t = conTheme;
        ConsoleTheme.Bg bg = (t == null) ? null : t.background;
        android.graphics.Bitmap bm = (bg == null || bg.image == null) ? null : conLoadBg(bg.image);
        if (bm == null) {
            if (conBgImage.getVisibility() != View.GONE) {
                conBgImage.setImageDrawable(null);
                conBgImage.setBackground(null);
                conBgImage.setVisibility(View.GONE);
            }
            if (conBgDim != null) conBgDim.setVisibility(View.GONE);
            consoleLayer.setBackgroundColor(cBg());
            return;
        }
        int vw = consoleLayerBox.getWidth(), vh = consoleLayerBox.getHeight();
        if ("tile".equals(bg.fit)) {
            conBgImage.setImageDrawable(null);
            android.graphics.drawable.BitmapDrawable bd =
                    new android.graphics.drawable.BitmapDrawable(getResources(), bm);
            bd.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
            conBgImage.setBackground(bd);
        } else {
            conBgImage.setBackground(null);
            conBgImage.setImageBitmap(bm);
            if ("stretch".equals(bg.fit)) {
                conBgImage.setScaleType(ImageView.ScaleType.FIT_XY);
            } else if ("center".equals(bg.fit)) {
                conBgImage.setScaleType(ImageView.ScaleType.CENTER);
            } else if (vw > 0 && vh > 0) {
                conBgImage.setScaleType(ImageView.ScaleType.MATRIX);
                conBgImage.setImageMatrix(conBgMatrix(bm, bg.fit, bg.anchor, vw, vh));
            } else {
                conBgImage.setScaleType("contain".equals(bg.fit)
                        ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
            }
        }
        conBgImage.setAlpha(Math.max(0f, Math.min(1f, bg.opacity)));
        // 视差：图片比视口高 50%，才有可移动的余量（否则上移后底边会被拉出屏幕）
        ViewGroup.LayoutParams lp = conBgImage.getLayoutParams();
        int want = (bg.parallax && vh > 0) ? Math.round(vh * 1.5f) : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp != null && lp.height != want) { lp.height = want; conBgImage.setLayoutParams(lp); }
        conBgImage.setVisibility(View.VISIBLE);
        if (conBgDim != null) {
            float dim = Math.max(0f, Math.min(1f, bg.dim));
            conBgDim.setBackgroundColor(Color.argb(Math.round(dim * 255f), 0, 0, 0));
            conBgDim.setVisibility(dim > 0f ? View.VISIBLE : View.GONE);
        }
        if (Build.VERSION.SDK_INT >= 31) {   // 模糊只有 Android 12+ 支持，低版本静默忽略
            try {
                conBgImage.setRenderEffect(bg.blur > 0
                        ? android.graphics.RenderEffect.createBlurEffect(bg.blur, bg.blur,
                                android.graphics.Shader.TileMode.CLAMP)
                        : null);
            } catch (Throwable ignored) {}
        }
        consoleLayer.setBackgroundColor(Color.TRANSPARENT);   // 底色交给底层图片
    }

    /** 视差：控制台滚动时背景按 0.5 倍速上移（上限半屏高，保证底边不出屏）。 */
    private void conBgParallax(int scrollY) {
        ConsoleTheme t = conTheme;
        if (conBgImage == null || consoleLayerBox == null) return;
        if (t == null || t.background == null || !t.background.parallax) return;
        if (conBgImage.getVisibility() != View.VISIBLE) return;
        float h = consoleLayerBox.getHeight();
        if (h <= 0f) return;
        conBgImage.setTranslationY(-Math.min(scrollY * 0.5f, h * 0.5f));
    }

    /** 主题 logo：解不出来就保留内置图标（不报错）。 */
    private void conThemeApplyLogo(ImageView v) {
        ConsoleTheme t = conTheme;
        if (v == null || t == null || t.logo == null) return;
        android.graphics.Bitmap bm = conLoadLogo(t.logo);
        if (bm != null) v.setImageBitmap(bm);
    }

    /** 控制台表头的小 logo（没配置则返回 null，布局保持不变）。 */
    private View conThemeLogoView(int sizeDp) {
        ConsoleTheme t = conTheme;
        if (t == null || t.logo == null) return null;
        android.graphics.Bitmap bm = conLoadLogo(t.logo);
        if (bm == null) return null;
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bm);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.rightMargin = dp(6);
        iv.setLayoutParams(lp);
        return iv;
    }

    // ---------- 顶部提示条（回退提示） ----------

    /** 主题配置有问题时的顶部黄条；null = 不显示。 */
    private View conThemeBanner() {
        ConsoleTheme t = conTheme;
        if (t == null) return null;
        String text = t.bannerText();
        if (text == null) return null;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(cShape(0x33F5A524, 0xFFF5A524, 1, 8));
        bar.setPadding(dp(12), dp(9), dp(12), dp(9));
        bar.addView(cText("⚠ " + text, 11.5f, 0xFFF5A524, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(cText("详情 ›", 11.5f, 0xFFF5A524, false));
        bar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeShowDetail(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        bar.setLayoutParams(lp);
        return bar;
    }

    private void conThemeShowDetail() {
        ConsoleTheme t = conTheme;
        String body = (t == null) ? "没有加载到主题配置（console.json 不存在或未生效）。" : t.detail();
        TextView tv = cText(body, 12f, cSub(), false);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(cShape(conDark() ? 0xFF0F1524 : 0xFFF2F4F8, 0, 0, 6));
        sv.setPadding(dp(12), dp(12), dp(12), dp(12));
        sv.addView(tv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        conDialogView("控制台主题 · 诊断", sv, null, null, "关闭");
    }

    /** 自绘按钮：内边距固定、单行、超长省略号，不再出现“文字超出按钮”的情况。 */
    /**
     * v1.21（UI 统一）：一行「界面外观」三选一 —— 跟随页面 / 浅色 / 深色。
     *
     * 引导页完成页与控制台「主题」页共用同一份实现、同一个偏好（{@link #KEY_UI_SCHEME}）。
     * 点一下立即生效：壳（控制台 / 引导页 / 启动页 + 系统栏）整层换色。
     */
    private View shellSchemeRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(cShape(cCard(), cLine(), 1, 12));

        box.addView(cText("界面外观", 15f, cText(), true));
        box.addView(cText("控制台、引导页、启动页会跟随 DSH 页面的色调；也可以在这里固定成浅色或深色。",
                12.5f, cSub(), false), cTop(dp(4)));

        final String[] keys = {"follow", "light", "dark"};
        final String[] labels = {"跟随页面", "浅色", "深色"};
        final String cur = uiSchemePref();
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            final boolean on = key.equals(cur);
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * conFontScale());
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(12), dp(9), dp(12), dp(9));
            // v1.21：选中态也用淡色 tonal（与 cButton 同一套），不再用实心强调色块
            b.setTextColor(on ? cAccent() : cSub());
            b.setBackground(cShape(on ? shellTintFill() : cTrack(),
                    on ? shellTintStroke() : cLine(), 1, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setUiScheme(key); }
            });
            seg.addView(b);
        }
        box.addView(seg, cTop(dp(12)));
        return box;
    }

    private Button cButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, (primary ? 13.5f : 12.5f) * conFontScale());   // v1.17.4：fontScale
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinWidth(dp(primary ? 96 : 68));
        b.setMinimumWidth(dp(primary ? 96 : 68));
        // v1.19.6：高度也要兜底。原来只钉了宽度 —— 父布局高度不够时按钮会被压成
        // 一条扁色块、文字被裁掉（用户真机反馈："扁扁的，然后也没有文字"）。
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setIncludeFontPadding(false);
        // v1.21（用户反馈"不喜欢深蓝色按钮"）：按钮改成**淡色 tonal 风格** ——
        // · 主按钮 = 强调色按比例混进卡片底色（浅色 14% / 深色 24%），文字用强调色 + 淡描边；
        // · 次按钮 = 纯文字按钮（无填充、无描边），文字压到次级色。
        // 整屏因此不再出现大块饱和蓝，深浅两套都更轻。
        if (primary) {
            b.setTextColor(cAccent());
            b.setBackground(cShape(shellTintFill(), shellTintStroke(), 1, 10));
        } else {
            b.setTextColor(cSub());
            b.setBackground(cShape(0x00000000, 0x00000000, 0, 10));
        }
        return b;
    }

    /** 统一“禁用”外观：自绘背景下系统不会自动变灰，必须手动降透明度。 */
    private void cSetEnabled(Button b, boolean on) {
        if (b == null) return;
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.38f);
    }

    /** 进度条（自绘：轨道 + 强调色填充，风格与页面一致）。 */
    private void conSetProgress(int pct) {
        if (conFill == null || conSpacer == null) return;
        int p = Math.max(0, Math.min(100, pct));
        LinearLayout.LayoutParams f = (LinearLayout.LayoutParams) conFill.getLayoutParams();
        LinearLayout.LayoutParams s = (LinearLayout.LayoutParams) conSpacer.getLayoutParams();
        f.weight = Math.max(p, 0.01f);
        s.weight = Math.max(100 - p, 0.01f);
        conFill.setLayoutParams(f);
        conSpacer.setLayoutParams(s);
    }

    /** 插件开关（自绘小胶囊，比系统 Switch 更可控且与页面同风格）。 */
    private TextView cToggle(final String id, boolean on) {
        final TextView t = new TextView(this);
        t.setTag(Boolean.valueOf(on));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        conTogglePaint(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !((Boolean) t.getTag()).booleanValue();
                t.setTag(Boolean.valueOf(now));
                conTogglePaint(t);
                conSetPluginDisabled(id, !now);
                conToast("dsh-" + id + (now ? " 已启用" : " 已关闭") + "（重启引擎生效）");
                // v1.19.6：改完立刻重绘（改完立刻重绘）——
                // 只改自己那个 TextView 的话，页头「插件列表 · 已启用 N / M」会停在旧数字上，
                // 用户看不出这一下生效没有（真机验收发现，正好砸在路线图③"可开可关"的反馈上）。
                renderConsole();
            }
        });
        return t;
    }

    private void conTogglePaint(TextView t) {
        boolean on = ((Boolean) t.getTag()).booleanValue();
        t.setText(on ? "已启用" : "已关闭");
        t.setTextColor(on ? cGreen() : cSub());
        t.setBackground(cShape(on ? (conDark() ? 0x241F9D6B : 0x1A1F9D6B) : 0x00000000,
                on ? cGreen() : cLine(), 1, 12));
    }

    private LinearLayout.LayoutParams cTop(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = cGap(topMargin);   // v1.17.5：compact 时间距减半
        return lp;
    }

    private View cSep(int topMargin) {
        View v = new View(this);
        v.setBackgroundColor(cLine());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = cGap(topMargin);   // v1.17.5：compact 时间距减半
        v.setLayoutParams(lp);
        return v;
    }

    private View cNavRow(String title, String sub, String value, final int page) {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(cText(title, 14f, cText(), false));
        if (sub != null && sub.length() > 0) left.addView(cText(sub, 11f, cSub(), false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(15), 0, dp(15));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null && value.length() > 0) row.addView(cText(value, 11f, cSub(), false));
        row.addView(cText("›", 14f, cSub(), false));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { consolePage = page; renderConsole(); }
        });
        return row;
    }

    private View conBackRow(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button back = cButton("‹ 控制台", false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        back.setPadding(0, dp(4), dp(8), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { consolePage = 0; renderConsole(); }
        });
        row.addView(back);
        row.addView(cText(title, 12.5f, cSub(), false));
        return row;
    }

    private void conToast(final String s) {
        ui.post(new Runnable() { @Override public void run() {
            try { android.widget.Toast.makeText(MainActivity.this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
        }});
    }

    // ---------- 页面渲染 ----------
    private void renderConsole() {
        if (consoleBody == null) return;
        // v1.19.6 修（第六轮真机）：换页必须回页首。
        // 原来只换 consoleBody 的子视图，**从不重置 ScrollView 的偏移** → 换页会继承上一页的
        // 滚动位置：主控台滚到底点「主题」，落点直接在主题页中段，第一行「导出主题包」
        // 不在可视区（验收脚本因此"点了没反应"，一度被误判成导出功能坏了）。
        if (consolePage != conLastRenderedPage) {
            conLastRenderedPage = consolePage;
            if (consoleLayer != null) consoleLayer.scrollTo(0, 0);
        }
        conThemeReloadIfChanged(false);   // v1.17.4：热重载（比 mtime+size，变了才重新解析）
        conBgApply();                     // 背景图/压暗/透明度（位图有缓存，每秒调用也不解码）
        consoleBody.removeAllViews();
        View warnBar = conThemeBanner();
        if (warnBar != null) consoleBody.addView(warnBar);
        if (consolePage == 1) { renderConsolePerm(); return; }
        if (consolePage == 2) { renderConsolePlug(); return; }
        if (consolePage == 3) { renderConsoleLog(); return; }
        if (consolePage == 4) { renderConsoleTheme(); return; }
        if (consolePage == PAGE_SELFCHECK) { renderConsoleSelfCheck(); return; }   // v1.19.x：内核自检（负页号哨兵，避免与自定义页冲突）
        if (consolePage == PAGE_SESSION_HEAL) { renderConsoleSessionHeal(); return; }   // v1.19.x：会话级自愈（自修复 ③）
        if (consolePage == PAGE_SESSION_ADMIN) { renderConsoleSessionAdmin(); return; }   // v1.19.4：会话管理（删除）
        if (consolePage == PAGE_SESSION_TRASH) { renderConsoleSessionTrash(); return; }   // v1.19.4：回收站
        if (consolePage == PAGE_BROWSER) { renderConsoleBrowser(); return; }   // v1.19.6 · 阶段 C：AI 浏览器
        if (consolePage >= 5) {                       // v1.17.8：layout.pages 的额外页
            ConsoleTheme ct = conTheme;
            int idx = consolePage - 4;
            if (ct != null && ct.pages != null && idx >= 1 && idx < ct.pages.size()) {
                renderConsoleCustomPage(ct, idx);
                return;
            }
            consolePage = 0;                          // 配置换了 / 页没了 → 回主控台
        }
        renderConsoleMain();
    }


    /**
     * 精简版主控台（缺省风格）。
     * 设计意图：一屏讲清三件事 —— ① 现在什么状态 ② 我要做什么（一个按钮）③ 去哪找别的。
     * 刻意**不做卡片、不做装饰分隔线**（原来 9 张卡片的视觉噪音就来自这里）。
     */
    /**
     * 精简版主控台（缺省风格）—— v1.19.5 重做（方案 B：状态块 + 分组）。
     *
     * 设计意图：一屏讲清三件事 —— ① 现在能不能用（状态块）② 我要做什么（主按钮）③ 去哪儿找别的（三组入口）。
     * 硬约束：
     *   · 每个字仍走 t("key", "默认值")，所以主题的 text 覆盖照旧生效；
     *   · layout.pages 存在时本方法根本不会被调用（renderConsole 里"整页自拼"优先）；
     *   · 会话分组不是卡片（不进 order/hidden 体系），因此不动任何既有卡片语义。
     */
    private void renderConsoleSimple() {
        LinearLayout col = consoleBody;
        conRenderedSimple = true;

        // v1.19.7（新版骨架吃主题布局）：新版主控台**条目化** ——
        // 从此它也吃主题的 layout.hidden / layout.cardOrder / actions。
        // 条目 id 一律沿用 classic 的卡片 id，所以老主题里写过的「隐藏/排序」在新版下照样有意义。
        // （表头与页脚固定、不参与排序 —— 与 classic 的做法一致。）
        java.util.LinkedHashMap<String, View> items = new java.util.LinkedHashMap<String, View>();

        // ── 表头：品牌 + 版本 + 右侧"完整版"入口 ──
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(conBrandView(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(8);
        head.addView(cText(conVersionLabel(), 10f, cSub(), false), vlp);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        // v1.19.6：右上角原来有个「完整版」按钮（切回旧的卡片平铺）。用户 2026-10-05：
        // 「既然已经重做了，就没有必要弄一个回到之前页面的按钮了」→ 已移除。
        // 旧卡片版仍是 layout.pages 那些积木（extract.block / engine.status / …）的实现来源，
        // 只是不再作为"可选风格"暴露。
        col.addView(head);

        // ── 状态块：运行环境 + 引擎 合成一块（一眼看清"能不能用 + 下一步做什么"） ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cShape(cCard(), cLine(), 1, 12));
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        // v1.19.7：主操作块（环境 + 引擎 + 主按钮 + 重启/停止 + 详情）作为一个**可排序条目**，
        // 不再直接挂到 col 上。它受保护、不可隐藏（hidden 里写 extract / engine 都会被忽略）——
        // 它是「解压 / 启动引擎」的唯一入口，藏了就没法用了。
        items.put("extract", card);

        // 大字状态：未解压时讲"运行环境"，解压好了讲"引擎"（两个字段都留着，由 refreshConsole 切可见性）
        conExState = cText("", 16f, cText(), true);
        card.addView(conExState);
        conEnState = cText("", 16f, cText(), true);
        card.addView(conEnState);
        conEnMeta = cText("", 11f, cSub(), false);
        card.addView(conEnMeta, cTop(cGap(4)));
        conExMeta = cText("", 11f, cSub(), false);
        card.addView(conExMeta, cTop(cGap(2)));
        // 解压进度条（只在解压时显示）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(cShape(cTrack(), 0, 0, 3));
        conFill = new View(this);
        conFill.setBackgroundColor(cAccent());
        conSpacer = new View(this);
        bar.addView(conFill, new LinearLayout.LayoutParams(0, dp(6), 1f));
        bar.addView(conSpacer, new LinearLayout.LayoutParams(0, dp(6), 0f));
        bar.setVisibility(View.GONE);
        conBar = bar;
        card.addView(bar, cTop(cGap(10)));
        // 主按钮（文案随状态变，refreshConsole 驱动）
        uiSimpleActionBtn = cButton(t("btn.extract.run", "解压文件"), true);
        uiSimpleActionBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conSimplePrimaryAction(); }
        });
        card.addView(uiSimpleActionBtn, cTop(cGap(14)));
        // 次要操作：重启 / 停止（只在引擎活着时可用）
        LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        conEnRestart = cButton(t("btn.engine.restart", "重启"), false);
        conEnRestart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conRestartEngine(); }
        });
        conEnStop = cButton(t("btn.engine.stop", "停止"), false);
        conEnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conStopEngine(); }
        });
        sub.addView(conEnRestart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(8);
        sub.addView(conEnStop, slp);
        card.addView(sub, cTop(cGap(8)));
        // 详情块不再挂到状态块上（v1.21 用户反馈：展开后只有一行"布局标记/插件与补丁面"，
        // 与「运行环境」弹窗里的信息、按钮完全重复，属于无效入口）→ 入口与文案一并去掉。
        conDetailBox = conExtractDetail();
        conDetailBox.setVisibility(View.GONE);

        // ── 会话（把原来藏在「内核自检」页里的两个入口提到首页） ──
        items.put("group.sessions", cGrpTitle(t("title.grpSessions", "会话")));
        items.put("sessionadmin", cNavRow(t("card.sessionadmin", "会话管理"),
                t("desc.sessionadmin", "删除不需要的会话 · 先进回收站，可恢复"), "", PAGE_SESSION_ADMIN));
        items.put("sessionheal", cNavRow(t("card.sessionheal", "会话修复"),
                t("desc.sessionheal", "坏图毒死的会话：只降级那一条消息"), "", PAGE_SESSION_HEAL));

        // ── 系统 ──
        items.put("group.system", cGrpTitle(t("title.grpSystem", "系统")));
        // **常驻**的运行环境入口（「重新解压」是修坏树的一键入口，v1.19.7 起进 KEEP_CARDS，不许藏）。
        items.put("env", cNavRowEnv());
        // AI 浏览器的常驻入口（同屏查看 / 页签 / 关闭）：同屏是"AI 正在点哪一页"的唯一人眼通道。
        items.put("browser", cNavRow(t("card.browser", "AI 浏览器"),
                t("desc.browser", "看 AI 正在哪一页 · 同屏查看（画面可直接操作，拖顶部小条搬窗）"),
                conBrowserSummary(), PAGE_BROWSER));
        items.put("perm", cNavRow(t("card.perm", "授予权限"), t("desc.perm", "存储 · 通知 · 悬浮窗 · 电池 · root · Shizuku · 无障碍"),
                conPermSummary(), 1));
        items.put("plugins", cNavRow(t("card.plugins", "插件"), t("desc.plugins", "关掉用不到的，省上下文"), conPlugSummary(), 2));
        items.put("log", cNavRow(t("card.log", "日志"), conLogLine(), "", 3));
        items.put("theme", cNavRow(t("card.theme", "主题"), t("desc.theme", "外观 / 布局 / 文案都由 console.json 决定 · 点这里导入导出"),
                "", 4));

        // ── 诊断 ──
        items.put("group.diagnose", cGrpTitle(t("title.grpDiagnose", "诊断")));
        items.put("selfcheck", cNavRow(t("card.selfcheck", "内核自检"), t("desc.selfcheckShort", "清单式一致性证明 · 自愈账本"), "", PAGE_SELFCHECK));

        // v1.19.7：主题的自定义动作卡（actions[]）—— classic 一直渲染它，新版以前**根本不渲染**。
        // 用户手上那份「软软小白团」正好用了 actions，属于"设了却没生效"。
        View actCard = cardActions();
        if (actCard != null) items.put("actions", actCard);

        // 按主题的 layout.hidden / layout.cardOrder 过滤 + 排序后一次性挂上主控台
        emitConsoleItems(col, items);

        // ── 页脚：状态 + 三个等宽按钮（救援能力一项不少） ──
        col.addView(cSep(cGap(20)));
        conFoot = cText(t("status.ready", "就绪"), 11f, cSub(), false);
        col.addView(conFoot);
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        Button rescue = cButton(t("btn.rescue", "救援"), false);
        rescue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        rescue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conShowRescueDialog(); }
        });
        foot.addView(rescue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button upd = cButton(t("card.update", "检查更新"), false);
        upd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        upd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conToast("正在检查…"); checkForUpdate(true); }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ulp.leftMargin = dp(8);
        foot.addView(upd, ulp);
        Button th = cButton(t("btn.shellTheme", "界面主题"), false);
        th.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        th.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeDialog(); }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        foot.addView(th, tlp);
        col.addView(foot, cTop(cGap(10)));

        refreshConsole();
    }

    /** v1.19.7：新版主控台里「分组标题 → 它管着哪些行」（整组被隐藏时标题一并收掉）。 */
    private static final String[][] SIMPLE_GROUPS = {
        {"group.system", "env", "browser", "perm", "plugins", "log", "theme"},
        {"group.diagnose", "selfcheck"},
        // v1.21（用户要求）：会话两行移到最后 —— 它们不是日常高频操作，
        // 原来排在最上面会挡住「启动引擎 / 权限 / 日志」这些常用入口。
        {"group.sessions", "sessionadmin", "sessionheal"},
    };

    /**
     * v1.19.7（新版骨架吃主题布局）：把「条目表」按主题的 `layout.hidden` / `layout.cardOrder`
     * 过滤排序后挂上主控台。
     *
     * 为什么需要它：v1.19.6 起主控台缺省走新版骨架，而新版骨架是**硬编码**的 ——
     * `layout.cardOrder` / `layout.hidden` / `actions` 三层在新版下**静默失效**
     * （`conCardsOrder()` 只在 classic 分支被调；`cardActions()` 只由 `conCardView("actions")` 调；
     *  `renderConsoleSimple()` 里对主题对象的唯一引用是一句 `conThemeDialog()`）。
     *
     * · **不可隐藏**：`extract`（新版里它是主操作块，解压/启动引擎/重启/停止全在里面）、
     *   `env` / `theme` / `selfcheck`（KEEP_CARDS：重新解压、换主题、自修复的唯一入口）。
     * · **分组标题自动收**：某一组里的行全被隐藏时，标题自己也不再出现（不留孤立小标题）。
     * · **顺序语义与 classic 的 `conCardsOrder()` 完全一致**：`cardOrder` 里列到的按它排，
     *   没列到的按内置默认顺序接在后面 —— 老主题一个字都不用改。
     */
    private void emitConsoleItems(LinearLayout col, java.util.LinkedHashMap<String, View> items) {
        ConsoleTheme ct = conTheme;
        java.util.List<String> hidden = new java.util.ArrayList<String>();
        if (ct != null) hidden.addAll(ct.hiddenCards);

        for (int g = 0; g < SIMPLE_GROUPS.length; g++) {
            boolean anyVisible = false;
            for (int k = 1; k < SIMPLE_GROUPS[g].length; k++) {
                if (!hidden.contains(SIMPLE_GROUPS[g][k]) && items.containsKey(SIMPLE_GROUPS[g][k])) anyVisible = true;
            }
            if (!anyVisible) hidden.add(SIMPLE_GROUPS[g][0]);   // 整组都没了 → 标题也别留
        }
        hidden.remove("extract");   // 主操作块不给藏（藏了就没有启动引擎的入口）

        java.util.List<String> order = new java.util.ArrayList<String>();
        if (ct != null && ct.cardOrder != null) order.addAll(ct.cardOrder);
        for (String id : items.keySet()) if (!order.contains(id)) order.add(id);

        String prev = null;
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            if (hidden.contains(id)) continue;
            View v = items.get(id);
            if (v == null) continue;
            boolean isGrp = id.startsWith("group.");
            int top;
            if (prev == null) top = isGrp ? cGap(20) : cGap(12);
            else if (isGrp) top = cGap(20);
            else top = prev.startsWith("group.") ? 0 : cGap(12);
            col.addView(v, cTop(top));
            prev = id;
        }
    }

    /** 分组小标题（方案 B 的三组：会话 / 系统 / 诊断）。 */
    private View cGrpTitle(String s) {
        return cText(s, 11f, cSub(), true);
    }

    // ==================== v1.19.6：细页面统一版式的构件 ====================
    //
    // 背景：主控台按方案 B 重做后，细页面（权限 / 插件 / 日志 / 主题 / 内核自检）还是
    // 老的"行 + 分隔线"堆法，跟主页面不是一套视觉。用户 2026-10-05：
    // 「只重置了主页面 那些细页面都没有重做」。
    // 统一版式 = 返回行 + 一句说明 + 分组小标题 + 卡片块（块内是"标题 / 副标题 / 右列短状态"的行）
    //           + 危险操作单独放底部（离手远）。

    /** 主控台「运行环境」那一行的副标题（refreshConsole 里更新，跟状态块同源）。 */
    private TextView conEnvState = null;

    /** 卡片块：圆角 + 卡片底色 + 与主控台状态块同一套内边距。 */
    private LinearLayout cCardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(cShape(cCard(), cLine(), 1, 12));
        box.setPadding(dp(15), dp(4), dp(15), dp(4));
        return box;
    }

    /** 卡片块内行与行之间的极淡分隔线（v1.19.6：与权限页那条同一套视觉）。 */
    private View cCardSep() {
        View sep = new View(this);
        sep.setBackgroundColor(cLine());
        sep.setAlpha(0.5f);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return sep;
    }

    /**
     * 行式列表的标题只有一行位置：过长就压成一行并截断。
     * 会话标题来自用户的第一句话，真机上见过整段贴进来的（不截断会把一行撑成十几行）。
     */
    private String cCut(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= max ? one : (one.substring(0, max) + "…");
    }

    /** 卡片块里的一行：标题 + 副标题 + 右列短状态（可选 ›），整行可点。 */
    private View cCardRow(String title, String sub, String right, boolean chevron, View.OnClickListener click) {
        return cCardRow(title, sub, right, cSub(), chevron, click);
    }

    /** 同上，但右列状态可指定颜色（已授权=绿 / 未授权=红）。 */
    private View cCardRow(String title, String sub, String right, int rightColor, boolean chevron,
                          View.OnClickListener click) {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(cText(title, 13.5f, cText(), false));
        if (sub != null && sub.length() > 0) left.addView(cText(sub, 11f, cSub(), false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null && right.length() > 0) row.addView(cText(right, 11.5f, rightColor, false));
        if (chevron) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(6);
            row.addView(cText("›", 14f, cSub(), false), clp);
        }
        if (click != null) row.setOnClickListener(click);
        return row;
    }

    /** 卡片块里的一行：右侧放一个控件（开关 / 小按钮），不做整行点击。 */
    private View cCardRowWith(String title, String sub, View right) {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(cText(title, 13.5f, cText(), false));
        if (sub != null && sub.length() > 0) left.addView(cText(sub, 11f, cSub(), false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);
        row.addView(right, rlp);
        return row;
    }

    /** 说明段落（11sp 次要色 + 行距），细页面统一用它写"这一页是干什么的"。 */
    private View cNote(String s) {
        TextView tv = cText(s, 11f, cSub(), false);
        tv.setLineSpacing(dp(2), 1f);
        return tv;
    }

    /**
     * 主控台「运行环境」入口（常驻）。
     * 为什么必须是常驻：见 renderConsoleSimple 里的注释 —— 解压完成后主按钮变成「启动引擎」，
     * 没有这一行就再也点不到「解压 / 重新解压 / 校验」了。
     */
    private View cNavRowEnv() {
        conEnvState = cText("", 11f, cSub(), false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(cText(t("title.grpEnv", "运行环境"), 14f, cText(), false));
        left.addView(conEnvState);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(15), 0, dp(15));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cText("›", 14f, cSub(), false));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conEnvDialog(); }
        });
        return row;
    }

    /**
     * 「运行环境」弹窗：解压 / 重新解压 / 校验文件 / 看详情。
     * 全部复用既有链路（conExtractClick 自己在"已解压"时会转成 conReExtract）；
     * 这里一个字节都不碰用户数据 —— 解压只覆盖 payload 里有的文件。
     */
    private void conEnvDialog() {
        final boolean ready = conFilesReady();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cNote(ready
                ? "运行环境已就绪。引擎起不来（升级后报 import 错、混装树）时先点「重新解压」——"
                  + "它只覆盖内核自己的文件，不动你的会话、插件与配置。"
                : "还没解压。首次使用必须先解压运行环境（约 2 分钟），之后才能启动引擎。"));
        box.addView(cText(conExtractMetaText(ready), 12f, cText(), false), cTop(cGap(10)));

        Button run = cButton(ready ? t("btn.extract.rerun", "重新解压") : t("btn.extract.run", "解压文件"), true);
        run.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDialogOverlay(); conExtractClick(); }
        });
        box.addView(run, cTop(cGap(14)));

        Button verify = cButton(t("btn.extract.verify", "校验文件"), false);
        verify.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDialogOverlay(); conVerifyFiles(); }
        });
        box.addView(verify, cTop(cGap(8)));

        // v1.21（用户反馈）：原来这里还有个「看详情」按钮 —— 它只是把状态块里那张
        // "布局标记 / 插件与补丁面"的小卡片展开，内容与校验结论重复，属于无效入口，已移除。
        conDialogView(t("title.grpEnv", "运行环境"), box, "关闭", null, null);
    }


    /** 精简版那个主按钮按下去干什么：看当前状态决定（解压 / 启动 / 打开界面）。 */
    private void conSimplePrimaryAction() {
        boolean ready = conFilesReady();
        if (!ready) { conExtractClick(); return; }
        if (conEngineRunning()) { enterMainUi(); return; }
        conEngineClick();
    }

    /** 日志行的一行摘要（精简版用；格式与卡片版保持一致口径）。 */
    private String conLogLine() {
        try {
            File f = conLogFile();
            long kb = f.exists() ? Math.max(1, f.length() / 1024) : 0;
            String when = f.exists()
                    ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(f.lastModified()))
                    : "";
            return "dsh-web.log · " + kb + " KB" + (when.length() > 0 ? " · " + when : "");
        } catch (Throwable t) { return "dsh-web.log"; }
    }

    /**
     * 救援入口（精简版把它收进一个弹窗，避免占主界面；能力一项不少）。
     * 这是本项目的"不可移除面"：安全模式 / 导出 / 导入一个都不能少。
     */
    private void conShowRescueDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cText(t("card.rescueDesc", "引擎起不来时，用安全模式跳过用户层启动（不丢数据）；也可以随时导出全部数据做备份。"),
                11f, cSub(), false));
        Button safe = cButton("安全模式启动", false);
        safe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDialogOverlay(); conSafeMode(); }
        });
        box.addView(safe, cTop(cGap(12)));
        Button exp = cButton("导出全部数据", false);
        exp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDialogOverlay(); conBackupExport(); }
        });
        box.addView(exp, cTop(cGap(8)));
        Button imp = cButton("从备份导入还原", false);
        imp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDialogOverlay(); conBackupImport(); }
        });
        box.addView(imp, cTop(cGap(8)));
        conDialogView("救援", box, "关闭", null, null);
    }

    private void renderConsoleMain() {
        // v1.19.6：只要不走精简骨架，就必须把 conRenderedSimple 复位。
        // 它控制 refreshConsole() 里「解压状态行 / 引擎状态行」的可见性切换，
        // 只置 true 不复位的话，主题热切到 classic（或 layout.pages）时会把卡片版的
        // 状态行整行藏掉 —— 真机上表现为「解压卡片只剩一个按钮」。
        conRenderedSimple = false;
        ConsoleTheme ct0 = conTheme;
        if (ct0 != null && ct0.pages != null && !ct0.pages.isEmpty()) {
            renderConsolePages(ct0);        // v1.17.8：layout.pages 整页自定义（优先级最高，不受 style 影响）
            return;
        }
        // v1.19.x：风格分流 —— simple（缺省）走精简骨架；classic 走原来的卡片平铺（代码未改）
        if (!"classic".equals(consoleUiStyle())) {
            renderConsoleSimple();
            return;
        }
        LinearLayout col = consoleBody;
        // ---- 表头（固定，不参与 layout.order）----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        View brandLogo = conThemeLogoView(16);   // v1.17.4：主题 logo（没配置则不加视图）
        if (brandLogo != null) top.addView(brandLogo);
        top.addView(conBrandView(),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(cText(conVersionLabel(), 10f, cSub(), false));
        col.addView(top);

        // ---- 卡片 / 导航行：按 layout.order（先减去 hidden）依次渲染（v1.17.5 第 4 步）----
        String[] order = conCardsOrder();
        for (int i = 0; i < order.length; i++) {
            View v = conCardView(order[i]);
            if (v != null) col.addView(v);
        }

        // ---- 页脚（固定：状态行 + 说明 + 壳的深浅色设置）----
        conFoot = cText(t("status.ready", "就绪"), 11f, cSub(), false);
        col.addView(conFoot, cTop(cGap(14)));
        col.addView(cText(t("desc.footer", "换内核版本 / 覆盖安装后需要重新解压；平时只用到「启动引擎」。"),
                11f, cSub(), false), cTop(cGap(6)));
        col.addView(conShellThemeRow());
        refreshConsole();
    }

    // ---------- v1.17.5 布局与文案（第 4 步：内置卡片的显隐·顺序·文案） ----------

    /** 间距：compact=true 时减半（卡片/分隔线的上下边距与控制台留白都走这里）。 */
    private int cGap(int v) {
        ConsoleTheme ct = conTheme;
        return (ct != null && ct.compact) ? Math.max(1, Math.round(v * 0.5f)) : v;
    }

    /**
     * 文案覆盖：text 表里没给（或给了空串）就用内置中文。
     * 键表见 release-src/console-theme/THEME-PACK-SPEC.md §3（card.* / btn.* / title.* / status.* / desc.*）。
     */
    private String t(String key, String def) {
        ConsoleTheme ct = conTheme;
        if (ct == null) return def;
        String v = ct.text(key);
        return (v == null || v.length() == 0) ? def : v;
    }

    /**
     * 生效的卡片顺序：layout.order → 没列出的内置卡片按内置顺序补在后面；
     * 再按 layout.hidden 过滤（rescue / theme 强制保留 —— 它们是这个 App 的救援面）。
     */
    private String[] conCardsOrder() {
        String[] def = {"extract", "engine", "rescue", "selfcheck", "actions", "browser", "perm", "plugins", "log", "theme", "update"};
        ConsoleTheme ct = conTheme;
        java.util.List<String> base = new java.util.ArrayList<String>();
        if (ct != null && ct.cardOrder != null) {
            base.addAll(ct.cardOrder);
            for (int i = 0; i < def.length; i++) if (!base.contains(def[i])) base.add(def[i]);
        } else {
            for (int i = 0; i < def.length; i++) base.add(def[i]);
        }
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < base.size(); i++) {
            String id = base.get(i);
            if (ct != null && ct.hiddenCards.contains(id)
                    && !"rescue".equals(id) && !"theme".equals(id)) continue;
            if (!out.contains(id)) out.add(id);
        }
        return out.toArray(new String[out.size()]);
    }

    /** 一张卡片 / 一行导航的生成入口（卡片 id 表见 THEME-PACK-SPEC.md §4）。 */
    private View conCardView(String id) {
        if ("extract".equals(id)) return cardExtract();
        if ("engine".equals(id)) return cardEngine();
        if ("rescue".equals(id)) return cardRescue();
        if ("actions".equals(id)) return cardActions();
        if ("perm".equals(id)) return cardPerm();
        if ("plugins".equals(id)) return cardPlugins();
        if ("log".equals(id)) return cardLog();
        if ("update".equals(id)) return cardUpdate();
        if ("theme".equals(id)) return cardTheme();   // v1.17.6：主题页入口（不可隐藏）
        if ("selfcheck".equals(id)) return cardSelfCheck();   // v1.19.x：内核自检（自修复功能）
        if ("browser".equals(id)) return cardBrowser();       // v1.19.6 · 阶段 C：AI 浏览器
        return null;
    }

    /** ① 解压文件（状态 + 进度条 + 按钮 + 详情折叠）。 */
    private View cardExtract() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(16)));
        conExState = cText("", 15f, cText(), false);
        box.addView(conExState, cTop(cGap(16)));
        conExMeta = cText("", 11f, cSub(), false);
        conExMeta.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!conFilesReady()) return;
                consoleDetailOpen = !consoleDetailOpen;
                refreshConsole();
            }
        });
        box.addView(conExMeta, cTop(cGap(7)));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(cShape(cTrack(), 0, 0, 2));
        conFill = new View(this);
        conFill.setBackgroundColor(cAccent());
        conSpacer = new View(this);
        bar.addView(conFill, new LinearLayout.LayoutParams(0, dp(4), 1f));
        bar.addView(conSpacer, new LinearLayout.LayoutParams(0, dp(4), 0f));
        bar.setVisibility(View.GONE);
        conBar = bar;
        box.addView(bar, cTop(cGap(10)));
        conDetailBox = conExtractDetail();
        box.addView(conDetailBox);
        conExBtn = cButton(t("btn.extract.run", "解压文件"), true);
        conExBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conExtractClick(); }
        });
        box.addView(conExBtn, cTop(cGap(12)));
        return box;
    }

    /** ② 启动引擎（状态 + 启动/重启/停止）。 */
    private View cardEngine() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(18)));
        conEnState = cText("", 15f, cText(), false);
        box.addView(conEnState, cTop(cGap(16)));
        conEnMeta = cText("", 11f, cSub(), false);
        box.addView(conEnMeta, cTop(cGap(7)));
        LinearLayout enActs = new LinearLayout(this);
        enActs.setOrientation(LinearLayout.HORIZONTAL);
        conEnBtn = cButton(t("btn.engine.start", "启动引擎"), true);
        conEnBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conEngineClick(); }
        });
        enActs.addView(conEnBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        conEnRestart = cButton(t("btn.engine.restart", "重启"), false);
        conEnRestart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conRestartEngine(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);
        enActs.addView(conEnRestart, rlp);
        conEnStop = cButton(t("btn.engine.stop", "停止"), false);
        conEnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conStopEngine(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        enActs.addView(conEnStop, slp);
        box.addView(enActs, cTop(cGap(12)));
        return box;
    }

    /**
     * ②·5 救援（安全模式 / 备份）—— 「不可隐藏」（这个 App 的救援面）。
     * 解决的真实痛点：装的插件把 profile 的 patch 文件写成非法 YAML → 引擎直接拒启，
     * 而「修」又得先让引擎跑起来 → 形成死结，过去只能清数据。
     */
    private View cardRescue() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(18)));
        boolean safe = safeModeActive();
        int fails = bootFailures();
        box.addView(cText(safe ? t("status.rescue.safe", "安全模式：已开启") : t("card.rescue", "救援"),
                15f, cText(), false), cTop(cGap(16)));
        String rescueDesc;
        if (safe) {
            rescueDesc = "已旁置 profile 的用户层，用出厂配置启动——会话/凭证/设置都还在。"
                    + "修好之后再「退出安全模式」把用户层还回去。";
        } else if (fails >= BOOT_FAIL_HINT_AT) {
            rescueDesc = "连续 " + fails + " 次启动失败。很可能是装的插件把配置文件写坏了——"
                    + "用「安全模式启动」跳过用户层，不丢任何数据。";
        } else {
            rescueDesc = t("desc.rescue", "引擎起不来时，用安全模式跳过用户层启动（不丢数据）；"
                    + "也可以随时导出全部数据做备份。");
        }
        box.addView(cText(rescueDesc, 11f, fails >= BOOT_FAIL_HINT_AT && !safe ? cRed() : cSub(), false),
                cTop(cGap(7)));
        LinearLayout rsActs = new LinearLayout(this);
        rsActs.setOrientation(LinearLayout.HORIZONTAL);
        Button safeBtn = cButton(safe ? t("btn.rescue.exitSafe", "退出安全模式") : t("btn.rescue.safe", "安全模式启动"), false);
        safeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (safeModeActive()) conExitSafeMode(); else conSafeMode(); }
        });
        rsActs.addView(safeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button expBtn = cButton(t("btn.rescue.export", "导出全部数据"), true);
        expBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conBackupExport(); }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        elp.leftMargin = dp(8);
        rsActs.addView(expBtn, elp);
        box.addView(rsActs, cTop(cGap(12)));
        Button impBtn = cButton(t("btn.rescue.import", "从备份导入还原"), false);
        impBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conBackupImport(); }
        });
        box.addView(impBtn, cTop(cGap(8)));
        return box;
    }

    /**
     * ⑦ 自定义按钮（`actions[]`，v1.17.7 第 3 轮）。
     * 没配 actions 就返回 null（不占位置）；「可执行动作（shell/http/intent/prompt）用红描边」标记，
     * 点它们会先弹确认框，把命令/网址/意图/提示词原文摆出来再让你决定。
     */
    private View cardActions() {
        ConsoleTheme ct = conTheme;
        if (ct == null || ct.actions.isEmpty()) return null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(18)));
        box.addView(cText(t("card.actions", "自定义按钮"), 15f, cText(), false), cTop(cGap(16)));
        String desc = t("desc.actions", "来自主题配置；带红框的按钮执行前会先让你确认。");
        box.addView(cText(desc, 11f, cSub(), false), cTop(cGap(7)));
        int i = 0;
        while (i < ct.actions.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                if (i + k >= ct.actions.size()) {
                    View filler = new View(this);      // 奇数个时占位，保持半宽对齐
                    row.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));
                    continue;
                }
                final ConsoleTheme.Act act = ct.actions.get(i + k);
                Button b = cButton(act.label, false);
                if (act.executable) {
                    b.setTextColor(cRed());
                    b.setBackground(cShape(0x00000000, cRed(), 1, 8));
                }
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { conRunAction(act); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (k == 1) lp.leftMargin = dp(8);
                row.addView(b, lp);
            }
            box.addView(row, cTop(cGap(10)));
            i += 2;
        }
        return box;
    }

    /** 点了一个自定义按钮：可执行动作先确认（把它要干的事原样摆出来）。 */
    private void conRunAction(final ConsoleTheme.Act a) {
        if (a == null) return;
        if (a.confirm && a.executable) {
            conDialog("执行这个动作？",
                    "「" + a.label + "」\n\n" + conActionDetail(a)
                            + "\n\n它来自主题配置（可能是 AI 生成的），确认内容是你认识的就执行。",
                    "执行", new Runnable() { @Override public void run() { conRunActionNow(a); } }, "取消");
            return;
        }
        conRunActionNow(a);
    }

    /** 确认框里那段"它到底要干什么"。 */
    private String conActionDetail(ConsoleTheme.Act a) {
        if ("shell".equals(a.type)) {
            return "类型：执行命令" + (a.privileged ? "（特权：Shizuku / root）" : "（App 身份）")
                    + "\n命令：" + a.cmd;
        }
        if ("url".equals(a.type)) return "类型：打开网址\n" + a.url;
        if ("http".equals(a.type)) return "类型：HTTP " + (a.method == null ? "GET" : a.method) + "\n" + a.url;
        if ("intent".equals(a.type)) {
            return "类型：发 Intent\n" + (a.action == null ? "" : a.action)
                    + (a.data == null ? "" : "\n" + a.data)
                    + (a.pkg == null ? "" : "\n包名：" + a.pkg);
        }
        if ("prompt".equals(a.type)) return "类型：把提示词发给 DSH 的 AI\n" + a.text;
        return "类型：" + a.type;
    }

    /** 真正执行（可执行动作已经在上一步确认过）。 */
    private void conRunActionNow(final ConsoleTheme.Act a) {
        try {
            String ty = a.type;
            if ("url".equals(ty)) { conOpenUrl(a.url); return; }
            if ("clipboard".equals(ty)) {
                String s = "token".equals(a.copy) ? conFindApiKey() : a.text;
                if (s == null || s.length() == 0) {
                    conToast("没找到可复制的内容" + ("token".equals(a.copy) ? "（API Key 要在 DSH 界面里设置过）" : ""));
                    return;
                }
                conThemeCopy(s, "已复制到剪贴板");
                return;
            }
            if ("toast".equals(ty)) { conToast(a.text == null || a.text.length() == 0 ? a.label : a.text); return; }
            if ("settings".equals(ty)) { conOpenAppSettings(); return; }
            if ("shell".equals(ty)) { conRunShellAction(a); return; }
            if ("engine.start".equals(ty) || "engine.openUi".equals(ty)) { conEngineClick(); return; }
            if ("engine.restart".equals(ty)) { conRestartEngine(); return; }
            if ("engine.stop".equals(ty)) { conStopEngine(); return; }
            if ("extract.run".equals(ty)) { conExtractClick(); return; }
            if ("extract.verify".equals(ty)) {
                conToast(conFilesReady() ? "运行环境与内核树已就绪 ✓" : "还没解压（缺 " + conMissingKey() + "）");
                return;
            }
            if ("perm.open".equals(ty)) { consolePage = 1; renderConsole(); return; }
            if ("log.view".equals(ty)) { conViewLog(); return; }
            if ("log.share".equals(ty)) { conShareLog(); return; }
            if ("log.clear".equals(ty)) { conClearLog(); return; }
            if ("theme.reload".equals(ty)) { conThemeReloadNow(); return; }
            if ("theme.export".equals(ty)) { conThemeExport(); return; }
            if ("theme.import".equals(ty)) { conThemeImport(); return; }
            if ("theme.reset".equals(ty)) { conThemeReset(); return; }
            // http / intent / prompt：本版不动手，如实说明（不假装执行）
            if ("http".equals(ty)) { conToast("HTTP 动作还没接（要等第 3 轮余下部分）"); return; }
            if ("intent".equals(ty)) { conToast("Intent 动作还没接（要等第 3 轮余下部分）"); return; }
            if ("prompt".equals(ty)) {
                conThemeCopy(a.text, "已复制提示词：去 DSH 对话里粘贴即可（自动发送要等内核 RPC 那一步）");
                return;
            }
            conToast("未知动作：" + ty);
        } catch (Throwable t) {
            Log.e(TAG, "action run", t);
            conToast("动作执行失败：" + t.getMessage());
        }
    }

    private void conOpenUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { conToast("打不开网址：" + t.getMessage()); }
    }

    private void conOpenAppSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { conToast("打不开设置页：" + t.getMessage()); }
    }

    /** 尽力而为：从引擎配置里把 API Key 抠出来（找不到就返回 null，由调用方如实提示）。 */
    private String conFindApiKey() {
        String[] files = {"profiles/web/cordis.yml", "settings.yaml", "settings.yaml.imported"};
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "(?i)(api[_-]?key|apikey|apiKey)\\s*[:=]\\s*[\"']?([A-Za-z0-9_\\-\\.]{8,})");
        for (String rel : files) {
            try {
                File f = new File(new File(payloadDir(), "dshhome"), rel);
                if (!f.exists()) continue;
                String txt = ConsoleTheme.readText(f);
                java.util.regex.Matcher m = pat.matcher(txt);
                if (m.find()) return m.group(2);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** shell 动作：特权走 Shizuku（复用既有通道），否则用 App 身份跑；结果弹窗展示。 */
    private void conRunShellAction(final ConsoleTheme.Act a) {
        conToast("正在执行…");
        new Thread(new Runnable() { @Override public void run() {
            final String result;
            if (a.privileged) {
                result = shellViaShizuku(a.cmd, 30000);      // 返回 JSON（stdout/stderr/exit_code）
            } else {
                result = conRunShellLocal(a.cmd);
            }
            ui.post(new Runnable() { @Override public void run() { conShowShellResult(a, result); } });
        }}, "action-shell").start();
    }

    /** App 身份执行（无需 Shizuku/root，能力有限，但 echo/getprop/ls 这类够用）。 */
    private String conRunShellLocal(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 30000L;
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int r = in.read(buf);
                    if (r > 0) sb.append(new String(buf, 0, r, "UTF-8"));
                } else if (!p.isAlive()) {
                    break;
                } else {
                    try { Thread.sleep(40); } catch (InterruptedException ignored) {}
                }
            }
            int code = -1;
            try { code = p.exitValue(); } catch (Throwable ignored) {}
            if (code == -1) { try { p.destroy(); } catch (Throwable ignored) {} }
            return "退出码 " + code + "\n" + sb;
        } catch (Throwable t) {
            return "执行失败：" + t.getMessage();
        }
    }

    private void conShowShellResult(ConsoleTheme.Act a, String result) {
        String body = result == null ? "（无输出）" : result;
        if (body.length() > 4000) body = body.substring(0, 4000) + "…";
        TextView tv = cText("「" + a.label + "」\n命令：" + a.cmd + "\n\n" + body, 11.5f, cSub(), false);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(cShape(conDark() ? 0xFF0F1524 : 0xFFF2F4F8, 0, 0, 6));
        sv.setPadding(dp(12), dp(12), dp(12), dp(12));
        sv.addView(tv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        conDialogView("动作执行结果", sv, null, null, "关闭");
    }

    // ==================== v1.17.8 声明式控件树（layout.pages） ====================

    /** layout.pages 的首页：整页由主题自己拼（表头保留，末尾补救援面与额外页入口）。 */
    private void renderConsolePages(ConsoleTheme ct) {
        LinearLayout col = consoleBody;
        ConsoleTheme.Page home = ct.pages.get(0);

        // 表头只在「树里没放 brand.label」 时自动补 —— 放了就按主题自己的排法（否则会出现两条表头，
        // 真机踩过）。补的这一份同时保证"长按品牌字 → 以默认样式打开"这个逃生口始终存在。
        if (!ConsoleTheme.nodeHasBuiltin(home.children, "brand.label")) {
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.BOTTOM);
            View brandLogo = conThemeLogoView(16);
            if (brandLogo != null) top.addView(brandLogo);
            top.addView(conBrandView(),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(cText(conVersionLabel(), 10f, cSub(), false));
            col.addView(top);
        }
        conRenderNodes(col, home.children);

        // §7.4 唯一硬约束：救援面不可移除 —— 树里没写就自动补在末尾
        boolean hasRescue = ConsoleTheme.nodeHasBuiltin(home.children, "rescue.buttons")
                || ConsoleTheme.nodeHasBuiltin(home.children, "rescue.desc");
        if (!hasRescue) col.addView(cardRescue());
        if (!ConsoleTheme.nodeHasBuiltin(home.children, "theme.card")) col.addView(conThemeCardView());

        // 额外页的入口行（pages[1] → 第 5 页，以此类推）
        for (int i = 1; i < ct.pages.size(); i++) {
            ConsoleTheme.Page p = ct.pages.get(i);
            col.addView(cNavRow(p.title != null ? p.title : p.id,
                    t("desc.customPage", "自定义页面"), null, 4 + i));
            col.addView(cSep(0));
        }

        conFoot = cText(t("status.ready", "就绪"), 11f, cSub(), false);
        col.addView(conFoot, cTop(cGap(14)));
        col.addView(conShellThemeRow());
        refreshConsole();
    }

    /** 额外自定义页（consolePage ≥ 5）。 */
    private void renderConsoleCustomPage(ConsoleTheme ct, int idx) {
        LinearLayout col = consoleBody;
        ConsoleTheme.Page p = ct.pages.get(idx);

        col.addView(conBackRow(p.title != null ? p.title : p.id));
        conRenderNodes(col, p.children);
        if (!ConsoleTheme.nodeHasBuiltin(p.children, "theme.card")) col.addView(conThemeCardView());
    }

    /** 诊断用：下一帧打印 body 的每个子视图（类名 / 可见性 / 实测尺寸 / top / 是否挂在窗口上）。 */
    private void conDumpBody(final LinearLayout body, final String tag) {
        if (body == null) return;
        body.post(new Runnable() { @Override public void run() {
            try {
                Log.i(TAG, "dump[" + tag + "] body=" + System.identityHashCode(body)
                        + " attached=" + (body.getParent() != null)
                        + " h=" + body.getHeight() + " w=" + body.getWidth()
                        + " children=" + body.getChildCount());
                for (int i = 0; i < body.getChildCount(); i++) {
                    View c = body.getChildAt(i);
                    Log.i(TAG, "  [" + tag + "] child[" + i + "] " + c.getClass().getSimpleName()
                            + " vis=" + c.getVisibility() + " h=" + c.getHeight() + " w=" + c.getWidth()
                            + " top=" + c.getTop() + " attached=" + (c.getParent() != null));
                }
            } catch (Throwable t) { Log.w(TAG, "dump fail", t); }
        }});
    }

    /** 画一组节点（把每个节点做成 View 加进 parent）。 */
    private void conRenderNodes(LinearLayout parent, java.util.List<ConsoleTheme.Node> nodes) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.size(); i++) {
            ConsoleTheme.Node n = nodes.get(i);
            View v;
            try {
                v = conRenderNode(n);
            } catch (Throwable t) {
                // 单个节点出错不该连累后面的节点（真机排查过"只画出第一个节点"这类问题）
                Log.e(TAG, "render node[" + i + "] type=" + (n == null ? "null" : n.type) + " 失败", t);
                continue;
            }
            if (v == null) continue;
            LinearLayout.LayoutParams lp;
            // ⚠ spacer / divider 是"纯 View"：给它 WRAP_CONTENT，在 AT_MOST 下会被撑满整个可用高度
            //   （View.getDefaultSize 的行为），而 fillViewport 让内容短的页面正好走 AT_MOST —— 真机踩过：
            //   12dp 的缝吃了 1376px，后面的按钮被挤成 0 高。这里一律给「显式高度」。
            if ("spacer".equals(n.type)) {
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(n.size == null ? 12 : n.size.intValue()));
                parent.addView(v, lp);
                continue;
            }
            if ("divider".equals(n.type)) {
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(1, dp(1)));
                parent.addView(v, lp);
                continue;
            }
            if (n.weight != null && n.weight.floatValue() > 0f) {
                lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                        n.weight.floatValue());
            } else if (n.width != null) {
                lp = new LinearLayout.LayoutParams(dp(n.width.intValue()),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            } else {
                // 竖排（column / 根容器）默认占满宽；「横排（row）默认按内容宽」 ——
                // 否则一行里没写 weight 的节点会抢满整行，把带 weight 的兄弟挤成 0 宽（真机踩过）。
                lp = new LinearLayout.LayoutParams(
                        parent.getOrientation() == LinearLayout.HORIZONTAL
                                ? ViewGroup.LayoutParams.WRAP_CONTENT
                                : ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (n.align != null && "center".equals(n.align)) lp.gravity = Gravity.CENTER_HORIZONTAL;
            if (n.align != null && "right".equals(n.align)) lp.gravity = Gravity.END;
            parent.addView(v, lp);
        }
    }

    /** 画一个节点（返回 null = 不显示）。 */
    private View conRenderNode(ConsoleTheme.Node n) {
        if (n == null) return null;
        if (n.visible != null && !n.visible.booleanValue()) return null;
        String ty = n.type;
        if ("text".equals(ty)) {
            TextView tv = cText(n.text == null ? "" : n.text,
                    n.size == null ? 12f : n.size.floatValue(), conNodeColor(n.color, cSub()), false);
            if ("center".equals(n.align)) tv.setGravity(Gravity.CENTER);
            else if ("right".equals(n.align)) tv.setGravity(Gravity.RIGHT);
            return tv;
        }
        if ("button".equals(ty)) {
            Button b = cButton(n.text == null || n.text.length() == 0 ? "按钮" : n.text,
                    "primary".equals(n.style));
            if (n.action != null) {
                final ConsoleTheme.Act a = n.action;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { conRunAction(a); }
                });
            }
            return b;
        }
        if ("row".equals(ty) || "column".equals(ty)) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation("row".equals(ty) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            if ("row".equals(ty)) box.setGravity(Gravity.CENTER_VERTICAL);
            conRenderNodes(box, n.children);
            return box;
        }
        if ("card".equals(ty)) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(cShape(cCard(), 0, 0, conRadius(14)));
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            conRenderNodes(box, n.children);
            return box;
        }
        if ("spacer".equals(ty)) {
            View v = new View(this);
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(n.size == null ? 12 : n.size.intValue())));
            return v;
        }
        if ("divider".equals(ty)) {
            View v = new View(this);
            v.setBackgroundColor(cLine());
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
            return v;
        }
        if ("image".equals(ty)) {
            // 规范里 image 节点没有单独的 src 字段 —— 用 text 当"图片文件名 / /sdcard/… 绝对路径"
            String ref = n.text;
            android.graphics.Bitmap bm = (ref == null || ref.length() == 0) ? null : conDecodeFile(conImageFile(ref), 1024);
            if (bm == null) return null;
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (n.size != null) {
                iv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(n.size.intValue())));
            }
            return iv;
        }
        if ("builtin".equals(ty)) return conBuiltinView(n.id);
        return null;
    }

    /** 节点颜色：语义色名（text/sub/accent/green/red）或 #rgb/#rrggbb/#aarrggbb。 */
    private int conNodeColor(String spec, int def) {
        if (spec == null) return def;
        if ("text".equals(spec)) return cText();
        if ("sub".equals(spec)) return cSub();
        if ("accent".equals(spec)) return cAccent();
        if ("green".equals(spec)) return cGreen();
        if ("red".equals(spec)) return cRed();
        try {
            String v = spec;
            if (v.length() == 4) {      // #rgb → #rrggbb（Color.parseColor 不认 3 位简写）
                v = new StringBuilder("#")
                        .append(v.charAt(1)).append(v.charAt(1))
                        .append(v.charAt(2)).append(v.charAt(2))
                        .append(v.charAt(3)).append(v.charAt(3)).toString();
            }
            return Color.parseColor(v);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 内置积木（id 表见 SPEC §4）：可放在控件树的「任意位置、任意顺序、可重复」。
     * ⚠ perm.list / plugin.list 目前退化成"打开这一页"的按钮（页内清单还没拆成积木），如实标注。
     */
    private View conBuiltinView(String id) {
        if ("brand.label".equals(id)) return conBrandView();
        if ("version.label".equals(id)) return cText(conVersionLabel(), 10f, cSub(), false);
        if ("extract.block".equals(id)) return cardExtract();
        if ("extract.status".equals(id)) return conExtractStatusView();
        if ("extract.detail".equals(id)) { conDetailBox = conExtractDetail(); return conDetailBox; }
        if ("engine.status".equals(id)) return conEngineStatusView();
        if ("engine.buttons".equals(id)) return conEngineButtonsView();
        if ("rescue.desc".equals(id)) return conRescueDescView();
        if ("rescue.buttons".equals(id)) return conRescueButtonsView();
        if ("theme.card".equals(id)) return conThemeCardView();
        if ("perm.summary".equals(id)) {
            return cText(t("card.perm", "授予权限") + " · " + conPermSummary(), 12.5f, cSub(), false);
        }
        if ("plugin.summary".equals(id)) {
            return cText(t("card.plugins", "插件") + " · " + conPlugSummary(), 12.5f, cSub(), false);
        }
        if ("log.actions".equals(id)) return conLogActionsView();
        if ("log.view".equals(id)) return conLogPreviewView();
        if ("update.button".equals(id)) return conUpdateView();
        if ("perm.list".equals(id)) return conOpenPageButton(t("card.perm", "授予权限"), 1);
        if ("plugin.list".equals(id)) return conOpenPageButton(t("card.plugins", "插件"), 2);
        return null;
    }

    /** 品牌字：长按 = 逃生口（以默认样式打开控制台，防把自己改到点不动）。 */
    private View conBrandView() {
        TextView tv = cText(t("title.brand", "DEEPSEEK HARNESS"), 10f, cSub(), false);
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { conThemeEscapeDialog(); return true; }
        });
        return tv;
    }

    /** 逃生口：忽略主题配置（文件不删，随时可恢复）；长按品牌字或主题页都能进。 */
    private void conThemeEscapeDialog() {
        final boolean ignored = conThemeIgnored();
        conDialog("控制台主题",
                ignored ? "现在正以默认样式打开控制台（主题配置被忽略，文件没删）。\n\n要恢复主题样式吗？"
                        : "以默认样式打开控制台？\n\n这会忽略主题配置（文件不删），方便你在主题把自己改乱时找回操作入口。",
                ignored ? "恢复主题样式" : "以默认样式打开",
                new Runnable() { @Override public void run() { setConThemeIgnored(!ignored); } },
                "取消");
    }

    private boolean conThemeIgnored() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("console_theme_safe", false);
        } catch (Throwable t) { return false; }
    }

    private void setConThemeIgnored(boolean v) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("console_theme_safe", v).apply();
        } catch (Throwable ignored) {}
        conTheme = null;
        conThemeLoaded = false;
        conThemeStamp = null;
        conThemeReloadIfChanged(true);
        conBgApply();
        consolePage = 0;
        renderConsole();
        conToast(v ? "已按默认样式打开（主题配置没删，随时可恢复）" : "已恢复主题样式");
    }

    /** 退化成"打开这一页"的积木（perm.list / plugin.list）。 */
    private View conOpenPageButton(String title, final int page) {
        Button b = cButton(title + "（打开这一页）›", false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { consolePage = page; renderConsole(); }
        });
        return b;
    }

    // ---------- 内置积木的零件（与 card*() 共用同一批字段，refreshConsole 才能更新它们） ----------

    private View conExtractStatusView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        conExState = cText("", 15f, cText(), false);
        box.addView(conExState);
        conExMeta = cText("", 11f, cSub(), false);
        conExMeta.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!conFilesReady()) return;
                consoleDetailOpen = !consoleDetailOpen;
                refreshConsole();
            }
        });
        box.addView(conExMeta, cTop(cGap(7)));
        return box;
    }

    private View conEngineStatusView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        conEnState = cText("", 15f, cText(), false);
        box.addView(conEnState);
        conEnMeta = cText("", 11f, cSub(), false);
        box.addView(conEnMeta, cTop(cGap(7)));
        return box;
    }

    private View conEngineButtonsView() {
        LinearLayout enActs = new LinearLayout(this);
        enActs.setOrientation(LinearLayout.HORIZONTAL);
        conEnBtn = cButton(t("btn.engine.start", "启动引擎"), true);
        conEnBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conEngineClick(); }
        });
        enActs.addView(conEnBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        conEnRestart = cButton(t("btn.engine.restart", "重启"), false);
        conEnRestart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conRestartEngine(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);
        enActs.addView(conEnRestart, rlp);
        conEnStop = cButton(t("btn.engine.stop", "停止"), false);
        conEnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conStopEngine(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        enActs.addView(conEnStop, slp);
        return enActs;
    }

    private View conRescueDescView() {
        boolean safe = safeModeActive();
        int fails = bootFailures();
        String rescueDesc;
        if (safe) {
            rescueDesc = "已旁置 profile 的用户层，用出厂配置启动——会话/凭证/设置都还在。"
                    + "修好之后再「退出安全模式」把用户层还回去。";
        } else if (fails >= BOOT_FAIL_HINT_AT) {
            rescueDesc = "连续 " + fails + " 次启动失败。很可能是装的插件把配置文件写坏了——"
                    + "用「安全模式启动」跳过用户层，不丢任何数据。";
        } else {
            rescueDesc = t("desc.rescue", "引擎起不来时，用安全模式跳过用户层启动（不丢数据）；"
                    + "也可以随时导出全部数据做备份。");
        }
        return cText(rescueDesc, 11f, fails >= BOOT_FAIL_HINT_AT && !safe ? cRed() : cSub(), false);
    }

    private View conRescueButtonsView() {
        final boolean safe = safeModeActive();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rsActs = new LinearLayout(this);
        rsActs.setOrientation(LinearLayout.HORIZONTAL);
        Button safeBtn = cButton(safe ? t("btn.rescue.exitSafe", "退出安全模式") : t("btn.rescue.safe", "安全模式启动"), false);
        safeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (safeModeActive()) conExitSafeMode(); else conSafeMode(); }
        });
        rsActs.addView(safeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button expBtn = cButton(t("btn.rescue.export", "导出全部数据"), true);
        expBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conBackupExport(); }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        elp.leftMargin = dp(8);
        rsActs.addView(expBtn, elp);
        wrap.addView(rsActs);
        Button impBtn = cButton(t("btn.rescue.import", "从备份导入还原"), false);
        impBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conBackupImport(); }
        });
        wrap.addView(impBtn, cTop(cGap(8)));
        return wrap;
    }

    /** theme.card：主题状态 + 五个操作（和主题页同一批方法）。 */
    private View conThemeCardView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(16)));
        box.addView(cText(t("card.theme", "主题") + " · " + conThemeSummary(), 13f, cText(), false), cTop(cGap(14)));
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        Button rl = cButton(t("btn.theme.reload", "重新加载"), false);
        rl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        rl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeReloadNow(); }
        });
        r1.addView(rl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button ex = cButton(t("btn.theme.export", "导出主题包"), false);
        ex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        ex.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeExport(); }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(8);
        r1.addView(ex, lp2);
        Button im = cButton(t("btn.theme.import", "导入主题包"), false);
        im.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        im.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeImport(); }
        });
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp3.leftMargin = dp(8);
        r1.addView(im, lp3);
        box.addView(r1, cTop(cGap(10)));
        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        Button rs = cButton(t("btn.theme.reset", "恢复默认"), false);
        rs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        rs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeReset(); }
        });
        r2.addView(rs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button od = cButton(t("btn.theme.openDir", "打开主题目录"), false);
        od.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        od.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeOpenDir(); }
        });
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp4.leftMargin = dp(8);
        r2.addView(od, lp4);
        Button tp = cButton(t("btn.theme.page", "主题页"), true);
        tp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { consolePage = 4; renderConsole(); }
        });
        LinearLayout.LayoutParams lp5 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp5.leftMargin = dp(8);
        r2.addView(tp, lp5);
        box.addView(r2, cTop(cGap(8)));
        return box;
    }

    private View conLogActionsView() {
        LinearLayout logActs = new LinearLayout(this);
        logActs.setOrientation(LinearLayout.HORIZONTAL);
        Button lv = cButton(t("btn.log.view", "查看"), false);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conViewLog(); }
        });
        Button le = cButton(t("btn.log.share", "分享"), false);
        le.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        le.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conShareLog(); }
        });
        Button lc = cButton(t("btn.log.clear", "清空"), false);
        lc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conClearLog(); }
        });
        logActs.addView(lv);
        logActs.addView(le);
        logActs.addView(lc);
        return logActs;
    }

    /** log.view：日志末尾几行（只读预览）。 */
    private View conLogPreviewView() {
        try {
            File f = conLogFile();
            if (!f.exists()) return cText("还没有日志（引擎没启动过）", 11f, cSub(), false);
            String tail = conTailOf(f, 8);
            TextView tv = cText(tail, 10.5f, cSub(), false);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            return tv;
        } catch (Throwable t) {
            return cText("读日志失败：" + t.getMessage(), 11f, cSub(), false);
        }
    }

    private View conUpdateView() {
        TextView updLink = cText(t("card.update", "检查更新") + " · 当前 " + conVersionLabel(),
                12f, cSub(), false);
        updLink.setPadding(0, cGap(10), 0, cGap(4));
        updLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conToast("正在检查…"); checkForUpdate(true); }
        });
        return updLink;
    }

    /** ③ 授予权限（导航行）。 */
    private View cardPerm() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(20)));
        box.addView(cNavRow(t("card.perm", "授予权限"),
                t("desc.perm", "存储 · 通知 · 悬浮窗 · 电池 · root · Shizuku · 无障碍"), conPermSummary(), 1));
        box.addView(cSep(0));
        return box;
    }

    /** ④ 插件开关（导航行）。 */
    private View cardPlugins() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cNavRow(t("card.plugins", "插件"),
                t("desc.plugins", "关掉用不到的，省上下文"), conPlugSummary(), 2));
        box.addView(cSep(0));
        return box;
    }

    /** ⑤ 日志（查看 / 分享 / 清空 + 整行进日志页）。 */
    private View cardLog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout logActs = new LinearLayout(this);
        logActs.setOrientation(LinearLayout.HORIZONTAL);
        Button lv = cButton(t("btn.log.view", "查看"), false);
        lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conViewLog(); }
        });
        Button le = cButton(t("btn.log.share", "分享"), false);
        le.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        le.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conShareLog(); }
        });
        logActs.addView(lv);
        logActs.addView(le);
        // v1.15.1：清空入口先前只在日志子页里，主页面看不到（用户反馈"清空日志还是没有"）。
        // 这里补一个「清空」按钮，主页面一键直达；日志行本身也仍可点进日志页。
        Button lc = cButton(t("btn.log.clear", "清空"), false);
        lc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conClearLog(); }
        });
        logActs.addView(lc);
        LinearLayout logLeft = new LinearLayout(this);
        logLeft.setOrientation(LinearLayout.VERTICAL);
        logLeft.addView(cText(t("card.log", "日志"), 14f, cText(), false));
        logLeft.addView(cText(conLogSummary(), 11f, cSub(), false));
        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.setGravity(Gravity.CENTER_VERTICAL);
        logRow.setPadding(0, dp(12), 0, dp(12));
        logRow.addView(logLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        logRow.addView(logActs);
        // v1.15.1 修复：原来这一行只有「查看 / 分享」两个快捷按钮，「没有任何入口」
        // 把 consolePage 设为 3 —— 于是渲染日志页的 renderConsoleLog()（含「清空日志」）
        // 成了不可达的死代码，用户找不到清空入口。现在整行可点进入日志页，并补 › 提示。
        logRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { consolePage = 3; renderConsole(); }
        });
        logRow.addView(cText("›", 14f, cSub(), false));
        box.addView(logRow);
        box.addView(cSep(0));
        return box;
    }

    /** ⑥ 检查更新（手动入口，v1.12 起不再启动时自动检查）。 */
    private View cardUpdate() {
        LinearLayout upd = new LinearLayout(this);
        upd.setOrientation(LinearLayout.HORIZONTAL);
        upd.setGravity(Gravity.CENTER_VERTICAL);
        TextView updLink = cText(t("card.update", "检查更新"), 12f, cSub(), false);
        updLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conToast("正在检查…"); checkForUpdate(true); }
        });
        upd.addView(updLink, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        upd.addView(cText("当前 " + conVersionLabel(), 11f, cSub(), false));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, cGap(16), 0, 0);
        box.addView(upd);
        return box;
    }

    /**
     * 壳的深浅色设置行（v1.13.12；「恢复默认控制台主题」也在这个弹窗里）。
     * 注意：这是 「App 主题」（跟随系统/浅色/深色），与主题包的 appearance 是两回事。
     */
    /**
     * 页脚的「界面主题 · 模式 ›」行。
     * ⚠ 它被**完整版页脚**（renderConsoleMain）与**自定义页页脚**（renderConsolePages）使用，
     *   精简版（方案 B）已改用三个等宽按钮，但这里不能删 —— 删之前必须 grep 全部调用点。
     */
    private View conShellThemeRow() {
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setGravity(Gravity.CENTER_VERTICAL);
        themeRow.setPadding(0, dp(12), 0, dp(12));
        themeRow.addView(cText(t("btn.shellTheme", "界面主题"), 12f, cSub(), false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        themeRow.addView(cText(themeModeLabel(themeMode()) + " ›", 12f, cSub(), false));
        themeRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeDialog(); }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(themeRow);
        box.addView(cSep(0));
        return box;
    }
    // 原来那一行"界面主题 · 浅色 ›"被按钮取代，且主题模式的显示与切换都在 conThemeDialog() 里。

    /** layout.defaultPage / detailOpen：只在主题重载时应用一次（不覆盖用户当次的点击）。 */
    private void conThemeApplyLayout() {
        ConsoleTheme ct = conTheme;
        if (ct == null) return;
        if (ct.defaultPage != null) {
            int p = ct.defaultPage.intValue();
            boolean okPage = (p >= 0 && p <= 4)                       // 0~4 内置页（页号 5 已归自定义页，内置页不得占用）
                    || (p >= 5 && ct.pages != null && (p - 4) < ct.pages.size());   // v1.17.8：自定义页
            if (okPage) consolePage = p;
            else ct.notes.add("layout.defaultPage=" + p + "：这一页不存在（内置 0~4，自定义页从 5 起），已忽略");
        }
        if (ct.detailOpen != null) consoleDetailOpen = ct.detailOpen.booleanValue();
    }

    // ==================== v1.17.6 主题页（第 5 步 · consolePage == 4） ====================
    // 设计稿 §3.1：四块 —— ①当前状态 + 五个操作 ②让 AI 做主题包（5 条可复制提示词）
    // ③规范与自检入口 ④可执行动作确认区（第 3 轮才执行，现在只如实列出）

    /** 主控台的「主题」导航行（card id = theme，不可隐藏：它是换主题的唯一入口）。 */
    private View cardTheme() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cNavRow(t("card.theme", "主题"),
                t("desc.theme", "外观 / 布局 / 文案都由 console.json 决定 · 点这里导入导出"),
                conThemeSummary(), 4));
        box.addView(cSep(0));
        return box;
    }

    /** 主控台那一行右侧的状态。 */
    private String conThemeSummary() {
        ConsoleTheme ct = conTheme;
        if (ct == null) return t("status.theme.builtin", "内置默认");
        String name = (ct.name != null && ct.name.length() > 0) ? ct.name : "未命名主题";
        if (ct.fatal) return name + " · " + t("status.theme.fatal", "已整体回退");
        if (ct.reverted > 0) return name + " · " + ct.reverted + t("status.theme.reverted", " 项已回退");
        return name;
    }

    /** 主题页正文。 */
    private void renderConsoleTheme() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("title.themePage", "主题")));
        col.addView(cNote(t("desc.themePage",
                "这一页管的是控制台自己的样子。配置文件在手机存储里，改完存盘几秒内自动生效，不用重启 App。")),
                cTop(cGap(8)));

        // ---------- ① 当前主题（卡片：状态 + 一个主操作） ----------
        col.addView(cGrpTitle(t("title.themeStatus", "当前主题")), cTop(cGap(16)));
        LinearLayout c1 = cCardBox();
        c1.addView(cCardRow(t("title.themeStatus", "当前主题"), conThemeStatusText(), "", false, null));
        col.addView(c1, cTop(cGap(8)));

        LinearLayout a1 = new LinearLayout(this);
        a1.setOrientation(LinearLayout.HORIZONTAL);
        Button rl = cButton(t("btn.theme.reload", "重新加载"), true);
        rl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeReloadNow(); }
        });
        a1.addView(rl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 「恢复默认」是可逆的，但仍是"改数据"，按统一版式放到最下面单独一块（见 ④ 维护）。
        col.addView(a1, cTop(cGap(12)));

        // ---------- ①-b 界面外观（壳级深浅色） ----------
        // v1.21（UI 统一）：与下面的主题包 appearance.dark **不是一回事** ——
        // 这一项决定"壳"（控制台 / 引导页 / 启动等待页 + 系统栏）的深浅，
        // 默认「跟随页面」= 跟着 DSH 页面实测底色走，从而与网页同一色调。
        col.addView(cGrpTitle(t("title.grpShellScheme", "界面外观（壳）")), cTop(cGap(20)));
        col.addView(shellSchemeRow(), cTop(cGap(8)));

        // ---------- ② 导入 / 导出（行式入口） ----------
        col.addView(cGrpTitle(t("title.grpThemeIo", "导入 / 导出")), cTop(cGap(20)));
        LinearLayout c2 = cCardBox();
        c2.addView(cCardRow(t("btn.theme.export", "导出主题包"),
                t("desc.themeExport", "把当前主题打成 zip（含 console.json 与图片），导出后可直接分享"),
                "", true, new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeExport(); }
        }));
        c2.addView(cCardRow(t("btn.theme.import", "导入主题包"),
                t("desc.themeImport", "从 zip 装一份主题；现有配置会先备份，可反悔"),
                "", true, new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeImport(); }
        }));
        col.addView(c2, cTop(cGap(8)));

        // v1.19.x：让「恢复默认」可逆 —— 只有真的存在被收起来的配置时才显示这个入口
        int nBackups = conThemeBackups().size();
        if (nBackups > 0) {
            col.addView(cCardRow(t("btn.theme.backups", "我保存过的主题") + "（" + nBackups + "）",
                    t("desc.themeBackups", "恢复默认 / 导入之前的旧配置都收在这里，点一下直接恢复"),
                    "", true, new View.OnClickListener() {
                @Override public void onClick(View v) { conThemeShowBackups(); }
            }), cTop(cGap(8)));
        }

        // ---------- ③ 规范与自检（行式入口） ----------
        col.addView(cGrpTitle(t("title.themeSpec", "规范与自检")), cTop(cGap(20)));
        col.addView(cNote(t("desc.themeSpec",
                "规范就在主题目录里（THEME-PACK-SPEC.md），连同 schema、示例、零依赖校验器一起随包分发；"
                        + "AI 生成的包可以先自检一遍再导入。")), cTop(cGap(6)));
        LinearLayout c3 = cCardBox();
        c3.addView(cCardRow(t("btn.theme.openSpec", "打开规范"),
                t("desc.themeSpecShort", "THEME-PACK-SPEC.md · 含卡片 / 积木 / 动作三张 id 表"),
                "", true, new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeOpenSpec(); }
        }));
        c3.addView(cCardRow(t("btn.theme.openDir", "打开主题目录"),
                t("desc.themeDir", "console.json、背景图与规范都放在这里"),
                "", true, new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeOpenDir(); }
        }));
        c3.addView(cCardRow(t("btn.theme.diag", "诊断"),
                t("desc.themeDiag", "看解析结果、回退项与布局页数"),
                "", true, new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeShowDetail(); }
        }));
        col.addView(c3, cTop(cGap(8)));

        Button cc = cButton(t("btn.theme.copyCheck", "复制自检命令"), false);
        cc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                conThemeCopy("python3 theme_pack_check.py 你的主题包.zip", "自检命令已复制（在电脑或手机终端里跑）");
            }
        });
        col.addView(cc, cTop(cGap(12)));

        // ---------- ④ 让 AI 做主题包 ----------
        col.addView(cGrpTitle(t("title.themeAi", "让 AI 做主题包")), cTop(cGap(20)));
        col.addView(cNote(t("desc.themeAi",
                "复制一条提示词发给任意 AI（手机上的 DSH、电脑上的、网页的都行），它产出的 zip 用上面的「导入主题包」装进来。")),
                cTop(cGap(6)));
        LinearLayout c4 = cCardBox();
        for (int i = 0; i < CON_THEME_PROMPTS.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, cGap(11), 0, cGap(11));
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(cText(CON_THEME_PROMPTS[i][0], 13.5f, cText(), false));
            left.addView(cText(CON_THEME_PROMPTS[i][2], 11f, cSub(), false));
            TextView cp = cText(t("btn.theme.copy", "复制"), 12f, cAccent(), false);
            cp.setPadding(dp(12), dp(6), dp(12), dp(6));
            cp.setBackground(cShape(0x00000000, cAccent(), 1, 12));
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(cp);
            View.OnClickListener click = new View.OnClickListener() {
                @Override public void onClick(View v) { conThemeCopyPrompt(idx); }
            };
            row.setOnClickListener(click);
            cp.setOnClickListener(click);
            c4.addView(row);
        }
        col.addView(c4, cTop(cGap(8)));

        // ---------- ⑤ 维护：恢复默认（可撤销，但仍属"改数据"→ 红字、单独一块、放最后） ----------
        col.addView(cGrpTitle(t("title.grpThemeCare", "维护")), cTop(cGap(20)));
        LinearLayout c5 = cCardBox();
        c5.addView(cCardRow(t("btn.theme.reset", "恢复默认主题"),
                t("desc.themeReset", "把 console.json 改名留底（不删）；之后可从「我保存过的主题」恢复"),
                t("btn.theme.resetShort", "恢复"), cRed(), true, new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeReset(); }
        }));
        col.addView(c5, cTop(cGap(8)));

        // 逃生口（也放在主题页里，免得长按品牌字那条路被自己改没了）
        TextView esc = cText(conThemeIgnored()
                        ? t("btn.theme.unsafe", "恢复主题样式（现在按默认样式打开）")
                        : t("btn.theme.safe", "以默认样式打开（忽略主题配置，文件不删）"),
                11.5f, cAccent(), false);
        esc.setPadding(0, cGap(12), 0, 0);
        esc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conThemeEscapeDialog(); }
        });
        col.addView(esc);

        // ---------- ⑥ 可执行动作（只读展示，实际执行在主控台的「自定义按钮」卡片里） ----------
        ConsoleTheme ct = conTheme;
        if (ct != null && !ct.execActions.isEmpty()) {
            col.addView(cGrpTitle(t("title.themeActions", "可执行动作（本版不会执行）")), cTop(cGap(20)));
            col.addView(cText("这份配置里有 " + ct.execActions.size()
                    + " 个可执行动作（shell / http / intent / prompt）。"
                    + "它们在主控台的「自定义按钮」卡片里可以点，「点的时候会先弹确认」、"
                    + "把要执行的命令/网址/提示词原样摆出来；http / intent / prompt 三类本版还没接执行。",
                    11f, cRed(), false), cTop(cGap(7)));
            for (int i = 0; i < ct.execActions.size(); i++) {
                col.addView(cText("· " + ct.execActions.get(i), 11f, cSub(), false), cTop(cGap(4)));
            }
        }
    }


    // ==================== 「我保存过的主题」（v1.19.x：让"恢复默认"可逆） ====================
    //
    // 背景：conThemeReset() 只把 console.json 改名为 console.json.disabled-<时间戳>（不删除，是可逆的安全设计），
    // 但界面上原本**没有任何入口能读回来** —— 用户点了「恢复默认」就再也找不回自己的定制。
    // 这里补上：列出所有"被收起来"的配置 + 一键恢复（覆盖前再留一份底）。

    /** 是否是我们自己收起来的配置备份（不是普通文件）。 */
    private static boolean isThemeBackupName(String n) {
        return n.startsWith("console.json.disabled-") || n.startsWith("console.json.replaced-")
                || n.startsWith("console.json.bak");
    }

    /** 列出全部可恢复的配置备份（按时间倒序）。 */
    private java.util.List<File> conThemeBackups() {
        java.util.List<File> out = new java.util.ArrayList<File>();
        try {
            File[] fs = consoleDir().listFiles();
            if (fs == null) return out;
            for (int i = 0; i < fs.length; i++) {
                if (!fs[i].isFile()) continue;
                String n = fs[i].getName();
                // .bak / .bak-import-* 都算；但不收当前正在用的 console.json
                if (n.equals("console.json")) continue;
                if (isThemeBackupName(n)) out.add(fs[i]);
            }
            java.util.Collections.sort(out, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
        } catch (Throwable t) {
            Log.w(TAG, "conThemeBackups failed", t);
        }
        return out;
    }

    /** 把时间戳文件名变成人话：console.json.disabled-261004-201533 → 26-10-04 20:15:33。 */
    private String conThemeBackupLabel(File f) {
        String n = f.getName();
        String ts = null;
        int dash = n.indexOf('-');
        if (dash > 0 && n.length() >= dash + 12) ts = n.substring(dash + 1, dash + 12);
        String kind = n.startsWith("console.json.disabled-") ? "恢复默认时收起的"
                : n.startsWith("console.json.replaced-") ? "被新配置替换的"
                : "上次生效时的备份";
        String when = ts != null && ts.length() == 11
                ? ("20" + ts.substring(0, 2) + "-" + ts.substring(2, 4) + "-" + ts.substring(4, 6)
                   + " " + ts.substring(6, 8) + ":" + ts.substring(8, 10) + ":" + ts.substring(10, 11))
                : new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(f.lastModified()));
        String name = "";
        String head = readTextFile(f, 4096);
        if (head != null) {
            String nm = jsonField(head, "name");
            if (nm != null && nm.length() > 0) name = "「" + nm + "」 ";
        }
        return name + kind + " · " + when + " · " + (f.length() / 1024) + " KB";
    }

    /**
     * 「我保存过的主题」：**一个弹窗解决**（用户 2026-10-04 明确要求）。
     * 每行 = 名称 · 类型 · 时间 · 大小 + 行尾「恢复」，点一下直接恢复；
     * 同一个弹窗里还有「清空记录」（红框按钮 + 二次确认，只删备份、绝不动正在用的 console.json）。
     * 恢复不再套第二层确认 —— 因为它本身可逆：覆盖前会把当前这份也留一份底。
     */
    private void conThemeShowBackups() {
        final java.util.List<File> list = conThemeBackups();
        if (list.isEmpty()) { conToast("没有可恢复的配置（你还没「恢复默认」过）"); return; }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cText("点任意一条右边的「恢复」就换回那份配置 —— 换之前会把现在这份也留一份底，随时能换回来。",
                11f, cSub(), false));

        final int shown = Math.min(list.size(), 30);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < shown; i++) {
            inner.addView(conThemeBackupRow(list.get(i)), cTop(cGap(8)));
        }
        if (list.size() > shown) {
            inner.addView(cText("…另外 " + (list.size() - shown) + " 份未列出（先「清空记录」再进来看）",
                    10.5f, cSub(), false), cTop(cGap(8)));
        }
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(inner);
        // 固定高度：内容多时滚动。⚠ 不能给 WRAP_CONTENT —— 弹窗里是 AT_MOST，
        // 纯容器在 AT_MOST 下会取满 specSize（本项目 spacer 撑爆整页的同一个坑）。
        int h = Math.max(64, Math.min(360, shown * 58));
        sc.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
        box.addView(sc, cTop(cGap(10)));
        box.addView(cText("共 " + list.size() + " 份 · " + conThemeBackupsSizeText(list)
                        + "\n「清空记录」只删这些备份文件，不动当前正在用的 console.json。",
                10.5f, cSub(), false), cTop(cGap(10)));

        conDialogView("我保存过的主题（" + list.size() + "）", box, "清空记录", new Runnable() {
            @Override public void run() { conThemeClearBackupsConfirm(list); }
        }, "关闭", true);
    }

    /** 备份列表里的一行：左边「名称 · 类型 · 时间 · 大小」，右边「恢复」。 */
    private View conThemeBackupRow(final File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(cShape(cCard(), cLine(), 1, 10));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView tv = cText(conThemeBackupLabel(f), 11.5f, cText(), false);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        Button b = cButton("恢复", false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { conThemeRestoreBackupNow(f); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        row.addView(b, lp);
        return row;
    }

    /** 备份合计大小（弹窗底部那行用）。 */
    private String conThemeBackupsSizeText(java.util.List<File> list) {
        long n = 0;
        for (int i = 0; i < list.size(); i++) n += list.get(i).length();
        if (n >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
        if (n >= 1024L) return (n / 1024L) + " KB";
        return n + " B";
    }

    /** 「清空记录」的二次确认：把要删的东西列清楚，并写明不动正在用的配置。 */
    private void conThemeClearBackupsConfirm(final java.util.List<File> list) {
        conDialogDanger("清空恢复记录？",
                "会删除下面这些备份文件（共 " + list.size() + " 份 · " + conThemeBackupsSizeText(list) + "）：\n"
              + "· console.json.disabled-*（点「恢复默认」时收起的）\n"
              + "· console.json.replaced-*（恢复操作换下来的）\n"
              + "· console.json.bak*（导入主题前的备份）\n\n"
              + "只删这些备份，不动当前正在用的 console.json —— 你现在看到的主题不会变。\n"
              + "删掉之后这些历史配置就找不回来了。",
                "确认清空",
                new Runnable() { @Override public void run() { conThemeClearBackupsNow(list); } },
                "取消");
    }

    /** 真正清空（只删 conThemeBackups() 给出的那些文件，一个别的都不碰），并记一笔账本。 */
    private void conThemeClearBackupsNow(java.util.List<File> list) {
        int ok = 0;
        int failed = 0;
        long bytes = 0;
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            File f = list.get(i);
            long n = f.length();
            boolean gone;
            try { gone = f.delete() || !f.exists(); } catch (Throwable t) { gone = false; }
            if (!gone) { failed++; continue; }
            ok++;
            bytes += n;
            if (ok <= 50) {
                if (ok > 1) names.append(",");
                names.append("\"").append(jesc(f.getName())).append("\"");
            }
        }
        names.append("]");
        try {
            conLedgerWrite("theme-backup-clear", "{\"deleted\":" + ok + ",\"failed\":" + failed
                    + ",\"bytes\":" + bytes + ",\"files\":" + names + "}");
        } catch (Throwable ignored) {}
        conToast("已清空 " + ok + " 份备份" + (failed > 0 ? ("（" + failed + " 份没删掉）") : "")
                + " · 当前主题未改动");
        renderConsole();
    }

    // （v1.19.x）conThemePickBackup / conThemeRestoreBackup 已删除：
    // 单弹窗改造后不再需要「列表弹窗」和「恢复确认弹窗」两层 ——
    // 行内「恢复」直接调 conThemeRestoreBackupNow()，而它自己会先把当前这份留底（可逆）。

    private void conThemeRestoreBackupNow(File src) {
        try {
            File cur = conThemeFile();
            String ts = new java.text.SimpleDateFormat("yyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            if (cur.exists()) {
                // 当前这份也留底（用 .replaced- 前缀，语义与"恢复默认时收起"区分开）
                File keep = new File(consoleDir(), "console.json.replaced-" + ts);
                copyFileShallow(cur, keep);
            }
            copyFileShallow(src, cur);
            conTheme = null;
            conThemeLoaded = false;
            conThemeStamp = null;
            conThemeReloadIfChanged(true);
            conBgApply();
            renderConsole();
            conLedgerWrite("theme-restore", "{\"from\":\"" + jesc(src.getName()) + "\",\"to\":\"console.json\""
                    + ",\"loaded\":" + (conTheme != null) + "}");
            conToast(conTheme != null ? "已恢复：" + conThemeBackupLabel(src) : "已恢复，但这份配置有语法错误（走整体回退）");
        } catch (Throwable t) {
            conToast("恢复失败：" + t.getMessage());
        }
    }

    /** 单文件浅拷贝（恢复主题用；不做递归、不跟链）。 */
    private void copyFileShallow(File src, File dst) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /** 主题页顶部那几行状态：来源 / 路径 / 生效范围 / 上次回退。 */
    private String conThemeStatusText() {
        ConsoleTheme ct = conTheme;
        StringBuilder sb = new StringBuilder();
        File f = conThemeFile();
        if (ct == null) {
            int backups = conThemeBackups().size();
            sb.append("主题：内置默认（").append(f.exists() ? "配置文件存在但没生效？" : "没有 console.json").append("）");
            if (backups > 0) sb.append("\n可恢复：有 ").append(backups).append(" 份你用过的配置（点下面「我保存过的主题」）");
        } else {
            sb.append("主题：").append(ct.name != null && ct.name.length() > 0 ? ct.name : "未命名主题");
            if (ct.fatal) sb.append("\n状态：整体回退默认（配置有语法错误）");
            else if (ct.reverted > 0) sb.append("\n状态：").append(ct.reverted).append(" 项非法已回退，其余生效中");
            else sb.append("\n状态：已生效");
            sb.append("\n生效范围：");
            sb.append(ct.background != null ? "配色·字号·圆角·背景图" : "配色·字号·圆角");
            if (ct.cardOrder != null || !ct.hiddenCards.isEmpty() || ct.compact) sb.append("·布局");
            if (ct.textCount() > 0) sb.append("·文案（").append(ct.textCount()).append(" 条）");
        }
        sb.append("\n路径：").append(f.getAbsolutePath());
        // 只在"回退记录比当前配置还新"时才说"上次回退"——否则那是很早以前留下的历史文件，
        // 挂在当前主题下面会让人以为现在的配置有问题。
        File err = new File(consoleDir(), "last-error.txt");
        if (err.exists() && err.lastModified() >= f.lastModified()) {
            sb.append("\n上次回退：有（同目录 last-error.txt，").append(err.length()).append(" 字节）");
        }
        return sb.toString();
    }

    /** 「重新加载」：强制重读配置（改完文件想立刻应用时用）。 */
    private void conThemeReloadNow() {
        conThemeLoaded = false;
        conThemeStamp = null;
        conThemeReloadIfChanged(true);
        conBgApply();
        renderConsole();
        conToast("已重新加载：" + conThemeSummary());
    }

    // ---------- 导出 / 导入 ----------

    /** 导出主题包：console.json + 图片 + 生成的 theme.json，打进 /sdcard/<pkg>/console-theme-<名>.zip。 */
    private void conThemeExport() {
        if (!conThemeFile().exists()) {
            conToast("还没有 console.json 可导出（当前是内置默认）");
            return;
        }
        conToast("正在打包…");
        new Thread(new Runnable() { @Override public void run() {
            File out = null; String err = null; int n = 0;
            try {
                String name = "theme";
                ConsoleTheme ct = conTheme;
                if (ct != null && ct.name != null && ct.name.length() > 0) name = conThemeSafeName(ct.name);
                // 落在 <pkgRoot>/（console/ 的上一级）—— 与 THEME-PACK-SPEC.md §0 一致，
                // 也免得导出的 zip 又出现在"主题目录"里被当成素材。
                File dir = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, "console-theme-" + name + ".zip");
                n = conThemeWriteZip(out, name);
            } catch (Throwable t) {
                Log.e(TAG, "theme export", t);
                err = t.getMessage();
                if (out != null) { try { out.delete(); } catch (Throwable ignored) {} }
                out = null;
            }
            final File of = out; final String e = err; final int cnt = n;
            ui.post(new Runnable() { @Override public void run() {
                if (of == null) { conToast("导出失败：" + e); return; }
                conToast("已导出 " + cnt + " 个文件：" + of.getName());
                conShareExportedFile(of, "application/zip", "导出主题包");
            }});
        }}, "theme-export").start();
    }

    private int conThemeWriteZip(File out, String name) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n = 0;
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(new FileOutputStream(out)));
        try {
            String tj = "{\n  \"schema\": 1,\n  \"name\": \"" + jesc(name) + "\",\n"
                    + "  \"author\": \"\",\n  \"app\": \"" + jesc(conVersionLabel()) + "\"\n}\n";
            zipAddBytes(zos, "theme.json", tj.getBytes("UTF-8"));
            n++;
            File dir = consoleDir();
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    File f = kids[i];
                    if (!f.isFile()) continue;
                    String bn = f.getName();
                    if ("console.json".equals(bn)) {
                        zipAdd(zos, f, "console.json", buf);
                        n++;
                    } else if (conThemeIsImage(bn)) {
                        zipAdd(zos, f, bn, buf);
                        n++;
                    }
                }
            }
        } finally {
            try { zos.close(); } catch (Throwable ignored) {}
        }
        return n;
    }

    private static boolean conThemeIsImage(String n) {
        String s = n.toLowerCase(java.util.Locale.US);
        return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".webp");
    }

    private static String conThemeSafeName(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 24; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
        }
        return sb.length() == 0 ? "theme" : sb.toString();
    }

    /** 导入主题包：SAF 选 zip → 后台读进内存 → 校验 → 确认 → 写入。 */
    private void conThemeImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_THEME_FILE);
        } catch (Throwable t) { conToast("无法打开文件选择器：" + t.getMessage()); }
    }

    private void conThemeImportFromUri(final Uri uri) {
        conToast("正在读取主题包…");
        new Thread(new Runnable() { @Override public void run() {
            String cfg = null, tname = null, err = null;
            final java.util.LinkedHashMap<String, byte[]> files = new java.util.LinkedHashMap<String, byte[]>();
            int total = 0;
            try {
                InputStream raw = getContentResolver().openInputStream(uri);
                if (raw == null) throw new java.io.IOException("无法读取所选文件");
                java.util.zip.ZipInputStream zis =
                        new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw));
                byte[] buf = new byte[16384];
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String nm = e.getName();
                    String base = nm.substring(nm.lastIndexOf('/') + 1);   // 允许包一层目录
                    if (base.length() == 0 || base.startsWith(".")) continue;
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    int r;
                    while ((r = zis.read(buf)) > 0) bos.write(buf, 0, r);
                    byte[] data = bos.toByteArray();
                    total += data.length;
                    if (total > 8 * 1024 * 1024) throw new java.io.IOException("主题包太大（>8MB）");
                    if ("console.json".equals(base)) {
                        cfg = new String(data, "UTF-8");
                    } else if ("theme.json".equals(base)) {
                        try {
                            tname = new org.json.JSONObject(new String(data, "UTF-8")).optString("name", null);
                        } catch (Throwable ignored) {}
                    } else if (conThemeIsImage(base)) {
                        files.put(base, data);
                    }
                }
                zis.close();
            } catch (Throwable t) {
                Log.e(TAG, "theme import read", t);
                err = t.getMessage();
            }
            final String fcfg = cfg, fname = tname, ferr = err;
            ui.post(new Runnable() { @Override public void run() {
                if (ferr != null) { conToast("读取失败：" + ferr); return; }
                if (fcfg == null) {
                    conDialog("导入主题包", "这个 zip 里没有 console.json —— 主题包必须包含它。", "知道了", null, null);
                    return;
                }
                conThemeImportConfirm(fcfg, fname, files);
            }});
        }}, "theme-import-read").start();
    }

    /** 校验 + 让用户看清楚将要发生什么，再决定覆盖。 */
    private void conThemeImportConfirm(final String cfgText, final String themeName,
                                       final java.util.LinkedHashMap<String, byte[]> files) {
        final ConsoleTheme probe = ConsoleTheme.fromText(cfgText);
        if (probe.fatal) {
            StringBuilder sb = new StringBuilder("这个主题包不能用（「现有配置未改动」）：\n");
            for (int i = 0; i < probe.errors.size(); i++) sb.append("\n· ").append(probe.errors.get(i));
            conDialog("导入主题包 · 校验失败", sb.toString().replace("**", ""), "知道了", null, null);
            return;
        }
        String nm = (themeName != null && themeName.length() > 0) ? themeName
                : (probe.name != null && probe.name.length() > 0 ? probe.name : "未命名主题");
        StringBuilder body = new StringBuilder();
        body.append("主题：").append(nm).append('\n');
        body.append("包含：console.json");
        if (!files.isEmpty()) body.append(" + ").append(files.size()).append(" 张图片");
        body.append("\n\n导入会覆盖当前的 console.json 与同名图片（现有配置先备份成 console.json.bak-import-<时间>）。");
        if (probe.reverted > 0) {
            body.append("\n\n⚠ ").append(probe.reverted).append(" 项非法，导入后会回退成内置值：");
            for (int i = 0; i < probe.warnings.size() && i < 6; i++) body.append("\n· ").append(probe.warnings.get(i));
        }
        if (!probe.execActions.isEmpty()) {
            body.append("\n\n⚠ 含 ").append(probe.execActions.size())
                .append(" 个可执行动作（shell/http/intent/prompt）：本版「不会执行」它们（第 3 轮才启用，且必须逐个确认）。");
        }
        conDialog("导入主题包", body.toString().replace("**", ""), "导入", new Runnable() {
            @Override public void run() { conThemeImportNow(cfgText, files); }
        }, "取消");
    }

    private void conThemeImportNow(String cfgText, java.util.LinkedHashMap<String, byte[]> files) {
        try {
            File dir = consoleDir();
            if (!dir.exists() && !dir.mkdirs()) { conToast("主题目录不可写（是否缺存储权限？）"); return; }
            File cur = conThemeFile();
            if (cur.exists()) {
                String ts = new SimpleDateFormat("yyMMdd-HHmmss").format(new java.util.Date());
                conThemeCopyFile(cur, new File(dir, "console.json.bak-import-" + ts));
            }
            conThemeWrite(new File(dir, "console.json"), cfgText.getBytes("UTF-8"));
            int n = 0;
            for (java.util.Map.Entry<String, byte[]> en : files.entrySet()) {
                conThemeWrite(new File(dir, en.getKey()), en.getValue());
                n++;
            }
            conTheme = null;
            conThemeLoaded = false;
            conThemeStamp = null;
            conThemeReloadIfChanged(true);
            conBgApply();
            consolePage = 4;
            renderConsole();
            conToast("已导入「" + (conTheme != null && conTheme.name != null && conTheme.name.length() > 0
                    ? conTheme.name : "主题") + "」（含 " + n + " 张图片）");
        } catch (Throwable t) {
            Log.e(TAG, "theme import", t);
            conToast("导入失败：" + t.getMessage());
        }
    }

    private static void conThemeWrite(File f, byte[] data) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        try { fos.write(data); } finally { try { fos.close(); } catch (Throwable ignored) {} }
    }

    private static void conThemeCopyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        try {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    // ---------- 目录 / 规范 / 提示词 ----------

    /** 打开主题目录：能调起文件管理器就调，调不起来就把路径复制到剪贴板。 */
    private void conThemeOpenDir() {
        File dir = consoleDir();
        if (!dir.exists()) dir.mkdirs();
        String path = dir.getAbsolutePath();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("file://" + path), "resource/folder");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        conThemeCopy(path, "已复制主题目录路径：" + path);
    }

    /** 打开规范：借 LogShareProvider 把 SPEC 拷到可分享目录，再用系统查看器打开；都不行就复制路径。 */
    private void conThemeOpenSpec() {
        final File spec = new File(consoleDir(), "THEME-PACK-SPEC.md");
        if (!spec.exists()) { conToast("规范文件不在（是否缺存储权限？）"); return; }
        try {
            File dst = new File(LogShareProvider.shareDir(this), "THEME-PACK-SPEC.md");
            conThemeCopyFile(spec, dst);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(LogShareProvider.uriFor(this, dst.getName()), "text/plain");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            File dst = new File(LogShareProvider.shareDir(this), "THEME-PACK-SPEC.md");
            conThemeCopyFile(spec, dst);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, LogShareProvider.uriFor(this, dst.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "打开规范"));
            return;
        } catch (Throwable ignored) {}
        conThemeCopy(spec.getAbsolutePath(), "已复制规范路径（用文件管理器打开）");
    }

    /** 复制第 idx 条提示词；⑤ 是"复制规范全文"，点击时才读文件。 */
    private void conThemeCopyPrompt(int idx) {
        String body = CON_THEME_PROMPTS[idx][1];
        if (body.length() == 0) {
            File spec = new File(consoleDir(), "THEME-PACK-SPEC.md");
            try {
                body = "请按下面这份规范生成 / 修改 DeepSeek Harness 安卓控制台的主题包（zip）。\n\n"
                        + ConsoleTheme.readText(spec);
            } catch (Throwable t) {
                conToast("读不到规范文件（" + spec.getName() + "）");
                return;
            }
        }
        conThemeCopy(body, "已复制：" + CON_THEME_PROMPTS[idx][0]);
    }

    private void conThemeCopy(String text, String msg) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { conToast("剪贴板不可用"); return; }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text));
            conToast(msg);
        } catch (Throwable t) {
            conToast("复制失败：" + t.getMessage());
        }
    }

    /**
     * 把一个刚导出的文件调起系统分享面板（与「分享日志」同一条链路：
     * LogShareProvider 的 content:// + 临时读权限），用户可以直接发到 QQ / 微信 / 网盘 / 邮件，
     * 也可以选"保存到文件"。「导出到 /sdcard 的那份仍然保留」，这里是"再发一份出去"。
     */
    private void conShareExportedFile(File src, String mime, String title) {
        if (src == null || !src.exists()) return;
        try {
            File dst = new File(LogShareProvider.shareDir(this), src.getName());
            copyFile(src, dst);
            Uri uri = LogShareProvider.uriFor(this, dst.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        } catch (Throwable t) {
            conToast("调不起分享：" + t.getMessage());
        }
    }

    /**
     * 5 条提示词：{标题, 正文, 副标题}。正文为空表示"点击时读规范全文"。
     * ⚠ 与 THEME-PACK-SPEC.md §6 保持同一套（改了要同步）。
     */
    private static final String[][] CON_THEME_PROMPTS = {
        {"① 从零生成一个主题",
         "请帮我做一个 DeepSeek Harness 安卓控制台的主题包（zip）。\n"
         + "规范：THEME-PACK-SPEC.md（或见 console.schema.json + console.example.json）\n"
         + "要求：深色、低饱和，强调色青绿 #2dd4bf；背景用一张海边夜景（生成 1080×2400 的图，命名 bg.jpg）；\n"
         + "     卡片半透明 cardsAlpha 0.82、背景 dim 0.28（字要看得清）；隐藏\"检查更新\"；把\"启动引擎\"改叫\"点火\"；\n"
         + "     不要任何 shell/http/intent/prompt 动作。\n"
         + "输出：1) console.json 全文 2) theme.json 全文 3) 打包命令 zip mytheme.zip theme.json console.json bg.jpg",
         "深色 + 背景图 + 改名，一条到位"},
        {"② 改我现在这个主题",
         "这是我现在的控制台主题配置（console.json 全文如下）。请在此基础上改：\n"
         + "- 把强调色换成暖橙 #f59e0b，背景图换成 /sdcard/Pictures/my.jpg\n"
         + "- 圆角改 22、字体放大到 1.1\n"
         + "- 文案里\"重启\"改成\"重开\"\n"
         + "其余保持不变，输出新的 console.json + theme.json + 打包命令。\n"
         + "<把你的 console.json 贴在这里>",
         "先复制你的 console.json 一起贴过去"},
        {"③ 只要一套配色",
         "只给我 appearance 这一段：企业蓝 #2563eb 为强调色，浅色底 #f8fafc、卡片 #ffffff、文字 #0f172a、\n"
         + "次要 #64748b、分割线 #e2e8f0；深色底 #0b1220、卡片 #111a2b、文字 #e6edf7、次要 #94a3b8、分割线 #1e293b。\n"
         + "输出可直接合并进 console.json 的 JSON 片段。",
         "不动布局，只要颜色"},
        {"④ 调布局与文案",
         "这是我现在的控制台主题配置。请只改这三件事，其余保持不动：\n"
         + "1) 顺序：把\"日志\"提到最上面，\"检查更新\"挪到最后；\n"
         + "2) 隐藏：把\"检查更新\"整行藏掉；\n"
         + "3) 文案：\"启动引擎\"改叫\"点火\"、\"重启\"改叫\"重开\"、\"停止\"改叫\"熄火\"。\n"
         + "输出新的 console.json（含 layout.order / layout.hidden / text 三段）。\n"
         + "<把你的 console.json 贴在这里>",
         "换顺序 / 藏卡片 / 改按钮名字"},
        {"⑤ 复制规范全文",
         "",
         "把整份规范当上下文发给 AI（较长）"},
    };


    /** 与控制台同一套视觉的弹窗（平色底、同字体、同按钮样式，跟随系统深浅色）。 */
    private void conDialog(String title, String body, String positive, final Runnable onPositive, String negative) {
        TextView t = null;
        if (body != null && body.length() > 0) t = cText(body, 12.5f, cSub(), false);
        conDialogView(title, t, positive, onPositive, negative);
    }

    /**
     * 同风格弹窗的「危险动作」版：确认按钮画成红框。
     * 与主题动作的安全规格一致 —— 会写盘/有副作用的动作，按钮必须显眼，正文必须写清它到底要干什么。
     */
    private void conDialogDanger(String title, String body, String positive, final Runnable onPositive, String negative) {
        TextView t = null;
        if (body != null && body.length() > 0) t = cText(body, 12.5f, cSub(), false);
        conDialogView(title, t, positive, onPositive, negative, true);
    }

    /** 同风格弹窗的通用版：内容自定（例如带滚动的日志正文）。 */
    private void conDialogView(String title, View content, String positive, final Runnable onPositive, String negative) {
        conDialogView(title, content, positive, onPositive, negative, false);
    }

    /** 同上；danger=true 时确认按钮画红框（会写盘 / 有副作用的动作）。 */
    private void conDialogView(String title, View content, String positive, final Runnable onPositive, String negative, boolean danger) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // v1.13：卡片自己画圆角背景（原来用直角色块，系统对话框面板的圆角/描边会露在外面，
        // 看上去就是“弹窗外面还套了一层小白边/小黑边”）。
        box.setBackground(cShape(cBg(), 0, 0, 16));
        box.setPadding(dp(22), dp(22), dp(22), dp(14));
        if (title != null && title.length() > 0) box.addView(cText(title, 16f, cText(), true));
        // v1.19.6 修复：正文一律放进 ScrollView（调用方已经给 ScrollView 的就沿用，不套两层）。
        // 为什么必须包：见下面 post() 前的注释 —— 正文一长，动作按钮会被挤出屏幕且**点不到**。
        if (content != null) {
            ScrollView sv = (content instanceof ScrollView) ? (ScrollView) content : null;
            if (sv == null) {
                sv = new ScrollView(this);
                sv.addView(content, new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            box.addView(sv, cTop(dp(12)));
        }

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.RIGHT);
        if (negative != null) {
            Button nb = cButton(negative, false);
            nb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeDialogOverlay(); }
            });
            acts.addView(nb);
        }
        if (positive != null) {
            Button pb = cButton(positive, true);
            if (danger) {
                pb.setTextColor(cRed());
                pb.setBackground(cShape(0x00000000, cRed(), 1, 8));
            }
            pb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeDialogOverlay();
                    if (onPositive != null) onPositive.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            acts.addView(pb, lp);
        }
        box.addView(acts, cTop(dp(18)));

        // 改用 Activity 内自绘浮层（见 showDialogOverlay 注释），
        // 不再走系统 AlertDialog —— 它会把主题的深色圆角面板画在卡片外面。
        showDialogOverlay(box);
    }

    /**
     * 把弹窗做成 Activity 自己视图树里的浮层，而不是系统对话框窗口。
     *
     * 症状：弹窗卡片外面还套着一层深色圆角框（用户截图可见）。
     * 成因：AlertDialog 的面板背景来自 Activity 主题（Theme.Black 的 alertDialogTheme），
     *   那层 frame 画在 「对话框布局自己身上」，只把 *窗口* 背景设成透明并不管用
     *   （旧代码就是把窗口背景设透明，所以外框一直在）。
     * 做法：自绘「遮罩 + 圆角卡片」，不经过任何系统对话框窗口 —— 没有主题面板，
     *   也就没有外框；顺带把圆角/边距/点空白取消都握在自己手里。
     */
    /** 弹窗卡片里的第一个 ScrollView（正文区）；没有就返回 null。 */
    private ScrollView dialogScroller(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = dialogScroller(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    private void showDialogOverlay(View card) {
        closeDialogOverlay();
        FrameLayout host = null;
        try { host = (FrameLayout) findViewById(android.R.id.content); } catch (Throwable ignored) {}
        if (host == null || card == null) return;
        card.setClickable(true);                 // 卡片自己吃掉点击，避免点卡片也被当成“点空白”
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);    // 70% 黑遮罩（原系统对话框的 dim 观感）
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDialogOverlay(); }   // 点空白 = 取消
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(20);
        scrim.addView(card, lp);
        // 兜底：卡片高度不超过屏幕 80%，**但只压缩正文区** —— 标题与动作按钮永远留在屏幕里。
        //
        // 为什么不能像原来那样"直接把卡片截到 80%"（v1.19.6 真机实测复现）：
        //   竖直 LinearLayout 在高度被截断后，排在最后的子 View（动作按钮行）会被裁到可视区
        //   之外 —— 不显示、不可点，uiautomator 里连节点都没有。真机现场：会话修复详情弹窗
        //   （28 处坏引用）只剩正文，按钮全不见了，只能按返回键逃。
        //   原来那句注释「超出部分由内容自己的 ScrollView 滚」只对**调用方自己传了 ScrollView**
        //   的调用点成立；`conDialog(长正文)` 传的是裸 TextView，所以必然踩。
        //   现在：正文设成 height=0 + weight=1，LinearLayout 先量标题/按钮，剩余空间全给正文，
        //   正文在自己内部滚 —— 三个调用点（日志、主题诊断）本来就传 ScrollView，行为不变。
        scrim.post(new Runnable() {
            @Override public void run() {
                try {
                    View c = scrim.getChildAt(0);
                    if (c == null) return;
                    int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f);
                    if (c.getHeight() <= maxH) return;
                    ViewGroup.LayoutParams p = c.getLayoutParams();
                    p.height = maxH;
                    c.setLayoutParams(p);
                    // 正文区 = 卡片视图树里的第一个 ScrollView（conDialogView 刚包好的那个）。
                    // ⚠ 不能用"在 conDialogView 里抓住引用"的写法：这段兜底在
                    //   showDialogOverlay() 里，两个方法作用域不通（patch57 就是在这儿栽的，
                    //   javac 报「找不到符号 · 变量 fsv」）。
                    ScrollView sc = dialogScroller(c);
                    if (sc != null) {
                        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                        if (sc.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                            nlp.topMargin = ((LinearLayout.LayoutParams) sc.getLayoutParams()).topMargin;
                        }
                        sc.setLayoutParams(nlp);
                    }
                } catch (Throwable ignored) {}
            }
        });
        host.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        dialogOverlay = scrim;
    }

    /** 关掉当前自绘弹窗（没有则什么都不做）；按钮回调与返回键共用。 */
    private void closeDialogOverlay() {
        View v = dialogOverlay;
        dialogOverlay = null;
        if (v == null) return;
        try {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
        } catch (Throwable ignored) {}
    }

    private View conExtractDetail() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        box.addView(cText("布局标记 hoisted-1 · 插件与补丁面已验证", 11f, cSub(), false), cTop(dp(8)));
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button re = cButton("重新解压", false);
        re.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conReExtract(); }
        });
        Button vf = cButton("校验", false);
        vf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conVerifyFiles(); }
        });
        acts.addView(re);
        acts.addView(vf);
        box.addView(acts, cTop(dp(10)));
        return box;
    }

    private void refreshConsole() {
        if (!consoleVisible || consoleBody == null || consolePage != 0) return;
        boolean ready = conFilesReady();
        if (conExState != null) conExState.setText(extracting ? t("status.extract.running", "正在解压…")
                : (ready ? t("status.extract.done", "已解压") : t("status.extract.idle", "未解压")));
        if (conExMeta != null) conExMeta.setText(conExtractMetaText(ready));
        if (conEnvState != null) conEnvState.setText(conExtractMetaText(ready));   // v1.19.6：运行环境入口的副标题
        if (conDetailBox != null) conDetailBox.setVisibility(ready && consoleDetailOpen ? View.VISIBLE : View.GONE);
        if (conBar != null) conBar.setVisibility(extracting ? View.VISIBLE : View.GONE);
        if (!extracting) conSetProgress(ready ? 100 : 0);
        if (conExBtn != null) {
            conExBtn.setText(extracting ? t("status.extract.running", "解压中…")
                    : (ready ? t("btn.extract.rerun", "重新解压") : t("btn.extract.run", "解压文件")));
            cSetEnabled(conExBtn, !extracting && !starting);
        }
        boolean run = conEngineRunning();          // 真就绪（端口探测通过）
        boolean booting = conEngineBooting();      // 已拉起、还在监听前的空窗期
        if (run && engineStartTs == 0L) engineStartTs = System.currentTimeMillis();
        // v1.13：只在“真就绪”时结束启动中；并且启动中不得点亮入口。
        // （反面教训：把“node 进程活着”当就绪 → 按钮提前亮、点进去 3080 未监听 → “点了没反应”）
        if (run) starting = false;
        else if (starting && engineStartTs > 0
                && System.currentTimeMillis() - engineStartTs > 150000L) starting = false;   // 超时兜底
        boolean busy = !run && (booting || starting);
        if (conEnState != null) conEnState.setText(run ? t("status.engine.ready", "引擎运行中")
                : (busy ? t("status.engine.starting", "启动中…") : t("status.engine.idle", "未启动")));
        if (conEnMeta != null) conEnMeta.setText(conEngineMetaText(run, ready, busy));
        if (conEnBtn != null) {
            conEnBtn.setText(run ? t("btn.engine.openUi", "打开主界面")
                    : (busy ? t("status.engine.starting", "启动中…") : t("btn.engine.start", "启动引擎")));
            cSetEnabled(conEnBtn, !busy && (run || ready));
        }
        cSetEnabled(conEnRestart, run || busy);
        cSetEnabled(conEnStop, run || busy);
        if (conFoot != null) conFoot.setText(run ? t("status.serving", "本地服务已就绪")
                : (busy ? t("status.starting", "正在启动引擎…") : t("status.ready", "就绪")));
        // v1.19.5 精简版的状态块：未解压时讲"运行环境"，解压好了讲"引擎"——
        // 避免同一屏出现两套状态（v1.19.2 记录过的"糙"点之一）。
        // ⚠ 只在精简版生效：卡片版那两个字段一直都该可见，不能被这里改掉。
        if (conRenderedSimple) {
            if (conExState != null) conExState.setVisibility(ready ? View.GONE : View.VISIBLE);
            if (conEnState != null) conEnState.setVisibility(ready ? View.VISIBLE : View.GONE);
            if (conEnMeta != null) conEnMeta.setVisibility(ready ? View.VISIBLE : View.GONE);
        }
        // 精简版：把"下一步该干什么"收敛到一个按钮上（文案随状态变）
        if (uiSimpleActionBtn != null) {
            uiSimpleActionBtn.setText(!ready ? (extracting ? t("status.extract.running", "解压中…") : t("btn.extract.run", "解压文件"))
                    : (run ? t("btn.engine.openUi", "打开主界面")
                           : (busy ? t("status.engine.starting", "启动中…") : t("btn.engine.start", "启动引擎"))));
            cSetEnabled(uiSimpleActionBtn, !extracting && !busy && (run || ready || !extracting));
        }
    }

    private String conExtractMetaText(boolean ready) {
        if (extracting) return conExtractMsg != null ? conExtractMsg
                : t("status.extract.working", "正在解压运行时与内核树…");
        if (ready) {
            String s = conFilesSummary != null ? conFilesSummary : "运行环境与内核树已就绪";
            // B9：把完整性结论直接摆在文件行上（OK 不打扰，DRIFT 才显眼）
            String pi = payloadIntegrity;
            if (pi != null) s += pi.startsWith("DRIFT") ? " · ⚠ 完整性异常" : " · 完整性 OK";
            else if (consoleDetailOpen) s += " · 正在核对完整性…";
            // v1.21（用户要求）：这里不再追加"· 点这一行看详情" ——
            // 状态块已经没有点击（详情入口已删除），这句话只会误导。
            return s;
        }
        return t("desc.extract", "需要解压运行环境与内核（约 2.5 万个文件 / 约 220 MB）；解压完成后才能启动引擎。");
    }

    private String conEngineMetaText(boolean run, boolean ready, boolean busy) {
        if (run) {
            long mins = engineStartTs > 0 ? Math.max(0, (System.currentTimeMillis() - engineStartTs) / 60000) : 0;
            return "端口 " + enginePort + " · 已运行 " + mins + " 分 · 通知 " + notifyPort();
        }
        // v1.13：启动中显示统一的倒计时文案（真机 node 预熟要 17~25 秒，必须给用户一个“在动”的反馈）
        if (busy) {
            long sec = engineStartTs > 0 ? Math.max(0, (System.currentTimeMillis() - engineStartTs) / 1000) : 0;
            return "端口 " + enginePort + " · 启动中… 已等待 " + sec + " 秒";
        }
        // v1.13：原来无论文件是否已解压都写“解压完成后可启动”，已解压时这句误导人。
        return (ready ? "点「启动引擎」开始 · 端口 " : "解压完成后可启动 · 端口 ") + enginePort;
    }

    // ---------- 动作：解压 / 启动 ----------
    private void conExtractClick() {
        if (extracting || starting) return;
        if (conFilesReady()) { conReExtract(); return; }
        conStartExtract();
    }

    private void conStartExtract() {
        if (extracting) return;
        if (!conFilesReady()) {
            // 本次安装还没解压过（升级安装最常见）：清掉旧标记 → 走一次「完整内部解压」
            // （runtime/node/so/dshhome/rish 全部重写），避免“拿着上一版的树”看着像已解压。
            try { new File(payloadDir(), ".extracted").delete(); } catch (Throwable ignored) {}
        }
        extracting = true;
        conExtractMsg = null;
        extractOnlyMode = true;
        filesPreparedThisBoot = false;
        refreshConsole();
        startEngine();   // 复用同一条链路；extractOnlyMode 会在文件准备完后提前返回
    }

    private void conReExtract() {
        if (extracting || starting) { conToast("正在忙，稍后"); return; }
        filesPreparedThisBoot = false;
        extracting = true;
        conExtractMsg = null;
        extractOnlyMode = true;
        refreshConsole();
        new Thread(new Runnable() { @Override public void run() {
            try { new File(payloadDir(), ".extracted").delete(); } catch (Throwable ignored) {}
            try { new File(payloadDir(), "dshroot/.complete").delete(); } catch (Throwable ignored) {}
            ui.post(new Runnable() { @Override public void run() { extracting = false; conStartExtract(); } });
        }}, "extract-reset").start();
    }

    private void conEngineClick() {
        if (starting) return;
        // v1.13：判定“引擎是否已在跑”必须先真正探一次端口，而探测「只能在后台线程做」
        // （主线程做网络 IO → NetworkOnMainThreadException 被吞 → 误判未启动 → 又拉起第二个 node）。
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean running = conProbeEngineNow();
                ui.post(new Runnable() { @Override public void run() { conEngineClickAfterProbe(running); } });
            }
        }, "engine-click-probe").start();
    }

    private void conEngineClickAfterProbe(boolean running) {
        if (starting) return;
        if (running) {
            engineStartAborted = false;
            engineStoppedByUser = false;
            enterMainUi();
            return;
        }
        if (!conFilesReady()) { conToast("先解压文件"); return; }
        extracting = false;
        extractOnlyMode = false;
        starting = true;
        engineStartTs = System.currentTimeMillis();
        refreshConsole();
        startEngine();   // 文件已就绪 → 只起引擎
    }

    /**
     * 「重启」原来只 destroy 内存里的 nodeProcess 句柄 ——
     * Activity 被重建 / 进程被杀后重开时那个句柄是 null，于是点了重启等于什么都没发生
     * （用户反馈原话：“点重启其实没有用，你并没有被重启”）。
     * 现在改成按 PID 真杀（见 killEngineNow），并等端口真正释放后再拉起新引擎。
     */
    private void conRestartEngine() {
        if (starting) return;
        conToast("正在重启引擎…");
        engineStoppedByUser = false;
        engineStartAborted = false;
        new Thread(new Runnable() { @Override public void run() {
            killEngineNow();
            // 等端口释放：否则紧接着的探针会看到旧进程还 listen → 误判“已在运行” → 又不重启
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline && portListening(enginePort)) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            ui.post(new Runnable() { @Override public void run() { conEngineClick(); } });
        }}, "engine-restart").start();
    }

    private void conStopEngine() {
        // v1.13：旧实现只 destroy 进程，两个后果 —— ① 在飞的 waitForServer 仍每秒刷“已等待 N 秒”（界面一直计时）；
        // ② 看门狗 5 秒后看到 nodeProcess 非空却已死 → 又把引擎拉起来（用户看到的“停不掉”）。
        // 同样不能只 destroy 句柄 —— 句柄丢了就什么都停不掉，改用 killEngineNow（按 PID）。
        engineStoppedByUser = true;
        engineStartAborted = true;
        // v1.21：把"用户主动停止"持久化。否则进程被杀重开后这个内存标记就丢了，
        // 而 v1.21 的秒进会在冷启动时自动把引擎又拉起来 —— 与用户意图相反。
        markEngineStoppedByUser(true);
        conToast("正在停止引擎…");
        // v1.13.12：停引擎 = 虚拟屏一起销毁（用户确认的行为）。虚拟屏由 AI 经引擎驱动，
        // 引擎停了虚拟屏就是一块没人管的孤儿屏；不一起收掉的话它还挂在屏幕上。
        VsreenBridgeService.requestDestroyVscreen();
        new Thread(new Runnable() { @Override public void run() {
            killEngineNow();
            starting = false;
            engineStartTs = 0L;
            ui.post(new Runnable() { @Override public void run() {
                setStatus("引擎已停止");
                refreshConsole();
                conToast("引擎已停止");
            }});
        }}, "engine-stop").start();
    }

    /**
     * 找出当前真正在跑的引擎 node 进程 PID（不依赖内存里的 Process 句柄）。
     *
     * 为什么可行：node 是本 App 的子进程、「同一个 uid」，而同 uid 的进程在 /proc 里互相可见
     * （真机实测：App 身份能读到 /proc/&lt;pid&gt;/cmdline）。所以哪怕 Activity 被重建、
     * 句柄丢了，也仍然能定位并终止它。
     * 认人条件：cmdline 同时含 bin.js、web、--port &lt;enginePort&gt;，避免误杀别的 node。
     */
    private int findEnginePid() {
        return findEnginePid(enginePort);
    }

    /** v1.21：静态版引擎 PID 查找（供「完全退出」在无 Activity 实例时使用）。 */
    private static int findEnginePid(int port) {
        File[] kids;
        try { kids = new File("/proc").listFiles(); } catch (Throwable t) { return -1; }
        if (kids == null) return -1;
        int self = android.os.Process.myPid();
        for (File d : kids) {
            String name = d.getName();
            if (name == null || name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(name); } catch (Throwable t) { continue; }
            if (pid == self) continue;
            String cmd = readProcCmdline(pid);
            if (cmd == null || cmd.length() == 0) continue;
            if (cmd.indexOf("bin.js") < 0) continue;
            if (cmd.indexOf(" web") < 0) continue;
            if (cmd.indexOf("--port " + port) < 0) continue;
            return pid;
        }
        return -1;
    }

    /** 读 /proc/&lt;pid&gt;/cmdline（NUL 分隔 → 空格）。读不到返回 null。 */
    private static String readProcCmdline(int pid) {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/" + pid + "/cmdline");
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) return "";
            for (int i = 0; i < n; i++) if (buf[i] == 0) buf[i] = ' ';
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 真把引擎进程杀掉（阻塞直到死透或超时）。
     * 先 SIGTERM 让 node 正常退出（会释放端口），6 秒内没死再 SIGKILL 兜底。
     * 调用方必须在后台线程（内部有 sleep / 轮询）。
     */
    private void killEngineNow() {
        int pid = findEnginePid();
        if (pid > 0) {
            try { android.os.Process.sendSignal(pid, 15); } catch (Throwable ignored) {}   // SIGTERM
        }
        try { if (nodeProcess != null && nodeProcess.isAlive()) nodeProcess.destroy(); } catch (Throwable ignored) {}
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            if (findEnginePid() <= 0) break;
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        int still = findEnginePid();
        if (still > 0) {
            Log.w(TAG, "engine pid " + still + " still alive after SIGTERM, SIGKILL");
            try { android.os.Process.killProcess(still); } catch (Throwable ignored) {}     // SIGKILL 兜底
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        }
        nodeProcess = null;
    }

    private void enterMainUi() {
        // v1.21：渲染进程崩溃过的 WebView 实例已不可用（loadUrl 会二次崩溃）→ 重建 Activity
        // （等同"刷新界面"；重建后窗口恢复路径会直接起引擎/进主界面）。
        if (webViewBroken) {
            Log.i(TAG, "enterMainUi: WebView 已损坏，重建界面");
            webViewBroken = false;
            try { recreate(); return; } catch (Throwable ignored) {}
        }
        consoleVisible = false;
        if (consoleLayer != null) consoleLayer.setVisibility(View.GONE);
        if (consoleLayerBox != null) consoleLayerBox.setVisibility(View.GONE);   // v1.17.4：背景图一起收
        if (conTick != null) conTick.removeCallbacks(consoleTick);
        // 没有 token 时先从日志里捞回来（否则 WebView 只能 401 认证页 → 白屏）
        if (engineTokenUrl == null) {
            String u = conTokenFromLog();
            if (u != null) engineTokenUrl = u;
        }
        loadHome();
    }

    private void conExtractDone() {
        extracting = false;
        consoleDetailOpen = false;
        computeFilesSummaryAsync();
        refreshConsole();
        int healed = lastHealOrphans;
        // 自愈账本：解压/清理是最常见的一次"自愈"，必须留证（依据 = 全量解压按 payload 覆盖 + 清 dsh* 陈旧包）
        conLedgerWrite("extract", "{\"trigger\":\"user-extract\",\"prunedStaleKernelEntries\":" + healed
                + ",\"kernelRoot\":\"" + jesc(String.valueOf(dshrootDir)) + "\","
                + "\"note\":\"全量解压：按 payload 覆盖内核树，并清理 payload 已没有的 dsh* 陈旧内核包（用户数据与第三方插件不动）\"}");
        conToast(healed > 0
                ? "解压完成（顺带清理了 " + healed + " 个内核树里多余的文件），可以启动引擎了"
                : "解压完成，可以启动引擎了");
    }

    private void conExtractFailed(String msg) {
        extracting = false;
        refreshConsole();
        conToast("解压失败：" + msg);
    }

    private void conEngineFailed(String msg) {
        starting = false;
        refreshConsole();
        conToast("引擎启动失败：" + msg);
    }

    /** 只负责起引擎（文件已就绪）：健康探测 → spawnNode → 等就绪。v1.12 从 startEngine 拆出。 */
    private void launchEngine(File payload) {
        if (healthOk()) {
            starting = false;
            engineAliveCached = true;                        // v1.13：写回缓存 → 控制台立刻显示“引擎运行中”
            engineProbeAt = System.currentTimeMillis();
            if (engineStartTs == 0L) engineStartTs = System.currentTimeMillis();
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
            if (!consoleVisible) loadHome();
            return;
        }
        showIndeterminate("正在启动 DeepSeek Harness…");
        starting = true;
        if (engineStartTs == 0L) engineStartTs = System.currentTimeMillis();
        // v1.13：已有一个 node 进程在跑（可能只是还没开始监听端口）→ 「绝不再 spawn 第二个」。
        // 旧实现只看 healthOk()：node 启动中的那几秒会被误判为“没在跑”→ 重复 spawn。
        // 真机日志实证：同一时刻两个 node 抢 3080，第二次流程的 notify 端口 3081 直接 EADDRINUSE。
        Process alive = nodeProcess;
        if (alive != null && alive.isAlive()) {
            Log.w(TAG, "engine process already alive, wait instead of respawning");
            waitForServer();
            starting = false;
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
            return;
        }
        // v1.13 第二道防线：句柄丢了（App 重启/被系统回收）也不凭 healthOk() 就重建 ——
        // node 启动中虽然不响应 HTTP，但「端口已经 listen」；只要端口被占就不该再拉一个。
        // （引擎日志里 EADDRINUSE 高达 72 次 vs 成功启动 38 次，重复拉起是最高频的浪费。）
        if (portListening(enginePort)) {
            Log.w(TAG, "port " + enginePort + " already listening, wait instead of respawning");
            waitForServer();
            starting = false;
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
            return;
        }
        try {
            spawnNode(payload);
        } catch (Throwable t) {
            starting = false;
            Log.e(TAG, "spawnNode failed", t);
            final String msg = String.valueOf(t.getMessage());
            setStatus("引擎启动失败：" + msg);
            writeStartupDiag(msg);
            ui.post(new Runnable() { @Override public void run() { conEngineFailed(msg); } });
            return;
        }
        waitForServer();
        starting = false;
        ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
    }

    // ---------- 文件摘要 / 校验 ----------
    private void computeFilesSummaryAsync() {
        if (!conFilesReady()) { conFilesSummary = null; return; }
        new Thread(new Runnable() { @Override public void run() {
            final String s = conComputeFilesSummary();
            payloadIntegrity = checkPayloadIntegrity();   // B9：runtime/ 只有几十个文件，代价可忽略
            ui.post(new Runnable() { @Override public void run() { conFilesSummary = s; refreshConsole(); } });
        }}, "files-summary").start();
    }

    private String conComputeFilesSummary() {
        try {
            java.util.ArrayDeque<File> q = new java.util.ArrayDeque<File>();
            q.add(new File(payloadDir(), "dshroot"));
            long n = 0;
            long bytes = 0;
            while (!q.isEmpty()) {
                File f = q.poll();
                File[] cs = f.listFiles();
                if (cs == null) continue;
                for (int i = 0; i < cs.length; i++) {
                    if (cs[i].isDirectory()) q.add(cs[i]);
                    else { n++; bytes += cs[i].length(); }
                }
            }
            return String.format(java.util.Locale.US, "%,d 个文件 · %d MB", Long.valueOf(n), Long.valueOf(bytes / 1048576L));
        } catch (Throwable t) { return null; }
    }

    private void conVerifyFiles() {
        conToast("校验中…");
        new Thread(new Runnable() { @Override public void run() {
            final String miss = conMissingKey();
            int done = 0;
            try { done = getSharedPreferences("dsh_prefs", MODE_PRIVATE).getInt("payload_build_code", 0); } catch (Throwable ignored) {}
            final int fdone = done;
            final int cur = conBuildCode();
            final String s = conComputeFilesSummary();
            final String integrity = checkPayloadIntegrity();   // B9：与打包清单核对 runtime/
            ui.post(new Runnable() { @Override public void run() {
                if (miss != null) conToast("校验失败：缺 " + miss + "，请点「重新解压」");
                else if (fdone != cur) conToast("校验失败：内部文件是旧版本解压的（记录 " + fdone + " / 当前 " + cur + "），请重新解压");
                else if (integrity != null && integrity.startsWith("DRIFT")) conToast("校验：" + integrity);
                else conToast("校验通过，" + (s == null ? "文件齐全" : s) + " · 已对应当前安装版本");
            }});
        }}, "files-verify").start();
    }

    // ============ B9：payload 完整性清单核对 ============
    private static final String PAYLOAD_MANIFEST_ASSET = "payload_manifest.txt";
    private static final String INTEGRITY_FILE = "payload-integrity.txt";
    /** 最近一次完整性核对结论（控制台文件行与「校验」按钮共用；null = 还没跑过）。 */
    private volatile String payloadIntegrity = null;

    /** 从 "files=123 bytes=456" 里取一个字段；取不到返回 -1。 */
    private static long manifestField(String text, String key) {
        int i = text.indexOf(key + "=");
        if (i < 0) return -1;
        int p = i + key.length() + 1;
        int end = p;
        while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') end++;
        if (end == p) return -1;
        try { return Long.parseLong(text.substring(p, end)); } catch (Throwable t) { return -1; }
    }

    /** 从清单里取一个**字符串**字段（形如 `key=value` 的整行）；取不到返回 ""。 */
    private static String manifestStr(String text, String key) {
        if (text == null) return "";
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        return "";
    }

    private static final String PREF_PAYLOAD_SHA = "payload_zip_sha256";

    /**
     * 本次安装的 payload.zip 与上次解压过的那份是否不同。
     *
     * 为什么需要：`dshroot-fast` 同步只刷 REVISION + 官方白名单，**不 stat 也不解压新增文件**。
     * 于是本次打包如果**新增了文件**（踩过的例子：node-pty 替身包 `dshroot/lib/node_modules/node-pty/`），
     * fast 同步会让它永远不落地 —— 表现为"新补丁装了却没生效"，报错还停在旧状态。
     * payload.zip 的 SHA-256（打包时写进 assets/payload_manifest.txt）一变，就改走
     * `dshroot-add`：缺失文件会写、白名单照旧覆盖，代价只是一次 stat（仅升级那次）。
     */
    private boolean payloadZipChanged() {
        try {
            String builtin = manifestStr(readAssetText(PAYLOAD_MANIFEST_ASSET), "payload_zip_sha256");
            if (builtin.isEmpty()) return false;   // 旧包没有清单 → 维持原行为
            String done = getSharedPreferences("dsh_prefs", MODE_PRIVATE).getString(PREF_PAYLOAD_SHA, "");
            return !builtin.equals(done);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同步成功后记住本次 payload.zip 的 SHA-256（下次比较用）。 */
    private void rememberPayloadZipSha() {
        try {
            String builtin = manifestStr(readAssetText(PAYLOAD_MANIFEST_ASSET), "payload_zip_sha256");
            if (!builtin.isEmpty()) {
                getSharedPreferences("dsh_prefs", MODE_PRIVATE).edit().putString(PREF_PAYLOAD_SHA, builtin).apply();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 把解压结果与打包清单核对（B9）。**只对 runtime/ 做判定**：这块完全由 App 拥有，
     * 且 B8 刚改过它的打包方式 —— soname 别名由 applyLinks 重建（不增加字节），
     * 一旦别名被"实体化"（历史上 ICU 三份独立 inode）就会字节暴涨，正是要抓的静默膨胀。
     * dshroot/ 只作参考：它含用户改动（.complete、REVISION）与用户层文件，不能严格比对。
     * @return null=清单缺失（旧包）；"OK …" / "DRIFT …"
     */
    private String checkPayloadIntegrity() {
        try {
            final String txt = readAssetText(PAYLOAD_MANIFEST_ASSET);
            long mfFiles = -1, mfBytes = -1, links = -1;
            for (String raw : txt.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("subtree runtime files=")) {
                    String body = line.substring("subtree runtime ".length());
                    mfFiles = manifestField(body, "files");
                    mfBytes = manifestField(body, "bytes");
                } else if (line.startsWith("links_expected=")) {
                    links = manifestField(line, "links_expected");
                }
            }
            if (mfBytes <= 0) return null;
            // ① 别名（LINKS.txt 里的名字）必须排除在字节统计之外：
            //    File.length() 会跟随软链返回**目标大小**，硬链也会各自计一次 —— 不排除就会把
            //    一份库算成三份（首版检查就是这么误报 +81MB 的）。
            java.util.HashSet<String> aliases = new java.util.HashSet<String>();
            File lf = new File(payloadDir(), "runtime/lib/LINKS.txt");
            if (lf.exists()) {
                java.io.BufferedReader lr = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(lf), "UTF-8"));
                String ln;
                while ((ln = lr.readLine()) != null) {
                    ln = ln.trim();
                    if (ln.isEmpty() || ln.startsWith("#")) continue;
                    String[] parts = ln.split("\\t+");
                    if (parts.length >= 2) aliases.add(parts[0].trim());
                }
                lr.close();
            }
            // ② 遍历 runtime/：非别名文件计字节；别名单独统计"是否存在"与"是否独立 inode"
            File runtimeDir = new File(payloadDir(), "runtime");
            long files = 0, bytes = 0, aliasSeen = 0, aliasIndependent = 0;
            java.util.ArrayDeque<File> q = new java.util.ArrayDeque<File>();
            q.add(runtimeDir);
            while (!q.isEmpty()) {
                File f = q.poll();
                File[] cs = f.listFiles();
                if (cs == null) continue;
                for (int i = 0; i < cs.length; i++) {
                    if (cs[i].isDirectory()) { q.add(cs[i]); continue; }
                    String rel = relativize(runtimeDir, cs[i]);
                    // 运行期**生成**的两个文件（合成 CA bundle / git 证书配置）不在包里，不该算差异；
                    // runtime/etc/openssl.cnf 是包内文件，必须照常计数。
                    if (rel.equals("etc/cacert.pem") || rel.equals("etc/gitconfig")) continue;
                    // LINKS.txt 里的名字是 **runtime/lib 下**的裸名；rel 是相对 runtime/ 的路径 → 去前缀再比对
                    String aliasKey = rel.startsWith("lib/") ? rel.substring(4) : rel;
                    if (aliases.contains(aliasKey)) {
                        aliasSeen++;
                        String tgt = linkTargetOf(aliases, aliasKey);
                        if (tgt != null && isIndependentCopy(cs[i], new File(new File(runtimeDir, "lib"), tgt))) {
                            aliasIndependent++;
                        }
                        continue;
                    }
                    files++;
                    bytes += cs[i].length();
                }
            }
            long slack = Math.max(65536L, mfBytes / 50);                       // 2% 且不少于 64KB
            boolean bytesHigh = bytes > mfBytes + slack;
            boolean bytesLow  = bytes < mfBytes - slack;
            boolean filesOff  = files != mfFiles;
            boolean aliasMissing = links > 0 && aliasSeen < links;
            String info = "runtime files=" + files + "/" + mfFiles + " bytes=" + bytes + "/" + mfBytes
                    + " 别名=" + aliasSeen + "/" + (links < 0 ? "?" : links)
                    + (aliasIndependent > 0 ? "（其中 " + aliasIndependent + " 个是独立副本，下次启动自愈）" : "");
            String verdict;
            if (bytesHigh)           verdict = "DRIFT 体积异常偏大（可能有重复实体）：" + info;
            else if (bytesLow)       verdict = "DRIFT 体积偏小（可能缺文件）：" + info;
            else if (filesOff)       verdict = "DRIFT 文件数不符：" + info;
            else if (aliasMissing)   verdict = "DRIFT 别名缺失（soname 链接没建起来）：" + info;
            else if (aliasIndependent > 0) verdict = "DRIFT 别名被实体化（已标记自愈）：" + info;
            else                     verdict = "OK " + info;
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(getFilesDir(), INTEGRITY_FILE));
                fos.write((verdict + "\n").getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {}
            if (verdict.startsWith("DRIFT")) Log.w(TAG, "payload integrity " + verdict);
            else Log.i(TAG, "payload integrity " + verdict);
            return verdict;
        } catch (Throwable t) {
            Log.w(TAG, "checkPayloadIntegrity: " + t.getMessage());
            return null;
        }
    }

    /** runtime/ 内的相对路径（POSIX 分隔符）。 */
    private static String relativize(File root, File f) {
        String r = root.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (a.startsWith(r)) {
            String s = a.substring(r.length());
            while (s.startsWith("/")) s = s.substring(1);
            return s;
        }
        return f.getName();
    }

    /** 从别名集合里反查目标名（重新读一遍 LINKS.txt 太浪费，这里只在检查里用一次）。 */
    private String linkTargetOf(java.util.HashSet<String> aliases, String rel) {
        try {
            File lf = new File(payloadDir(), "runtime/lib/LINKS.txt");
            java.io.BufferedReader lr = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(lf), "UTF-8"));
            String ln;
            while ((ln = lr.readLine()) != null) {
                ln = ln.trim();
                if (ln.isEmpty() || ln.startsWith("#")) continue;
                String[] parts = ln.split("\\t+");
                if (parts.length >= 2 && parts[0].trim().equals(rel)) { lr.close(); return parts[1].trim(); }
            }
            lr.close();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 两个路径是否指向**不同**的 inode（软链会跟随，所以软链/硬链都判为同一 inode）。 */
    private static boolean isIndependentCopy(File a, File b) {
        try {
            android.system.StructStat sa = android.system.Os.stat(a.getAbsolutePath());
            android.system.StructStat sb = android.system.Os.stat(b.getAbsolutePath());
            return !(sa.st_dev == sb.st_dev && sa.st_ino == sb.st_ino);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 权限页 ----------
    private String conPermSummary() {
        // v1.21（用户反馈）：这里原来只有 9 项，而首启引导有 11 页 —— 两处口径不一致。
        // 现在把引导里出现、权限页缺的三项补齐：存储权限（旧版 Android）、修改系统设置、使用情况访问。
        String[] ids = {"legacy", "storage", "settings", "usage", "notify", "overlay", "battery",
                "root", "shizuku", "a11y", "applist", "install"};
        int ok = 0;
        for (int i = 0; i < ids.length; i++) if (conPermOk(ids[i])) ok++;
        return "已授权 " + ok + " / " + ids.length;
    }

    private boolean conPermOk(String id) {
        try {
            if ("storage".equals(id)) {
                if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
                return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
            }
            if ("notify".equals(id)) {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                return nm != null && nm.areNotificationsEnabled();
            }
            if ("overlay".equals(id)) return Settings.canDrawOverlays(this);
            if ("battery".equals(id)) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
            if ("root".equals(id)) return conRootOk;
            if ("shizuku".equals(id)) return shizukuOk != null && shizukuOk.booleanValue();
            if ("a11y".equals(id)) return conA11yEnabled();
            if ("applist".equals(id)) return conAppListOk();
            if ("install".equals(id)) return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
            // ↓ v1.21（用户反馈）：补齐"引导页有、权限页原先没有"的三项，两处口径一致
            if ("legacy".equals(id)) {          // 存储权限：Android 10 及以下才是独立权限
                if (Build.VERSION.SDK_INT >= 30) return true;   // 11+ 由「所有文件访问」统一覆盖
                return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
            }
            if ("settings".equals(id)) return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
            if ("usage".equals(id)) {
                android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
                if (ops == null) return false;
                int mode;
                if (Build.VERSION.SDK_INT >= 29) {
                    mode = ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(), getPackageName());
                } else {
                    mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(), getPackageName());
                }
                return mode == android.app.AppOpsManager.MODE_ALLOWED;
            }
        } catch (Throwable t) { return false; }
        return false;
    }

    private boolean conA11yEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            return s.toLowerCase().contains(getPackageName().toLowerCase());
        } catch (Throwable t) { return false; }
    }

    /** 最近一次"能看到多少个应用"（引导页/权限页显示用；-1 = 还没测过）。 */
    private volatile int lastAppListCount = -1;

    /**
     * 「读取应用列表」是否可用。
     *
     * 事实（见 AndroidManifest 与 dsh-tool-android 注释）：本应用 **targetSdk=28**，
     * 而 Android 11+ 的应用可见性过滤**只作用于 targetSdk≥30 的应用** ——
     * 所以现在 `getInstalledApplications()` 本就能看到全部应用，**不需要、也没有**
     * 可弹窗授予的权限；清单里的 QUERY_ALL_PACKAGES / &lt;queries&gt; 是给"将来提 targetSdk"兜底的。
     *
     * 判据用**实际能看到的应用数**（≥10 视为正常）：这样两种真实故障都能反映出来 ——
     *   ① 将来提 targetSdk 后没声明可见性 → 只看到自己（1~2 个）；
     *   ② 部分国产系统（MIUI/ColorOS 等）在「应用信息 → 权限」里把「读取应用列表」关掉。
     */
    private boolean conAppListOk() {
        try {
            PackageManager pm = getPackageManager();
            int n = pm.getInstalledApplications(0).size();
            lastAppListCount = n;
            return n >= 10;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开本应用的「应用信息 → 权限」页（国产 ROM 的「读取应用列表」开关在这里）。 */
    private void openAppDetailsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            openSystemSetting(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        }
    }

    /**
     * 主动触发一次「读取应用列表」并刷新状态。
     *
     * 为什么需要它：部分国产系统（MIUI / HyperOS / ColorOS / HarmonyOS 等）会在应用**第一次真正
     * 查询已安装应用**时弹一个系统框「允许 XXX 读取已安装应用列表吗？」——它既不是 Android
     * 标准运行时权限（无法用 requestPermissions 申请），也不在 AOSP 的权限列表里，所以只能
     * "真的去查一次"才会出现。用户此前的实际遭遇：引导页没这一项 → 直到让 agent 查应用时才弹框，
     * 场景很突兀。现在把这次查询挪到引导页/ap权限页的按钮上，在用户知情时一次性问掉。
     *
     * 查询走后台线程（binder 调用可能较慢）；结果回主线程：刷新状态行、给提示；
     * 如果仍然看不到足够应用（用户点了拒绝，或 ROM 把它做成了设置开关），再跳「应用信息」页。
     */
    private void requestAppListAccess() {
        new Thread(new Runnable() { @Override public void run() {
            int count = -1;
            try { count = getPackageManager().getInstalledApplications(0).size(); }
            catch (Throwable ignored) { count = -1; }
            final int n = count;
            ui.post(new Runnable() { @Override public void run() {
                lastAppListCount = n;
                if (n < 0) {
                    conToast("读取应用列表失败，请在「应用信息 → 权限」里检查");
                } else if (n >= 10) {
                    conToast("已能看到 " + n + " 个应用 ✓");
                } else {
                    conToast("当前只能看到 " + n + " 个应用：若刚才弹窗请选「允许」；"
                            + "仍不行就到「应用信息 → 权限」打开「读取应用列表」");
                }
                try { refreshAllStatuses(); } catch (Throwable ignored) {}
                if (n >= 0 && n < 10) openAppDetailsSettings();
            }});
        }}, "applist-probe").start();
    }

    private void conRefreshRootAsync() {
        if (System.currentTimeMillis() - conRootProbeTs < 5000) return;
        conRootProbeTs = System.currentTimeMillis();
        new Thread(new Runnable() { @Override public void run() {
            boolean ok = false;
            try { ok = rootAvailable(); } catch (Throwable ignored) {}
            conRootOk = ok;
            ui.post(new Runnable() { @Override public void run() { refreshConsole(); } });
        }}, "root-probe").start();
    }

    private void renderConsolePerm() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.perm", "授予权限")));
        // v1.19.6：统一版式（返回行 + 一句说明 + 分组小标题 + 卡片块）。
        col.addView(cNote(t("desc.permPage", "点任意一行去授权 / 管理。root 与 Shizuku 二选一即可（root 优先）。")
                + "  " + conPermSummary()), cTop(cGap(8)));

        col.addView(cGrpTitle(t("title.grpPermBasic", "基本权限")), cTop(cGap(16)));
        LinearLayout c1 = cCardBox();
        // v1.21（用户反馈）：权限页原来只有 9 项、引导页有 11 页 —— 口径不一致。
        // 这里按**引导页的顺序**补齐三项：存储权限（旧版 Android）/ 修改系统设置 / 使用情况访问。
        addPermRow(c1, "存储权限", "读写手机文件（Android 10 及以下需要；11+ 由上面「所有文件访问」覆盖）", "legacy");
        addPermRow(c1, "所有文件访问", "读写 /sdcard，AI 才能碰你的文件", "storage");
        addPermRow(c1, "通知", "AI 发通知 / 提醒", "notify");
        addPermRow(c1, "悬浮窗", "黑鲸鱼悬浮窗 / 虚拟屏预览", "overlay");
        addPermRow(c1, "修改系统设置", "AI 调音量 / 亮度 / 铃声这类系统开关", "settings");
        addPermRow(c1, "使用情况访问", "AI 查「今天用了多久某个 App」", "usage");
        addPermRow(c1, "电池优化", "设为「不限制」，否则切后台引擎会被杀", "battery");
        col.addView(c1, cTop(cGap(8)));

        col.addView(cGrpTitle(t("title.grpPermPriv", "特权通道（二选一）")), cTop(cGap(18)));
        LinearLayout c2 = cCardBox();
        addPermRow(c2, "root（超级用户）", "替代 Shizuku 跑特权命令：装应用 / 改设置 / 虚拟屏点击 / 任意 shell", "root");
        addPermRow(c2, "Shizuku（免 root 特权通道）", "有 root 时用 root；没 root 时装 Shizuku 走同一套能力", "shizuku");
        col.addView(c2, cTop(cGap(8)));

        col.addView(cGrpTitle(t("title.grpPermMore", "其它")), cTop(cGap(18)));
        LinearLayout c3 = cCardBox();
        addPermRow(c3, "无障碍服务（读屏 / 点屏）", "android_screen / tap / type / see（不需要 root 或 Shizuku）", "a11y");
            // v1.21（我们保留）：读取应用列表 —— android_apps / 启动应用工具依赖
            addPermRow(c3, "读取应用列表", "AI 查看 / 启动你装的应用（多数手机无需授权）", "applist");
        addPermRow(c3, "安装未知应用", "android_package 装 APK 用", "install");
        // issue #30：工作区入口原先只在首启引导完成页，走完引导就再无入口（只能清数据重走引导）。
        // 这里复用同一套 onWorkspaceRowClick()，使权限页也能查看 / 更改 / 恢复默认。
        addWorkspaceRow(c3);
        col.addView(c3, cTop(cGap(8)));

        col.addView(cNote("root 只能由你在 root 管理器（Magisk / KernelSU）里授予本应用；"
                + "设备没 root 时这一项显示「本机无 root」。"), cTop(cGap(14)));
        conRefreshRootAsync();
    }

    /**
     * 控制台「权限」页的 AI 工作区行：显示当前绑定路径，点按进入「更改 / 恢复默认」。
     * 与引导完成页共用 onWorkspaceRowClick()，保证两条入口行为一致（issue #30）。
     */
    private void addWorkspaceRow(LinearLayout col) {
        final String cur = workspacePath();
        final boolean unset = (cur == null || cur.isEmpty());
        // v1.19.6：跟权限行统一 —— 右列短状态（选择 / 更改）+ ›，整行可点。
        // conWorkspaceDesc 这个字段保留：工作区变更后要靠它做局部刷新。
        conWorkspaceDesc = cText(unset ? "未设置（AI 文件操作在内部目录）" : cur, 11f, cSub(), false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(cText("AI 工作区（可选）", 13.5f, cText(), false));
        left.addView(conWorkspaceDesc);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(cText(unset ? "选择" : "更改", 11.5f, cAccent(), false));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        row.addView(cText("›", 14f, cSub(), false), clp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onWorkspaceRowClick(); }
        });
        col.addView(row);
    }

    /** 工作区变更后，若正停在控制台「权限」页就整页重绘（按钮文案「选择 / 更改」跟着变）。 */
    private void refreshConsolePermIfShown() {
        if (consoleVisible && consolePage == 1) renderConsole();
    }

    private void addPermRow(LinearLayout col, String title, String desc, final String id) {
        boolean ok = conPermOk(id);
        boolean noRoot = "root".equals(id) && !conRootOk;
        String state = ok ? t("status.perm.granted", "已授权")
                : (noRoot ? t("status.perm.noroot", "本机无 root") : t("status.perm.denied", "未授权"));
        final int color = ok ? cGreen() : (noRoot ? cSub() : cRed());
        // v1.19.6：右列只放一个短状态（原来是一个「管理 / 去授权」按钮 + 状态文字，一行里塞两样）；
        // 整行可点、带 › —— 与主控台的入口行同一套交互。无 root 时也给提示（点一下会说明）。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        View row = cCardRow(title, desc, state, color, true, new View.OnClickListener() {
            @Override public void onClick(View v) { conPermAction(id); }
        });
        col.addView(row);
        // 行之间给一条极淡的分隔（卡片内部；最后一行后面那条由卡片内边距兜住，视觉上可接受）
        View sep = new View(this);
        sep.setBackgroundColor(cLine());
        sep.setAlpha(0.5f);
        sep.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        col.addView(sep);
    }

    private void conPermAction(String id) {
        try {
            if ("storage".equals(id)) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } else {
                    requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                }
                return;
            }
            if ("notify".equals(id)) {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
                return;
            }
            if ("overlay".equals(id)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                return;
            }
            if ("battery".equals(id)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                return;
            }
            if ("root".equals(id)) {
                conToast("请在 root 管理器（Magisk / KernelSU）里给本应用授予 root");
                return;
            }
            if ("shizuku".equals(id)) {
                // v1.13.3：这里原来只调 probeShizuku()（纯探测）——用户点「管理」永远不会弹授权框。
                // 真机实测：引擎里 AI 调 shizuku_status / shizuku_shell（内部走 rish 广播）能弹出 Shizuku 授权框，
                // 控制台却不弹；差别就在“有没有发出真实请求”。改走统一入口：
                // 实时探测 → 未授权就 requestPermission + 两条真实触发（binder / rish）→ 仍未授权给手动引导。
                showShizukuDialog();
                return;
            }
            if ("a11y".equals(id)) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            if ("applist".equals(id)) {
                // 国产 ROM 的「读取应用列表」是"第一次真查询时弹框"，没有可申请的权限；
                // 所以这里直接触发一次真实查询（弹框就在这里出），查询完再刷新状态。
                requestAppListAccess();
                return;
            }
            if ("install".equals(id)) {
                if (Build.VERSION.SDK_INT >= 26) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } else {
                    conToast("本机系统无需单独授权");
                }
                return;
            }
            // ↓ v1.21：与引导页同样的三项目标页
            if ("legacy".equals(id)) {
                if (Build.VERSION.SDK_INT >= 30) { conToast("Android 11 及以上由「所有文件访问」统一管理"); return; }
                requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
                return;
            }
            if ("settings".equals(id)) {
                if (Build.VERSION.SDK_INT < 23) { conToast("本机系统无需单独授权"); return; }
                openSystemSetting(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                return;
            }
            if ("usage".equals(id)) { openSystemSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS); return; }
        } catch (Throwable t) {
            conToast("打不开系统页：" + t.getMessage());
        }
    }

    // ---------- 插件页 ----------
    private static final String[][] CON_PLUGINS = {
        {"tool-browser", "AI 浏览器：结构化 DOM 快照 + 稳定 ref（关掉则 AI 不能上网）"},
        {"tool-vscreen", "虚拟屏：建屏 / 看图 / 点击 · 8 个工具"},
        {"tool-accessibility", "无障碍读屏 / 手势 / 截图理解"},
        {"tool-android", "用量统计 / 悬浮窗 / 装包 / 应用与设置 / 截图 / 输入"},
        {"tool-shizuku", "特权 shell / 通知 / 剪贴板（root 或 Shizuku 任一）"},
        {"llm-pi-ai", "第三方供应商适配（关掉则「添加提供方」不可用）"},
        {"session-telemetry-otel", "遥测上报 · Android 上不需要"},
        {"session-log-download", "会话日志导出按钮（右上角）"},
        {"pwsh-sandbox", "PowerShell 沙箱 · Android 无 pwsh"},
        {"bash-sandbox", "bash 沙箱（本项目用 bash-local 替代）"},
        {"sandbox", "沙箱服务"},
    };

    private String conPlugSummary() {
        int on = 0;
        for (int i = 0; i < CON_PLUGINS.length; i++) if (!conPluginDisabled(CON_PLUGINS[i][0])) on++;
        return "已启用 " + on + " / " + CON_PLUGINS.length;
    }

    private String conPatchPath() { return new File(payloadDir(), "dshhome/cordis.patch.yml").getAbsolutePath(); }

    // ---------- cordis.patch.yml 读写（v1.13 重写） ----------
    // 文件结构：顶层是「平铺的 patch 条目数组」 —— 要么 `- id: <行id>` + `disabled: true` / `config:`
    // （作用在别层已注册的行上），要么 `- insert:` 桶（桶内 `    - id: <行id>` + `      name:` 注册新行）。
    // 两条硬规则：① patch 按列表顺序生效 —— insert 桶里的行必须先被注册，之后的行才能按 id 命中该行；
    // ② 开关只能「原地」改写条目本身那一行 —— 删掉 `- id:` 行会留下悬空 `name:`（YAML 重复键 → 引擎启动即崩）。

    /** 行首缩进宽度（空格/Tab 各计 1，够用）。 */
    private static int conIndentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    private static boolean conBlankOrComment(String trimmed) {
        return trimmed.length() == 0 || trimmed.startsWith("#");
    }

    private static String conSpaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    /** 拆行（去行尾 CR，保留空行）。 */
    private static java.util.List<String> conSplitLines(String txt) {
        String[] raw = txt.split("\n");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < raw.length; i++) {
            String l = raw[i];
            if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
            out.add(l);
        }
        return out;
    }

    private static String conJoinLines(java.util.List<String> lines, String nl) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) { sb.append(lines.get(i)); sb.append(nl); }
        return sb.toString();
    }

    /** 该行若是 `- id: xxx` 则返回 xxx，否则 null。 */
    private static String conRowIdOf(String trimmed) {
        if (!trimmed.startsWith("- id:")) return null;
        String id = trimmed.substring(5).trim();
        return id.length() == 0 ? null : id;
    }

    private int conFindRow(java.util.List<String> lines, String id) {
        for (int i = 0; i < lines.size(); i++) {
            String rid = conRowIdOf(lines.get(i).trim());
            if (rid != null && rid.equals(id)) return i;
        }
        return -1;
    }

    /** 条目块末行下标（含）：往下直到同级/更浅缩进的非空白行。 */
    private int conRowEnd(java.util.List<String> lines, int idIdx) {
        int base = conIndentOf(lines.get(idIdx));
        int end = idIdx;
        for (int j = idIdx + 1; j < lines.size(); j++) {
            String t = lines.get(j).trim();
            if (t.length() == 0) break;              // 空行即条目结束
            if (t.startsWith("#")) continue;         // 注释归下一条目
            if (conIndentOf(lines.get(j)) <= base) break;
            end = j;
        }
        return end;
    }

    /** 该行是否位于 `- insert:` 桶内（桶内行只能原地开关；顶层条目作用于别层注册的行）。 */
    private boolean conInsideInsert(java.util.List<String> lines, int idIdx) {
        int base = conIndentOf(lines.get(idIdx));
        for (int j = idIdx - 1; j >= 0; j--) {
            String t = lines.get(j).trim();
            if (conBlankOrComment(t)) continue;
            if (conIndentOf(lines.get(j)) < base) return t.startsWith("- insert:");
        }
        return false;
    }

    /** 条目内原地增/删/改 `disabled:` 行（绝不搬动条目本身）。 */
    private void conSetRowDisabled(java.util.List<String> lines, int idIdx, boolean disabled) {
        int end = conRowEnd(lines, idIdx);
        int at = -1;
        for (int j = idIdx + 1; j <= end; j++) {
            if (lines.get(j).trim().startsWith("disabled:")) { at = j; break; }
        }
        if (disabled) {
            String ind = conSpaces(conIndentOf(lines.get(idIdx)) + 2);
            if (at >= 0) lines.set(at, ind + "disabled: true");
            else lines.add(idIdx + 1, ind + "disabled: true");
        } else if (at >= 0) {
            lines.remove(at);
        }
    }

    /** 顶层 patch 条目（id 由别层注册，如 sandbox）：禁用=确保该条目存在且 disabled: true；
     *  启用=删掉 disabled 行，条目再无其它键时连 `- id:` 行一起删；新增一律「追加到文件末尾」
     *  （patch 按顺序生效，插入行必须先被注册）。 */
    private void conSetTopEntryDisabled(java.util.List<String> lines, String id, boolean disabled) {
        int at = conFindRow(lines, id);
        if (at < 0) {
            if (!disabled) return;
            lines.add("- id: " + id);
            lines.add("  disabled: true");
            return;
        }
        if (disabled) { conSetRowDisabled(lines, at, true); return; }
        int end = conRowEnd(lines, at);
        boolean hasOther = false;
        for (int j = at + 1; j <= end; j++) {
            String t = lines.get(j).trim();
            if (conBlankOrComment(t) || t.startsWith("disabled:")) continue;
            hasOther = true;
            break;
        }
        if (hasOther) { conSetRowDisabled(lines, at, false); return; }
        for (int j = end; j > at; j--) lines.remove(j);
        lines.remove(at);
    }

    /** 结构性自愈（返回是否有改动）：修复控制台旧实现写坏的 cordis.patch.yml。
     *  旧实现关插件时把 `- id: xxx` 整行删掉、再把条目搬到文件顶部，后果两连：
     *   ① 原地留下悬空 `name:` → YAML “duplicated mapping key” → 引擎「启动即崩」、App 反复重拉（界面一直闪）；
     *   ② 搬上去的条目落在 `- insert:` 之前 → 插入行还没注册 → 就算 YAML 合法也不生效。
     *  自愈动作：按包名补回被删的 `- id:` 行；把“只有 disabled 的顶层条目”折叠回它对应的 insert 行内。 */
    /** 从 `name: '@deepseek-ai/dsh-tool-vscreen'` 反推行 id（补回被删的 `- id:` 行用）。 */
    private static String conIdFromName(String raw) {
        String n = raw;
        if (n.length() > 1 && ((n.charAt(0) == '\'' && n.endsWith("'"))
                || (n.charAt(0) == '"' && n.endsWith("\"")))) {
            n = n.substring(1, n.length() - 1);
        }
        if (n.startsWith("@deepseek-ai/dsh-")) n = n.substring("@deepseek-ai/dsh-".length());
        else { int k = n.lastIndexOf('/'); if (k >= 0) n = n.substring(k + 1); }
        return n.length() == 0 ? null : n;
    }

    private boolean conNormalizePatchLines(java.util.List<String> lines) {
        boolean changed = false;
        // ① 悬空 name 行 → 补回 `- id:`。判据：一个条目里只允许一个 `name:` 键，
        //   同一个条目里再碰到第二个 name（前面不是 `- id:`）就说明它的 id 行被删了。
        int curRow = -1;            // 当前条目的 `- id:` 缩进；-1 = 不在条目内
        boolean sawName = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.length() == 0) { curRow = -1; sawName = false; continue; }   // 空行结束条目
            if (t.startsWith("#")) continue;
            int ind = conIndentOf(lines.get(i));
            if (conRowIdOf(t) != null) { curRow = ind; sawName = false; continue; }
            if (!t.startsWith("name:")) continue;
            if (curRow >= 0 && ind > curRow && !sawName) { sawName = true; continue; }   // 正常键
            String id = conIdFromName(t.substring(5).trim());
            if (id == null) continue;
            int at = (curRow >= 0 && ind > curRow) ? curRow : (ind > 2 ? ind - 2 : 0);
            lines.add(i, conSpaces(at) + "- id: " + id);
            curRow = at;
            sawName = false;    // 下一轮处理原 name 行时把它计作新条目的 name
            changed = true;
            i++;
        }
        // ② 顶层“只有 disabled”的条目若对应文件内某个 insert 行 → 折叠进行内
        for (int i = 0; i < lines.size(); i++) {
            String rid = conRowIdOf(lines.get(i).trim());
            if (rid == null || conIndentOf(lines.get(i)) != 0) continue;
            int end = conRowEnd(lines, i);
            boolean hasDisabled = false, hasOther = false;
            for (int j = i + 1; j <= end; j++) {
                String tt = lines.get(j).trim();
                if (conBlankOrComment(tt)) continue;
                if (tt.startsWith("disabled:")) { hasDisabled = true; continue; }
                hasOther = true;
                break;
            }
            if (!hasDisabled || hasOther) continue;
            int row = -1;
            for (int j = 0; j < lines.size(); j++) {
                String jid = conRowIdOf(lines.get(j).trim());
                if (jid != null && jid.equals(rid) && j != i && conInsideInsert(lines, j)) { row = j; break; }
            }
            if (row < 0) continue;
            conSetRowDisabled(lines, row, true);
            for (int j = end; j > i; j--) lines.remove(j);
            lines.remove(i);
            changed = true;
            i--;
        }
        return changed;
    }

    /** 起引擎前调用（幂等；失败只记日志，不阻断启动）。 */
    private void conHealPatchConfig() {
        try {
            File f = new File(conPatchPath());
            if (!f.exists()) return;
            String txt = readFileText(f);
            if (txt == null) return;
            String nl = txt.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
            java.util.List<String> lines = conSplitLines(txt);
            if (!conNormalizePatchLines(lines)) return;
            conWriteText(f, conJoinLines(lines, nl));
            Log.w(TAG, "cordis.patch.yml repaired (broken by an older console plugin toggle)");
        } catch (Throwable t) {
            Log.w(TAG, "conHealPatchConfig", t);
        }
    }

    private boolean conPluginDisabled(String id) {
        try {
            String txt = readFileText(new File(conPatchPath()));
            if (txt == null) return false;
            java.util.List<String> lines = conSplitLines(txt);
            int i = conFindRow(lines, id);
            if (i < 0) return false;
            int end = conRowEnd(lines, i);
            for (int j = i + 1; j <= end; j++) {
                String t = lines.get(j).trim();
                if (t.startsWith("disabled:")) return t.indexOf("true") >= 0;
            }
        } catch (Throwable t) { Log.w(TAG, "conPluginDisabled", t); }
        return false;
    }

    private void conSetPluginDisabled(String id, boolean disabled) {
        try {
            File f = new File(conPatchPath());
            String txt = readFileText(f);
            if (txt == null) return;
            String nl = txt.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
            java.util.List<String> lines = conSplitLines(txt);
            conNormalizePatchLines(lines);   // 先自愈，避免“越点越烂”
            int at = conFindRow(lines, id);
            if (at >= 0 && conInsideInsert(lines, at)) conSetRowDisabled(lines, at, disabled);
            else conSetTopEntryDisabled(lines, id, disabled);
            conWriteText(f, conJoinLines(lines, nl));
        } catch (Throwable t) { Log.w(TAG, "conSetPluginDisabled", t); conToast("写配置失败：" + t.getMessage()); }
    }

    private void conWriteText(File f, String s) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        try { fos.write(s.getBytes("UTF-8")); } finally { fos.close(); }
    }

    private void renderConsolePlug() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.plugins", "插件")));
        col.addView(cNote(t("desc.pluginsPage",
                "关掉的插件不加载：工具不进 AI 的工具表，也少占上下文。改动在重启引擎后生效。")), cTop(cGap(8)));

        col.addView(cGrpTitle(t("title.grpPlugList", "插件列表") + " · " + conPlugSummary()), cTop(cGap(16)));
        LinearLayout box = cCardBox();
        for (int i = 0; i < CON_PLUGINS.length; i++) {
            final String id = CON_PLUGINS[i][0];
            boolean disabled = conPluginDisabled(id);
            box.addView(cCardRowWith("dsh-" + id, CON_PLUGINS[i][1], cToggle(id, !disabled)));
        }
        col.addView(box, cTop(cGap(8)));

        Button apply = cButton(t("btn.plugins.restart", "重启引擎生效"), true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conRestartEngine(); }
        });
        col.addView(apply, cTop(cGap(14)));
    }

    // ========== v1.19.6 · 阶段 C：AI 浏览器页（同屏查看 / 页签 / 关闭） ==========
    //
    // 设计（tmp-diag/v1180/browser-process-design.md「阶段 C」）：
    //   · 「同屏查看」= 把 :browser 那扇承载窗由 INVISIBLE 切可见（原生侧的 panel op）。
    //     **只切可见性** —— 动尺寸会改 WebView 的 CSS 视口，AI 的 ref / 点击坐标口径全建立在它上面。
    //   · 只读：承载窗保持 NOT_TOUCHABLE，触摸穿透到下面，用户照样操作控制台。
    //   · 本页所有 op 都在**后台线程**跑（op 是阻塞编排，AI 的 op 可能正占着）；
    //     handleBrowserRequest 是 synchronized 的，所以不会与 AI 抢 BrowserIpc 那唯一的 inbox。
    //   · 状态只认原生回的 running / visible / tabs（panelStateJson），前端不猜。

    /** 状态缓存：后台线程写、UI 线程读。 */
    private volatile boolean conBrowserRunning = false;
    private volatile boolean conBrowserPanel = false;
    private volatile int conBrowserCount = -1;          // -1 = 还没查过
    private volatile String conBrowserErr = "";
    private volatile String conBrowserTabs = "";        // tabs.list 原文
    private volatile boolean conBrowserBusy = false;
    private volatile boolean conBrowserQueried = false;

    /** 主控台那一行的右列短状态（没查过就不显示 —— 别每渲染一次就打一次 op）。 */
    private String conBrowserSummary() {
        if (conBrowserBusy) return t("status.browserBusy", "查询中…");
        if (!conBrowserQueried) return "";
        if (!conBrowserRunning || conBrowserCount <= 0) return t("status.browserIdle", "未运行");
        return conBrowserCount + " 个页签" + (conBrowserPanel ? " · " + t("status.browserPanelOn", "同屏中") : "");
    }

    /** 状态行文字（**UI 线程**：要用 t()）。 */
    private String conBrowserStateText() {
        if (conBrowserErr.length() > 0) return conBrowserErr;
        if (!conBrowserQueried) return t("status.browserBusy", "查询中…");
        if (!conBrowserRunning || conBrowserCount <= 0) return t("status.browserIdle", "未运行");
        return t("status.browserRunning", "运行中") + " · " + conBrowserCount + " 个页签 · "
                + (conBrowserPanel ? t("status.browserPanelOn", "同屏中") : t("status.browserPanelOff", "同屏关"));
    }

    /** 行内直连（控制台 → 主进程 → :browser）。op 必须在**非主线程**调用。 */
    private String conBrowserCall(String op, String argsJson) {
        return handleBrowserRequest("{\"op\":\"" + op + "\",\"args\":" + argsJson + ",\"timeout_ms\":8000}");
    }

    /**
     * 跑一次浏览器操作：op == null 表示"只刷新状态"。
     * 跑完自动重绘（主控台那一行的短状态也要跟着变）。
     */
    private void conBrowserRun(final String op, final String argsJson) {
        if (conBrowserBusy) { conToast(t("status.browserBusy", "查询中…")); return; }
        conBrowserBusy = true;
        conBrowserQueried = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (op != null) conBrowserApply(op, conBrowserCall(op, argsJson));
                    conBrowserApply("panel.state", conBrowserCall("panel.state", "{}"));
                    if (conBrowserRunning) {
                        conBrowserApply("tabs.list", conBrowserCall("tabs.list", "{}"));
                    } else {
                        conBrowserTabs = "";
                        conBrowserCount = 0;
                    }
                } catch (Throwable t) {
                    conBrowserErr = String.valueOf(t.getMessage());
                } finally {
                    conBrowserBusy = false;
                    conBrowserReleaseIfIdle();
                }
                ui.post(new Runnable() { @Override public void run() {
                    if (consolePage != PAGE_BROWSER && consolePage != 0) return;
                    if (conBrowserErr.length() > 0 && op != null) conToast(conBrowserErr);
                    renderConsole();
                }});
            }
        }, "con-browser-op").start();
    }

    /**
     * 浏览器没在跑就把绑定松掉。
     * 为什么必须做：BrowserIpc 是 BIND_AUTO_CREATE —— "打开控制台看一眼状态"本身就会把
     * :browser 进程拉起来；不松绑它就一直挂着（空进程也是内存）。
     * 松绑只影响主进程这一侧；子进程由系统按需回收（这正是设计里的"可整套丢弃"）。
     */
    private synchronized void conBrowserReleaseIfIdle() {
        if (conBrowserRunning) return;
        try {
            if (browserIpc != null) browserIpc.release();
        } catch (Throwable ignored) {}
    }

    /** 回执 → 状态缓存。字段只认原生给的 running / visible / tabs。 */
    private void conBrowserApply(String op, String res) {
        try {
            org.json.JSONObject j = new org.json.JSONObject(res == null ? "{}" : res);
            if (!j.optBoolean("ok", false)) {
                String why = j.optString("error", "");
                if (why.length() == 0) {
                    why = j.optString("reason", "");
                    if (why.length() == 0) why = "未知错误";
                }
                conBrowserErr = why;
                // 连不上 / 还没打开页面 / 缺悬浮窗权限 —— 这时候它确实没在跑，按"未运行"显示
                conBrowserRunning = false;
                conBrowserPanel = false;
                conBrowserCount = 0;
                conBrowserTabs = "";
                return;
            }
            conBrowserErr = "";
            conBrowserRunning = j.optBoolean("running", conBrowserRunning);
            conBrowserPanel = j.optBoolean("visible", conBrowserPanel);
            if (j.has("tabs")) conBrowserCount = j.optInt("tabs", 0);
            if ("tabs.list".equals(op)) {
                conBrowserRunning = true;
                conBrowserCount = j.optInt("count", 0);
                conBrowserTabs = res;
            }
            if ("nav".equals(op) && j.optBoolean("closed", false)) {
                // 关掉浏览器 = 全部页签连同页面一起释放（原生 closeWebView），同屏自然也没了
                conBrowserRunning = false;
                conBrowserPanel = false;
                conBrowserCount = 0;
                conBrowserTabs = "";
            }
        } catch (Throwable t) {
            conBrowserErr = String.valueOf(t.getMessage());
            conBrowserCount = 0;
            conBrowserTabs = "";
        }
    }

    /** tabs.list 原文 → 「标题 / tabId · 地址 / 是否当前」三列（解析失败给空表，不抛）。 */
    private java.util.List<String[]> conBrowserTabRows() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        String raw = conBrowserTabs;
        if (raw == null || raw.length() == 0) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONObject(raw).optJSONArray("tabs");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject tb = arr.optJSONObject(i);
                if (tb == null) continue;
                String id = tb.optString("tabId", "");
                String title = tb.optString("title", "");
                String url = tb.optString("url", "");
                String head = title.length() > 0 ? title : (url.length() > 0 ? url : "(空白页)");
                out.add(new String[]{cCut(head, 26), cCut(id + " · " + url, 46),
                        tb.optBoolean("active", false) ? "1" : "0"});
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 「同屏查看」开关：与插件开关同一套视觉，但绑定的是**面板**而不是插件。 */
    private TextView conBrowserPanelToggle() {
        final TextView tg = new TextView(this);
        tg.setTag(Boolean.valueOf(conBrowserPanel));
        tg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tg.setPadding(dp(12), dp(6), dp(12), dp(6));
        tg.setSingleLine(true);
        tg.setGravity(Gravity.CENTER);
        conTogglePaint(tg);
        // v1.21（用户反馈）：浏览器没在跑时，点这个开关会"亮一下又弹回已关闭" ——
        // 因为本地先做了乐观切换，而原生侧回的是 no-browser，重绘后状态自然回到关闭。
        // 这里直接**禁用并给出下一步**：要么点下面的「打开测试页」，要么让 AI 用浏览器工具开一个网页。
        if (!conBrowserRunning) {
            tg.setEnabled(false);
            tg.setAlpha(0.45f);
            tg.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    conToast(t("hint.browserNotOpen", "浏览器还没打开：先点下面「打开测试页」，或让 AI 用浏览器工具打开一个网页"));
                }
            });
            return tg;
        }
        tg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (conBrowserBusy) { conToast(t("status.browserBusy", "查询中…")); return; }
                final boolean want = !conBrowserPanel;
                // 先给即时反馈；真状态以 op 回执为准（回来会整体重绘一次）
                tg.setTag(Boolean.valueOf(want));
                conTogglePaint(tg);
                conBrowserRun("panel", "{\"show\":" + (want ? "true" : "false") + "}");
            }
        });
        return tg;
    }

    private void conBrowserConfirmClose() {
        conDialog(t("btn.browser.close", "关闭浏览器"),
                t("desc.browserClose", "关掉全部页签并回收浏览器进程（不影响引擎）"),
                t("btn.browser.close", "关闭浏览器"), new Runnable() { @Override public void run() {
                    conBrowserRun("nav", "{\"op\":\"close\"}");
                } }, "取消");
    }

    private void renderConsoleBrowser() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.browser", "AI 浏览器")));
        col.addView(cNote(t("desc.browserPage",
                "AI 浏览器跑在独立进程里：页面崩了不会带走控制台和引擎。这里能看它正在哪一页，也能把画面同屏显示出来。")),
                cTop(cGap(8)));
        // v1.19.6 · 路线图③：插件关着的时候必须说清楚「AI 现在用不了浏览器」——
        // 否则页面上"未运行 / 还没有页签"看起来像浏览器坏了。整行可点 → 直达「插件」页。
        if (conPluginDisabled("tool-browser")) {
            LinearLayout pbox = cCardBox();
            pbox.addView(cCardRow(t("status.browserPluginOff", "插件已关闭"),
                    t("desc.browserPluginOff", "AI 现在用不了浏览器工具 · 去「插件」页打开 dsh-tool-browser · 重启引擎后生效"),
                    "›", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { consolePage = 2; renderConsole(); }
                    }));
            col.addView(pbox, cTop(cGap(12)));
        }

        // 首次进页面自动查一次（异步，不卡 UI；查完会重绘）
        if (!conBrowserQueried) conBrowserRun(null, null);

        col.addView(cGrpTitle(t("title.grpBrowserState", "浏览器状态")), cTop(cGap(16)));
        LinearLayout box = cCardBox();
        String right = conBrowserErr.length() > 0 ? t("status.browserUnreachable", "读不到状态")
                : (conBrowserRunning ? t("status.browserRunning", "运行中") : t("status.browserIdle", "未运行"));
        box.addView(cCardRow(t("card.browser", "AI 浏览器"), conBrowserStateText(), right, false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { conBrowserRun(null, null); }
                }));
        box.addView(cCardSep());
        box.addView(cCardRowWith(t("btn.browser.panel", "同屏查看"),
                t("desc.browserPanel", "把画面显示成一块浮窗 · 拖顶部小条移动、点小条关掉 · 画面可直接操作"),
                conBrowserPanelToggle()));
        col.addView(box, cTop(cGap(8)));

        col.addView(cGrpTitle(t("title.grpBrowserTabs", "页签")), cTop(cGap(16)));
        LinearLayout tbox = cCardBox();
        java.util.List<String[]> rows = conBrowserTabRows();
        if (rows.isEmpty()) {
            tbox.addView(cCardRow(t("status.browserNoTab", "还没有页签"),
                    t("desc.browserNoTab", "让 AI 打开一个网页后，这里会列出它的页签"), "", false, null));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) tbox.addView(cCardSep());
                String[] r = rows.get(i);
                tbox.addView(cCardRow(r[0], r[1],
                        "1".equals(r[2]) ? t("status.browserActive", "当前") : "", false, null));
            }
        }
        col.addView(tbox, cTop(cGap(8)));

        // v1.21（用户反馈）：浏览器只能由 AI 的浏览器工具打开，用户在控制台上**没有办法**
        // 把它从"未运行"变成"正在运行"（于是「同屏查看」永远点不动）。这里补一个入口：
        // 未运行时给一枚「打开测试页」，直接用原生 open op 打开一个页面，页面一起来就能同屏查看。
        if (!conBrowserRunning) {
            Button openTest = cButton(t("btn.browser.openTest", "打开测试页"), true);
            openTest.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    conBrowserRun("open", "{\"url\":\"https://example.com\"}");
                }
            });
            col.addView(openTest, cTop(cGap(14)));
            col.addView(cNote(t("desc.browserOpenTest",
                    "浏览器平时由 AI 的浏览器工具按需打开（它有自己的进程，不用时会回收）。"
                    + "这枚按钮只是方便你验证同屏查看与页签功能。")), cTop(cGap(6)));
        }

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button rf = cButton(t("btn.browser.reload", "刷新"), false);
        rf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conBrowserRun(null, null); }
        });
        acts.addView(rf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button cl = cButton(t("btn.browser.close", "关闭浏览器"), false);
        cl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { conBrowserConfirmClose(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(8);
        acts.addView(cl, clp);
        col.addView(acts, cTop(cGap(14)));

        col.addView(cNote(t("desc.browserWarn",
                "边界（写死在代码里）：① 同屏是只读的（能看不能点，触摸穿透）；② 不改窗口尺寸 —— AI 的点击坐标口径依赖它；③ 浏览器崩溃只死 :browser 进程，控制台与引擎不受影响。")),
                cTop(cGap(10)));
    }

    /** AI 浏览器卡片（完整版布局 / layout.pages 下用；精简版走主控台那一行）。 */
    private View cardBrowser() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(20)));
        box.addView(cNavRow(t("card.browser", "AI 浏览器"),
                t("desc.browser", "看 AI 正在哪一页 · 同屏查看（能看不能点）"), conBrowserSummary(), PAGE_BROWSER));
        box.addView(cSep(0));
        return box;
    }

    // ---------- 日志页 ----------

    // ==================== 自修复：自检页 + 自愈账本（v1.19.x） ====================
    //
    // 设计稿：tmp-diag/v1180/selfheal-design.md（我们的四条）。
    //  ① 清单式一致性证明 —— KernelSelfCheck.java（只读，随包 kernel-manifest.tsv）
    //  ② 自愈账本 —— 每次自检/修复/快照写一条 JSON，追加式，可读可审计
    //  ③ 会话级自愈、④ AI 只解释不执行 —— 见后续轮次
    // 安全边界：自检「只读」；唯一会写盘的是「配置快照」（白名单文件，自己另存一份，不动原文件）。

    private KernelSelfCheck.Result conSelfCheckResult = null;
    private volatile boolean conSelfCheckRunning = false;
    // 自检进度（后台线程写、UI 线程读；volatile 数组元素在真机上够用，且只用于显示）
    private final int[] conSelfCheckProgress = new int[]{0, 0};
    private volatile String conSelfCheckPhase = "";
    private volatile boolean conSelfCheckCancel = false;
    private volatile long conSelfCheckStart = 0L;

    /**
     * 自检页的页号：「用负数做哨兵」。
     * 为什么不用 5：自定义页从 `4 + i`（i≥1）编号，「第一张自定义页就是 5」 ——
     * 用 5 会把用户的第一张自定义页顶掉（本轮代码核对发现的冲突）。
     * 负数与所有 `>= 0` 的页号结构上不可能撞，也不受以后新增内置页影响。
     */

    // ==================== 控制台风格（v1.19.x：默认精简版；一键可切回完整版） ====================
    //
    // 用户反馈："东西多了很杂很乱，做的简洁一点" + "不能让控制台全自定义失效"。
    // 所以风格只是个**开关**：simple（缺省）用精简骨架渲染主控台；classic 用原来的卡片平铺。
    // 其它自定义能力（text / actions / pages / appearance / order / hidden / 积木）两种风格下都照旧。
    private TextView uiSimpleActionText = null;   // 精简版那个主按钮的文字
    private Button uiSimpleActionBtn = null;

    /**
     * 当前生效的控制台风格：**只看主题**（v1.19.6，用户 2026-10-05 拍板）。
     *
     * · 缺省 = `simple`（新版·方案 B）；
     * · 主题包里写 `"layout": {"style": "classic"}` 仍可切回旧的卡片平铺
     *   —— 界面上的风格开关已下线（主控台右上角那个「完整版」按钮也一并移除），
     *   所以这个口子只留给"愿意改配置文件"的人；
     * · ⚠ **不再读取界面偏好 `console_ui_style`**：界面上没有切回来的入口了，
     *   读它只会把老用户永久锁在旧界面里（v1.19.2 踩过的"切过去回不来"）。
     *
     * 保留这个方法而不是内联常量：`renderConsoleMain()` 用它做分流，而旧卡片版仍是
     * `layout.pages` 里那些积木（`extract.block` / `engine.status` / …）的实现来源。
     */
    private String consoleUiStyle() {
        ConsoleTheme ct = conTheme;
        if (ct != null && "classic".equals(ct.style)) return "classic";
        return "simple";
    }

    private static final int PAGE_SELFCHECK = -100;
    /** 会话修复页（自修复 ③；负页号哨兵：与所有 >=0 的自定义页号结构上不可能冲突）。 */
    private static final int PAGE_SESSION_HEAL = -101;
    /** 上一次会话扫描的结果（null = 还没扫过）。 */
    private SessionHeal.AuditResult conSessionAudit;
    /** 会话扫描进行中（后台线程）。 */
    private boolean conSessionScanning;
    /** 正在修复的会话 id（非 null 表示有修复在跑）。 */
    private String conSessionHealingId;
    /** 最近一次会话修复的结果。 */
    private SessionHeal.HealResult conSessionLastHeal;

    /** 当前控制台是不是**精简版**渲染的（refreshConsole 里据此切换状态字段的可见性；卡片版不受影响）。 */
    private volatile boolean conRenderedSimple = false;

    // ==================== 会话管理：删除会话（v1.19.4） ====================
    //
    // 用户 2026-10-04：「这个软件不能删会话，删不了会话，这是个问题，加一个页面专门删会话」。
    // 删的时候**先移到回收站**（<dshHome>/sessions-deleted，在 sessions 之外、内核扫不到），
    // 「彻底删除」只在回收站里做 —— 误删还能捞回来。
    // 边界：只动 sessions/ 与回收站这两棵树；越界在 Java 与脚本两侧各拦一次。

    /** 会话管理页（负页号哨兵）。 */
    private static final int PAGE_SESSION_ADMIN = -102;
    /** 回收站页（负页号哨兵）。 */
    private static final int PAGE_SESSION_TRASH = -103;
    /**
     * v1.19.6 · 阶段 C：AI 浏览器页（同屏查看 / 页签 / 关闭）。
     * 用负数哨兵的理由同 PAGE_SELFCHECK：自定义页从 5 起编号，内置页不得占用。
     */
    private static final int PAGE_BROWSER = -104;
    /** 会话列表（null = 还没加载过）。 */
    private SessionAdmin.ListResult conAdminList;
    /** 回收站列表。 */
    private SessionAdmin.TrashResult conAdminTrash;
    /** 正在跑脚本（加载或增删）。 */
    private boolean conAdminBusy;
    /** 列表一次渲染多少条（话题只增不减，分批渲染，免得几百条卡住）。 */
    private int conAdminShowCount = 30;
    /** 最近一次操作的结果。 */
    private SessionAdmin.OpResult conAdminLastOp;

    /**
     * 显示层去 markdown 痕迹：原生 TextView 不解析 markdown，`「加粗」`、`` `代码` ``
     * 会「原样显示记号」，很影响观感。这里只"去掉记号、保留文字"，不做富文本。
     * 作为兜底使用：以后新写的文案即使带了 markdown，也不会漏到界面上。
     */
    static String uiPlain(String s) {
        if (s == null) return "";
        String r = s;
        if (r.indexOf('*') >= 0) r = r.replace("", "").replace("*", "");
        if (r.indexOf('`') >= 0) r = r.replace("", "");
        return r;
    }

    /** 账本目录：/sdcard/<pkgRoot>/heal-ledger/（放外部，方便用户与 AI 直接读）。 */
    private File conHealLedgerDir() {
        File d = new File(new File(Environment.getExternalStorageDirectory(), pkgRoot()), "heal-ledger");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 追加一条账本记录（自己拼 JSON，不引依赖）。失败只记日志，绝不打断主流程。 */
    private void conLedgerWrite(String kind, String jsonBody) {
        try {
            File dir = conHealLedgerDir();
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
            String ts = f.format(new java.util.Date());
            File out = new File(dir, ts + "-" + kind + ".json");
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"ts\": \"").append(new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(new java.util.Date())).append("\",\n");
            sb.append("  \"app\": \"").append(getPackageName()).append("\",\n");
            sb.append("  \"versionName\": \"").append(conVersionLabel()).append("\",\n");
            sb.append("  \"kernelVersion\": \"").append(assetText("dshroot_kernel_version.txt")).append("\",\n");
            sb.append("  \"body\": ").append(jsonBody).append("\n");
            sb.append("}\n");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            Log.i(TAG, "heal ledger: " + out.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "heal ledger write failed", t);
        }
    }

    /** 账本条数 + 最新一条的时间（自检页显示用）。 */
    private String conLedgerSummary() {
        try {
            File[] fs = conHealLedgerDir().listFiles();
            if (fs == null || fs.length == 0) return "账本为空（还没有过自检或修复记录）";
            long newest = 0;
            for (int i = 0; i < fs.length; i++) newest = Math.max(newest, fs[i].lastModified());
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
            return fs.length + " 条记录 · 最近 " + f.format(new java.util.Date(newest));
        } catch (Throwable t) {
            return "账本读取失败";
        }
    }

    /** 控制台主控台的第 9 张卡：内核自检入口。 */
    /**
     * 找内核树根（自检/快照用）：`dshrootDir` 只在 prepareFiles() 里赋值，而冷启动会直接停在控制台 ——
     * 那时树其实已经在磁盘上，字段却是 null（真机实测：自检报"找不到内核树：null"）。
     * 所以这里按 App 自己的路径规则兜底：内部优先（快且可靠），再外部（内部空间不足时的回退）。
     */
    private File conKernelRoot() {
        try {
            if (dshrootDir != null && new File(dshrootDir, "lib").isDirectory()) return dshrootDir;
            File internal = new File(payloadDir(), "dshroot");
            if (new File(internal, "lib").isDirectory()) return internal;
            File external = new File(new File(Environment.getExternalStorageDirectory(), pkgRoot()), "dshroot");
            if (new File(external, "lib").isDirectory()) return external;
            // 都没找到：把内部路径返回去，让自检如实报"找不到内核树：<路径>"
            return internal;
        } catch (Throwable t) {
            return dshrootDir;
        }
    }

    private View cardSelfCheck() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cSep(cGap(16)));
        box.addView(cText(t("card.selfcheck", "内核自检"), 13f, cText(), true));
        String sub = conSelfCheckResult != null
                ? conSelfCheckResult.summary()
                : (KernelSelfCheck.manifestAvailable(this)
                    ? "清单式一致性证明：比对随包清单，指出「具体哪个文件」缺失/不符（只读，不改任何东西）"
                    : "本包不带内核树清单（kernel-manifest.tsv），只能做基础项自检");
        box.addView(cText(sub, 11f, cSub(), false), cTop(cGap(6)));
        box.addView(cNavRow("打开自检页", "快速自检（秒级）· 全量校验（逐个 sha256）· 自愈账本", "", PAGE_SELFCHECK));
        return box;
    }


    // ==================== 自修复 ④：AI 只提案，App 才执行（v1.19.x） ====================
    //
    // 设计稿 §1 ④ / §2 P3：AI 只做"读证据 / 给判断 / 提建议"；执行永远走 App 的红框 + 点击确认 + 显示原文。
    // 这里把"建议"落成「数据文件」（不含任何可执行代码），App 只解析动作名并在白名单里查表。
    // 三道闸门：① 动作名白名单 ② 提案过期（源指纹/代次变了就不许执行）③ 同一故障最多执行 2 次。

    /** 提案目录（AI 与用户都能写、App 只读）。 */
    private File conHealProposalDir() {
        File d = new File(new File(Environment.getExternalStorageDirectory(), pkgRoot()), "heal-proposals");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 白名单：动作 id → 中文说明。「写死在代码里」 —— AI 只能从这几个里选，无法扩展或注入新动作。
     */
    private static final String[][] HEAL_ACTIONS = {
        {"resync", "重新解压（全量按 payload 覆盖内核树，并清理 payload 已没有的 dsh* 陈旧包）"},
        {"selfcheck", "再跑一次清单式证明（修完必须能再证明一次：期望 缺失 0 / 不符 0）"},
        {"snapshot-config", "配置快照（先留前代，再动手）"},
        {"open-ledger", "查看自愈账本（只读）"},
    };

    private static String healActionDesc(String id) {
        for (int i = 0; i < HEAL_ACTIONS.length; i++) if (HEAL_ACTIONS[i][0].equals(id)) return HEAL_ACTIONS[i][1];
        return null;
    }

    /**
     * 源码指纹：用来判断"提案提出之后，源有没有变"。
     * 关心的是内核树的关键文件（REVISION / 内核版本 / 清单资产大小），不是整棵树（那太慢）。
     */
    private String conHealSourceHash() {
        try {
            StringBuilder sb = new StringBuilder();
            File root = conKernelRoot();
            if (root != null) {
                File rev = new File(root, "REVISION");
                if (rev.isFile()) sb.append(rev.length()).append(':').append(rev.lastModified());
                File pkg = new File(root, "lib/node_modules/@deepseek-ai/dsh/package.json");
                if (pkg.isFile()) sb.append('|').append(pkg.length()).append(':').append(pkg.lastModified());
            }
            sb.append('|').append(assetText("dshroot_kernel_version.txt"));
            String s = sb.toString();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 读若干条提案（只读，按时间倒序，最多 8 条）。 */
    private java.util.List<String[]> conReadProposals() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        try {
            File[] fs = conHealProposalDir().listFiles();
            if (fs == null) return out;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            for (int i = 0; i < fs.length && out.size() < 8; i++) {
                if (!fs[i].getName().endsWith(".json")) continue;
                String txt = readTextFile(fs[i], 64 * 1024);
                if (txt == null) continue;
                String action = jsonField(txt, "action");
                String why = jsonField(txt, "reason");
                String srcHash = jsonField(txt, "sourceHash");
                out.add(new String[]{fs[i].getAbsolutePath(), action == null ? "" : action,
                        why == null ? "" : why, srcHash == null ? "" : srcHash});
            }
        } catch (Throwable t) {
            Log.w(TAG, "read proposals failed", t);
        }
        return out;
    }

    /** 小工具：读文本文件（上限 cap 字节）。 */
    private String readTextFile(File f, int cap) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[Math.min(cap, (int) Math.max(1, f.length()))];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 点"执行"：三道闸门依次过 —— 白名单 → 过期 → 次数。任何一条不过都「如实说明并拒绝」。
     */
    private void conHealExecuteProposal(final String[] p) {
        if (p == null || p.length < 4) return;
        final String path = p[0], action = p[1], why = p[2], srcHash = p[3];
        String desc = healActionDesc(action);
        if (desc == null) {
            conDialog("这个提案不能执行",
                    "动作名「" + action + "」不在白名单里。\n\n唯一允许的四个动作是：\n"
                  + "· resync / selfcheck / snapshot-config / open-ledger\n\n"
                  + "（AI 只能提建议，不能自己造动作；白名单写死在 App 里。）",
                    "好", null, null);
            return;
        }
        String nowHash = conHealSourceHash();
        if (srcHash != null && srcHash.length() > 0 && !"unknown".equals(srcHash) && !srcHash.equals(nowHash)) {
            conDialog("提案已过期",
                    "提案提出时内核树指纹是 " + srcHash + "，现在是 " + nowHash + " —— 「源已经变了」。\n\n"
                  + "为避免「照着一份旧判断去修一棵新树」，这个提案不允许执行。\n"
                  + "请重新跑一次自检，并让 AI 按最新结果重新提案。",
                    "好", null, null);
            return;
        }
        int times = conHealTimesFor(nowHash);
        if (times >= 2) {
            conDialog("已经修过两次了",
                    "同一份源指纹（" + nowHash + "）上的修复动作已经执行过 " + times + " 次。\n\n"
                  + "按设计稿的护栏：「同一故障最多自动执行 2 次，第 3 次只报告不执行」 —— 防止「越修越坏」。\n"
                  + "请把账本 / 自检报告导出给人看，或换一条思路（例如先做配置快照、或直接重新解压）。",
                    "好", null, null);
            return;
        }
        conDialog("执行这个修复动作？",
                "动作：「" + desc + "」\n\n"
              + "AI 的判断依据：" + (why.length() > 0 ? why : "（提案里没写原因）") + "\n\n"
              + "提案文件：" + path + "\n"
              + "源指纹：" + nowHash + "（与本提案一致）\n"
              + "这一份源上已执行：" + times + " 次（上限 2 次）\n\n"
              + "⚠ 执行会改动内核树（只碰内核自己的 @deepseek-ai 命名空间，用户数据与第三方插件不动），"
              + "且会在执行前写一条账本记录。",
                "执行",
                new Runnable() { @Override public void run() { conHealRunAction(action, path, why, nowHash); } },
                "取消");
    }

    /** 同一源指纹上已执行过几次（数账本，不数内存 —— 重启也不忘）。 */
    private int conHealTimesFor(String sourceHash) {
        int n = 0;
        try {
            File[] fs = conHealLedgerDir().listFiles();
            if (fs == null) return 0;
            for (int i = 0; i < fs.length; i++) {
                if (!fs[i].getName().contains("-heal-action")) continue;
                String txt = readTextFile(fs[i], 32 * 1024);
                if (txt != null && txt.contains(sourceHash)) n++;
            }
        } catch (Throwable ignored) {}
        return n;
    }

    /** 真正执行白名单动作（已过三道闸门）。 */
    private void conHealRunAction(final String action, String proposalPath, String why, String srcHash) {
        conLedgerWrite("heal-action", "{\"action\":\"" + action + "\",\"proposal\":\"" + jesc(proposalPath)
                + "\",\"reason\":\"" + jesc(why) + "\",\"sourceHashBefore\":\"" + srcHash
                + "\",\"sourceHashAfter\":\"" + conHealSourceHash() + "\"}");
        if ("resync".equals(action)) { conReExtract(); return; }
        if ("selfcheck".equals(action)) { conRunSelfCheck(false); return; }
        if ("snapshot-config".equals(action)) { conSnapshotConfigNow(); return; }
        if ("open-ledger".equals(action)) { conShowLedger(); return; }
        conToast("未知动作：" + action);
    }

    /** 自检页底部：AI 提案区（只读展示 + 点击执行）。 */
    /** 自检页底部：AI 提案区（只读展示 + 点击执行；AI 只提建议，执行必须用户点）。 */
    private View conHealProposalBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(cGrpTitle(t("title.grpHealProposal", "AI 修复提案")), cTop(cGap(18)));
        box.addView(cNote(t("desc.healProposal",
                "AI 读自检结果与账本后，可以把判断与建议写成 /sdcard/" + pkgRoot() + "/heal-proposals/*.json；"
              + "App 只「读」它、绝不会自动执行 —— 执行必须你点。动作白名单写死在 App 里："
              + "resync / selfcheck / snapshot-config / open-ledger。"
              + "提案里带源指纹，源一变即过期；同一源上最多执行 2 次。")), cTop(cGap(8)));

        java.util.List<String[]> ps = conReadProposals();
        LinearLayout card = cCardBox();
        if (ps.isEmpty()) {
            card.addView(cCardRow(t("status.noProposal", "当前没有提案文件"),
                    t("desc.noProposal", "AI 写出提案后，这里会出现它、并列出它想做什么"), "", false, null));
        } else {
            for (int i = 0; i < ps.size(); i++) {
                if (i > 0) card.addView(cCardSep());
                final String[] p = ps.get(i);
                String desc = healActionDesc(p[1]);
                String fingerprint = (p[3] == null || p[3].length() == 0)
                        ? t("desc.healNoFingerprint", "（提案未提供 → 会跳过过期检查）") : p[3];
                String sub = (p[2] != null && p[2].length() > 0 ? ("依据：" + p[2] + " · ") : "")
                        + t("desc.healFingerprint", "源指纹：") + fingerprint;
                Button run = cButton(desc == null ? t("btn.heal.notRunnable", "不可执行")
                        : t("btn.heal.run", "执行（需确认）"), false);
                run.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                if (desc == null) run.setTextColor(cRed());
                run.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) { conHealExecuteProposal(p); }
                });
                card.addView(cCardRowWith(desc == null ? ("【不在白名单】" + p[1]) : desc, cCut(sub, 72), run));
            }
        }
        box.addView(card, cTop(cGap(8)));
        box.addView(cNote(t("desc.healProposalWarn",
                "⚠ 执行会改动内核树（只碰内核自己的 @deepseek-ai 命名空间）；每次执行都会写账本。")), cTop(cGap(10)));
        return box;
    }


    /** consolePage == PAGE_SELFCHECK：内核自检页（负页号哨兵）。 */
    private void renderConsoleSelfCheck() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.selfcheck", "内核自检")));
        col.addView(cText(t("desc.selfcheck",
                        "这是「清单式一致性证明」：拿随包清单（每个文件的路径/大小/sha256）跟设备上的内核树逐条比对，"
                      + "回答的是「到底哪个文件不对」，而不是「感觉坏了」。全程只读，不会改任何文件。"),
                11f, cSub(), false), cTop(cGap(8)));

        KernelSelfCheck.Result r = conSelfCheckResult;
        if (conSelfCheckRunning) {
            // 进度条（自绘，跟页面同风格）：全量校验逐个算 sha256，实测 2.5 万文件约 200 秒
            int done = conSelfCheckProgress[0], total = conSelfCheckProgress[1];
            double frac = total > 0 ? Math.min(1.0, done / (double) total) : 0.0;
            long secs = (System.currentTimeMillis() - conSelfCheckStart) / 1000;
            col.addView(cText("自检进行中 · " + (conSelfCheckPhase == null ? "" : conSelfCheckPhase),
                    12.5f, cText(), true), cTop(cGap(12)));
            // 内联自绘进度条（既有那条权重条是「解压卡」的全局单例，不能复用）
            LinearLayout track = new LinearLayout(this);
            track.setOrientation(LinearLayout.HORIZONTAL);
            int pct = total > 0 ? (int) Math.round(frac * 100) : 0;
            View fill = new View(this);
            fill.setBackground(cShape(cAccent(), 0, 0, 6));
            View rest = new View(this);
            rest.setBackground(cShape(cTrack(), 0, 0, 6));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(8), Math.max(pct, 0.01f));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(8), Math.max(100 - pct, 0.01f));
            track.addView(fill, flp);
            track.addView(rest, rlp);
            col.addView(track, cTop(cGap(8)));
            String line = total > 0
                    ? ("已处理 " + done + " / " + total + " 个文件（" + Math.round(frac * 100) + "%）")
                    : ("已处理 " + done + " 个文件（总数读取中…）");
            col.addView(cText(line + " · 已用 " + secs + "s", 11f, cSub(), false), cTop(cGap(6)));
            Button cancel = cButton("取消", false);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { conSelfCheckCancel = true; conToast("正在停止…"); }
            });
            col.addView(cancel, cTop(cGap(10)));
            return;
        }
        if (r == null) {
            col.addView(cText("还没有跑过自检。建议先点「快速自检」（只比大小，秒级）。",
                    12f, cText(), false), cTop(cGap(12)));
        } else {
            // v1.19.6：结论放最上面（大字 + 绿/红），数字一行带过，"下一步"紧跟其后
            int color = "ok".equals(r.verdict) ? cGreen() : ("fail".equals(r.verdict) ? cRed() : cSub());
            LinearLayout c0 = cCardBox();
            c0.addView(cText(uiPlain(r.verdict.toUpperCase() + " · " + r.headline), 15f, color, true),
                    cTop(cGap(12)));
            c0.addView(cText("清单文件 " + r.manifestFiles + " · 树上文件 " + r.treeFiles
                    + " · 已校验内容 " + r.hashed + " · 用时 " + (r.elapsedMs / 1000) + "s"
                    + (r.quick ? "（快速：只比大小）" : "（全量：大小 + sha256）"),
                    11f, cSub(), false), cTop(cGap(6)));
            if (!r.samples.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < r.samples.size(); i++) sb.append("· ").append(r.samples.get(i)).append("\n");
                c0.addView(cText(uiPlain(sb.toString().trim()), 11f, cText(), false), cTop(cGap(8)));
            }
            if (!r.nextSteps.isEmpty()) {
                StringBuilder sb = new StringBuilder("下一步：\n");
                for (int i = 0; i < r.nextSteps.size(); i++) sb.append(i + 1).append(". ").append(r.nextSteps.get(i)).append("\n");
                c0.addView(cText(uiPlain(sb.toString().trim()), 11f, cSub(), false), cTop(cGap(8)));
            }
            c0.addView(new View(this), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
            col.addView(c0, cTop(cGap(12)));
        }

        // 动作
        col.addView(cGrpTitle(t("title.grpSelfCheckActions", "动作")), cTop(cGap(18)));
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button q = cButton(t("btn.selfcheck.quick", "快速自检"), true);
        q.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conRunSelfCheck(true); } });
        acts.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button f = cButton(t("btn.selfcheck.full", "全量校验"), false);
        f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conRunSelfCheck(false); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(8);
        acts.addView(f, lp);
        col.addView(acts, cTop(cGap(10)));

        // v1.19.6：原来这一行是「看账本 | 配置快照」两个按钮，与下面账本卡片里的入口重复；
        // 账本入口已并入「自愈账本」卡片行（带摘要 + ›），这里只留配置快照。
        Button snap = cButton(t("btn.selfcheck.snapshot", "配置快照"), false);
        snap.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conSnapshotConfigConfirm(); } });
        col.addView(snap, cTop(cGap(10)));

        // v1.19.6：自愈账本从"一段灰字"收进卡片行（标题 / 摘要 / 右列「看账本 ›」，整行可点）
        col.addView(cGrpTitle(t("title.grpLedger", "自愈账本")), cTop(cGap(18)));
        LinearLayout lb = cCardBox();
        lb.addView(cCardRow(t("card.ledger", "自愈账本"),
                t("desc.ledger", "每次自检与修复追加一条 JSON，AI 可以直接读它向你解释修过什么")
                        + " · " + conLedgerSummary(),
                t("btn.selfcheck.ledger", "看账本"), true, new View.OnClickListener() {
            @Override public void onClick(View x) { conShowLedger(); }
        }));
        col.addView(lb, cTop(cGap(8)));
        col.addView(cNote(t("desc.ledgerDir", "目录：") + "/sdcard/" + pkgRoot() + "/heal-ledger/"), cTop(cGap(6)));
        col.addView(conHealProposalBlock());
        // v1.19.6：原来这里还挂着「会话修复 / 会话管理」两个入口 —— 它们在 v1.19.5 已经提到
        // 主控台首页的「会话」分组，这里再放一次就是同一入口出现两次，已移除。

        col.addView(cNote(t("desc.selfcheckWarn",
                "边界（写死在代码里）：① 自检「只读」，不删不改；"
              + "② 「缺失/内容不符」才是需要处理的真问题，「清单外文件」只是参考信息"
              + "（清单在开发树上算的，天然比出货树少，多数是第三方依赖）；"
              + "③ 真正的清理只发生在两个 @deepseek-ai 目录里、且只删 dsh 开头的陈旧条目 —— "
              + "用户数据（dshhome 下）与第三方插件（dshhome/profiles 下）永远只报不改。")), cTop(cGap(8)));
    }

    // ==================== 自修复 ③：会话级自愈（v1.19.x） ====================
    //
    // 设计稿 selfheal-design.md §1 ③：坏附件（例如文件头合法、IDAT 已损坏的 PNG）一旦作为
    // tool_result 进入会话历史，之后每次请求都会重新读它、每次都以同样方式失败，用户只能放弃整条
    // 会话（v1.15.1 记录过两条）。这里做的是「只降级那一条消息」：把坏掉的 image/file 块换成等价的
    // 文字说明，会话的正文、上下文与顺序一字不动。
    //
    // 边界（写死在代码里，不得绕过）：
    //   ① 只处理用户点选的那一条会话，没有「扫全目录批量修」的入口；
    //   ② 引擎在跑时不允许改写（内核可能正在写同一条会话）—— 让用户先停引擎；
    //   ③ 写盘前先备份，写完立刻复检；每次修复写一条自愈账本。
    // 真正的解帧/改写/复检在随包脚本 assets/session-heal.mjs 里（会话是多帧 zstd，Java 侧没有
    // 现成的 zstd 实现，而 payload 自带 node 原生支持它）。

    /** 加载会话 + 回收站两份清单（后台线程；只读）。 */
    private void conAdminLoad() {
        if (conAdminBusy) return;
        conAdminBusy = true;
        conToast("正在读取会话列表…（只读）");
        renderConsole();
        final File payload = payloadDir();
        final File dshHome = new File(payload, "dshhome");
        new Thread(new Runnable() { @Override public void run() {
            final SessionAdmin.ListResult lr = SessionAdmin.list(MainActivity.this, payload, dshHome);
            final SessionAdmin.TrashResult tr = SessionAdmin.trashList(MainActivity.this, payload, dshHome);
            ui.post(new Runnable() { @Override public void run() {
                conAdminBusy = false;
                conAdminList = lr;
                conAdminTrash = tr;
                if (consolePage == PAGE_SESSION_ADMIN || consolePage == PAGE_SESSION_TRASH) renderConsole();
                conToast(lr.headline());
            }});
        }}, "dsh-session-admin-list").start();
    }

    /** 会话管理页。 */
    /** 会话管理页（v1.19.6：统一版式 —— 说明 / 结论卡 / 行式列表 / 等宽操作区 / 边界）。 */
    private void renderConsoleSessionAdmin() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.sessionadmin", "会话管理")));
        col.addView(cNote(t("desc.sessionadminPage",
                "这里可以删掉不需要的会话。删除是「先移到回收站」—— 随时能恢复；"
              + "真要腾空间，去回收站里「彻底删除」。删除只动这一条会话，不碰别的。")), cTop(cGap(8)));

        View lastOp = conLastOpCard();
        if (lastOp != null) col.addView(lastOp, cTop(cGap(14)));

        if (conAdminBusy) {
            col.addView(cText("正在读取…（只读，不改任何东西）", 12f, cText(), true), cTop(cGap(12)));
            return;
        }
        SessionAdmin.ListResult r = conAdminList;
        if (r == null) {
            col.addView(cNote(t("desc.sessionadminIdle",
                    "还没有读取过列表。点下面的「读取会话列表」开始 —— 只读。")), cTop(cGap(12)));
        } else {
            int show = r.ok ? Math.min(conAdminShowCount, r.sessions.size()) : 0;
            col.addView(cGrpTitle(t("title.grpSessionList", "会话列表")), cTop(cGap(16)));
            LinearLayout box = cCardBox();
            if (!r.ok) {
                box.addView(cCardRow(t("status.readFailed", "读取失败"), cCut(r.error, 80), "",
                        cRed(), false, null));
            } else if (r.sessions.isEmpty()) {
                box.addView(cCardRow(t("status.sessionNone", "一条会话都没有"),
                        t("desc.sessionNone", "在 Web 界面里发一条消息，这里就会出现它"), "", false, null));
            } else {
                for (int i = 0; i < show; i++) {
                    if (i > 0) box.addView(cCardSep());
                    box.addView(conAdminRow(r.sessions.get(i)));
                }
            }
            col.addView(box, cTop(cGap(8)));
            col.addView(cNote(r.ok ? r.headline() : cCut(r.error, 120)), cTop(cGap(8)));
            if (r.ok && r.sessions.size() > show) {
                Button more = cButton(t("btn.session.more", "显示更多")
                        + "（还有 " + (r.sessions.size() - show) + " 条）", false);
                more.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) { conAdminShowCount += 30; renderConsole(); }
                });
                col.addView(more, cTop(cGap(10)));
            }
        }

        // 操作区：两个等宽按钮（首次读取=主操作实心；回收站带条数）
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button load = cButton(r == null ? t("btn.session.load", "读取会话列表")
                : t("btn.session.reload", "重新读取"), r == null);
        load.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conAdminLoad(); } });
        acts.addView(load, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int trashN = conAdminTrash == null ? 0 : conAdminTrash.count;
        Button trash = cButton(t("card.sessiontrash", "回收站") + "（" + trashN + "）", false);
        trash.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { consolePage = PAGE_SESSION_TRASH; renderConsole(); }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        acts.addView(trash, tlp);
        col.addView(acts, cTop(cGap(14)));

        col.addView(cNote(t("desc.sessionadminWarn",
                "边界（写死在代码里）：① 删除 = 移到 <dshHome>/sessions-deleted/（回收站），不是 rm；"
              + "② 回收站在 sessions/ 之外，内核扫不到、不会被当成会话加载；"
              + "③ 只动 sessions/ 与回收站这两棵树，越界一律拒绝；"
              + "④ 每次删除 / 恢复 / 彻底删都写一条自愈账本。")), cTop(cGap(12)));
    }



    /** 会话列表里的一行。 */
    /**
     * 会话列表里的一行：标题 / 元信息 / 右列「删除」（红字 + ›），整行可点。
     * 点下去是**确认框**（把会话名、大小、行数、会做什么原样摆出来），不会直接删。
     */
    private View conAdminRow(final SessionAdmin.SessionInfo si) {
        String meta = si.sizeText() + " · " + si.lines + " 行 · " + si.timeText()
                + (si.badFrames > 0 ? (" · ⚠ " + si.badFrames + " 个坏数据块") : "");
        return cCardRow(cCut(si.displayTitle(), 40), meta,
                t("btn.session.delete", "删除"), cRed(), true, new View.OnClickListener() {
            @Override public void onClick(View x) { conAdminConfirmTrash(si); }
        });
    }

    /** 「上次操作」结论卡（会话管理 / 回收站共用；没操作过就返回 null，调用方要判空）。 */
    private View conLastOpCard() {
        SessionAdmin.OpResult last = conAdminLastOp;
        if (last == null) return null;
        LinearLayout box = cCardBox();
        box.addView(cCardRow(t("status.lastOp", "上次操作"), cCut(last.headline(), 90),
                last.ok ? t("status.done", "完成") : t("status.failed", "失败"),
                last.ok ? cGreen() : cRed(), false, null));
        return box;
    }


    /** 回收站页。 */
    /** 回收站页（v1.19.6：统一版式）。 */
    /** 回收站页（v1.19.6：统一版式）。 */
    /** 回收站页（v1.19.6：统一版式）。 */
    private void renderConsoleSessionTrash() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.sessiontrash", "回收站")));
        col.addView(cNote(t("desc.sessiontrashPage",
                "删掉的会话先放在这里（在 sessions/ 之外，内核看不到它们）。"
              + "可以「恢复」回原位；确认不要了再「彻底删除」—— 那一步不可恢复。")), cTop(cGap(8)));

        View lastOp = conLastOpCard();
        if (lastOp != null) col.addView(lastOp, cTop(cGap(14)));

        if (conAdminBusy) {
            col.addView(cText("正在读取…（只读）", 12f, cText(), true), cTop(cGap(12)));
            return;
        }
        SessionAdmin.TrashResult t = conAdminTrash;
        if (t == null) {
            col.addView(cNote(t("desc.sessiontrashIdle",
                    "还没有读取过回收站。点下面的「读取回收站」。")), cTop(cGap(12)));
        } else {
            col.addView(cGrpTitle(t("title.grpTrashList", "回收站里的会话")), cTop(cGap(16)));
            LinearLayout box = cCardBox();
            if (!t.ok) {
                box.addView(cCardRow(t("status.readFailed", "读取失败"), cCut(t.error, 80), "",
                        cRed(), false, null));
            } else if (t.entries.isEmpty()) {
                box.addView(cCardRow(t("status.trashEmpty", "回收站是空的"),
                        t("desc.trashEmptyHint", "删掉的会话会先出现在这里"), "", false, null));
            } else {
                for (int i = 0; i < t.entries.size(); i++) {
                    if (i > 0) box.addView(cCardSep());
                    box.addView(conAdminTrashRow(t.entries.get(i)));
                }
            }
            col.addView(box, cTop(cGap(8)));
            col.addView(cNote(t.ok ? t.headline() : cCut(t.error, 120)), cTop(cGap(8)));
        }

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button load = cButton(t("btn.session.loadTrash", "读取回收站"), t == null);
        load.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conAdminLoad(); } });
        acts.addView(load, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = cButton(t("btn.session.backAdmin", "回会话管理"), false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { consolePage = PAGE_SESSION_ADMIN; renderConsole(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = dp(8);
        acts.addView(back, blp);
        col.addView(acts, cTop(cGap(14)));

        // 维护：不可逆的那一步单独成组、离手远（与日志页「清空日志」同一规格）
        if (t != null && t.ok && t.count > 0) {
            col.addView(cGrpTitle(t("title.grpTrashCare", "维护")), cTop(cGap(20)));
            LinearLayout box2 = cCardBox();
            box2.addView(cCardRow(t("btn.session.purgeAll", "清空回收站")
                            + "（" + t.count + " 条 · " + SessionAdmin.human(t.totalBytes) + "）",
                    t("desc.sessionPurgeAll", "把回收站里的会话一次性真正删掉，删完找不回来"),
                    t("btn.session.purgeAllShort", "清空"), cRed(), true, new View.OnClickListener() {
                @Override public void onClick(View x) { conAdminConfirmPurgeAll(); }
            }));
            col.addView(box2, cTop(cGap(8)));
        }
        col.addView(cNote(t("desc.sessiontrashWarn",
                "「彻底删除」会真的从磁盘删掉，不可恢复 —— 所以它只在这里提供，且每次都要单独确认。")), cTop(cGap(12)));
    }




    /** 回收站列表里的一行。 */
    /**
     * 回收站列表里的一行：左列标题 / 元信息，右列两个小按钮 ——「恢复」（实心主操作）
     * 与「彻底删除」（红字，点了还要过红框确认）。
     */
    private View conAdminTrashRow(final SessionAdmin.TrashInfo ti) {
        String sub = ti.sizeText() + " · " + ti.timeText()
                + (ti.slug.length() > 0 ? (" · 原位置 " + ti.slug) : "");
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        Button rs = cButton(t("btn.session.restore", "恢复"), true);
        rs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        rs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { conAdminConfirmRestore(ti); }
        });
        btns.addView(rs);
        Button pg = cButton(t("btn.session.purge", "彻底删除"), false);
        pg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        pg.setTextColor(cRed());
        pg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { conAdminConfirmPurge(ti); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(6);
        btns.addView(pg, plp);
        return cCardRowWith(cCut(ti.displayTitle(), 22), sub, btns);
    }


    /** 删除确认（红框）：明说"只是移到回收站、能恢复"。 */
    private void conAdminConfirmTrash(final SessionAdmin.SessionInfo si) {
        conDialogDanger("删除这个会话？",
                si.displayTitle() + "\n\n"
              + si.sizeText() + " · " + si.lines + " 行 · 最后活动 " + si.timeText() + "\n\n"
              + "会做什么：把它移到回收站（<dshHome>/sessions-deleted/），随时能在「回收站」里恢复。\n"
              + "不会做什么：不碰别的会话，不碰用户数据里的其它东西。",
                "确认删除（可恢复）",
                new Runnable() { @Override public void run() { conAdminDoTrash(si); } },
                "取消");
    }

    private void conAdminDoTrash(final SessionAdmin.SessionInfo si) {
        conAdminOp("trash", "正在删除（移到回收站）…", new Runnable() { @Override public void run() {
            conAdminLastOp = SessionAdmin.trash(MainActivity.this, payloadDir(), new File(payloadDir(), "dshhome"), si.dir);
            if (conAdminLastOp.ok) {
                conLedgerWrite("session-trash", "{\"session\":\"" + conJsonEsc(si.id) + "\",\"title\":\""
                        + conJsonEsc(si.title) + "\",\"bytes\":" + si.bytes + ",\"moved\":\""
                        + conJsonEsc(conAdminLastOp.moved) + "\",\"recoverable\":true}");
                conAdminShowCount = Math.max(30, conAdminShowCount);
            }
        }});
    }

    private void conAdminConfirmRestore(final SessionAdmin.TrashInfo ti) {
        conDialog("恢复这个会话？",
                ti.displayTitle() + "\n\n" + ti.sizeText() + " · 删除于 " + ti.timeText() + "\n\n"
              + "会把它放回「原来那条路径」（原位置：" + (ti.slug.length() > 0 ? ti.slug : "未知") + "）。",
                "恢复", new Runnable() { @Override public void run() {
                    conAdminOp("restore", "正在恢复…", new Runnable() { @Override public void run() {
                        conAdminLastOp = SessionAdmin.restore(MainActivity.this, payloadDir(), new File(payloadDir(), "dshhome"), ti.dir);
                        if (conAdminLastOp.ok) {
                            conLedgerWrite("session-restore", "{\"entry\":\"" + conJsonEsc(ti.entry) + "\",\"restoredTo\":\""
                                    + conJsonEsc(conAdminLastOp.restoredTo) + "\"}");
                        }
                    }});
                } }, "取消");
    }

    private void conAdminConfirmPurge(final SessionAdmin.TrashInfo ti) {
        conDialogDanger("彻底删除这个会话？",
                ti.displayTitle() + "\n\n" + ti.sizeText() + "\n\n"
              + "这一步不可恢复 —— 会真的从磁盘上删掉它，回收站里也不会再有。",
                "彻底删除", new Runnable() { @Override public void run() {
                    conAdminOp("purge", "正在彻底删除…", new Runnable() { @Override public void run() {
                        conAdminLastOp = SessionAdmin.purge(MainActivity.this, payloadDir(), new File(payloadDir(), "dshhome"), ti.dir);
                        if (conAdminLastOp.ok) {
                            conLedgerWrite("session-purge", "{\"entry\":\"" + conJsonEsc(ti.entry) + "\",\"bytes\":"
                                    + conAdminLastOp.bytes + ",\"recoverable\":false}");
                        }
                    }});
                } }, "取消");
    }

    private void conAdminConfirmPurgeAll() {
        final SessionAdmin.TrashResult t = conAdminTrash;
        final int n = t == null ? 0 : t.count;
        final long bytes = t == null ? 0 : t.totalBytes;
        conDialogDanger("清空回收站？",
                "会真的删掉回收站里全部 " + n + " 条会话（" + SessionAdmin.human(bytes) + "）。\n\n"
              + "这一步不可恢复，删完就找不回来了。当前正在用的会话不受影响。",
                "确认清空", new Runnable() { @Override public void run() {
                    conAdminOp("purge-all", "正在清空回收站…", new Runnable() { @Override public void run() {
                        conAdminLastOp = SessionAdmin.purgeAll(MainActivity.this, payloadDir(), new File(payloadDir(), "dshhome"));
                        if (conAdminLastOp.ok) {
                            conLedgerWrite("session-purge-all", "{\"entries\":" + conAdminLastOp.entries
                                    + ",\"bytes\":" + conAdminLastOp.bytes + ",\"recoverable\":false}");
                        }
                    }});
                } }, "取消");
    }

    /** 跑一个会话管理操作（后台线程 + 完成后重绘）；operation 里只做"调脚本 + 记账本"。 */
    private void conAdminOp(final String what, String busyText, final Runnable operation) {
        if (conAdminBusy) { conToast("上一个操作还没结束"); return; }
        conAdminBusy = true;
        conToast(busyText);
        renderConsole();
        new Thread(new Runnable() { @Override public void run() {
            try { operation.run(); } catch (Throwable t) { Log.w(TAG, "session-admin " + what, t); }
            ui.post(new Runnable() { @Override public void run() {
                conAdminBusy = false;
                // ⚠ 操作成功后必须**重新读**列表与回收站：只 renderConsole() 会用旧数据渲染，
                //   表现为「删了但条数没变 / 回收站计数不更新」（真机实测踩到）。
                if (conAdminLastOp != null && conAdminLastOp.ok) {
                    conToast(conAdminLastOp.headline());
                    conAdminLoad();
                } else {
                    renderConsole();
                    if (conAdminLastOp != null) conToast(conAdminLastOp.headline());
                }
            }});
        }}, "dsh-session-admin-" + what).start();
    }

    private String conShortId(String id) {
        if (id == null) return "";
        return id.length() <= 16 ? id : id.substring(0, 16) + "…";
    }

    /** 最小的 JSON 字符串转义（写账本用；不引依赖）。 */
    private String conJsonEsc(String s) {
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

    /** 会话列表里的一行（可点 → 详情）。 */
    /** 有问题的会话：一行（会话 id / 症状 / 右列体积行数），整行可点 → 详情弹窗。 */
    private View conSessionRow(final SessionHeal.SessionInfo si) {
        return cCardRow(conShortId(si.id), si.summary(),
                si.sizeText() + " · " + si.lines + " 行", cSub(), true,
                new View.OnClickListener() {
                    @Override public void onClick(View x) { conSessionDetail(si); }
                });
    }


    /** 扫描会话（后台线程；全程只读）。 */
    private void conSessionScan() {
        if (conSessionScanning || conSessionHealingId != null) return;
        conSessionScanning = true;
        conSessionLastHeal = null;
        conToast("正在扫描会话（只读，不改任何东西）…");
        renderConsole();
        final File payload = payloadDir();
        final File dshHome = new File(payload, "dshhome");
        new Thread(new Runnable() { @Override public void run() {
            final SessionHeal.AuditResult r = SessionHeal.audit(MainActivity.this, payload, dshHome);
            ui.post(new Runnable() { @Override public void run() {
                conSessionScanning = false;
                conSessionAudit = r;
                consolePage = PAGE_SESSION_HEAL;
                renderConsole();
                conToast(r.headline());
            }});
        }}, "dsh-session-scan").start();
    }

    /** 会话修复页。 */
    /** 会话修复页（v1.19.6：统一版式 —— 结论卡在上 + 行式列表 + 操作区）。 */
    private void renderConsoleSessionHeal() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.sessionheal", "会话修复")));
        col.addView(cNote(t("desc.sessionhealPage",
                "坏附件（比如内容已经损坏的图片）一旦写进会话历史，之后每次发消息都会重新读它、"
              + "每次都以同样方式失败，整条会话就只能放弃。"
              + "这里只做一件事：把坏掉的那一条消息里的附件换成一行文字说明，会话的正文、上下文与顺序一字不动。"
              + "扫描是只读的；真正改写前会让你确认，并且先把原文件备份一份。")), cTop(cGap(8)));

        SessionHeal.HealResult last = conSessionLastHeal;
        if (last != null) {
            boolean good = last.ok && last.applied;
            String sub = cCut(last.headline(), 90)
                    + (last.backup != null && last.backup.length() > 0
                        ? ("；原文件已备份到同目录 " + new File(last.backup).getName()) : "");
            LinearLayout lb = cCardBox();
            lb.addView(cCardRow(t("status.lastHeal", "上次修复"), sub,
                    good ? t("status.done", "完成")
                         : (last.ok ? t("status.noChange", "无改动") : t("status.failed", "失败")),
                    good ? cGreen() : (last.ok ? cSub() : cRed()), false, null));
            col.addView(lb, cTop(cGap(14)));
        }

        if (conSessionHealingId != null) {
            col.addView(cText("正在修复…（先备份，再只降级坏引用，最后复检）", 12f, cText(), true), cTop(cGap(12)));
            return;
        }
        if (conSessionScanning) {
            col.addView(cText("正在扫描会话…（只读）", 12f, cText(), true), cTop(cGap(12)));
            return;
        }

        SessionHeal.AuditResult r = conSessionAudit;
        if (r == null) {
            col.addView(cNote(t("desc.sessionhealIdle",
                    "还没有扫描过。点下面的「扫描会话」开始 —— 扫描全程只读。")), cTop(cGap(12)));
        } else if (!r.ok) {
            col.addView(cGrpTitle(t("title.grpSessionBad", "扫描结果")), cTop(cGap(16)));
            LinearLayout box = cCardBox();
            box.addView(cCardRow(t("status.scanFailed", "扫描失败"), cCut(r.error, 80), "",
                    cRed(), false, null));
            col.addView(box, cTop(cGap(8)));
        } else {
            java.util.List<SessionHeal.SessionInfo> bad = r.problemSessions();
            // 结论在上：一张卡说清"扫了多少、坏了几条"，下面才是列表
            LinearLayout c0 = cCardBox();
            c0.addView(cText(r.headline(), 13f, bad.isEmpty() ? cGreen() : cRed(), true), cTop(cGap(12)));
            c0.addView(cText(bad.isEmpty()
                            ? t("desc.noBadSession", "所有会话的附件引用都能对上实体文件、内容也完整 —— 不需要修什么。")
                            : t("desc.sessionBadHint", "点一条查看它坏在哪、再决定要不要修（只会动你点的这一条）："),
                    11f, cSub(), false), cTop(cGap(6)));
            c0.addView(new View(this), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
            col.addView(c0, cTop(cGap(12)));

            if (!bad.isEmpty()) {
                col.addView(cGrpTitle(t("title.grpSessionBadList", "有问题的会话")), cTop(cGap(16)));
                LinearLayout box = cCardBox();
                for (int i = 0; i < bad.size(); i++) {
                    if (i > 0) box.addView(cCardSep());
                    box.addView(conSessionRow(bad.get(i)));
                }
                col.addView(box, cTop(cGap(8)));
            }
            int normal = r.sessionCount - r.badSessionCount;
            if (normal > 0) {
                col.addView(cNote("另有 " + normal + " " + t("desc.sessionNormalOther",
                        "条会话没有发现问题（未在下面列出）。")), cTop(cGap(10)));
            }
        }

        Button scan = cButton(r == null ? t("btn.session.scan", "扫描会话")
                : t("btn.session.rescan", "重新扫描"), r == null);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { conSessionScan(); }
        });
        col.addView(scan, cTop(cGap(14)));

        col.addView(cNote(t("desc.sessionhealWarn",
                "边界（写死在代码里）：① 只处理你点选的那一条，没有「批量修复」这种东西；"
              + "② 引擎在跑时不允许改写（内核可能正在写同一条会话），要先停止引擎；"
              + "③ 改写前把原文件备份成 <会话文件>.corrupt-<时间>；"
              + "④ 修完立刻复检：坏引用必须归零、行数与 JSON 合法性必须不变，否则如实报「部分完成」。")), cTop(cGap(12)));
    }


    /** 详情弹窗：把「会改什么」原样摆出来，再让用户去确认。 */
    private void conSessionDetail(final SessionHeal.SessionInfo si) {
        StringBuilder sb = new StringBuilder();
        sb.append("会话 ").append(si.id).append("\n");
        sb.append(si.sizeText()).append(" · ").append(si.lines).append(" 行 · ")
          .append(si.frames).append(" 个数据块\n\n");
        sb.append("将把下面 ").append(si.badCount).append(" 处引用换成一行文字说明")
          .append("（保留文件名与尺寸，不删消息、不动其他内容）：\n");
        int shown = Math.min(si.bad.size(), 8);
        for (int i = 0; i < shown; i++) {
            sb.append("· ").append(si.bad.get(i).describe()).append("\n");
        }
        if (si.bad.size() > shown) sb.append("· …另外 ").append(si.bad.size() - shown).append(" 处同类问题\n");
        sb.append("\n改写前会把原文件备份成同目录的 .corrupt-<时间>；")
          .append("修完立刻复检：坏引用必须归零、行数与 JSON 合法性不变。");
        conDialog("会话 " + conShortId(si.id), sb.toString(), "我明白，去确认", new Runnable() {
            @Override public void run() { conSessionHealConfirm(si); }
        }, "取消");
    }

    /** 引擎在跑时不允许改写会话（内核可能正在写同一条）。 */
    private boolean conSessionHealBlockedByEngine() {
        if (!conEngineRunning()) return false;
        conDialog("先停止引擎",
                "修复要改写会话文件，而引擎正在运行 —— 它可能同时在写同一条会话。\n\n"
              + "请先回主控台点「休息一下」（停止引擎），再回来修复。"
              + "这样做是为了不让一次修复把正在使用的会话写坏。",
                "知道了", null, null);
        return true;
    }

    /** 红框确认：真正会写盘的那一步（与主题动作同规格）。 */
    private void conSessionHealConfirm(final SessionHeal.SessionInfo si) {
        if (conSessionHealBlockedByEngine()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("动作：只降级这一条会话里的坏附件引用（").append(si.badCount).append(" 处）\n");
        sb.append("会话：").append(si.id).append("\n");
        sb.append("位置：").append(si.file).append("\n\n");
        sb.append("会做什么：\n");
        sb.append("1. 先把原文件复制成 <会话文件>.corrupt-<时间>（失败就中止，一个字都不改）；\n");
        sb.append("2. 只把那些坏掉的附件块换成文字说明（形如「【附件不可用】…」），其余字节原样保留；\n");
        sb.append("3. 立刻复检：坏引用必须归零、行数与 JSON 合法性必须不变；\n");
        sb.append("4. 写一条自愈账本（含动作清单与前后字节数）。\n\n");
        sb.append("不会做什么：不删消息、不动别的会话、不批量扫描用户数据。");
        conDialogDanger("只降级这条会话的坏附件", sb.toString(), "确认修复（先备份）", new Runnable() {
            @Override public void run() { conSessionHealRun(si); }
        }, "取消");
    }

    /** 真正执行（已经在上一步确认过）。 */
    private void conSessionHealRun(final SessionHeal.SessionInfo si) {
        if (conSessionHealingId != null) { conToast("已经有一个修复在跑"); return; }
        if (conSessionHealBlockedByEngine()) return;
        conSessionHealingId = si.id;
        conToast("正在修复（先备份，再只降级坏引用）…");
        renderConsole();
        final File payload = payloadDir();
        final File dshHome = new File(payload, "dshhome");
        new Thread(new Runnable() { @Override public void run() {
            final SessionHeal.HealResult hr = SessionHeal.heal(MainActivity.this, payload, dshHome, si.file, true);
            try {
                conLedgerWrite("session-heal", conSessionHealLedgerJson(si, hr));
            } catch (Throwable ignored) {}
            ui.post(new Runnable() { @Override public void run() {
                conSessionHealingId = null;
                conSessionLastHeal = hr;
                consolePage = PAGE_SESSION_HEAL;
                renderConsole();
                StringBuilder sb = new StringBuilder();
                sb.append(hr.headline()).append("\n");
                if (hr.backup != null && hr.backup.length() > 0) {
                    sb.append("\n原文件已备份：").append(new File(hr.backup).getName());
                }
                if (hr.applied) {
                    sb.append("\n复检：坏引用 ").append(hr.badRefsAfter)
                      .append(" · 行数 ").append(hr.linesBefore).append(" → ").append(hr.linesAfter)
                      .append(" · JSON 损坏 ").append(hr.jsonBadAfter);
                }
                if (hr.refused != null && hr.refused.length() > 0) sb.append("\n\n").append(hr.refused);
                conDialog(hr.ok ? "修复完成" : "修复结果", sb.toString(), "好", null, null);
            }});
        }}, "dsh-session-heal").start();
    }

    /** 写进自愈账本的内容（症状 → 判据 → 动作 → 前后字节 → 结果）。 */
    private String conSessionHealLedgerJson(SessionHeal.SessionInfo si, SessionHeal.HealResult hr) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"trigger\":\"console\",\"kind\":\"session-attachment-degrade\",");
        sb.append("\"symptom\":\"会话历史里有坏附件引用（每次请求都会复现）\",");
        sb.append("\"session\":\"").append(conJsonEsc(si.id)).append("\",");
        sb.append("\"sessionFile\":\"").append(conJsonEsc(si.file)).append("\",");
        sb.append("\"badRefsBefore\":").append(si.badCount).append(",");
        sb.append("\"attRefs\":").append(si.attRefs).append(",");
        sb.append("\"ok\":").append(hr.ok).append(",");
        sb.append("\"applied\":").append(hr.applied).append(",");
        sb.append("\"refused\":\"").append(conJsonEsc(hr.refused)).append("\",");
        sb.append("\"verdict\":\"").append(conJsonEsc(hr.verdict)).append("\",");
        sb.append("\"backup\":\"").append(conJsonEsc(hr.backup)).append("\",");
        sb.append("\"framesRewritten\":").append(hr.framesRewritten).append(",");
        sb.append("\"bytesBefore\":").append(hr.bytesBefore).append(",");
        sb.append("\"bytesAfter\":").append(hr.bytesAfter).append(",");
        sb.append("\"badRefsAfter\":").append(hr.badRefsAfter).append(",");
        sb.append("\"linesBefore\":").append(hr.linesBefore).append(",");
        sb.append("\"linesAfter\":").append(hr.linesAfter).append(",");
        sb.append("\"verifiedBy\":\"session-heal.mjs 复检：坏引用归零 + 行数与 JSON 合法性不变\",");
        StringBuilder acts = new StringBuilder("[");
        for (int i = 0; i < hr.actions.size() && i < 50; i++) {
            if (i > 0) acts.append(",");
            acts.append("\"").append(conJsonEsc(hr.actions.get(i))).append("\"");
        }
        acts.append("]");
        sb.append("\"actions\":").append(acts);
        sb.append("}");
        return sb.toString();
    }

    /** 跑自检（后台线程；全量校验要算 sha256，绝不能在主线程做）。 */
    private void conRunSelfCheck(final boolean quick) {
        if (conSelfCheckRunning) return;
        conSelfCheckRunning = true;
        conSelfCheckResult = null;
        conSelfCheckProgress[0] = 0;
        conSelfCheckProgress[1] = 0;
        conSelfCheckPhase = quick ? "比对大小" : "校验内容（sha256）";
        conSelfCheckCancel = false;
        conSelfCheckStart = System.currentTimeMillis();
        conToast(quick ? "开始快速自检（只比大小）…" : "开始全量校验（逐个 sha256，可能要 1~3 分钟）…");
        renderConsole();
        // 进度刷新：后台只写数据，这里按 ~400ms 节流重绘（不刷屏、也不拖慢校验）
        final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (!conSelfCheckRunning) return;
                if (conSelfCheckCancel) return;
                if (consoleVisible && consolePage == PAGE_SELFCHECK) renderConsole();
                ui.postDelayed(this, 400);
            }
        };
        ui.postDelayed(ticker, 400);
        final File root = conKernelRoot();
        new Thread(new Runnable() { @Override public void run() {
            KernelSelfCheck.Result r = null;
            try {
                r = KernelSelfCheck.run(MainActivity.this, root, quick, new KernelSelfCheck.Progress() {
                    @Override public void onProgress(String phase, int done, int total) {
                        conSelfCheckProgress[0] = done;
                        if (total > 0) conSelfCheckProgress[1] = total;
                        if (phase != null && phase.length() > 0) conSelfCheckPhase = phase;
                    }
                    @Override public boolean cancelled() { return conSelfCheckCancel; }
                });
            } catch (Throwable t) {
                r = new KernelSelfCheck.Result();
                r.verdict = "fail";
                r.headline = "自检抛异常：" + t;
            }
            final KernelSelfCheck.Result fr = r;
            // 账本：自检结果本身就是证据（依据什么判断、结论是什么）
            try {
                conLedgerWrite("selfcheck", "{\"trigger\":\"console\",\"result\":" + fr.toLedgerJson(quick ? "quick" : "full", String.valueOf(root)) + "}");
            } catch (Throwable ignored) {}
            conSelfCheckRunning = false;
            conSelfCheckResult = fr;
            ui.post(new Runnable() { @Override public void run() {
                consolePage = PAGE_SELFCHECK;
                renderConsole();
                conToast(uiPlain("自检完成：" + fr.verdict + "（缺失 " + fr.missing + " / 不符 " + fr.mismatch + "）"));
            }});
        }}, "dsh-selfcheck").start();
    }

    /** 看账本：弹窗列出最近几条（自绘弹窗，与既有风格一致）。 */
    private void conShowLedger() {
        try {
            File[] fs = conHealLedgerDir().listFiles();
            if (fs == null || fs.length == 0) { conToast("账本还空着：跑一次自检就会写下第一条"); return; }
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (int i = 0; i < fs.length && shown < 10; i++) {
                sb.append("· ").append(f.format(new java.util.Date(fs[i].lastModified()))).append("  ").append(fs[i].getName()).append("\n");
                shown++;
            }
            sb.append("\n共 ").append(fs.length).append(" 条。目录：/sdcard/").append(pkgRoot()).append("/heal-ledger/");
            conDialog("自愈账本（最近 " + shown + " 条）", sb.toString(), "好", null, null);
        } catch (Throwable t) {
            conToast("账本读取失败：" + t.getMessage());
        }
    }

    /** 配置快照（白名单文件的只读副本）—— 唯一会写盘的动作，所以走确认框。 */
    private void conSnapshotConfigConfirm() {
        conDialog("配置快照",
                "把内核配置里最容易被改坏、也最需要能回退的几个文件「另存一份副本」（不动原文件）：\n\n"
              + "· dshhome/profiles/web/cordis.patch.yml（插件树/开关）\n"
              + "· dshhome/profiles/web/package.json（插件注册表）\n"
              + "· dshhome/settings.yaml（界面设置）\n\n"
              + "存到 /sdcard/" + pkgRoot() + "/heal-ledger/config-snapshot-<时间>/，并写一条账本。",
                "建立快照",
                new Runnable() { @Override public void run() { conSnapshotConfigNow(); } },
                "取消");
    }

    private void conSnapshotConfigNow() {
        try {
            if (dshrootDir == null) { conToast("内核树还没就位，先解压"); return; }
            File dshhome = new File(dshrootDir.getParentFile(), "dshhome");
            if (!dshhome.isDirectory()) { conToast("找不到 dshhome： " + dshhome); return; }
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
            File dir = new File(conHealLedgerDir(), "config-snapshot-" + f.format(new java.util.Date()));
            dir.mkdirs();
            String[] rels = {
                    "profiles/web/cordis.patch.yml",
                    "profiles/web/package.json",
                    "settings.yaml",
            };
            StringBuilder items = new StringBuilder("[");
            int n = 0;
            for (int i = 0; i < rels.length; i++) {
                File src = new File(dshhome, rels[i]);
                if (!src.isFile()) continue;
                String sha = sha256Of(src);
                File dst = new File(dir, rels[i].replace('/', '_'));
                java.io.FileInputStream in = new java.io.FileInputStream(src);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[16384];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                in.close();
                out.close();
                if (n > 0) items.append(",");
                items.append("{\"path\":\"").append(rels[i]).append("\",\"bytes\":").append(src.length())
                     .append(",\"sha256\":\"").append(sha).append("\"}");
                n++;
            }
            items.append("]");
            conLedgerWrite("config-snapshot", "{\"dir\":\"" + dir.getName() + "\",\"files\":" + items + "}");
            conToast("已建立配置快照（" + n + " 个文件）→ " + dir.getName());
            renderConsole();
        } catch (Throwable t) {
            conToast("快照失败：" + t.getMessage());
        }
    }

    private void renderConsoleLog() {
        LinearLayout col = consoleBody;
        col.addView(conBackRow(t("card.log", "日志")));
        col.addView(cNote(t("desc.logPage",
                "「查看」直接看末尾 200 行；「分享」调用系统分享（QQ / 微信 / 邮件…都能选），"
              + "正文里带完整日志路径与末尾 400 行。")), cTop(cGap(8)));

        col.addView(cGrpTitle(t("title.grpLogFile", "日志文件")), cTop(cGap(16)));
        LinearLayout box = cCardBox();
        box.addView(cCardRow(t("btn.log.view", "查看日志"), conLogSummary(), "", true, new View.OnClickListener() {
            @Override public void onClick(View x) { conViewLog(); }
        }));
        col.addView(box, cTop(cGap(8)));

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        Button e = cButton(t("btn.log.share", "分享"), true);
        e.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conShareLog(); } });
        acts.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button v = cButton(t("btn.log.view", "查看日志"), false);
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { conViewLog(); } });
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = dp(8);
        acts.addView(v, vlp);
        col.addView(acts, cTop(cGap(12)));

        // v1.19.6：清空日志是**不可逆**的，按统一版式单独成块、离手远（原来紧跟在操作区下面一行）
        col.addView(cGrpTitle(t("title.grpLogCare", "维护")), cTop(cGap(20)));
        LinearLayout box2 = cCardBox();
        box2.addView(cCardRow(t("btn.log.clear", "清空日志"),
                t("desc.logClear", "只清日志，不影响正在运行的引擎；之后的新日志会继续写入"),
                t("btn.log.clearShort", "清空"), cRed(), true, new View.OnClickListener() {
            @Override public void onClick(View x) { conClearLog(); }
        }));
        col.addView(box2, cTop(cGap(8)));
    }

    private File conLogFile() { return new File(getFilesDir(), "dsh-web.log"); }

    /**
     * 与日志相关的全部文件：内部/外部两份 dsh-web.log + 两份 startup-diag.txt。
     * 外部那份是 v1.7.1 起镜像的（/sdcard/<pkgRoot>/），两处都要清，否则外部的旧内容还在。
     */
    private File[] conLogTargets() {
        File extRoot = new File(Environment.getExternalStorageDirectory(), pkgRoot());
        return new File[]{
                new File(getFilesDir(), "dsh-web.log"),
                new File(getFilesDir(), "startup-diag.txt"),
                // v1.21：工作区判定事实（偏好值 / 四级候选目录的可写性 / 权限状态 / 引擎 cwd）。
                // 真机报"默认工作区无法设置"时，这份文件一眼就能看出卡在哪一级。
                new File(getFilesDir(), "workspace-diag.txt"),
                // v1.21：前端报错/接口失败（mobile.js 的 error/reject/fetch 钩子经 dshshell.note 落盘）。
                new File(getFilesDir(), "web-notes.log"),
                new File(extRoot, "dsh-web.log"),
                new File(extRoot, "startup-diag.txt"),
                new File(extRoot, "workspace-diag.txt"),
        };
    }

    /**
     * v1.21：把"工作区是怎么定的"写进 files/workspace-diag.txt（控制台「日志→分享」会带上）。
     * 内容：偏好值、四级候选目录各自是否存在/可写、存储权限与"所有文件访问"状态、最终选中的引擎 cwd。
     * 这样真机出问题时不用猜。
     */
    private void writeWorkspaceDiag(File engineCwd) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("workspace-diag @ ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date())).append('\n');
            sb.append("pref(workspace_path) = ").append(String.valueOf(workspacePath())).append('\n');
            sb.append("externalStorageState = ").append(Environment.getExternalStorageState()).append('\n');
            try {
                sb.append("WRITE_EXTERNAL_STORAGE granted = ")
                        .append(checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                                == PackageManager.PERMISSION_GRANTED).append('\n');
            } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 30) {
                try { sb.append("MANAGE_EXTERNAL_STORAGE granted = ").append(Environment.isExternalStorageManager()).append('\n'); }
                catch (Throwable ignored) {}
            }
            sb.append("--- 候选目录 ---\n");
            appendProbe(sb, "1 共享 /sdcard/DeepSeekHarness/Workspace", defaultWorkspaceDir());
            appendProbe(sb, "2 变体 /sdcard/" + pkgRoot() + "/Workspace", variantWorkspaceDir());
            appendProbe(sb, "3 应用外部 files/Workspace", appExternalWorkspaceDir());
            appendProbe(sb, "4 应用内部 filesDir/Workspace", appInternalWorkspaceDir());
            sb.append("engine cwd = ").append(engineCwd == null ? "(未设置)" : engineCwd.getAbsolutePath()).append('\n');
            File out = new File(getFilesDir(), "workspace-diag.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false);
            try { fos.write(sb.toString().getBytes("UTF-8")); } finally { try { fos.close(); } catch (Throwable ignored) {} }
            // 同时镜像一份到外部目录（用户能直接用文件管理器拿走），以及**追加到 dsh-web.log**
            // —— 后者最重要：用户已经在分享那个文件，这样不必额外找文件就能看到工作区判定。
            try {
                File extRoot = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                if (!extRoot.exists()) extRoot.mkdirs();
                java.io.FileOutputStream ef = new java.io.FileOutputStream(new File(extRoot, "workspace-diag.txt"), false);
                try { ef.write(sb.toString().getBytes("UTF-8")); } finally { try { ef.close(); } catch (Throwable ignored) {} }
            } catch (Throwable ignored) {}
            try {
                java.io.FileOutputStream lf = new java.io.FileOutputStream(new File(getFilesDir(), "dsh-web.log"), true);
                try {
                    lf.write(("\n[app] ---- workspace-diag ----\n").getBytes("UTF-8"));
                    lf.write(sb.toString().getBytes("UTF-8"));
                    lf.write(("[app] ---- /workspace-diag ----\n").getBytes("UTF-8"));
                } finally { try { lf.close(); } catch (Throwable ignored) {} }
            } catch (Throwable ignored) {}
            Log.i(TAG, "workspace-diag 已写入 " + out.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "writeWorkspaceDiag failed", t);
        }
    }

    private void appendProbe(StringBuilder sb, String label, File dir) {
        try {
            if (dir == null) { sb.append(label).append(": (null)\n"); return; }
            sb.append(label).append(": ").append(dir.getAbsolutePath())
                    .append(" exists=").append(dir.exists())
                    .append(" writableByApp=").append(isWritableDir(dir))
                    .append('\n');
        } catch (Throwable t) {
            sb.append(label).append(": probe failed ").append(t).append('\n');
        }
    }

    /**
     * 清空日志。
     * 关键：引擎写日志的句柄是追加模式（FileOutputStream(f, true) → O_APPEND），
     * 所以把文件截断到 0 既不会打断正在运行的引擎，也不会丢后续日志 ——
     * 追加写总是写到当前文件末尾，截断后自然从 0 重新开始。
     * （不要用 delete()：那会把 inode 摘掉，引擎的旧句柄会继续往已删除的文件里写，
     * 于是"显示已清空、新日志却消失"。）
     */
    private void conClearLog() {
        long total = 0;
        for (File f : conLogTargets()) {
            try { if (f.exists()) total += f.length(); } catch (Throwable ignored) {}
        }
        if (total == 0) { conToast("日志已经是空的"); return; }
        // 与控制台其余弹窗统一走自绘浮层（conDialog），不再用系统 AlertDialog
        // ——后者会带一层主题面板外框，和本页其它弹窗不是一套视觉。
        conDialog("清空日志",
                "当前共 " + (total / 1024) + " KB。\n\n"
                        + "清空不影响正在运行的引擎，之后的新日志会继续正常写入。",
                "清空",
                new Runnable() { @Override public void run() { conClearLogNow(); } },
                "取消");
    }

    private void conClearLogNow() {
        int n = 0;
        long freed = 0;
        for (File f : conLogTargets()) {
            try {
                if (!f.exists() || f.length() == 0) continue;
                long len = f.length();
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                try { raf.setLength(0); } finally { raf.close(); }
                freed += len;
                n++;
            } catch (Throwable t) {
                Log.w(TAG, "clear log failed: " + f, t);
            }
        }
        conToast(n == 0 ? "没有可清空的日志" : ("已清空 " + n + " 个日志文件，释放 " + (freed / 1024) + " KB"));
        if (consoleVisible && consolePage == 3) renderConsole();
    }

    private String conLogSummary() {
        try {
            File f = conLogFile();
            if (!f.exists()) return "还没有日志（引擎没启动过）";
            return "dsh-web.log · " + (f.length() / 1024) + " KB · "
                    + new SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(f.lastModified()));
        } catch (Throwable t) { return "日志不可读"; }
    }

    private String conTailOf(File f, int maxLines) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 65536);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, "UTF-8");
            String[] lines = s.split("\n");
            if (lines.length <= maxLines) return s;
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - maxLines; i < lines.length; i++) { sb.append(lines[i]); sb.append('\n'); }
            return sb.toString();
        } catch (Throwable t) { return "读取失败：" + t.getMessage(); }
    }

    private void conViewLog() {
        try {
            File f = conLogFile();
            if (!f.exists()) { conToast("还没有日志（引擎没启动过）"); return; }
            TextView tv = cText(conTailOf(f, 200), 10.5f, cText(), false);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            ScrollView sv = new ScrollView(this);
            // v1.19.6 修复：这里原来用 isDark()（系统偏好），而正文文字走的是主题色 ——
            // 主题写 appearance.dark:"dark" 而手机是浅色时，就是"浅底 + 浅字"，正文看不见。
            // 同文件另外两处同样的写法（主题诊断 L4922 / …L5872）本来就是 conDark()，属漏改。
            sv.setBackground(cShape(conDark() ? 0xFF0F1524 : 0xFFF2F4F8, 0, 0, 6));
            sv.setPadding(dp(12), dp(12), dp(12), dp(12));
            sv.addView(tv);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
            conDialogView("dsh-web.log（末尾 200 行）", sv, "关闭", null, null);
        } catch (Throwable t) { conToast("读取日志失败：" + t.getMessage()); }
    }

    /** v1.12：不再只能导到固定目录 —— 直接调系统分享（QQ / 微信 / 邮件随意选）。 */
    @SuppressWarnings("unused")
    private void conShareLogText() {
        conToast("正在准备日志…");
        new Thread(new Runnable() { @Override public void run() {
            final String body = conBuildShareText();
            ui.post(new Runnable() { @Override public void run() { conSendShare(body); } });
        }}, "log-share").start();
    }

    private String conBuildShareText() {
        try {
            File f = conLogFile();
            String head = "DeepSeek Harness 日志\n"
                    + "版本 " + conVersionLabel() + "\n"
                    + "端口 " + enginePort + " · " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "\n"
                    + "完整日志路径：" + f.getAbsolutePath() + "\n"
                    + "-------- dsh-web.log（末尾 400 行）--------\n";
            if (!f.exists()) return head + "（还没有日志：引擎没启动过）";
            return head + conTailOf(f, 400);
        } catch (Throwable t) { return "日志读取失败：" + t.getMessage(); }
    }

    /** v1.13：以文件形式分享日志（原来是纯文字）。
     * 准备好 dsh-web.log + 诊断文件 → 拷到 cache/share → 用 LogShareProvider 的 content:// URI 发出去。 */
    private void conShareLog() {
        conToast("正在准备日志文件…");
        new Thread(new Runnable() { @Override public void run() {
            final java.util.List<File> files = new java.util.ArrayList<File>();
            String err = null;
            try {
                File dir = LogShareProvider.shareDir(MainActivity.this);
                // 清掉上一次的残留，避免分享到旧文件
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) { try { o.delete(); } catch (Throwable ignored) {} }

                File log = conLogFile();
                if (log.exists() && log.length() > 0) {
                    File dst = new File(dir, "dsh-web.log");
                    copyFile(log, dst);
                    files.add(dst);
                }
                // 启动诊断（启动失败时才有）与外部日志镜像，一并带上
                File diag = new File(getFilesDir(), "startup-diag.txt");
                if (diag.exists() && diag.length() > 0) {
                    File dst = new File(dir, "startup-diag.txt");
                    copyFile(diag, dst);
                    files.add(dst);
                }
            } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            final String e = err;
            ui.post(new Runnable() { @Override public void run() {
                if (files.isEmpty()) { conToast("没有可分享的日志：" + (e != null ? e : "引擎还没启动过")); return; }
                conSendLogFiles(files);
            } });
        }}, "log-share").start();
    }

    /** 发多个文件（ACTION_SEND_MULTIPLE + 读权限临时授权）。 */
    private void conSendLogFiles(java.util.List<File> files) {
        try {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<Uri>();
            for (File f : files) uris.add(LogShareProvider.uriFor(this, f.getName()));
            Intent send;
            if (uris.size() == 1) {
                send = new Intent(Intent.ACTION_SEND);
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            send.setType("text/plain");   // 兼容性好：IM/邮件/网盘都能接
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "分享日志文件");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        } catch (Throwable t) { conToast("调不起分享：" + t.getMessage()); }
    }

    /** 原纯文字分享（保留：某些接不住文件的场景可回退）。 */
    private void conSendShare(String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "DeepSeek Harness 日志");
            send.putExtra(Intent.EXTRA_TEXT, text);
            Intent chooser = Intent.createChooser(send, "分享日志");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        } catch (Throwable t) { conToast("调不起分享：" + t.getMessage()); }
    }

    private void conExportLog() {
        conToast("正在导出…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                File dir = new File(Environment.getExternalStorageDirectory(), "Download/DSH日志-" + ts);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed: " + dir);
                File log = conLogFile();
                if (log.exists()) conCopyFile(log, new File(dir, "dsh-web.log"));
                File ext = new File(Environment.getExternalStorageDirectory(), pkgRoot());
                File mirror = new File(ext, "dsh-web.log");
                if (mirror.exists()) conCopyFile(mirror, new File(dir, "dsh-web-mirror.log"));
                File diag = new File(ext, "startup-diag.txt");
                if (diag.exists()) conCopyFile(diag, new File(dir, "startup-diag.txt"));
                final String p = dir.getAbsolutePath();
                ui.post(new Runnable() { @Override public void run() { conToast("已导出到 " + p); } });
            } catch (Throwable t) {
                final String m = String.valueOf(t.getMessage());
                ui.post(new Runnable() { @Override public void run() { conToast("导出失败：" + m); } });
            }
        }}, "log-export").start();
    }

    private void conCopyFile(File src, File dst) throws IOException {
        File p = dst.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    // ==================== 安全模式（救援启动） ====================
    //
    // 为什么需要它：引擎启动时要把 profile 的 patch 文件当配置解析。一旦这些文件
    // 被写坏（用户装的插件写坏配置是常见路径），内核直接拒启：
    //   dsh: failed to parse overlay <...>/profiles/web/cordis.patch.yml: YAMLException: ...
    // 而「修它」又必须先把引擎跑起来（Web UI 与 AI 都靠引擎）—— 形成死结。
    //
    // 安全模式：把 profile 的「用户层」（可能被写坏的那些）整体旁置到旁边的
    // web.userlayer-<时间>/，再从 APK 里恢复出厂 profile 文件。
    // 用户数据（sessions / storages / .credentials.yaml / settings.yaml / 工作区绑定）「一律不动」。
    // 引擎跑起来后，AI 可以直接读旁置目录里的坏文件去定位问题；修好后「退出安全模式」还回去。

    /** profile 目录（web）。 */
    private File profileDir() { return new File(payloadDir(), "dshhome/profiles/web"); }

    /** 用户层：可能被用户/插件改坏、安全模式下要旁置的部分。 */
    private static final String[] PROFILE_USER_LAYER = {
            "cordis.patch.yml", "package.json", "pnpm-lock.yaml", "node_modules"
    };

    /** 出厂就该有的 profile 文件（payload.zip 里带，恢复安全模式时写回）。 */
    private static final String[] PROFILE_SHIPPED = {
            "cordis.yml", "cordis.patch.yml", "package.json", "pnpm-workspace.yaml"
    };

    /** 最近一次安全模式旁置目录名（退出安全模式时据此还原）。 */
    private static final String KEY_SAFE_STASH = "safe_mode_stash";

    private boolean safeModeActive() { return prefs().getBoolean(KEY_SAFE_MODE, false); }
    private void setSafeModeFlag(boolean on) { prefs().edit().putBoolean(KEY_SAFE_MODE, on).apply(); }
    private int bootFailures() { return prefs().getInt(KEY_BOOT_FAILS, 0); }
    private void resetBootFailures() {
        if (bootFailures() != 0) prefs().edit().putInt(KEY_BOOT_FAILS, 0).apply();
    }
    private void bumpBootFailure() {
        final int n = bootFailures() + 1;
        prefs().edit().putInt(KEY_BOOT_FAILS, n).apply();
        Log.w(TAG, "engine boot failure #" + n);
        ui.post(new Runnable() { @Override public void run() {
            if (consoleVisible) refreshConsole();
            if (n == BOOT_FAIL_HINT_AT) conToast("引擎连续启动失败：可在控制台用「安全模式启动」，不会丢数据");
        }});
    }

    /** 安全模式启动（带确认）。 */
    private void conSafeMode() {
        conDialog("安全模式启动",
                "做法：把 profile 的用户层（可能被插件写坏的配置）整体旁置，"
                        + "用出厂配置启动引擎。\n\n"
                        + "你的会话记录、API Key、模型配置、工作区绑定都会原样保留；"
                        + "被旁置的文件也不删，启动后 AI 可以直接读它们来定位问题。\n\n"
                        + "现在进入安全模式吗？",
                "进入安全模式",
                new Runnable() { @Override public void run() { conSafeModeNow(); } },
                "取消");
    }

    private void conSafeModeNow() {
        conToast("正在进入安全模式…");
        new Thread(new Runnable() { @Override public void run() {
            final String err;
            try {
                killEngineNow();
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline && portListening(enginePort)) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                err = enterSafeMode();
            } catch (Throwable t) {
                Log.e(TAG, "enter safe mode failed", t);
                ui.post(new Runnable() { @Override public void run() { conToast("进入安全模式失败：" + t.getMessage()); } });
                return;
            }
            ui.post(new Runnable() { @Override public void run() {
                conToast(err == null ? "已进入安全模式，正在启动引擎…" : err);
                refreshConsole();
                if (err == null) { engineStoppedByUser = false; engineStartAborted = false; conEngineClick(); }
            }});
        }}, "safe-mode-on").start();
    }

    /** 退出安全模式（把旁置的用户层还回去）。 */
    private void conExitSafeMode() {
        conDialog("退出安全模式",
                "把上次旁置的用户层放回去（覆盖当前出厂配置）。\n\n"
                        + "如果刚才是因为插件把配置写坏才进的安全模式，建议先在对话里让 AI 把坏文件修好，"
                        + "否则退出后可能又启动不了。\n\n确认退出吗？",
                "退出并还原",
                new Runnable() { @Override public void run() { conExitSafeModeNow(); } },
                "取消");
    }

    private void conExitSafeModeNow() {
        conToast("正在退出安全模式…");
        new Thread(new Runnable() { @Override public void run() {
            final String err;
            try {
                killEngineNow();
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline && portListening(enginePort)) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                err = exitSafeMode();
            } catch (Throwable t) {
                Log.e(TAG, "exit safe mode failed", t);
                ui.post(new Runnable() { @Override public void run() { conToast("退出安全模式失败：" + t.getMessage()); } });
                return;
            }
            ui.post(new Runnable() { @Override public void run() {
                conToast(err == null ? "已退出安全模式，正在启动引擎…" : err);
                refreshConsole();
                if (err == null) { engineStoppedByUser = false; engineStartAborted = false; conEngineClick(); }
            }});
        }}, "safe-mode-off").start();
    }

    /** 真正干活：旁置用户层 + 恢复出厂 profile 文件。返回 null 表示成功，否则为错误文案。 */
    private String enterSafeMode() {
        try {
            File dir = profileDir();
            if (!dir.exists() && !dir.mkdirs()) return "无法创建 profile 目录：" + dir;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            File stash = new File(dir.getParentFile(), "web.userlayer-" + stamp);
            if (!stash.exists() && !stash.mkdirs()) return "无法创建旁置目录：" + stash;
            int moved = 0;
            for (String name : PROFILE_USER_LAYER) {
                File src = new File(dir, name);
                if (!src.exists()) continue;
                File dst = new File(stash, name);
                if (!src.renameTo(dst)) { backupCopyRec(src, dst); backupDeleteRec(src); }
                moved++;
            }
            int restored = restoreShippedProfileFiles();
            prefs().edit().putBoolean(KEY_SAFE_MODE, true).putString(KEY_SAFE_STASH, stash.getName()).apply();
            Log.w(TAG, "safe mode on: moved " + moved + " entries to " + stash + ", restored " + restored + " shipped files");
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "enterSafeMode", t);
            return "进入安全模式失败：" + t.getMessage();
        }
    }

    /** 把旁置的用户层放回去，并清掉安全模式标记。 */
    private String exitSafeMode() {
        try {
            File dir = profileDir();
            String stashName = prefs().getString(KEY_SAFE_STASH, null);
            if (stashName == null || stashName.isEmpty()) {
                setSafeModeFlag(false);
                return "没有找到旁置目录，已直接关闭安全模式";
            }
            File stash = new File(dir.getParentFile(), stashName);
            if (!stash.exists()) {
                setSafeModeFlag(false);
                return "旁置目录已不存在（" + stashName + "），已直接关闭安全模式";
            }
            int back = 0;
            File[] kids = stash.listFiles();
            if (kids != null) {
                for (File src : kids) {
                    File dst = new File(dir, src.getName());
                    backupDeleteRec(dst);   // 先清掉出厂版，再把用户层放回
                    if (!src.renameTo(dst)) { backupCopyRec(src, dst); backupDeleteRec(src); }
                    back++;
                }
            }
            setSafeModeFlag(false);
            Log.w(TAG, "safe mode off: restored " + back + " entries from " + stash);
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "exitSafeMode", t);
            return "退出安全模式失败：" + t.getMessage();
        }
    }

    // ==================== 备份：导出 / 导入 ====================
    // 用户需求：装插件把引擎弄崩时，能先把全部数据导出，清完重装再导回，什么都不丢。
    // 与安全模式互补：安全模式解决「不用清数据就能启动」，导出/导入解决「换机、彻底重装、留底」。

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    /** 参与备份的偏好项（其余是缓存/引擎运行态，不必带走）。 */
    private static final String[] BACKUP_PREF_S = {
            "workspace_path", "shizuku_app_id", "engine_port"
    };
    private static final String[] BACKUP_PREF_I = {
            "engine_boot_failures"
    };
    private static final String[] BACKUP_PREF_B = {
            "safe_mode_active", "setup_done"
    };

    /** 要导出/还原的用户数据根（相对 payload/dshhome）。 */
    /** 导出备份时被跳过的文件数（断链 / 读不了）——不静默，导出结果里如实报出来。 */
    private int backupSkipped = 0;
    /** 本次导入里属于附件的条目数（旧备份没有这一块 → 导入后提示去跑一次「会话修复」）。 */
    private int backupImportedAttachments = 0;
    /** 本次导入里属于「App 私有工作区」（files/ 前缀）的条目数。 */
    private int backupWorkspaceFiles = 0;
    /** v1.17.9 内核树自愈：本次全量同步清掉的孤儿文件数（控制台会如实提示）。 */
    private volatile int lastHealOrphans = 0;

    // ⚠ v1.19.4：必须含 "attachments" —— 会话历史里存的是附件**引用**（sha256 指向
    //   <dshhome>/attachments/v1/objects/…），实体不跟着走的话，「导出 → 清数据 → 导入」
    //   之后每一条带图的会话都会变成「附件实体不存在」、每次请求都失败
    //   （正是「会话修复」要治的那种病；备份功能不该批量制造它）。
    private static final String[] BACKUP_ROOTS = {
            "sessions", "storages", "attachments", ".credentials.yaml", "settings.yaml",
            "settings.yaml.imported", "cordis.patch.yml", ".anonymous-user-id"
    };

    private void conBackupExport() {
        conToast("正在打包全部数据…");
        new Thread(new Runnable() { @Override public void run() {
            File out = null; String err = null; int files = 0;
            try {
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                File dir = new File(Environment.getExternalStorageDirectory(), "Download");
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, "DSH备份-" + ts + ".zip");
                files = writeBackupZip(out);
            } catch (Throwable t) {
                Log.e(TAG, "backup export", t);
                err = t.getMessage();
                if (out != null) { try { out.delete(); } catch (Throwable ignored) {} }   // 别留半个 zip
                out = null;
            }
            final File f = out; final String e = err; final int n = files; final int skipped = backupSkipped;
            ui.post(new Runnable() { @Override public void run() {
                if (f == null) { conToast("导出失败：" + e); return; }
                conToast("已导出 " + n + " 个文件" + (skipped > 0 ? "（跳过 " + skipped + " 个读不了的）" : "")
                        + "：" + f.getName());
                conShareExportedFile(f, "application/zip", "导出全部数据");
            }});
        }}, "backup-export").start();
    }

    /** 把用户数据 + 偏好写成一个 zip。返回写入的文件数。 */
    private int writeBackupZip(File out) throws Exception {
        File home = new File(payloadDir(), "dshhome");
        byte[] buf = new byte[64 * 1024];
        int n = 0;
        backupSkipped = 0;
        java.util.zip.ZipOutputStream zos =
                new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(out)));
        try {
            // manifest：只写能帮人判断“这是什么包”的最小事实
            String manifest = "{\n"
                    + "  \"kind\": \"dsh-android-backup\",\n"
                    + "  \"format\": 1,\n"
                    + "  \"app\": \"" + jesc(conVersionLabel()) + "\",\n"
                    + "  \"package\": \"" + jesc(getPackageName()) + "\",\n"
                    + "  \"saved_at\": \"" + jesc(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())) + "\"\n"
                    + "}\n";
            zipAddBytes(zos, "manifest.json", manifest.getBytes("UTF-8"));
            // 偏好：用简单的 TSV（类型 + 值），避免再引一套 JSON 解析
            zipAddBytes(zos, "prefs.txt", backupPrefsText().getBytes("UTF-8"));
            // 用户数据
            for (String rel : BACKUP_ROOTS) {
                File src = new File(home, rel);
                if (!src.exists()) continue;
                n += zipAdd(zos, src, "dshhome/" + rel, buf);
            }
            // profile 用户层（可能被插件改坏，必须一起带走）
            File pdir = profileDir();
            for (String name : PROFILE_USER_LAYER) {
                File src = new File(pdir, name);
                if (!src.exists()) continue;
                n += zipAdd(zos, src, "dshhome/profiles/web/" + name, buf);
            }
            // v1.19.4：App 私有目录里的「工作区」—— **AI 的默认工作目录就是 <filesDir>**
            // （真实会话里记录的 cwd 是 /data/user/0/<pkg>/files），它在 payload 之外，
            // 原来的白名单一个字都没覆盖到 → 「导出 → 清数据 / 换机 → 导入」会把 AI 写在那里的东西全丢掉。
            // 排除 payload（内核树 + dshhome，dshhome 已单独备份）与 tools（随包脚本，可重建）。
            n += zipAddFilesExcept(zos, getFilesDir(), "files", buf);
        } finally { try { zos.close(); } catch (Throwable ignored) {} }
        return n;
    }

    private String backupPrefsText() {
        StringBuilder sb = new StringBuilder("# dsh-android-backup prefs v1\n");
        SharedPreferences p = prefs();
        for (String k : BACKUP_PREF_S) { String v = p.getString(k, null); if (v != null) sb.append("S\t").append(k).append('\t').append(v).append('\n'); }
        for (String k : BACKUP_PREF_I) { sb.append("I\t").append(k).append('\t').append(p.getInt(k, 0)).append('\n'); }
        for (String k : BACKUP_PREF_B) { sb.append("B\t").append(k).append('\t').append(p.getBoolean(k, false)).append('\n'); }
        return sb.toString();
    }

    /**
     * 把 <filesDir> 下**除 payload / tools 之外**的内容写进 zip（entry 前缀 files/）。
     * 为什么要单列一个方法：这两个子目录一个是被单独备份的内核+用户数据、一个是随包脚本，都不该重复进包。
     * @returns 写入的文件数。
     */
    private int zipAddFilesExcept(java.util.zip.ZipOutputStream zos, File dir, String prefix, byte[] buf) throws Exception {
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        java.util.Arrays.sort(kids, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        for (File k : kids) {
            String name = k.getName();
            if ("payload".equals(name) || "tools".equals(name)) continue;
            n += zipAdd(zos, k, prefix + "/" + name, buf);
        }
        return n;
    }

    private void zipAddBytes(java.util.zip.ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /** 递归把一个文件/目录写进 zip（跳过符号链接：内核会自己重建 profile 的链接）。返回文件数。 */
    private int zipAdd(java.util.zip.ZipOutputStream zos, File f, String entryName, byte[] buf) throws Exception {
        if (isSymlink(f)) return 0;
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entryName + "/"));
            zos.closeEntry();
            int n = 0;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) n += zipAdd(zos, k, entryName + "/" + k.getName(), buf);
            return n;
        }
        // ⚠ 先打开文件，成功了再写 zip 条目 —— 打不开（断链 / 刚被删）就跳过这一个文件。
        //   真机踩过：profiles/web/node_modules/.bin/dsh-memento-mcp 是断掉的软链，
        //   旧写法先 putNextEntry 再 new FileInputStream → 异常冒到顶层，整个「导出全部数据」失败。
        java.io.FileInputStream in;
        try {
            in = new java.io.FileInputStream(f);
        } catch (Throwable t) {
            backupSkipped++;
            Log.w(TAG, "backup skip " + entryName + ": " + t.getMessage());
            return 0;
        }
        zos.putNextEntry(new ZipEntry(entryName));
        try { int r; while ((r = in.read(buf)) > 0) zos.write(buf, 0, r); }
        finally { in.close(); }
        zos.closeEntry();
        return 1;
    }

    /** 符号链接判定：canonical 路径与「父目录 + 文件名」不一致即为链接。 */
    private boolean isSymlink(File f) {
        try {
            File p = f.getParentFile();
            if (p == null) return false;
            return !f.getCanonicalPath().equals(new File(p.getCanonicalPath(), f.getName()).getPath());
        } catch (Throwable t) { return false; }
    }

    private void conBackupImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_BACKUP_FILE);
        } catch (Throwable t) { conToast("无法打开文件选择器：" + t.getMessage()); }
    }

    private void conImportFromUri(final Uri uri) {
        conDialog("从备份导入还原",
                "会把备份里的用户数据（会话 / 凭证 / 设置 / 工作区）写回本机，然后重启引擎。\n\n"
                        + "当前同名数据会被覆盖（备份文件本身不动）。\n\n开始导入吗？",
                "开始导入",
                new Runnable() { @Override public void run() { conImportNow(uri); } },
                "取消");
    }

    private void conImportNow(final Uri uri) {
        conToast("正在导入…");
        new Thread(new Runnable() { @Override public void run() {
            final String err; int files = 0;
            try {
                killEngineNow();
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline && portListening(enginePort)) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                files = readBackupZip(uri);
                err = null;
            } catch (Throwable t) {
                Log.e(TAG, "backup import", t);
                ui.post(new Runnable() { @Override public void run() { conToast("导入失败：" + t.getMessage()); } });
                return;
            }
            final int n = files;
            final int attN = backupImportedAttachments;
            final int wsN = backupWorkspaceFiles;
            ui.post(new Runnable() { @Override public void run() {
                String extra = attN > 0
                        ? "（含 " + attN + " 个附件实体"
                        : "（这份备份里没有附件实体 —— 若会话里的图片打不开，去「会话修复」跑一次扫描，"
                          + "它只会把坏掉的那几条消息降级成文字";
                extra += (wsN > 0 ? "；含 " + wsN + " 个工作区文件" : "；没有工作区文件") + "）";
                conToast("已导入 " + n + " 个文件" + extra + "，正在重启引擎…");
                refreshConsole();
                engineStoppedByUser = false; engineStartAborted = false;
                conEngineClick();
            }});
        }}, "backup-import").start();
    }

    /** 读 zip 并还原。返回还原的文件数。 */
    private int readBackupZip(Uri uri) throws Exception {
        File base = payloadDir();
        byte[] buf = new byte[64 * 1024];
        int n = 0;
        boolean sawManifest = false;
        backupImportedAttachments = 0;
        backupWorkspaceFiles = 0;
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("无法读取所选文件");
        ZipInputStream zis = new ZipInputStream(raw);
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name.endsWith("/")) { zis.closeEntry(); continue; }
                if ("manifest.json".equals(name)) { sawManifest = true; zis.closeEntry(); continue; }
                if ("prefs.txt".equals(name)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    int r; while ((r = zis.read(buf)) > 0) bos.write(buf, 0, r);
                    applyBackupPrefs(new String(bos.toByteArray(), "UTF-8"));
                    zis.closeEntry();
                    continue;
                }
                if (name.startsWith("dshhome/attachments/")) backupImportedAttachments++;
                if (name.startsWith("files/")) {
                    // App 私有目录里的工作区（AI 的默认工作目录）：写回 <filesDir>/<相对路径>
                    File out = new File(getFilesDir(), name.substring("files/".length()));
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
                    FileOutputStream fos = new FileOutputStream(out);
                    try { int r; while ((r = zis.read(buf)) > 0) fos.write(buf, 0, r); }
                    finally { fos.close(); }
                    backupWorkspaceFiles++;
                    n++;
                    zis.closeEntry();
                    continue;
                }
                if (name.startsWith("dshhome/")) {
                    File out = new File(base, name);
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
                    FileOutputStream fos = new FileOutputStream(out);
                    try { int r; while ((r = zis.read(buf)) > 0) fos.write(buf, 0, r); }
                    finally { fos.close(); }
                    n++;
                }
                zis.closeEntry();
            }
        } finally { try { zis.close(); } catch (Throwable ignored) {} }
        if (!sawManifest) throw new IOException("这不像本应用的备份包（缺少 manifest.json）");
        // 用户层已还原 → 不再处于安全模式
        setSafeModeFlag(false);
        return n;
    }

    private void applyBackupPrefs(String text) {
        SharedPreferences.Editor ed = prefs().edit();
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length != 3) continue;
            String type = parts[0], key = parts[1], value = parts[2];
            try {
                if ("S".equals(type)) ed.putString(key, value);
                else if ("I".equals(type)) ed.putInt(key, Integer.parseInt(value.trim()));
                else if ("B".equals(type)) ed.putBoolean(key, Boolean.parseBoolean(value.trim()));
            } catch (Throwable ignored) {}
        }
        ed.apply();
    }

    /** 递归拷贝（rename 失败时的退路）。 */
    private void backupCopyRec(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("mkdir failed: " + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) backupCopyRec(k, new File(dst, k.getName()));
            return;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed: " + parent);
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try { byte[] b = new byte[64 * 1024]; int r; while ((r = in.read(b)) > 0) out.write(b, 0, r); }
            finally { out.close(); }
        } finally { in.close(); }
    }

    /** 递归删除。 */
    private void backupDeleteRec(File f) {
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) backupDeleteRec(k);
            }
            f.delete();
        } catch (Throwable ignored) {}
    }

    /** 从 assets/payload.zip 把出厂的 profile 文件写回（安全模式的关键一步）。 */
    private int restoreShippedProfileFiles() throws IOException {
        File dir = profileDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 " + dir);
        final String prefix = "dshhome/profiles/web/";
        byte[] buf = new byte[64 * 1024];
        int n = 0;
        ZipInputStream zis = new ZipInputStream(getAssets().open("payload.zip"));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                boolean want = false;
                for (String s : PROFILE_SHIPPED) {
                    if (name.equals(prefix + s)) { want = true; break; }
                }
                if (!want) { zis.closeEntry(); continue; }
                File out = new File(dir, name.substring(prefix.length()));
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    int r;
                    while ((r = zis.read(buf)) > 0) fos.write(buf, 0, r);
                } finally { fos.close(); }
                zis.closeEntry();
                n++;
            }
        } finally { zis.close(); }
        return n;
    }

    @Override
    public void onBackPressed() {
        // 自绘弹窗优先吃掉返回键（等同“取消”），避免返回键穿透到下层
        if (dialogOverlay != null) { closeDialogOverlay(); return; }
        // v1.12：控制台内的返回先回控制台首页，再退出
        if (consoleVisible) {
            if (consolePage != 0) { consolePage = 0; renderConsole(); return; }
            confirmExit();
            return;
        }
        // 有历史先回退（可关掉侧边栏/返回上一页）；没有历史则询问是否退出
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        confirmExit();
    }
}
