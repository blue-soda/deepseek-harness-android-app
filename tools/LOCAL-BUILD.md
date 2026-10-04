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

## 三、真实构建：从官方 APK 提取 payload（推荐，已实测）

**不要自己重建 runtime 与 dshroot。** 官方 APK 里的 `assets/payload.zip` 就是一份
**已打完 13 个补丁、开箱可用**的 devhome，而且它与出货逐字节一致。

```bash
# 1) 下载官方 APK（发布页有 md5，可自行核对）
#    https://github.com/woaiys3/deepseek-harness-android-app/releases/download/v1.17.3/DeepSeekHarness-official-v1.17.3.apk
#    实测 md5 = 1341959ce4e347f8fc5e761966bbf3bd（与发布页一致）

# 2) 从 APK 里取出 payload.zip
unzip -o DeepSeekHarness-official-v1.17.3.apk assets/payload.zip -d x/

# 3) 解压成 devhome（32,539 条目 / 438.9 MB）
mkdir -p devhome && cd devhome
unzip -q ../x/assets/payload.zip
mv dshhome .dsh                       # ← payload 叫 dshhome，build.sh 要的是 .dsh

# 4) ⚠️ 唯一的结构差异：payload 把 git 主程序放在 bin/git，devhome 要 git/bin/git
mkdir -p git/bin && mv bin/git git/bin/git

# 5) 补一个工具链 env.sh（payload 里没有）
mkdir -p build && cat > build/env.sh <<'EOF'
export JAVA_HOME="${JAVA_HOME:-/c/Programs/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
export APK_TOOLS="${APK_TOOLS:-$LOCALAPPDATA/Android/Sdk/build-tools/35.0.0}"
export PATH="$APK_TOOLS:$PATH"
EOF
```

然后照第二节的命令构建，把 `DSH_DEV_HOME` 指向这个 devhome 即可。

### 实测结果（2026-10-04）

```
BUILD OK -> android-app/DeepSeekHarness.apk
156,857,839 字节 —— 与官方 APK 大小完全相同
21 个条目全部对上（无多、无少）
其中 11 个条目与官方逐字节相同
```

差异全部可解释：

| 条目 | 差异 | 原因 |
|---|---|---|
| `META-INF/*` | 内容不同 | **签名密钥不同**（本机构建用了自签调试密钥） |
| `AndroidManifest.xml` | 内容不同 | aapt/build-tools 版本差异 |
| `assets/vscreen_shizuku.jar` | 14716 vs 14714 | 本机 JDK 21 现场编译 vs 维护者环境 |
| `assets/mobile.css` / `mobile.js` / `console-theme/console.example.json` | 略大 | **仓库当前版本与出货版有细微出入** |
| `assets/dshroot_revision.txt` | 内容不同 | 构建时间戳 |
| `assets/payload.zip` | 156,576,211 vs 156,576,168（+43 字节） | 上述差异的累积 |

payload 内的 14 个补丁特征串**全部命中**（`isHardlinkUnsupported`、`noopBinding`、
`js-fallback`、`DSH_ANDROID_PLUGIN_LOG`、`dsh-mobile-menu-btn`、`sandboxMode` …），
说明提取到的是一棵**已经打好补丁的内核树**。

### 这个 APK 能装吗

能。它是**真实可用的 arm64 APK**（只差用你自己的密钥重签）。
限制是 **arm64 only** —— x86_64 模拟器需要 `ndk_translation`，
且按 `docs/开发指南.md` 第六节还要额外三步（`-writable-system` 启动、
把 x86_64 的 `libz.so`/`libssl.so`/`libcrypto.so` 裸名库 push 进 `runtime/lib`、重拉 Shizuku）。

> **想要 x86_64 原生运行时**（避开转译的这三个坑），官方不出这种制品 ——
> 这时才需要走下面的"自己重建"路线。

---

## 四、模拟器实测记录（x86_64 + ndk_translation，2026-10-04）

本机构建出的 APK 装进 `Pixel_10_Pro`（`android-36.1 google_apis_playstore x86_64`）后，
逐步推进到"引擎启动"，记录如下。

### 走到哪一步了

| 环节 | 结果 |
|---|---|
| 安装 / 启动 / 10 步权限向导 | ✅ 全部通过（向导用 `uiautomator` + `input tap` 自动跳过） |
| 解压 payload | ✅ `extracted 2898 entries (mode=internal)` + `extracted 25787 entries (mode=dshroot)` |
| 触发引擎 | ✅ 进入 `engine-boot`，SELinux `avc: granted { execute } for name="node"` |
| **转译器加载** | ❌→✅ **已修复**（见下） |
| **node 本体加载** | ❌ 仍失败 |

### 已修复的坑：转译器要 x86_64 的裸名库

首个失败：

```
F linker: CANNOT LINK EXECUTABLE "/system/bin/ndk_translation_program_runner_binfmt_misc_arm64":
"/data/user/0/com.deepseek.harness/files/payload/runtime/lib/libz.so"
is for EM_AARCH64 (183) instead of EM_X86_64 (62)
```

