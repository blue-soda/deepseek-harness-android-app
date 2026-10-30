# 仓库结构

## 顶层目录

```
android-app/        Java 外壳源码 + 构建脚本 + 打包资源（APK 的全部 Java 侧逻辑）
  src/com/deepseek/harness/
    MainActivity.java         启动页 / 控制台（原生 UI）/ 引擎拉起与看护 / 本地 HTTP 服务
    OverlayService.java       悬浮窗（小人 + 气泡 + 面板），可见性/停靠/透明度
    AccessibilityService.java 无障碍屏幕助手后端（/status /dump /screenshot /tap /input …）
    VsreenBridgeService.java  虚拟屏预览窗 + 9029→9028 代理
    EngineService.java        前台服务（保活）
    …                         SessionAdmin / KernelSelfCheck / UsageStatsHelper / LocalAuth 等
  assets/                     随包资源：skills/（内置技能）、kernel 清单、dshroot 版本标记…
  build.sh                    Java 编译 + 资源打包 + payload staging 组装（build-apk.sh 会调它）
  variants.sh                 变体表（单一真源：包名/端口/外部目录/签名 keystore）
tools/              构建与诊断脚本（build-apk.sh 是总入口）
plugins/            内置工具插件（dsh-tool-android / accessibility / browser / shizuku / vscreen）
mobile-patch/       移动端前端补丁（mobile.js / mobile.css / inject.sh）
dsh-patches/        对内核树的补丁与 overlay（apply.sh）
console-theme/      控制台主题包规范与工具
config/             镜像配置（mirrors.env 是单一真源）
vendor/             内置第三方插件（ds-harness-remote，见 vendor/README.md）
skills/             仓库侧技能目录（设备内技能走 android-app/assets/skills/）
docs/               本文档区
```

## APK 里装了什么

1. **Java 外壳**（`android-app/src`）：启动页、控制台、悬浮窗、无障碍服务、特权桥、看门狗。
2. **payload**（构建期组装进 APK assets）：
   - `dshroot/`  — DSH 内核（`@deepseek-ai/dsh` npm 包 + 依赖闭包），即引擎本体；
   - `dshhome/`  — `$DSH_HOME`（profiles / 内置插件 / 配置）；
   - `runtime/`  — arm64（模拟器为 x86_64）的 node、bash、coreutils、git、python 等；
   - `bin/`、`npm/`、`pnpm/`、`python/`、`rish/` — 运行期工具与包装器。
3. **前端补丁** `mobile.js` / `mobile.css`（注入 DSH 的 web dist，做移动端适配）。

## 运行时（设备上）

| 东西 | 位置 |
|---|---|
| payload 解压目录 | `/data/user/0/com.deepseek.harness.community/files/payload/` |
| 外部可见目录（日志/工作区/导出） | `/sdcard/DeepSeekHarnessCommunity/` |
| 引擎日志 | `files/payload/dsh-web.log`，并镜像到 `/sdcard/DeepSeekHarnessCommunity/dsh-web.log` |
| 前端埋点/报错 | `files/web-notes.log`（控制台「日志→分享」会带上） |
| 偏好与令牌 | `shared_prefs/dsh_prefs.xml`（`local_token` 等）、`dsh_setup.xml`（引导/提取状态） |
| 引擎端口 / 通知端口 | **3086 / 3087** |
| 无障碍 HTTP 服务 | **3187** |
| 虚拟屏桥 / 核心 | **9029 / 9028**（桥是 App 内代理，核心是特权进程） |

> 端口与外部目录都由 `android-app/variants.sh` 唯一定义（历史踩坑：8999 互斥导致虚拟屏二选一）。

## 构建流水线（一句话版）

```
tools/build-apk.sh
  ├─ tools/fetch-runtime.sh     取 arm64/x86_64 运行时资产（sha256 pin，缓存 .cache/）
  ├─ android-app/build.sh       取内核（tools/fetch-kernel.sh）→ staging → 同步 plugins/ → 注入 mobile-patch
  │                             → 生成 AndroidManifest / BuildVariant.java → javac + d8 → 打包 → 签名
  └─ 产物：android-app/DeepSeekHarness*.apk（+ --emulator 时追加 x86_64 适配）
```

## 关键设计约定（改代码前必读）

- **插件是单一真源**：`plugins/dsh-tool-*` 由 `android-app/build.sh` 覆盖同步进 staging，
  直接改 payload 里的副本会被下次构建覆盖。
- **前端补丁的覆盖依赖白名单**：`MainActivity` 里有一份「必须随 APK 覆盖」的路径白名单
  （`mobile.js` / `mobile.css` / `index.html` 等在列）。**白名单路径写错 → 补丁永远不生效**（真实事故）。
- **可见性/存活判断不要只用 HTTP 探测**：引擎忙时 HTTP 会超时，统一用 TCP 端口探活（见 `docs/OPS.md`）。
