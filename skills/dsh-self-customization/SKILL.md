---
name: dsh-self-customization
description: 当任务要求"改这个 App 自己"（改 DSH/壳的行为、改界面、装插件、打内核补丁、重建或升级 APK、换签名）时使用。给出「走 plugin_manager/profile 补丁」还是「必须重建 APK」的判断矩阵、设备内与 PC 两条构建路径、社区密钥与共存规则、数据迁移步骤，以及哪些改动会被升级覆盖。源码仓库 https://github.com/blue-soda/deepseek-harness-android-app（上游 https://github.com/woaiys3/deepseek-harness-android-app）；社区签名密钥在 android-app/community.jks（alias=community，密码 dsh-community，公开密钥、故意入库）。
---

<!-- dsh-android-managed: v1 -->
<!-- 本文件由 DeepSeek Harness Android 壳在启动时落盘到 <filesDir>/.agents/skills/ ；
     壳只覆盖带本标记的文件，用户自己写的技能不受影响。 -->

# 改造 DeepSeek Harness（Android）自身

## 源码与签名密钥（自开发必读）

| 项 | 值 |
|---|---|
| 本仓库（fork，remote `blue-soda`） | `https://github.com/blue-soda/deepseek-harness-android-app` |
| **默认分支** | **`master`**（已设为仓库默认分支） |
| ⚠ 废弃分支 | `main` —— 是**分叉的旧树**（落后 master 155 个提交，**没有** `android-app/community.jks`、`android-app/variants.sh`、顶层 `tools/`）。看到这些文件缺失 = 你取错分支了，**回 `master`**，不要去猜"密钥不在仓库" |
| 上游仓库（remote `origin`） | `https://github.com/woaiys3/deepseek-harness-android-app` |
| **社区签名密钥** | `android-app/community.jks` —— **已入库，公开密钥** |
| · store 密码 / alias / key 密码 | `dsh-community` / `community` / `dsh-community` |
| · 证书 SHA-256 | `18:C9:7C:04:0B:B7:AB:30:15:60:63:41:E8:99:05:9B:4C:9D:05:25:3E:2D:E4:1B:4F:A5:D0:E4:B4:10:A8:E3` |
| 其它变体密钥 | `release.jks`（**不入仓库**，需自行准备；可用 `DSH_KEYSTORE` 覆盖）；本版 起构建只剩社区版 |
| 固定取文件（raw，分支写死 `master`） | `https://raw.githubusercontent.com/blue-soda/deepseek-harness-android-app/master/<路径>` |
| 镜像同路径（raw 不可达时首选） | `https://cdn.jsdelivr.net/gh/blue-soda/deepseek-harness-android-app@master/<路径>` |

### 开工自检（先做，10 秒，**别跳过**）

任何涉及本仓库文件的任务，开工第一步先确认"我在正确的分支上、关键文件在"：

```bash
RAW=https://raw.githubusercontent.com/blue-soda/deepseek-harness-android-app/master
# 三样必须同时存在；缺任何一样 = 分支/入径不对 → 停下报告，不要推断"仓库里没有"
for f in tools/build-apk.sh android-app/variants.sh android-app/community.jks; do
  echo "--- $f"; mirror-get "$f" | head -c 80; echo
done
```

**取文件顺序**（多源 + 失败显式报错，别只试一个）：

1. `mirror-get <仓库相对路径>` —— 随包 helper（见下节），自动按 4 条路重试；
2. `https://cdn.jsdelivr.net/gh/blue-soda/deepseek-harness-android-app@master/<路径>`；
3. `https://api.github.com/repos/blue-soda/deepseek-harness-android-app/contents/<路径>?ref=master`
   （返回 base64，用 `base64 -d` 还原；`-H "Accept: application/vnd.github.raw"` 更省事）；
4. `https://ghfast.top/https://raw.githubusercontent.com/...` 或 `https://gh-proxy.com/https://raw.githubusercontent.com/...`；
5. 原始 `raw.githubusercontent.com/...`。

> 顺序与镜像列表的**单一真源**是仓库里的 `config/mirrors.env`（取文件时按它排序，别自己另编一套）。
> 每一路失败都要**显式**；全部失败再报告 —— 报告里要写清"试过哪些源、各自什么错"。

