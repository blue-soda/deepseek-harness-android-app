# plugins/ —— 手机端自定义 DSH 工具插件

本项目给 DeepSeek Harness 增加的两个 Android 专属能力插件，**全部通过 Shizuku 特权通道执行**（免 Root）。

| 插件 | 包名 | 能力 |
|---|---|---|
| `dsh-tool-shizuku` | `@deepseek-ai/dsh-tool-shizuku` | **特权 shell**：任意系统命令（`pm` / `am` / `settings` / `dumpsys` / `input` / `screencap`…），异步执行 + 环境消毒 + dex 只读自愈 |
| `dsh-tool-android` | `@deepseek-ai/dsh-tool-android` | **结构化系统操作**：包管理（安装/卸载/清数据/授权）、应用管理（启动/强制停止）、系统设置读写、截图、模拟输入（点击/滑动/文本/按键） |

> 有了这两个插件，AI 不再只是"聊天"，而是能**真正操作你的手机**——这也是本项目和纯聊天 App 的核心区别。

## 如何启用

插件本身是 npm 包（`defineTool` 注册），由 DSH 组合配置 `config/cordis.patch.yml` 的 `insert:` 注入：

```yaml
- insert:
    - id: tool-shizuku
      name: '@deepseek-ai/dsh-tool-shizuku'
    - id: tool-android
      name: '@deepseek-ai/dsh-tool-android'
```

- 开发时：包放在 `dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/` 下，并在 dsh 的 `package.json` dependencies 里声明
- 打包时：随 `dsh-patches/overlay/` + `apply.sh` 归档进源码，build.sh 打进 APK

## 审批策略

- 默认：在用户已授权的 Shizuku 通道下**自动执行**（无需逐次审批）
- 恢复逐次确认：给 node 进程设环境变量 `SHIZUKU_APPROVE=ask`，此时 `dsh-tool-android` 的**危险操作**（install/uninstall/clear/grant/revoke/force_stop/put/tap/swipe/text/keyevent）会弹审批框

## Android 兼容三修复（踩坑沉淀）

1. **异步 `spawn` 而非 `spawnSync`**：同步执行会阻塞 node 事件循环，命令期间整个 DSH 后端（HTTP/WebSocket）卡死，前端报 `Failed to fetch`
2. **`sanitizeEnv()`**：剥离 `LD_LIBRARY_PATH / LD_PRELOAD / LD_DEBUG`——DSH 运行时的自定义 `libz.so` 会污染系统 `app_process` 的链接，不剥离报 `cannot find libz.so from verneed[1]`
3. **`ensureDexReadOnly()`**：每次执行前 `chmod 444` rish dex——Android 15 ART 拒绝加载"当前 uid 可写"的 dex（`Writable dex file is not allowed` → Abort）

## 已知限制

- **ColorOS 上 `pm install` 报 binder 限制**：安装 APK 需用户手动点装；`pm clear / uninstall` 可用
- Shizuku shell 权限上限（uid=2000）：不能读其他 App 私有数据、不能改 /system 分区（详见 `docs/legacy/dev-guide-2026-09.md` 第九节）

## 开发提示

- peerDependencies：`@deepseek-ai/dsh-tools`、`@deepseek-ai/cordis`（版本见各 package.json）
- 调试 rish：`app_process -Djava.class.path=<rish dex> /system/bin --system --nice-name=rish rikka.shizuku.shell.ShizukuShellLoader -c "<命令>"`，环境变量 `RISH_APPLICATION_ID=com.deepseek.harness.community`

## 来源与维护（本版 补充，2026-10-14 核对）

本目录是**上游源码的副本**，不是持续自研的插件目录 —— 这里的"上游"指 **Android 上游项目
（`woaiys3/deepseek-harness-android-app`，本仓库按版本同步它的源码）**，而不是 DSH 官方内核。

核对到的事实：

| 事实 | 证据 |
|---|---|
| 本目录 5 个插件随 `3819e7d「v1.19.0：同步源码」` 进来 | 该提交信息列明「相对上一次源码同步（v1.17.3）的主要变化：… 浏览器工具插件：`plugins/dsh-tool-browser/`」「四个插件（accessibility / android / shizuku / vscreen）描述与实现对齐」|
| `dsh-tool-browser` 是**上游 v1.19.0 引入**的（不是本项目新增） | 上面那条同步提交；且它**不在**本仓库 pin 的上游 payload v1.17.3 里（实测条目数 0），也没有对应的自研记录 |
| accessibility / android / shizuku / vscreen 在**上游 v1.17.3 的 payload 里就有** | 实测 pristine payload 的 `dshroot/lib/node_modules/@deepseek-ai/` 下这 4 个目录各 4 条目 |
| 这 5 个包都**不属于 DSH 官方内核** | 官方 `deepseek-ai/deepseek-harness @ dsh-v0.2.0-rc.2` 的 338 个 `@deepseek-ai/*` 包里，`dsh-tool-*` 一族只有 ask-user / bash / fs / web / terminal / todo 等 24 个，android / shizuku / accessibility / vscreen / browser 一个都没有；npm 上 `@deepseek-ai/dsh-tool-browser` 也是 404 |
| 上游最新 Release 是 **v1.19.0**（2026-10-05），本仓库 pin 的 payload 是 **v1.17.3**（2026-10-03） | GitHub Releases API 实测 |
| 本仓库在上游实现上做过**改进与补充**（不是纯副本） | 例：`dsh-tool-android` 的 v1.24 气泡补丁（`android-app/build.sh` 的「插件同步」自检标记）；`CHANGES.md` 里历次插件侧改动 |

**维护不变量**：`plugins/` 是这些插件在**本仓库的事实标准**——构建（`android-app/build.sh` 的
「插件同步」段）会用 `plugins/dsh-tool-*` 覆盖内核树里同名包（hoisted 与 dsh 内嵌套两处）。
因此上游再更新这些插件时，必须重新同步进 `plugins/`；否则构建出来的包仍是本目录里的旧副本。

> 说明：上文第 1 节「本项目给 DeepSeek Harness 增加的**两个** Android 专属能力插件」是早期说法
> （本项目早期是独立 Android 构建，后来转为跟随上游源码按版本同步）。**维护者已澄清的真实情况是：
> 这些插件包的源码大部分来自上游仓库，本仓库在其上做了改进与补充**（如 `dsh-tool-android` 的
> v1.24 结构化气泡补丁 —— 构建自检就盯着 `v1.24 bubble: 结构化 state/tool` 这个标记；
> 以及 `plugins/` 里对上游实现的若干修复）。归属上有疑问时，以本节的实测事实 + 上游对应版本源码为准。
