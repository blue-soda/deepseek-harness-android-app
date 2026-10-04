# 本机（Windows）构建说明

`android-app/build.sh` 设计成在 **Android 设备上或 Git Bash 里**运行，它需要一份
**devhome**（`runtime/` + `dshroot/` + `.dsh/` + 工具链）。仓库**不含** devhome，
所以直接跑会停在第 7 行：

```
build.sh: line 7: /data/data/com.coomi.android/files/home/build/env.sh: No such file or directory
```

本文记录两条路径：**桩构建**（验证工具链，立刻能出 APK）与**真实构建**（还缺什么）。

---

## 一、本机已验证可用（2026-10-04 实测）

| 组件 | 位置 | 状态 |
|---|---|---|
| JDK | `C:\Programs\jdk-21` | ✅ `javac` 在 PATH |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | ✅ build-tools **35.0.0** / 36.0.0，platforms **android-36** / 37.0 |
| `aapt` / `d8.bat` / `zipalign` / `apksigner.bat` | build-tools 35.0.0 | ✅ |
| Git Bash | `C:\Program Files\Git\bin\bash.exe` | ✅ 含 `cygpath`、GNU `stat` |
| Shizuku AAR | `android-app/libs/*.aar` | ✅ 已在仓库内 |

> 注意：`C:\WINDOWS\system32\bash.exe` 是 **WSL**，`build.sh` 会走错分支。
> 必须用 **Git Bash**。

---

## 二、桩构建（会产出一个不可用的 APK）

桩 devhome 只有目录骨架和占位文件，**payload 是假的**，因此 APK 装上去界面能起、
引擎起不来。它的价值是：**证明工具链与 7 步打包链没问题**，并把剩余工作
收敛成"产出真实 payload"这一件事。

```bash
# 1) 生成桩 devhome
bash tools/make-devhome-stub.sh build/devhome-stub

# 2) 准备 android.jar 与签名密钥（都不入仓库）
export ANDROID_JAR="$LOCALAPPDATA/Android/Sdk/platforms/android-36/android.jar"
keytool -genkeypair -v -keystore android-app/release.jks -alias dsh \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android -dname "CN=DSH Debug, O=Local, C=CN"

# 3) 构建
cd android-app
export DSH_DEV_HOME="$(cd ../build/devhome-stub && pwd)"
export JAVA_BIN="$JAVA_HOME/bin"
export KEYSTORE_PASS=android
export KEYSTORE_ALIAS=dsh
bash build.sh
```

**实测结果**：`android-app/DeepSeekHarness.apk`，**261.5 KB**，21 个条目，已签名。

```
BUILD OK -> .../android-app/DeepSeekHarness.apk
package: name='com.deepseek.harness' versionCode='52' versionName='1.17.3'
sdkVersion:'24'  targetSdkVersion:'28'
javac 完成，class 数：280      classes.dex 含 MainActivity，412184 bytes
vscreen_shizuku.jar: 14716 bytes
```

构建过程会打印四条预期内的警告：

```
!! 未找到 pnpm/   （插件管理「添加插件」将不可用）
!! 未找到 git/    （「添加插件」的 GitHub 地址输入与 AI 的 git 将不可用）
!! 未找到 python/ （AI 的 python / python3 将不可用）
!! 未找到 npm/    （AI 的 npm / npx 将不可用）
```

APK 内的完整条目（21 个）：`AndroidManifest.xml`、`classes.dex`、
`assets/{payload.zip, mobile.css, mobile.js, rish_shizuku.dex, vscreen_shizuku.jar}`、
`assets/console-theme/*`（4 个）、`res/*`。

---

## 三、真实构建还缺什么

| 缺什么 | 从哪来 | 备注 |
|---|---|---|
| `runtime/` | Termux aarch64/arm64 deb 解包出 `bin/node` + `lib/*.so` | 真机是 **arm64**；x86_64 模拟器用 `ndk_translation` |
| `dshroot/` | npm 解析出 **hoisted** 闭包后组装（`nodeLinker: hoisted`） | 组装后须**剥离全部原生模块**（`find dshroot -name '*.node' -o -name '*.so'` 必须为 0） |
| `.dsh/` | 跑一次 `dsh` 生成，或手工准备 | `build.sh` 从 `$H/.dsh/` 取，**不读仓库的 `config/`** |
| `pnpm/` `git/` `python/` `npm/` | 各自从 Termux deb 解包（可选，缺了只是对应能力不可用） | 见 `build.sh` 第 142–316 行的注释 |
| `rish/rish_shizuku.dex` | Shizuku 的 rish dex | 仓库的 `android-app-fix/` 或上游 Release |
| `deploy-patches-*.sh` | **上游未提交，需自己写** | 把 `dsh-patches/overlay-020/` 的 13 个文件落到 `dshroot` 上 |

**补齐顺序建议**：`runtime` → `dshroot`（含补丁重放）→ `.dsh` → 可选的 pnpm/git/python/npm → 真实构建。

---

## 四、不要提交的东西

`.gitignore` 已覆盖：`*.apk`、`android-app/release.jks`、`android-app/sdk/android.jar`、
`android-app/{staging,out,assets}/`、`payload.zip`、`.credentials.yaml`。
`build/devhome-stub/` 也在忽略范围内时无需额外处理；若不在，请自行加入 `.gitignore`。
