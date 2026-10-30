> **历史归档（可能过时）**：排障与构建请以 `docs/BUILD.md`、`docs/TROUBLESHOOTING.md` 为准。

# 构建 APK

一条命令，从零构建出可安装的 arm64 APK。

```bash
bash tools/build-apk.sh
```

产物：`android-app/DeepSeekHarness-community.apk`（约 105 MB；**默认变体 = 社区版**，
official 变体仍是历史名 `DeepSeekHarness.apk`）
同时生成与产物同名的 `.build-info.txt` 与 `.sha256`，记录这次构建的全部来源信息。

首次运行会做两件事，之后全部走缓存、可离线重复构建：

1. **装内核树**：从官方 npm registry 按仓库内冻结 lock 安装 `@deepseek-ai/dsh@0.2.0-rc.2`
   （`tools/fetch-kernel.sh`，约 10 秒；装好的树会打成一枚 253 MB 的 tar 缓存在 `.cache/kernel/`）；
2. **取 arm64 运行时**：下载我们自己的 sha256 pin 资产（`tools/fetch-runtime.sh`，91 MB）
   并解到 `.cache/devhome`（252 MB）。

**实测：缓存就绪后一条模拟器包 1 分 34 秒**（首次含下载/安装约 3~4 分钟）。**不需要上游 APK。**

---

## 1. 前置条件

| 需要 | 版本 | 说明 |
|---|---|---|
| **JDK** | 17 及以上（**21 已验证**） | 提供 `javac` / `keytool` / `jar` |
| **Android SDK** | 含 `build-tools` 与任一 `platforms/android-*` | 提供 `aapt` / `d8` / `zipalign` / `apksigner` / `android.jar` |
| **Node.js + npm** | 18+（**24 已验证**） | 装内核树用（`npm ci`）；也用于生成内核清单 |
| 常用工具 | `curl` `tar` `git` | 一般发行版自带 |
| 磁盘 | 约 3 GB 空闲 | 缓存 ~1.2 GB + 中间产物 ~1 GB + 产物 150 MB |

安装 SDK 组件（任意平台，需先有 `sdkmanager`）：

```bash
sdkmanager "build-tools;35.0.0" "platforms;android-36"
export ANDROID_HOME=/path/to/Android/Sdk     # 或 ANDROID_SDK_ROOT
```

`ANDROID_HOME` 没设时脚本会自己探测常见位置（Windows 的 `%LOCALAPPDATA%\Android\Sdk`、
Linux/macOS 的 `~/Android/Sdk`、`~/Library/Android/sdk`）。

### 最小集与"不需要什么"

- **最小组件集** = JDK 17+ + `build-tools`（aapt/d8/zipalign/apksigner）+ **任意一个** `platforms/android-*/android.jar`。
  本仓库**不用 Gradle、不用 AGP、不碰 Maven 仓库** —— 打包是 `aapt2 + javac + d8 + zipalign + apksigner`
  的直接调用（见 `android-app/build.sh`）。所以"maven.google.com 超时 / AGP 不认 SDK 布局"这类问题
  **在本仓库不存在**；`sdkmanager` 只在下载上面那几个组件时需要联网。
- **不需要 clone 上游仓库、也不需要上游 APK**：内核来自官方 npm registry（版本由
  `android-app/kernel/package-lock.json` 冻结，来历见 `android-app/kernel/README.md`），
  arm64 运行时来自我们自己的 sha256 pin 资产（`tools/fetch-runtime.sh`）。
- **不要在手机/平板 App 内构建 APK**：设备上没有、也不应该塞进构建工具链（最小集 raw 就 ~525MB，
  加 Gradle/AGP/SDK 要 +1~2GB，加 NDK 更不可行，与 APK 体积模型冲突）。手机上能做的是**运行期定制**
  （装插件 / 改 profile 补丁，见 `docs/开发指南.md` 的「定制面」一节）；要重建 APK 就在 PC 上做，
  社区版就是本仓库唯一构建的变体（公开社区密钥签名，可与官方包共存）。

**平台支持**：Windows 用 **Git Bash**（`C:\WINDOWS\system32\bash.exe` 是 WSL，不行）；
Linux、macOS 理论上同样可用，但**仅在 Windows + Git Bash 上实测过**。

---

## 2. 它做了什么（本版 起只有一条路）