> **版本自检**：本 skill 描述的是 **本版+** 的仓库 —— 单一构建路径（内核来自官方 npm registry +
> 仓库内冻结 lock、arm64 运行时来自 sha256 pin 的资产），`tools/build-apk.sh` **没有**
> `--kernel` / `--runtime` 参数，也**不再**下载上游 APK。若你取到的 `tools/build-apk.sh` 里还有
> 这两个参数，说明你拿到的是旧版（改动尚未推到 `master`）——
> （注意：新版 `build-apk.sh` 里**仍会**出现 `assets/payload.zip`，那是构建后**校验产物自身完整性**用的，不是旧版标志。）
> **以仓库当前 `master` 为准，并在报告里写明这个不一致**，不要照着旧版去猜新参数。

签名命令（`android-app/build.sh` 内部就是这一套）：

```bash
apksigner sign --ks android-app/community.jks \
  --ks-pass pass:dsh-community --ks-key-alias community \
  --out DeepSeekHarness-community.apk <unsigned.apk>
```

> ⚠ 社区密钥是**公开**的：任何人都能签出同包名 APK，因此它只适合社区分发。
> 要正式发布请换自己的 `release.jks`，并确保以后升级用同一把密钥（否则只能卸载重装）。
> ⚠ 真机构建时**不要**设置 `DSH_X64_BARE_LIBS`（那是 x86_64 模拟器适配，会把 x86_64 的
> libz/libssl/libcrypto 打进 payload → 真机 arm64 上 node 启动即崩）。

## 0. 先做判断：三条路，代价差一个数量级

| 需求 | 该走的路 | 为什么 |
|---|---|---|
| 加/改**工具**、加**模型供应商**、装 **bundle/插件**、调插件配置、加 **skill** | **A. 运行期定制**（`plugin_manager` 或 profile 补丁） | 秒级生效、**升级后存活**、不需要签名、不用重装 |
| 改**壳**（原生界面/权限/服务）、改 **payload**（内置 runtime/python/npm、mobile-patch、home 层补丁）、改**包名/端口/权限** | **B. 重建 APK** | 这些是 APK 内容，运行期改不了 |
| 改 **dshroot 内核 JS**（引擎内部实现、自带前端） | **C. 内核补丁**（可做，但**不持久**） | 见第 3 节：不改写成补丁就会被同步覆盖 |

> 判断口诀：**"能不能用插件表达？"** 能 → A；不能且属于"App 本体" → B；只有改引擎内部才行的 → C。

## 1. 路线 A：运行期定制（首选）

```bash
# 装插件（三种输入都支持：包名 / 本地目录 / GitHub 地址）
#   优先用 plugin_manager 工具，它内部走内置 pnpm
# 或直接编辑 profile 补丁层（用户文件，升级不覆盖）：
#   $DSH_HOME/profiles/web/cordis.patch.yml
```
- `$DSH_HOME` = 应用私有目录 `<filesDir>/payload/dshhome`（引擎启动时注入）。
- **能活下来**：插件（profile 清单是**合并**写入）、`profiles/web/cordis.patch.yml`、`settings.yaml`、
  `.credentials.yaml`、会话数据。
- **会被覆盖**：`dshroot/**` 内核树（见第 3 节）；`dshhome/cordis.patch.yml` 在 marker／关键项被改坏时
  会被从 payload 恢复（正常追加内容不受影响）。

## 2. 路线 B：重建 APK

**输入三样**：本仓库源码、构建脚本（`tools/build-apk.sh`）、一把签名密钥。

**推荐在 PC 上构建**（Windows 用 Git Bash；不需要 Gradle/AGP/maven，只要 JDK17+ 与
`build-tools` + 任一 `platforms/android-*/android.jar`）：

```bash
bash tools/build-apk.sh        # 社区版（唯一变体；公开密钥签名，可与官方包共存）
```
产物：`DeepSeekHarness-community.apk`。

**社区密钥（公开，故意如此）**：`android-app/community.jks`，别名 `community`，口令 `dsh-community`。
- 它的用途就是让**不持有维护者私钥**的人也能构建出可与官方包共存的自己那一份；
- 它**绝不能**用于签名正式发布物（正式包始终由私有 `release.jks` 签发）。