这正是 `docs/开发指南.md` 第六节第 2 条描述的现象 —— 转译器自己是 x86_64，它按**裸名**
从 `LD_LIBRARY_PATH` 解析 `libz.so` / `libssl.so` / `libcrypto.so`，而 `build.sh` 第 95–107 行
刚把这三个裸名实体化成了 arm64 副本。

**本仓库已把该手工步骤做成构建期开关**（见 `android-app/build.sh`）：

```bash
# 从模拟器取 x86_64 的三个裸名库
adb pull /system/lib64/libz.so      x64libs/
adb pull /system/lib64/libssl.so    x64libs/
adb pull /system/lib64/libcrypto.so x64libs/

export DSH_X64_BARE_LIBS=$PWD/x64libs
export DSH_X64_NO_LINKS=1     # 可选：清空 LINKS.txt，全部保留为实体文件
bash android-app/build.sh
```

设置后 `build.sh` 会：把三个 x86_64 库复制进 `staging/runtime/lib/`，并从 `LINKS.txt`
移除对应三条（否则 App 首次启动会按 `LINKS.txt` 把它们重建为指向 arm64 的链接，
把注入的库覆盖掉）。**真机 arm64 不要设置这两个变量。**

修复效果：转译器错误消失，错误前进一步。

### 仍未解决的坑：node 本体找不到 `libz.so.1`

```
F linker: CANNOT LINK EXECUTABLE ".../runtime/bin/node":
library "libz.so.1" not found: needed by main executable
```

`docs/开发指南.md` 第六节第 1 条把这条错误归因于「**ARM 库合并进 /system 的 overlay
存在 AVD 里，默认启动不挂载**」，并给出解法：AVD 必须带 `-writable-system` 启动。
**但仅加 `-writable-system` 不够** —— 实测还要 `adb remount` 把 overlay 挂上，而本机 AVD
用的是 **Play Store 生产镜像**，两条路都被堵死：

| 检查 | 结果 |
|---|---|
| `ro.build.type` | **`user`**（生产构建） |
| `ro.debuggable` | **`0`** |
| `adb root` | `adbd cannot run as root in production builds` |
| `adb remount` | `remount: inaccessible or not found` |
| `/system/lib64/arm64/` | 存在，**56 个** ARM64 系统库，**不含 `libz.so.1`** |
| `/system/lib64/libz.so` | 只有裸名，**没有 `libz.so.1`** |

也就是说：guest（arm64）加载器所在的 `/system/lib64/arm64` 是本镜像自带的精简集，
没有 `libz.so.1`；而它显然没有从 `LD_LIBRARY_PATH`（payload 的 `runtime/lib`，里面
**确实有** arm64 的 `libz.so.1`）解析出来。

**结论：要用模拟器验证，需要换一个非 Play Store 的镜像**（`google_apis` 而非
`google_apis_playstore`），使 `adb root` / `adb remount` 可用：

```bash
sdkmanager "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n dsh_test -k "system-images;android-36;google_apis;x86_64"
emulator -avd dsh_test -writable-system
adb root && adb remount        # ← Play Store 镜像做不到这两步
```

**或者直接用 arm64 真机**（那才是官方支持的目标环境，无需任何转译）。

---

## 五、自己重建（只在需要非 arm64 或自定义 node 时）

官方只出 arm64，所以下面的场景才需要自己重建：

- 要在 **x86_64 模拟器**上原生跑（不想用 `ndk_translation`）
- 要换 node 版本
- 不想再分发维护者的制品

`deepseek-harness-banyan-mvp/runtime/node/build-android-node-runtime.py` 是现成的工具：
从 Termux apt 抓包 → 解析 `Depends` 闭包 → 校验 SHA256 → 抽白名单文件 →
`readelf` 校验 ELF machine 与 `NEEDED` → 出 tar + manifest。它支持
`--target-platform android-x64` 与 `android-arm64`。

两点要注意：① 它把内容相同的 `.so` 去重成**符号链接**，而 Android 上链接不可靠，
最后仍需按 `LINKS.txt` 复制成实体（官方直接在构建期 `cp -L`，更省事）；
② Termux 构建把 `OPENSSLDIR` 写死指向 Termux 路径，必须补官方那三个证书/配置补丁
（`OPENSSL_CONF`、`GIT_EXEC_PATH`/`GIT_TEMPLATE_DIR`、`SSL_CERT_FILE`+`CURL_CA_BUNDLE`+`GIT_SSL_CAINFO`）。

---

## 六、还缺什么（自建 dshroot 时）

| 缺什么 | 从哪来 | 备注 |
|---|---|---|
| `dshroot/` | npm 解析出 **hoisted** 闭包后组装 + 重放 `dsh-patches/overlay-020/` 的 13 个文件 | 组装后须剥离**全部**原生模块 |
| `deploy-patches-*.sh` | **上游未提交，需自己写** | 见第 14 章的证据索引 |

---

## 七、不要提交的东西

`.gitignore` 已覆盖：`*.apk`、`android-app/release.jks`、`android-app/sdk/android.jar`、
`android-app/{staging,out,assets}/`、`payload.zip`、`.credentials.yaml`。
`build/devhome-stub/` 也在忽略范围内时无需额外处理；若不在，请自行加入 `.gitignore`。