| 步 | 动作 |
|---|---|
| 0 | 预检：JDK / SDK / build-tools / android.jar / 磁盘空间，缺什么直接说清楚 |
| 1 | 组装 `devhome`：取 arm64 运行时资产（sha256 pin）解到 `.cache/devhome`，并生成工具链 env |
| 2 | 准备签名密钥（没有就生成一个本地的） |
| 3 | 调用 `android-app/build.sh` 打包。**内核树在这一步装**：`tools/fetch-kernel.sh` 从官方 npm |
| 4 | **校验**：payload 结构、15 个补丁特征串、payload 清单 entries/bytes、APK 条目与签名 |
| 5 | 写出构建信息文件（含内核来源与运行时 pin） |

### 内核树与运行时分别从哪来

本仓库**不含** node 运行时，也**不含**打好补丁的 DSH 内核树 —— 这两样体积太大（约 460 MB）。

- **内核树**：官方 npm registry 的 `@deepseek-ai/dsh@0.2.0-rc.2` + 仓库内冻结 lock
  （610 条、全部带 integrity）。安装口径与上游 payload 当年完全一致：
  `npm ci --os=android --cpu=arm64 --omit=optional --omit=dev --ignore-scripts`。
  实测与上游 payload 的内核子树**逐包一致（535 包路径+版本相同、0 处版本不同）**。
- **arm64 运行时**：`runtime/ python/ npm/ pnpm/ git/ bash/ bin/ rish/ .dsh/` 打成一枚
  sha256 pin 的资产（node 26.4.0，Termux 构建）。官方 primary-runtime 不含 android target，
  所以这部分只能我们自己维护。
- **补丁与插件**：`dsh-patches/overlay`（45 个文件）+ `plugins/dsh-tool-*`（10 处同步）
  + `mobile-patch` 前端补丁，全部在构建期合成到 **staging**，并做 stale / 签名 / 关键字自检。

脚本会在第 4 步核对 15 个补丁特征串，**确认拿到的是已打补丁的树**，而不是空壳。

### 缓存与离线

| 缓存 | 位置 | 失效条件 |
|---|---|---|
| 内核树 tar（253 MB） | `.cache/kernel/dsh-r1-<lock sha16>.tar` | lock 变更、安装口径版本号 `RECIPE` 变更、`DSH_KERNEL_REFRESH=1`、`--clean` |
| 运行时资产（91 MB） | `.cache/dsh-android-runtime-arm64.tar.gz` | sha256 与 pin 不符（会自动重下；也可用 `DSH_RUNTIME_ARCHIVE=<文件>` 指定本地归档） |
| 解出的 devhome（252 MB） | `.cache/devhome` | 资产 pin 变更、`--clean` |

`--clean` 会清掉以上缓存（**保留签名密钥**），下一次构建重新联网安装/下载。

---

## 3. 常用命令

```bash
bash tools/build-apk.sh                      # 全自动（推荐）：唯一变体 = 社区版，公开密钥签名
bash tools/build-apk.sh --clean              # 清缓存重建（内核 tar / 运行时 / devhome 都重建）
bash tools/build-apk.sh --emulator           # 适配 x86_64 模拟器
bash tools/build-apk.sh --original-look      # 上游原版图标 + 原版悬浮窗形象（产物名加 -original）
bash tools/build-apk.sh --keystore my.jks    # 用你自己的密钥（需 export KEYSTORE_PASS）
bash tools/build-apk.sh --out /tmp/a.apk     # 指定产物路径
bash tools/build-apk.sh --no-verify          # 跳过校验（调试用）
bash tools/build-apk.sh --smoke              # 构建后若连着设备，装上去并提示怎么验
bash tools/build-apk.sh --help
```

> **外观（look，v1.24）**：默认构建用我们自己的卡通形象 —— 桌面图标是头像特写（`icon-src/icon-windows.png`
> 生成的自适应图标），悬浮窗是全身立绘（`icon-src/source.png`）。加 `--original-look` 则整套换成上游
> 原版：桌面图标 = `drawable/ic_launcher`（品牌蓝方块 + 白鲸鱼，与上游官方 APK 的 `android:icon` 同源），
> 悬浮窗 = `drawable/ic_whale_black`（上游 OverlayService 用的那只鲸鱼）。两者包名/签名相同，
> **装哪个就是哪套形象**（互相覆盖）；实现是构建期换资源引用，运行期零分支（见 `android-app/build.sh`
> 的「外观」段与 `res/values/look.xml`）。


