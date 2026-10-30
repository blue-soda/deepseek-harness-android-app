# 运维手册（设备侧）

## 1. 端口与进程

| 服务 | 端口 | 由谁提供 | 说明 |
|---|---|---|---|
| DSH 引擎（node） | **3086** | `EngineService` 拉起的 node | Web UI / API 都在这 |
| 本地通知/设置/剪贴板 HTTP | **3087** | `MainActivity` 内置 HTTP | 令牌鉴权（`local_token`） |
| 无障碍屏幕助手 | **3187** | `AccessibilityService` | `/status /dump /screenshot /tap /input …` |
| 虚拟屏桥（App 内代理） | **9029** | `VsreenBridgeService` | 转发到核心，并做本地鉴权 |
| 虚拟屏核心（特权进程） | **9028** | root/Shizuku 拉起的 java 进程 | 抓帧 + `input -d` 注入 |

```bash
adb shell "grep -c ':0C0E' /proc/net/tcp"     # 0C0E = 3086 十六进制（含 LISTEN/ESTABLISHED/TIME_WAIT）
adb shell "ps -A | grep -E 'binfmt_misc_arm64|bin.js'"   # 引擎进程
adb shell "ps -A | grep shizuku_server"                   # 特权通道
```

> **判活统一用 TCP 端口连通性**（400ms connect），不要只用 HTTP 探测：
> 引擎跑复杂任务时 HTTP 会 1.2s 超时 → 误判“未运行” → 看门狗反复重启（真实事故）。

## 2. 令牌与鉴权

```bash
adb shell "su 0 cat /data/user/0/com.deepseek.harness.community/shared_prefs/dsh_prefs.xml" | grep local_token
```

- 本地 HTTP（3087 / 9029）都要带 `x-dsh-token`（实现见 `LocalAuth`）；
- WebView 首次加载用的是带 token 的 URL（日志 `engine token url captured`）。

## 3. 重启与恢复

| 目标 | 做法 |
|---|---|
| 只重启引擎 | 控制台「重启」；或 `adb shell am force-stop <pkg>` 后重开 App |
| 完全退出（含引擎/悬浮窗/无障碍） | 悬浮窗面板「退出」（两步确认）→ `shutdownEverything()` |
| 重装但保留数据 | `adb install -r <apk>` |
| 彻底从零 | `adb uninstall <pkg>` → 安装 → 首次启动重新解压 |
| 冷启动慢 | 首次解压 1–3 分钟；后续启动若清单一致会走“快速同步”（几秒） |

## 4. payload 完整性自检

设备会在启动/控制台刷新时校验 `runtime/` 与随包清单：

- 正常：日志 `payload integrity OK runtime files=… 别名=…`，`files/integrity.txt` 为 `OK …`；
- 异常：`DRIFT …`（文件数/字节/别名不符）。**别名缺失会自动自愈**（重跑 `applyLinks` 并复算）；
- 兜底：控制台「运行环境」页有「重新解压」入口；实在不行 `adb uninstall` 重装。

```bash
adb shell "su 0 cat /data/user/0/com.deepseek.harness.community/files/integrity.txt"
```

## 5. 特权通道（root / Shizuku）

- App 通过环境变量 `ROOT_AVAILABLE` / `SHIZUKU_AVAILABLE` 决定是否启用特权工具；
- **Shizuku 授权一次即持久**，但**服务重启后需要重新拉起 server**；
- 模拟器上 `su` 只在 `adb shell` 可用（App 自己拿不到 root），因此模拟器要用 Shizuku；
- 拉起脚本：`tools/emu-shizuku.sh`（推送并在设备上 `su 0` 执行）；
- 秘钥授权状态可在控制台「授予权限」页看到四态：root 可用 / Shizuku 已授权 / 待授权 / 不可用。

## 6. 日志与导出

| 入口 | 路径 |
|---|---|
| 控制台「日志」 | `files/dsh-web.log`、`web-notes.log`，可一键分享 |
| 外部可见副本 | `/sdcard/DeepSeekHarnessCommunity/*.log` |
| 崩溃 | `/sdcard/DeepSeekHarnessCommunity/crash.log`（若存在） |

## 7. 常见设备侧事故与处置

| 现象 | 原因 | 处置 |
|---|---|---|
| 控制台显示“引擎未运行”但实际在跑 | 旧逻辑用 HTTP 探测 | 已改为 TCP 判活；若仍出现，抓 `adb logcat -s DeepSeekHarness` 找 `applyVisibleNow`/`engine died` |
| 会话中途 agent 无响应 | 引擎被系统杀掉（内存不足） | 提高 `hw.ramSize`（模拟器）或关掉电池优化；`engine died: … exit=9（疑似被系统杀掉）` 即此因 |
| 悬浮窗在 App 内不隐藏 | 前台判定用到 `IMPORTANCE_FOREGROUND` | 已改为 `<= IMPORTANCE_VISIBLE`；`adb logcat -s DSHOverlay` 看 `fgHide` |
| 前端改了没生效 | 白名单路径不含该文件 | 见 `docs/STRUCTURE.md` 的“白名单”约定 |
| 登录只弹空白页 | `window.open('about:blank')` 被 shim 抢走返回 null | 已修（占位窗口返回代理）；见 `mobile-patch/mobile.js` |
