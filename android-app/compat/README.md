# 兼容版（compat）构建差异说明

兼容版（包名 `com.deepseek.harness.compat`，端口 **3084**）**不是**简单改个包名就能得到的 ——
从 **v1.17.3** 起它**内嵌 GeckoView**（不再依赖系统 WebView），因此有 **4 个文件**与上一级目录（正式版 / Lite 共用的源码）不同。

> **怎么用**：把 `../`（即 `android-app/`）当基线 → 用本目录的文件替换同名文件 → 按下面第二节准备额外依赖 → 构建。

---

## 一、与正式版不同的文件

| 本目录文件 | 替换掉 | 为什么不同 |
|---|---|---|
| `MainActivity.java` | `android-app/src/com/deepseek/harness/MainActivity.java` | ① 包名 `com.deepseek.harness.compat`；② 文件末尾多一个内层类 **`GvWebView`**（`GeckoView` + `GeckoSession`，实现 `loadUrl/canGoBack/goBack/onResume/onPause/destroy/setBackgroundColor`，使上层 60+ 处调用**一行不用改**）；③ `refreshPageBackground()` **退化为空实现**（该版 GeckoView 没有 JS 求值接口）；④ `checkWebViewCompat()` 早退（自带引擎，不需要再判断系统 WebView） |
| `build.sh` | `android-app/build.sh` | 多 7 处 GeckoView 步骤：暂存 `geckoview/` 资源 → `aapt --extra-packages`（⚠ **aapt1 只认最后一个 extra-package，必须每个包单独跑一次 aapt 再合并 `R.java`**）→ javac classpath 加 `out/gv-cls` → **javac 后硬闸门**（断言 `androidx/core/R$id.class`、`androidx/annotation/R.class`、`androidx/lifecycle/R.class`、`org/mozilla/geckoview/R$drawable.class` 四个类存在，缺一即 `exit 1`）→ `d8 --min-api 26` → 打包后 `aapt add lib/arm64-v8a/*.so` → 用合并后的 manifest 打包 |
| `AndroidManifest.xml` | `android-app/AndroidManifest.xml` | `minSdkVersion 24 → **26**`（GeckoView 要求）；其余字段与正式版一致。GeckoView 需要的 89 个 `<service>` / 2 个 `<provider>` / `zygotePreloadName` 由构建期脚本 `merge-geckoview-manifest.mjs` 合并进去，**源 manifest 始终保持干净**（这也是上面那份 `build.sh` 要多一步的原因） |
| `res/drawable/ic_generic_file.xml` | `android-app/res/drawable/` | GeckoView 的 `GeckoAppShell` 引用 `R.drawable.ic_generic_file` |

**其余文件与正式版只差包名一行**：`ConsoleTheme.java`、`AccessibilityService.java`、`OverlayService.java`、`VsreenBridgeService.java`、`vscreen/{Main,FakeContext,Workarounds}.java`、
`EngineService.java`、`UsageStatsHelper.java`、`LogShareProvider.java`、`res/` 其余资源、`../mobile-patch/`。
批量改名基线：`sed -i '1s|.*|package com.deepseek.harness.compat;|'`（外加把源码放到 `src/com/deepseek/harness/compat/` 下）。

---

## 二、额外依赖（**不在本仓库**，体积太大）

放到 `devhome/geckoview/`（或构建脚本里 `DSH_GECKOVIEW` 指向的目录）：

- **GeckoView AAR**：`geckoview-omni-157.0.20260924084938.aar`（arm64，**90,437,682 B**）
  解包后需要：`classes.jar`、`assets/omni.ja`、`jni/arm64-v8a/*.so`（13 个，含 `libxul.so`）、该 AAR 自带的 `AndroidManifest.xml`、`res/drawable/ic_generic_file.xml`
- **androidx 20 件套**（按 GeckoView 的 POM 取：`androidx.core` / `annotation` / `lifecycle` / `collection` …）
  ＋ `kotlin-stdlib` / `kotlinx-coroutines` / `guava` / `snakeyaml`
  > ⚠ **反直觉但很关键**：GeckoView 自己的类**不引用 Kotlin**，但补进来的 `androidx.collection` 是 **Kotlin 写的**
  > （`ArrayMap` 里调用 `Intrinsics.checkNotNullParameter`）→ 少 `kotlin-stdlib` 会在 `GeckoSessionSettings.<init>` 直接 `NoClassDefFoundError`。

---

## 三、构建

```sh
export DSH_DEV_HOME=<devhome 路径>
export KEYSTORE_PASS=<签名口令>          # release.jks 的口令，缺失会报错中止
cd rebuild-v17/build-compat/android-app && sh build.sh
```

- 产物约 **250 MB**（比正式版 +86MB）；`aapt dump badging` 应显示 `versionName=…-compat`、`sdkVersion:'26'`。
- 构建源**不要放在中文路径**下；`javac` 必须 API 33、`d8` 用 build-tools **35.0.0**。

---

## 四、已知限制（如实记录，别当 bug）

- 该版 GeckoView **没有 JS 求值接口** → 「用页面实测底色回填状态栏」改为**跟随 App 主题**；`addJavascriptInterface("dshshell")` 整段移除（本项目从未使用该桥）。
- 附件选择走 `PromptDelegate.onFilePrompt` → `handleFilePrompt()`：**已实现但尚未在真机实测**。
- 未打包 `androidx.media3` / `play-services-fido`（按需 +4~5MB）；`android.credentials.*` 是 **Android 14+ 平台 API**，打不进包 → 老设备走 WebAuthn 会 `NoClassDefFoundError`（目前无解）。
- 与正式版 / Lite 一样：**payload 不带原生模块**（例外仅 `runtime/lib/*.so` 与 `python/lib/python3.14/lib-dynload/*.so`，且仅 arm64）。