> **脚本目录**（都在仓库里，路径由脚本自身位置推导，可在任意克隆 / 工作区里跑）：

| 位置 | 用途 |
|---|---|
| `tools/build-apk.sh` | **一键构建**（官方入口，上面这些选项都在这里）|
| `tools/build-apk.sh` | 唯一构建入口（`--emulator` / `--original-look` / `--clean`）|
| `tools/diag/` | 诊断工具（多数是 `#!/system/bin/sh`，**在设备上跑**）：`check-abi.sh` 交付前 ABI 门（真机包 `x86_64` 必须为 0）· `refresh-overlay-lf.sh` 把 overlay 刷成字节级一致 + 纯 LF · `emu-guestlibs.sh` 给 x86_64 模拟器补 arm64 soname 库 · `find-engine.sh` / `find-engine-env.sh` 找引擎进程与关键 env · `plugin-cli.sh` 以 App 身份跑 DSH CLI · `probe-profile.sh` 复刻 loader 的插件解析视角 · `dump-cfg.sh` / `cfg-stats.sh` 看实际生效的 profile 配置 · `check-shell-env.sh` 看终端默认 shell 来源 · `bench-boot.sh` / `bench-phases.sh` 启动耗时基准 |

> ⚠ `.cache/` 只是**缓存目录**（内核 tar、运行时资产、devhome、本地调试密钥），**不放脚本** ——
> 构建与诊断一律用上表里的脚本。

> **变体（v1.18）**：包名 / 端口 / 外部目录 / 显示名 / 默认密钥全部由 `android-app/variants.sh`
> 这一份真源决定，构建期生成 `BuildVariant.java` 并改写 manifest（含 provider authorities），
> 不需要手改源码。四个变体各自一段独立端口与独立 `/sdcard` 目录，**可同时安装同时运行**。
> 详见 [android-app/README.md](android-app/README.md) 的「变体构建」一节。
> `community` 变体使用仓库内**公开**的 `android-app/community.jks`（口令 `dsh-community`）；
> 官方三变体仍用私有 `release.jks`。
>
> **产物哈希与 CI（B6）**：构建结束会写 `DeepSeekHarness*.apk.build-info.txt`（含 git commit、
> 上游 tag/APK md5、JDK/build-tools、签名指纹、**产物 SHA-256**）与 `.apk.sha256` sidecar
> （`sha256sum -c` 可直接核对）。仓库另带 **只支持手动触发**的
> `.github/workflows/build-apk.yml`：选变体/上游 tag 即可产出带哈希的构建物。
> 目标是"输入可核对 + 哈希公示 + 差异可解释"，**不追求逐字节可复现**（aapt/签名含时间戳）。

---

## 4. 签名（务必读）

**默认行为**：脚本在 `.cache/local-debug.jks` 生成一个本地调试密钥，口令固定为
`dshandroid`。这个文件在 `.gitignore` 里，**不会进仓库**。

**为什么这件事重要**：Android 要求**同一包名的升级必须用同一签名密钥**。因此

- 用本地调试密钥构建的版本，**无法覆盖安装官方版**（签名不同，需先卸载）；
- 换一次密钥（比如删了 `.cache/` 又没用 `--clean` 的保留逻辑，或换机器），
  就**无法覆盖安装你自己上一版的构建**；
- `--clean` **不会**删掉这个密钥，正是为了保住升级链。

**正式分发**请用自己的密钥，并妥善备份：

```bash
export KEYSTORE_PASS='你的口令'
bash tools/build-apk.sh --keystore /secure/path/release.jks
```

`DSH_KEYSTORE` 环境变量也可以直接指定密钥路径（会透传给 `android-app/build.sh`）。

---

## 5. 出问题怎么办

