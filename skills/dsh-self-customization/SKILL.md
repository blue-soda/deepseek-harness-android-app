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
| 上游仓库（remote `origin`） | `https://github.com/woaiys3/deepseek-harness-android-app` |
| **社区签名密钥** | `android-app/community.jks` —— **已入库，公开密钥** |
| · store 密码 / alias / key 密码 | `dsh-community` / `community` / `dsh-community` |
| · 证书 SHA-256 | `18:C9:7C:04:0B:B7:AB:30:15:60:63:41:E8:99:05:9B:4C:9D:05:25:3E:2D:E4:1B:4F:A5:D0:E4:B4:10:A8:E3` |
| 其它变体密钥 | `release.jks`（**不入仓库**，需自行准备；可用 `DSH_KEYSTORE` 覆盖） |

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
bash tools/build-apk.sh --variant community   # 社区版：公开密钥签名，可与官方三个变体共存
bash tools/build-apk.sh --variant official|lite|compat
```
产物：`DeepSeekHarness.apk`（official）/ `DeepSeekHarness-<variant>.apk`。

**社区密钥（公开，故意如此）**：`android-app/community.jks`，别名 `community`，口令 `dsh-community`。
- 它的用途就是让**不持有维护者私钥**的人也能构建出可与官方包共存的自己那一份；
- 它**绝不能**用于签名正式发布物（正式包始终由私有 `release.jks` 签发）。

**在设备上构建：可行，但代价高**（如实说明，不要断言"做不到"）：
- 可以只用用户态工具链（JDK + `d8` + `aapt2` + `apksigner` + 一个 `android.jar`），
  **不需要 Gradle/AGP/SDK 规范布局**；工具链约 0.5–2 GB（进 Gradle/NDK 则更夸张）；
- 构建本身还会吃 ~1 GB 中间产物与 ~100 MB 产物；
- **最后一步安装仍需用户确认**（或已授权的 Shizuku）；
- 结论：**可行、不建议当常规路径**。常规做法是 PC 构建，或在 CI/他机构建后用哈希核对。

**变体共存规则**（四个变体端口与外部目录已彻底错开，可同时安装同时运行）：

| 变体 | 包名 | 引擎/通知 | 无障碍 | 虚拟屏 桥/核心 | 外部目录 |
|---|---|---|---|---|---|
| official | `com.deepseek.harness` | 3080/3081 | 3181 | 8999/8998 | `/sdcard/DeepSeekHarness` |
| lite | `com.deepseek.harness.beta` | 3082/3083 | 3183 | 9009/9008 | `/sdcard/DeepSeekHarnessLite` |
| compat | `com.deepseek.harness.compat` | 3084/3085 | 3185 | **8999/8998（暂与 official 冲突）** | `/sdcard/DeepSeekHarnessCompat` |
| community | `com.deepseek.harness.community` | 3086/3087 | 3187 | 9029/9028 | `/sdcard/DeepSeekHarnessCommunity` |

⚠️ compat 目前还没并入变体机制（它有一套独立的 GeckoView 差异文件），所以它的虚拟屏端口仍是
8999/8998：**compat 与 official 不能同时使用虚拟屏**（其它功能不受影响）。

**签名不同的包不能覆盖安装**：换包名/换签名必须先卸载，会丢应用内部数据（会话、凭证、插件元数据）。
迁移步骤：控制台 →「导出全部数据」→ 卸载 → 安装新包 → 「从备份导入还原」。
`/sdcard/<变体目录>` 下的外部内容（截图、主题、日志）不受卸载影响。

## 3. 路线 C：改 dshroot 内核 JS（能做，但不持久）

- **能做**：引擎以应用 uid 运行，对 `<filesDir>/payload/dshroot/**` 有写权限，改完重启引擎即生效
  （热生效，无需重装）。**不要**把这件事说成"做不到"。
- **不持久**：白名单文件（`FORCE_OVERWRITE_PREFIXES`，约 14 项，恰好是项目自己打过补丁的文件）
  每次同步都被覆盖；其余文件在**内核版本变化 / `.complete` 丢失 / 用户点「重新解压」**时被全量重写。
- **要持久**：走仓库的补丁机制 —— 改 `dsh-patches/overlay/`、`sh dsh-patches/apply.sh`、
  并把该文件加进 `FORCE_OVERWRITE_PREFIXES`，然后**重新打包 APK**，这样它随包下发、升级也保留。
- 运行期"临时验证"用直接改文件是完全可以的，只要知道它会在下次同步时消失。

## 4. 常见坑

1. **不要把"运行期改 payload"当持久方案**：解压/同步会覆盖（第 3 节白名单与全量重写规则）。
2. **升级语义**：同包名同签名可直接覆盖安装（配置/会话/插件都保留）；跨签名必须卸载。
3. **targetSdk 必须保持 28**：≥29 时应用私有目录挂 noexec，node 起不来。
4. **真机 arm64 不要设** `DSH_X64_BARE_LIBS` / `DSH_MATERIALIZE_LINKS`（那是 x86_64 模拟器与排障开关）。
5. **端口/目录不要硬编码**：新变体请改 `android-app/variants.sh`（单一真源），构建期会生成
   `BuildVariant.java` 并改写 manifest。
6. **改动壳/Java 一定要重新构建并安装**；只改插件 JS 则走路线 A，不用重装。

## 5. 想查证时的入口

- 端口与变体：`android-app/variants.sh`、`docs/开发指南.md`（端口一览）
- 定制存活面：`docs/开发指南.md`（"定制面：哪些改动能活下来"）
- 构建：`BUILD.md`、`tools/LOCAL-BUILD.md`
- 内核补丁：`dsh-patches/README.md`