**在设备上构建：可行，但代价高**（如实说明，不要断言"做不到"）：
- 可以只用用户态工具链（JDK + `d8` + `aapt2` + `apksigner` + 一个 `android.jar`），
  **不需要 Gradle/AGP/SDK 规范布局**；工具链约 0.5–2 GB（进 Gradle/NDK 则更夸张）；
  **v1.19.3 起 `aapt2` / `apksigner` / `ecj` / `dx` 已随 payload 内置**（`runtime/bin/`），其中：
  · **`aapt2` 可直接用**（本版 起随包 `libc++_shared.so` 已换成与它匹配的新版，实测
    `aapt2 version` → `Android Asset Packaging Tool (aapt) 2.20-android-16.0.0_r4`）；
  · **`apksigner` 可直接用**（不依赖 JVM，见上一节"安装后自证"）；
  · **`ecj` / `dx` 也**可直接用，**不需要 JDK**（本版 起包装脚本改成 `app_process` + 预置 jar；
    实测设备内 `Hello.java → Hello.class → classes.dex` 全通：ecj 3.18.0 / dx 1.16）。
    这里的两个坑值得记住（都实测过）：
      ① 现代 Android **删掉了 `dalvikvm`**（只有 `app_process`）—— 照 Termux 那套写法会报
         `dalvikvm: inaccessible or not found`；
      ② ART **拒载可写 dex**：jar 若是 0600 会**静默 SIGABRT(exit 134)**，所以包装脚本先
         `chmod 444`（apksigner 的 `classes.dex` 早就踩过同一个坑）。
    `dx --dex` 还有个使用细节：**输入要用相对路径/目录**（它要求类名与路径匹配，
    给绝对路径会报 `class name (X) does not match path (...)`）。

**共存规则**（本版 起只有社区版；端口与外部目录都避开官方包）：

| 变体 | 包名 | 引擎/通知 | 无障碍 | 虚拟屏 桥/核心 | 外部目录 |
|---|---|---|---|---|---|
| community | `com.deepseek.harness.community` | 3086/3087 | 3187 | 9029/9028 | `/sdcard/DeepSeekHarnessCommunity` |

（official / lite / compat 三个变体与救援版已删除，见 `android-app/variants.sh` 头部说明。）

**签名不同的包不能覆盖安装**：换包名/换签名必须先卸载，会丢应用内部数据（会话、凭证、插件元数据）。
迁移步骤：控制台 →「导出全部数据」→ 卸载 → 安装新包 → 「从备份导入还原」。
`/sdcard/<变体目录>` 下的外部内容（截图、主题、日志）不受卸载影响。

### 安装后自证（W6：装完必须核对"装进去的到底是哪个包"）

安装**必须由用户点确认**（`pm install` 在应用 uid 下会要求确认；有 Shizuku/root 时可以静默，
但仍应让用户知情）。装完后**不要凭"看到图标变了"就宣布成功** —— 读已安装的 `base.apk` 逐项核对：

```bash
PKG=com.deepseek.harness.community
P=/data/data/$PKG/files/payload
APK="$(pm path $PKG | head -1 | sed 's/package://')"     # 已安装的 base.apk（可直接读）
BUILT=/sdcard/DeepSeekHarnessCommunity/DeepSeekHarness-community.apk   # 你构建出来的那个

# ① 版本号（最便宜、最先看）
pm list packages --show-versioncode | grep "$PKG"        # → package:… versionCode:58
# versionName 用包内工具从已安装的 APK 里读（dumpsys 在设备上会 Permission Denial）
"$P/runtime/bin/unzip" -p "$APK" AndroidManifest.xml | tr -d '\0' | grep -ao '1\.[0-9.]*-community' | head -1

# ② 证书 SHA-256：证明"装的是我签的那份"，不是被换过的旧包
"$P/runtime/bin/apksigner" verify --print-certs "$APK"     | grep 'SHA-256 digest'
"$P/runtime/bin/apksigner" verify --print-certs "$BUILT"   | grep 'SHA-256 digest'

# ③ 关键资源条目：图标 + resources.arsc（换包名/换图标最容易出错的就这几项）
for e in resources.arsc $( "$P/runtime/bin/unzip" -Z1 "$BUILT" | grep -E '^res/.*(mipmap|drawable).*\.(png|xml)$' | head -5 ); do
  # ⚠ 不要用 `unzip -v | awk '/CRC-32/…'`：那是拿**表头行**在比，任何两个包都"相等"（假通过）。
  a=$( "$P/runtime/bin/unzip" -p "$APK"   "$e" 2>/dev/null | sha256sum | cut -c1-16 )
  b=$( "$P/runtime/bin/unzip" -p "$BUILT" "$e" 2>/dev/null | sha256sum | cut -c1-16 )
  [ "$a" = "$b" ] && echo "OK   $e  ($a)" || echo "差异 $e  已装=$a 构建=$b"
done

# ④ 安装后数据是否保留（换签名才需要卸载；同签名覆盖安装会保留）
ls "$P/dshhome" >/dev/null 2>&1 && echo "payload 在位 ✓"
```