| 现象 | 原因 / 解法 |
|---|---|
| `找不到 Android SDK` | 设 `ANDROID_HOME`；或 `--android-jar` 直接给 `android.jar` |
| `SDK 里没有 build-tools` | `sdkmanager "build-tools;35.0.0"` |
| `本机没有已验证的 build-tools 35.0.0，改用 X` | 只是提示。装 35.0.0 可让产物与文档记录一致 |
| `运行时资产 sha256 不符` | 资产被替换或下载损坏。确认无误后 `DSH_RUNTIME_SHA256=<实际值>` 显式接受并更新 pin |
| 下载卡住 / `Failed to connect to github.com` | 用 `DSH_RUNTIME_ARCHIVE=<本地归档>` 走离线；内核侧可用 `NPM_CONFIG_REGISTRY=<镜像>` |
| `keystore password was incorrect` | `android-app/release.jks` 是别人/别的口令留下的。删掉它，或用 `--keystore` 指定自己的 |
| 安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 与已装版本签名不同。先卸载：`adb uninstall com.deepseek.harness.community` |
| 装上了但引擎起不来 | App 内「日志」页看 `dsh-web.log`；构建期可先跑 `tools/diag/` 下的诊断脚本 |
| 模拟器上 node 报 `CANNOT LINK ... EM_AARCH64` 或 `libz.so.1 not found` | 见第 6 节 |
| 构建脚本报 `找不到 javac` | 设 `JAVA_HOME`，或把 `$JAVA_HOME/bin` 放\进 `PATH` |
| **构建很慢（Windows 上 8~10 分钟）** | 见下方说明 —— 是杀软实时扫描，不是脚本问题 |

### 构建很慢？先看分阶段耗时

脚本结尾会打印各阶段耗时。缓存全命中、且已加杀软排除项时正常长这样：

```
各阶段耗时：
   预检                         7 秒
   取运行时资产（缓存命中）   0 秒
   装内核树（tar 缓存命中）   13 秒
   组装 devhome                 0 秒
   签名密钥                   1 秒
   构建 APK                    62 秒     ← 正常情况下全部时间在这里
   校验                         1 秒
   记录构建信息             2 秒
   总计                        74 秒
```

除 `构建 APK` 之外全部命中缓存、几乎不耗时。**若 `构建 APK` 占了绝大部分时间，
先确认是不是杀软**：

`android-app/build.sh` 结尾有一条安全检查，要遍历 `staging/` 全部约 3.2 万个文件
搜索 API Key。这条 `grep` 平时只要 **4 秒**，但在 **Windows Defender 实时保护开启**时，
对**刚写出的** 440 MB 数据实测要 **456 秒**（占整个构建 88%）。

**实测对比（同一台机器、同一份缓存，仅此一项设置不同）：**

| 阶段 | 未加排除项 | 加了排除项 |
|---|---|---|
| 构建 APK | **510 秒** | **62 秒** |
| **总计** | **516 秒** | **74 秒** |

**7 倍加速。** 也就是说：构建本身只要 1 分钟出头，之前的 8.5 分钟几乎全是杀软开销。

验证与设置：

```powershell
# 查看实时保护状态（需管理员）
Get-MpComputerStatus | Select RealTimeProtectionEnabled, OnAccessProtectionEnabled
# 把构建目录加入排除项（需管理员）
Add-MpPreference -ExclusionPath 'C:\Workspace\deepseek-harness-android-app'
# 撤销
Remove-MpPreference -ExclusionPath 'C:\Workspace\deepseek-harness-android-app'
```

> **安全权衡**：加排除项意味着 Defender 不再扫描该目录。这是构建工作区（内含下载的
> 上游 APK 与解压出的依赖），不存放用户文档，属可接受的取舍 —— 且与开发机上常见的
> 做法一致（`~/.gradle`、Android SDK 等通常也在排除列表里）。
> 若你的环境有更严格的策略，可以不加，代价就是每次多等约 7 分钟。

Linux / CI 上没有这个问题，构建同样在 **1 分钟出头**量级。
（脚本本身未改动这条检查 —— 试过改成扫 zip 流，实测无改善甚至略慢，
因为二者都要读同一批刚写出的数据，真正的解法是让杀软别扫。）

---

## 6. 在 x86_64 模拟器上运行

### 6.1 让引擎真的跑起来（本版 实测结论）

x86_64 模拟器靠 `ndk_translation` 跑 arm64 的 node。踩过的坑与结论：

