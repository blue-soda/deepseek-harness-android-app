# 构建 APK

一条命令，从零构建出可安装的 arm64 APK。

```bash
bash tools/build-apk.sh
```

产物：`android-app/DeepSeekHarness.apk`（约 150 MB）
同时生成 `android-app/DeepSeekHarness.apk.build-info.txt`，记录这次构建的全部来源信息。

首次运行会从 GitHub 下载约 150 MB 的上游 APK 并解压约 440 MB，之后走缓存。
**从零到出包约 15 分钟。**

---

## 1. 前置条件

| 需要 | 版本 | 说明 |
|---|---|---|
| **JDK** | 17 及以上（**21 已验证**） | 提供 `javac` / `keytool` / `jar` |
| **Android SDK** | 含 `build-tools` 与任一 `platforms/android-*` | 提供 `aapt` / `d8` / `zipalign` / `apksigner` / `android.jar` |
| 常用工具 | `curl` `unzip` `tar` `git` | 一般发行版自带 |
| 磁盘 | 约 3 GB 空闲 | 缓存 ~1 GB + 中间产物 ~1 GB + 产物 150 MB |

安装 SDK 组件（任意平台，需先有 `sdkmanager`）：

```bash
sdkmanager "build-tools;35.0.0" "platforms;android-36"
export ANDROID_HOME=/path/to/Android/Sdk     # 或 ANDROID_SDK_ROOT
```

`ANDROID_HOME` 没设时脚本会自己探测常见位置（Windows 的 `%LOCALAPPDATA%\Android\Sdk`、
Linux/macOS 的 `~/Android/Sdk`、`~/Library/Android/sdk`）。

**平台支持**：Windows 用 **Git Bash**（`C:\WINDOWS\system32\bash.exe` 是 WSL，不行）；
Linux、macOS 理论上同样可用，但**仅在 Windows + Git Bash 上实测过**。

---

## 2. 它做了什么

| 步 | 动作 |
|---|---|
| 0 | 预检：JDK / SDK / build-tools / android.jar / 磁盘空间，缺什么直接说清楚 |
| 1 | 下载上游 Release APK，**校验 md5**（与发布页公布值比对，不符即拒绝） || 2 | 从 APK 里取出 `assets/payload.zip` |
| 3 | 解压组装成 `build.sh` 需要的 **devhome**，并做两处已知的目录修正 |
| 4 | 准备签名密钥（没有就生成一个本地的） |
| 5 | 调用 `android-app/build.sh` 完成 7 步打包 |
| 6 | **校验**：payload 结构、13 个补丁特征串、APK 条目与签名、manifest 关键字段 |
| 7 | 写出构建信息文件 |

### 关键点：devhome 从哪来

本仓库**不含** node 运行时，也**不含**打好补丁的 DSH 内核树 —— 这两样体积太大（约 590 MB）。

它们来自**上游 Release 的 APK**：那个 APK 里的 `assets/payload.zip` 就是维护者已经组装好、
**13 个 Android 补丁全部打好**的成品。脚本把它取出、做两处目录修正，就得到了 devhome：

```
payload.zip 顶层         →  devhome
  runtime/                      runtime/        node v26.4.0 + 66 个 .so（arm64）
  dshroot/                      dshroot/        DSH 内核树（补丁已打）
  dshhome/                      .dsh/           ← 改名
  git/  bin/git                 git/bin/git     ← 移动（build.sh 期望的位置）
  pnpm/ python/ npm/ rish/ bin/ 同名保留
```

脚本会在第 6 步核对 13 个补丁特征串，**确认拿到的确实是已打补丁的树**，而不是空壳。

### 下载有两条路（重要）

GitHub 的 release 直链以 `github.com` 开头。**部分地区 `github.com` 不可达，但
`api.github.com` 仍可用** —— 此时脚本会自动回退：

1. **直连**：`github.com/<repo>/releases/download/<tag>/<asset>`
2. **回退到 API**：查 `api.github.com/.../releases/tags/<tag>` 拿到 asset id，
   再用 `Accept: application/octet-stream` 从 API 端点下载（会 302 到
   `release-assets.githubusercontent.com`）

两条路都支持断点续传（`-C -`），中断后重跑会接着下，不会从零开始。
两条都失败时，用 `--apk <已下载的文件>` 直接喂进去即可。

> 实测（本项目所在网络，GitHub 直连被阻断）：第 1 条超时 → 自动走第 2 条，
> 以约 4.7 MB/s 下完 149.5 MB，md5 校验通过。

---

## 3. 常用命令

