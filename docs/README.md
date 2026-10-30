# 文档索引（先看这里）

> 目标：**只 clone 仓库 + 读文档，就能上手改代码、构建、测试、发版、运维。**
> 所有文档以本仓库当前代码为准；发现文档与代码不一致时，以代码为准并顺手改文档。

## 5 分钟上手（新 agent 请按序读）

| 顺序 | 文档 | 你会得到什么 |
|---|---|---|
| 1 | [STRUCTURE.md](STRUCTURE.md) | 仓库里每个目录是干什么的、APK 里装了什么、运行时怎么跑起来 |
| 2 | [BUILD.md](BUILD.md) | 从零准备环境 → `bash tools/build-apk.sh` 出包（含模拟器包） |
| 3 | [TESTING.md](TESTING.md) | 模拟器/真机测试方法、日志与取证方法、工具回归清单 |
| 4 | [OPS.md](OPS.md) | 端口/令牌/日志/完整性自愈/重启脚本等运维手册 |
| 5 | [RELEASE.md](RELEASE.md) | 改版本号 → 出 arm64 两包 → 上传 Release（慢网用 curl 直传） |
| 6 | [DEVELOPMENT.md](DEVELOPMENT.md) | 常见改动的落点：Java / 插件 / 前端补丁 / 控制台 / 主题 |
| 7 | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 用户可自助的排查步骤 + 开发侧已知坑 |

## 常用脚本（都在 tools/，已入库）

| 脚本 | 用途 |
|---|---|
| `tools/build-apk.sh` | 构建单个包（`--emulator` / `--original-look` / `--out`） |
| `tools/build-release.sh` | 一次出三包（arm64 / arm64-original-look / x86_64） |
| `tools/release-upload.sh` | 慢网用 curl 直传 Release 资产（`gh` 卡住时用） |
| `tools/release-check.sh` | 核对 Release 是否已发布 + 资产列表 |
| `tools/emu-start.sh` | 启动模拟器（带 guest 库补齐） |
| `tools/emu-shizuku.sh` | 模拟器上拉起 Shizuku server |
| `tools/emu-skip-setup.sh` | 跳过首次引导（仅测试用） |
| `tools/java-syntax-sanity.py` | Java 括号/大括号体检 |

## 其它文档

| 文档 | 说明 |
|---|---|
| [../CHANGES.md](../CHANGES.md) | 版本变更历史（最新在最上面） |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 如何提 Issue / PR |
| [../BUILD.md](../BUILD.md) | 历史构建笔记（含早期细节，**以 BUILD.md 之外的 docs/BUILD.md 为准**） |
| [build-legacy.md](build-legacy.md) | 更早期的构建细节归档（可能过时，仅供考古） |
| [screenshots/](screenshots/) | 界面截图 |
| [../plugins/README.md](../plugins/README.md) | 内置工具插件（`dsh-tool-*`）说明 |
| [../vendor/README.md](../vendor/README.md) | 内置 remote 插件的来源与更新方式 |
| [../mobile-patch/README.md](../mobile-patch/README.md) | 移动端适配补丁（`mobile.js` / `mobile.css`）说明 |
| [../console-theme/THEME-PACK-SPEC.md](../console-theme/THEME-PACK-SPEC.md) | 控制台主题包规范 |

## 一句话项目定位

把 **DeepSeek Harness（DSH，一个 Node 引擎 + Web 前端）** 打包成可安装的 Android APK：
APK 里带 Java 外壳（启动页/控制台/悬浮窗/无障碍服务/特权桥）+ 完整的 Node 运行时与内核 payload，
装上即可用，并可通过 **Shizuku / root / 无障碍**让 AI 操作手机。