1. **转译进程里的 guest（arm64）加载器完全不看 `LD_LIBRARY_PATH`** —— 实测：往 `LD_LIBRARY_PATH`
   指一个目录，放垃圾文件、放真 ELF、什么都不放，报错**完全相同**：
   `CANNOT LINK … library "libz.so.1" not found: needed by main executable`。
   它只在 **`/system/lib64/arm64/`** 里找（该镜像的 guest 库目录），而那里有 `libz.so`，
   却**没有** `libz.so.1` / `libc++_shared.so` / `libicu*.so.78` —— 所以 node 起不来、
   引擎启动超时。
2. 因此模拟器需要**把 node 依赖的 arm64 库按 soname 放进 guest 库目录**，仓库给了脚本：

   ```bash
   # ① 模拟器必须用「可写系统」启动（否则 /system 不可写）；图形后端用 swiftshader 更稳
   emulator -avd <你的AVD> -writable-system -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load
   # ② 装库（幂等；只推 node 真正需要的 10 个 arm64 库，约 46 MB）
   adb root && adb remount
   python tools/emu-arm64libs.py            # 可加 --dry-run 先看清单
   # ③ 之后再启动 App / 引擎即可
   ```

   验证：`adb shell su 0 sh -c '<payload>/runtime/bin/node --version'` 应打印 `v26.4.0`。
3. **真机（arm64 原生）不需要这一步**，也**不要**跑这个脚本。
4. 两个把人绕远的现象，记下来省时间：
   · 用 `| Select-Object -First N` 之类的**管道截断**启动模拟器，管道一关模拟器就退出；
   · 漏掉 `-gpu swiftshader_indirect` 时宿主端可能直接崩溃（`EXCEPTION_ACCESS_VIOLATION`）。

### 6.2 为什么 x86_64 包比 arm64 包小约 20 MB

以 v1.19.3 的两个产物为例（`arm64` 134.7 MB / `x86_64` 114.9 MB）。按 **payload.zip 的压缩后体积**
逐子树对比（压缩后体积才直接决定 APK 大小）：

| 子树 | arm64 | x86_64 | 差值 | 差在哪 |
|---|---|---|---|---|
| `runtime/share` | 12.5 MB / 757 文件 | **0 / 0 文件** | **+12.5 MB** | Termux 的 share 树：`man` 438、`doc` 194、`terminfo` 40、`awk`（awklib）29、`info` 29、`readline` 19 … |
| `runtime/bin` | 21.2 MB / 60 文件 | 17.1 MB / **5 文件** | **+4.1 MB** | x64 只剩 `node`(48.5 MB 未压缩)、`rg`、`curl`、`apksigner`、`LINKS.txt`；少的 55 个里最大的是 `aapt2` 3.5 MB、`coreutils` 1.3 MB、`gawk` 1.0 MB、`openssl` 0.94 MB，以及 `tar/find/less/grep/diff/sed/zip/zstd/xz/patch/jq` … |
| `runtime/lib` | 28.7 MB / 164 文件 | 25.7 MB / 52 文件 | **+3.0 MB** | 少的 112 个是 Termux 库（libpcre2、libgmp、libncursesw、libreadline、libpng16、protobuf/abseil 等） |
| **合计** | **126.8 MB** | **107.2 MB** | **19.6 MB** | 与 APK 体积差 19.8 MB 对得上（x86_64 只**多**了 3 个覆盖库 `libz.so`/`libssl.so`/`libcrypto.so`，未压缩约 2.8 MB） |

**为什么排除它们 —— 不只是省体积，更是正确性要求：**

- 被排掉的 927 个文件（`config/runtime-tools-files.txt`，脚本生成；构建日志会打印"已排除 N 个"）
  全是 **arm64 ELF**：在 x86_64 模拟器上根本跑不起来（见 §6.1：guest 加载器不认 `LD_LIBRARY_PATH`）；
- 更要紧的是**它们的库会绊倒 host 侧转译运行器** —— 现象就是
  `CANNOT LINK … is for EM_AARCH64 (183) instead of EM_X86_64 (62)`，**引擎直接起不来**。
  也就是说：不排除的话，x86_64 包连引擎都跑不起来。

**由此决定的验收分工（别搞错）：**

| 能/该在哪验证 | 内容 |
|---|---|
| **模拟器（x86_64 包）** | 引擎启动、Web 界面与接口、remote 插件加载、载荷提取与完整性检查、**内置 apksigner**（apksig 是 dex，与架构无关：`--version` → 0.9、`verify --print-certs`、`sign`） |
| **真机（arm64 包）** | GNU 工具集行为（如 toybox 与 GNU `grep 'a\|b'` 的差别）、`less`+terminfo、`aapt2`/`ecj`/`dx` 的设备内从零构建链、以及一切需要 arm64 原生的部分 |