```bash
bash tools/build-apk.sh                      # 全自动（推荐）
bash tools/build-apk.sh --clean              # 清缓存重建（保留签名密钥）
bash tools/build-apk.sh --apk ~/x.apk        # 用本地 APK，不下载
bash tools/build-apk.sh --upstream v1.17.4 --md5 <hex>   # 换上游版本
bash tools/build-apk.sh --keystore my.jks    # 用你自己的密钥（需 export KEYSTORE_PASS）
bash tools/build-apk.sh --out /tmp/a.apk     # 指定产物路径
bash tools/build-apk.sh --no-verify          # 跳过校验（调试用）
bash tools/build-apk.sh --smoke              # 构建后若连着设备，装上去并提示怎么验
bash tools/build-apk.sh --emulator           # 额外适配 x86_64 模拟器（见第 6 节）
bash tools/build-apk.sh --help
```

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
| `md5 不匹配` | 上游资产变了（或换了 tag）。确认无误后 `--md5 <实际值>` 显式接受 |
| 下载卡住 / `Failed to connect to github.com` | 直连被阻断。脚本会自动回退到 API 端点；两条都不通用 `--apk <本地文件>` |
| `keystore password was incorrect` | `android-app/release.jks` 是别人/别的口令留下的。删掉它，或用 `--keystore` 指定自己的 |
| 安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 与已装版本签名不同。先卸载：`adb uninstall com.deepseek.harness` |
| 装上了但引擎起不来 | App 内「日志」页看 `dsh-web.log`；或见 [tools/LOCAL-BUILD.md](LOCAL-BUILD.md) |
| 模拟器上 node 报 `CANNOT LINK ... EM_AARCH64` 或 `libz.so.1 not found` | 见第 6 节 |
| 构建脚本报 `找不到 javac` | 设 `JAVA_HOME`，或把 `$JAVA_HOME/bin` 放\进 `PATH` |
| **构建很慢（Windows 上 8~10 分钟）** | 见下方说明 —— 是杀软实时扫描，不是脚本问题 |

### 构建很慢？先看分阶段耗时

脚本结尾会打印各阶段耗时。缓存全命中时正常长这样：

```
各阶段耗时：
   预检                         3 秒
   获取上游 APK               1 秒
   提取 payload.zip             0 秒
   组装 devhome                 0 秒
   签名密钥                   0 秒
   构建 APK                   510 秒     ← 全部时间在这里
   校验                         1 秒
   记录构建信息             1 秒
```

除 `构建 APK` 之外全部命中缓存、几乎不耗时。**若 `构建 APK` 占了绝大部分时间，
先确认是不是杀软**：

`android-app/build.sh` 结尾有一条安全检查，要遍历 `staging/` 全部约 3.2 万个文件
搜索 API Key。这条 `grep` 平时只要 **4 秒**，但在 **Windows Defender 实时保护开启**时，
对**刚写出的** 440 MB 数据实测要 **456 秒**（占整个构建的 88%）。

验证与缓解：

```powershell
# 查看实时保护状态（管理员）
Get-MpComputerStatus | Select RealTimeProtectionEnabled, OnAccessProtectionEnabled
# 把构建目录加入排除项（管理员）—— 这是最有效的缓解
Add-MpPreference -ExclusionPath 'C:\Workspace\deepseek-harness-android-app'
```

Linux / CI 上没有这个问题，同样一次构建通常在 **1~2 分钟**量级。
（脚本本身未改动这条检查 —— 试过改成扫 zip 流，实测无改善甚至略慢，
因为二者都要读同一批刚写出的数据。）

---

## 6. 在 x86_64 模拟器上运行

上游只出 **arm64** 产物。x86_64 模拟器要靠 `ndk_translation` 转译，需要额外两步
（**真机 arm64 完全不需要**）：

1. **模拟器镜像必须是 userdebug**（`google_apis`，**不能**是 `google_apis_playstore`），
   否则 `adb root` / `adb remount` 被禁，无法补库；
2. **把 node 缺的 20 个 arm64 库装进 guest 目录** `/system/lib64/arm64/`。

完整步骤与原理见 [tools/LOCAL-BUILD.md 第四节](LOCAL-BUILD.md)。构建时加 `--emulator`
会自动带上需要的开关（`DSH_X64_BARE_LIBS` + `DSH_X64_NO_LINKS`），但你仍需自备
`x86_64` 的 `libz.so`/`libssl.so`/`libcrypto.so`（从模拟器 `/system/lib64/` 取）。

---

## 7. 这个仓库里有什么、没有什么

| 有 | 说明 |
|---|---|
| `android-app/` | APK 构建工程（Java 原生壳 + `build.sh`） |
| `android-app/compat/`、`android-app-fix/` | 兼容版（GeckoView）/ 救援工具 的差异文件 |
| `plugins/` | 4 个 DSH 插件源码（Shizuku / Android / 无障碍 / 虚拟屏） |
| `dsh-patches/` | DSH 的 Android 补丁**归档**（`overlay*/`）与重放脚本 |
| `mobile-patch/`、`legacy-patch/` | 前端移动端适配与老 WebView 降级 |
| `console-theme/` | 控制台主题包规范与校验工具 |
| `config/`、`docs/` | 配置镜像与文档 |
| `tools/` | **本仓库新增**：一键构建、桩 devhome、依赖闭包计算、构建说明 |

| 没有 | 为什么 / 从哪来 |
|---|---|
| node 运行时（`runtime/`） | 体积大；由 `tools/build-apk.sh` 从上游 APK 提取 |
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
| 本机构建的完整技术记录、模拟器实测全过程 | [tools/LOCAL-BUILD.md](LOCAL-BUILD.md) |
| 各补丁解决了什么问题 | [CHANGES.md](CHANGES.md) |
| 开发环境结构、真机/模拟器调试 | [docs/开发指南.md](docs/开发指南.md) |
| 贡献方式 | [CONTRIBUTING.md](CONTRIBUTING.md) |