要点：

- **`apksigner` / `unzip` / `sha256sum` 都在 payload 里**（v1.19.3 起内置 apksig；
  `runtime/bin/unzip` 来自 W3 的 GNU 工具集）→ 设备端就能自证，**不需要 PC**。
- 装完 App 会**重启**；`/sdcard/<变体目录>` 与 `<filesDir>/payload` 都不受影响（卸载才会丢内部数据）。
- 若 ② 的指纹与你构建时用的密钥不一致 → **装的是别的包**（旧版本或官方包），别继续往下走。
- 若 ③ 里 `resources.arsc` 有差异，通常意味着**不是同一个构建产物**（改了资源没重打，或装到了旧包）。

## 3. 路线 C：改 dshroot 内核 JS（能做，但不持久）

- **能做**：引擎以应用 uid 运行，对 `<filesDir>/payload/dshroot/**` 有写权限，改完重启引擎即生效
  （热生效，无需重装）。**不要**把这件事说成"做不到"。
- **不持久**：白名单文件（`FORCE_OVERWRITE_PREFIXES`，约 14 项，恰好是项目自己打过补丁的文件）
  每次同步都被覆盖；其余文件在**内核版本变化 / `.complete` 丢失 / 用户点「重新解压」**时被全量重写。
- **要持久**：走仓库的补丁机制 —— 改 `dsh-patches/overlay/`、`sh dsh-patches/apply.sh`、
  并把该文件加进 `FORCE_OVERWRITE_PREFIXES`，然后**重新打包 APK**，这样它随包下发、升级也保留。
- 运行期"临时验证"用直接改文件是完全可以的，只要知道它会在下次同步时消失。

## 3.9 平台环境陷阱（与 DSH/本仓库无关，但会咬人）

实测（设备端 agent 报告 + 我们自己的复现），下面三条**不是配置问题**，写脚本时按此规避：

| 现象 | 原因 | 规避 |
|---|---|---|
| `child_process.execSync`/`spawnSync` 报 `spawnSync /data/data/com.termux/files/usr/bin/sh ENOENT` | 内置 node 是 **Termux 构建**，Android 分支的默认 shell 被写死成 Termux 的 `sh`（明文嵌在二进制里的 JS 源码，环境变量救不了） | **本版 起已从根上修好**：构建期用等长字节替换把该串改成 `/system/bin/sh`（`tools/patch-node-shell.py`，实测 `execSync` 正常）。自己写脚本时仍建议显式给 `{ shell: process.env.DSH_BASH_PATH }` |
| 往 `/tmp` 写文件失败 | Android 没有可写的全局 `/tmp` | **只用 `$TMPDIR`**（引擎 env 已指向可写目录），例如 `mktemp -d "$TMPDIR/xxx.XXXXXX"` |
| `pnpm install` / 建软链失败 | `/sdcard` 是 FUSE，**不支持符号链接** | 随包 `.npmrc` 已设 `node-linker=hoisted`（pnpm 用 npm 式平铺、不建软链）；依赖目录仍建议放**应用私有目录**（`$HOME`/`$DSH_HOME`），`/sdcard` 只放源码 |

（这三条已在 bash 工具的描述里提示过一遍；写成表格是为了让写脚本的 agent 也能直接查到。）

## 4. 常见坑

1. **不要把"运行期改 payload"当持久方案**：解压/同步会覆盖（第 3 节白名单与全量重写规则）。
2. **升级语义**：同包名同签名可直接覆盖安装（配置/会话/插件都保留）；跨签名必须卸载。
3. **targetSdk 必须保持 28**：≥29 时应用私有目录挂 noexec，node 起不来。
4. **真机 arm64 不要设** `DSH_X64_BARE_LIBS` / `DSH_MATERIALIZE_LINKS`（那是 x86_64 模拟器与排障开关）。
5. **端口/目录不要硬编码**：新变体请改 `android-app/variants.sh`（单一真源），构建期会生成
   `BuildVariant.java` 并改写 manifest。
6. **改动壳/Java 一定要重新构建并安装**；只改插件 JS 则走路线 A，不用重装。

## 5. 想查证时的入口

- 端口与变体：`android-app/variants.sh`、`docs/legacy/dev-guide-2026-09.md`（端口一览）
- 定制存活面：`docs/legacy/dev-guide-2026-09.md`（"定制面：哪些改动能活下来"）
- 构建：`docs/BUILD.md`
- 内核补丁：`dsh-patches/README.md`