> 若强行让 x86_64 包也带上这些工具，就得同时解决"arm64 库与转译运行器冲突"的问题，
> 得不偿失；建议保持"模拟器验流程、真机验工具链"的分工。



> **`DSH_X64_BARE_LIBS` 里要放哪些库（本版 起由目录内容决定）**：构建期会把该目录（默认
> `.cache/x64libs`）里的**每一个文件**按**同名**注入 `runtime/lib`，并从 `LINKS.txt` 移除对应条目
> —— 不再写死三个名字。为什么必须同名：ndk_translation 的翻译运行器（host 侧 x86_64）会从
> `LD_LIBRARY_PATH`（= `runtime/lib`）解析**它自己**需要的库，撞上同名 arm64 文件就
> `CANNOT LINK … is for EM_AARCH64 (183) instead of EM_X86_64 (62)`，**引擎直接起不来**。
>
> ```bash
> mkdir -p .cache/x64libs
> for n in libz.so libssl.so libcrypto.so; do adb pull /system/lib64/$n .cache/x64libs/$n; done
> # 本版 实测还需要这个（注意存成**报错里的全名**）：
> adb pull /system/lib64/liblzma.so .cache/x64libs/liblzma.so.5.8.4
> ```
>
> 以后引擎日志若报出新的库名，按同样方式 `adb pull` 后存成报错里的那个名字即可（无需改代码）。
> 安全性：只覆盖 guest 侧没有引用的库（可用 `grep -c liblzma runtime/bin/node` 确认 node 不引用它）。



上游只出 **arm64** 产物。x86_64 模拟器要靠 `ndk_translation` 转译，需要额外两步
（**真机 arm64 完全不需要**）：

> **懒人路径**：`bash tools/emu-start.sh [AVD名]` 会把下面这些前提一次做全
> （带 `-writable-system` 与 `-gpu swiftshader_indirect` 启动、等启动完成、`adb root && adb remount`、
> 补 guest 库），并避开两个坑：管道截断会让模拟器跟着退出、漏 `-gpu swiftshader_indirect` 会崩。

1. **模拟器镜像必须是 userdebug**（`google_apis`，**不能**是 `google_apis_playstore`），
   否则 `adb root` / `adb remount` 被禁，无法补库；
2. **必须带 `-writable-system` 启动**，否则 AVD 的可写 system 覆盖层不挂载，第 3 步补的库会“消失”
   （现象：引擎起不来、`打开主界面` 一直打不开，logcat 里 node 报
   `CANNOT LINK EXECUTABLE ... library "libz.so.1" not found`）。
   Android Studio 默认**不带**这个开关 —— 在 Device Manager → 编辑该 AVD →
   *Additional emulator command line options* 里填 `-writable-system`；
3. **每次（重新）启动后补一次 guest 库** —— 现在**已并回构建流程**：

   ```bash
   bash tools/build-apk.sh --emulator          # 产物出来后自动补库（检测到模拟器时）
   # 想跳过自动补库：--no-emu-libs
   # 模拟器是构建之后才接上的，手动补一次即可（幂等）：
   adb root && adb remount
   python tools/emu-arm64libs.py               # 可加 --dry-run 先看清单
   ```

   脚本把 node 需要的 arm64 库（`libz.so.1`、`libsqlite3.so`、`libcui18n.so.78`、`libicuuc.so.78`、
   `libicudata.so.78`、`libc++_shared.so`、`libssl.so.3`、`libcrypto.so.3`、`libcares.so`、`libffi.so`，
   约 46 MB）按 soname 补进 `/system/lib64/arm64/`。**为什么不能靠 `LD_LIBRARY_PATH`**：实测把同名
   实体文件（软链 / aarch64 实体 / x86_64 实体三种形态）放进 LD 路径依然报 `libz.so.1 not found`，
   guest 链接器只搜那个目录；真机 arm64 完全不需要这些。

   > 历史说明（2026-10 修正）：这一步原本由 `tools/emu-guestlibs.sh` 手工完成，
   > 该脚本在仓库清理时被误判为"重复脚本"删除，而文档仍指向它 → 照文档做会"找不到脚本"，
   > 表现就是"以前加个参数就能跑、现在不行了"。现在它由 `tools/emu-arm64libs.py` 承担，
   > 且 `--emulator` 会自动调用，不再需要手工记这一步。

构建时加 `--emulator` 会自动带上包侧需要的开关（`DSH_X64_BARE_LIBS`），并**自动**给已连接的
模拟器补 guest 库；你仍需自备 `x86_64` 的 `libz.so`/`libssl.so`/`libcrypto.so`
（从模拟器 `/system/lib64/` 取，放进 `.cache/x64libs/`）。

---

## 7. 这个仓库里有什么、没有什么

| 有 | 说明 |
|---|---|
| `android-app/` | APK 构建工程（Java 原生壳 + `build.sh`） |
| `plugins/` | 4 个 DSH 插件源码（Shizuku / Android / 无障碍 / 虚拟屏） |
| `dsh-patches/` | DSH 的 Android 补丁**归档**（`overlay*/`）与重放脚本 |
| `mobile-patch/` | 前端移动端适配（构建期注入 staging 的 dist）|
| `console-theme/` | 控制台主题包规范与校验工具 |
| `config/`、`docs/` | 配置镜像与文档 |
| `tools/` | **本仓库新增**：一键构建、桩 devhome、依赖闭包计算、构建说明 |

### 应用图标（可重新生成）

启动图标由 `android-app/icon-src/dsh-desktop.ico` 生成，重新生成只需一条命令：

```sh
python tools/gen-icons.py        # 需要 Pillow
```

它会写 `android-app/res/mipmap-*/ic_launcher{,_foreground}.png`、`mipmap-anydpi-v26/ic_launcher.xml`，
并在 `values/colors.xml` 里维护 `ic_launcher_background`（自适应图标背景色，当前纯白）。
脚本头部注释写清了取舍（前景铺满 108dp / 白色背景的理由）。

⚠ `drawable/ic_launcher.xml`（小鲸鱼矢量图）**不是**启动图标，它是**通知小图标**与启动页 logo：
通知小图标会被系统按 alpha 蒙版渲染，换成彩色位图会变白块 —— 改图标时不要动它。

| 没有 | 为什么 / 从哪来 |
|---|---|
| node 运行时（`runtime/`） | 体积大；由 `tools/build-apk.sh` 从我们 pin 的运行时资产解出 |
| DSH 内核树（`dshroot/`） | 同上（且补丁已在内） |
| `android.jar` | Google SDK 许可范围；从本地 Android SDK 取 |
| 签名密钥 | 安全；本地生成或自备 |
| 产物（`*.apk`、`payload.zip`） | 大二进制走 Release，见 `CONTRIBUTING.md` |

---

## 8. 上游更新了怎么办

上游仓库：<https://github.com/woaiys3/deepseek-harness-android-app>

**注意：上游会 force-push 重写历史**（本项目已被影响过一次）。
所以不要在本仓库的 `main` 上开发 —— `main` 只用于跟随上游。

```bash
git fetch origin
git switch main && git reset --hard origin/main    # main 是上游镜像，可随时重置
git switch master && git rebase main               # 你自己的改动 rebase 上去
```

上游发新版后，改 `tools/build-apk.sh` 顶部的三个常量：

```sh
UPSTREAM_TAG="v1.17.4"
UPSTREAM_ASSET="DeepSeekHarness-official-${UPSTREAM_TAG}.apk"
UPSTREAM_MD5="<新包 md5，发布页公布值>"
```

然后 `bash tools/build-apk.sh --clean`。若第 6 步报**补丁特征串未命中**，
说明上游的补丁集变了 —— 对照 `dsh-patches/overlay-020/` 与 `CHANGES.md` 更新
脚本里的 `PATCHES` 清单。

---

## 9. 更进一步

| 想看什么 | 去哪 |
|---|---|
| 内核树来源与实测对照 | [android-app/kernel/README.md](android-app/kernel/README.md) |
| 各补丁解决了什么问题 | [CHANGES.md](CHANGES.md) |
| 开发环境结构、真机/模拟器调试 | [docs/开发指南.md](docs/开发指南.md) |
| 贡献方式 | [CONTRIBUTING.md](CONTRIBUTING.md) |
