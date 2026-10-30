# 排查（用户版 + 开发版）

## A. 用户可自助的部分

### 现象 1：安装后点开就闪退 / 秒退

- 确认版本是**最新 Release**；换版本前先**卸载旧的**再装。
- 装错架构也会闪退：**真机装 arm64**，**模拟器装 x86_64**（x64 包有 `ndk_translation`，装真机反而起不来）。

### 现象 2：一直“正在启动” / 转圈 / 白屏

- **首次启动要解压运行时（1–3 分钟，2 万+ 文件）**：进度走完前**别切后台、别锁屏**；
- 解压中断 → 杀掉 App 重开；看门狗每 5 秒检查引擎并自动重试；
- 仍失败 → 控制台「运行环境 → 重新解压」。

### 现象 3：页面显示「无法访问此网站 / ERR_CONNECTION_REFUSED」

引擎（node）没起来，按顺序查：

1. **电池优化**没设「不限制」→ App 切后台被杀，引擎随之死；
2. **解压不完整** → 清除数据重装（会重置设置，需重填 API Key）；
3. **内存不足**（模拟器常见）→ 提高 `hw.ramSize`，别同时开虚拟屏 + 浏览器同屏；
4. 只看状态不够，抓日志：`adb logcat -s DeepSeekHarness`，找 `engine died: … exit=9`（被系统杀）或 `payload integrity DRIFT …`。

### 现象 4：AI 无法操作手机

- 需要 **Shizuku / root** 或 **无障碍**二者之一；控制台「授予权限」页会显示四态；
- Shizuku 授权后若显示“待授权”，点一次「开启」会弹授权框；
- 无障碍被系统回收（升级 App 后常见）→ 重新开启，App 会提示「无障碍权限需要重新授权」。

### 现象 5：发消息报 `transport failed: fetch failed`

**先测网络**，不要怀疑服务端：

```bash
adb shell "toybox nc -z -w 5 api.deepseek.com 443 && echo NET_OK || echo NET_FAIL"
```

`NET_FAIL` 多半是模拟器/手机没网（飞行模式、Wi-Fi 未连）。网络通了仍失败就让引擎重启一次。

### 现象 6：「Sign in」只弹出空白页

已修（1.21.2+）。旧版本可用弹窗里的「Copy sign-in link」复制到浏览器完成登录；
登录完成后 DSH 会自动同步登录态。

## B. 开发侧已知坑（写根因，方便下次秒判）

| 症状 | 根因 | 结论 |
|---|---|---|
| 改了前端补丁没生效 | 覆盖白名单路径与设备实际路径不一致（多一层 `dsh/node_modules`） | 白名单必须逐字符对；`adb shell su 0 grep -c <独特串> <设备文件>` 验证 |
| 引擎“未运行”但实际在跑 | 用 HTTP 探测判活，引擎忙时 1.2s 超时 | 统一 `portListening()`（TCP 400ms）；HTTP 只用于“端口上是不是 DSH” |
| 看门狗反复重启引擎（堆出多个 node 进程） | 同上；探测失败即判死 | 判活加端口兜底 + 连续 3 次失败才重启 |
| 会话中途 agent 无响应、之后“引擎未运行” | 内存不足被 lmkd 杀（模拟器 2GB） | `hw.ramSize=4096`；日志 `exit=9/137（疑似被系统杀掉）` |
| 悬浮窗在 App 内不隐藏 | 前台判定只认 `IMPORTANCE_FOREGROUND(100)`，实际常是 `VISIBLE(200)` | 判据改 `importance <= IMPORTANCE_VISIBLE` |
| 悬浮窗“消失” | 窗口被 `removeView` 后没补挂 / `GONE` 视图被测量成 0 | 窗口常驻 + 丢失补挂；不要根据布局前的 `getWidth()` 判断尺寸 |
| 首次安装报 `DRIFT 别名缺失 162/163` | 首次解压时个别 soname 别名瞬时失败 | 完整性校验里自愈：重跑 `applyLinks` 再复算 |
| 虚拟屏预览窗闪烁 | 一次探测失败就收窗 | 连续 2 次未运行才收 |
| `contenteditable` 写不进字 | WebView 不实现 `ACTION_SET_TEXT`/`ACTION_PASTE` | 有特权用 `input keyevent 279`（先 tap 聚焦）；无特权只能用户手输 |
| 登录弹 `about:blank` | `window.open('about:blank')` 被 shim 接管并返回 `null` | 占位窗口返回代理对象，`location.replace` 直接交系统浏览器 |
| 白名单改完仍不生效（缓存） | WebView 可能用缓存的 `mobile.js` | 重装 APK 或清 WebView 缓存后重启 App |

## C. 取证清单（提 Issue / 让 agent 排查时附上）

```bash
adb shell dumpsys package com.deepseek.harness.community | grep versionName
adb logcat -d -s DeepSeekHarness | tail -50
adb shell "su 0 cat /data/user/0/com.deepseek.harness.community/files/integrity.txt"
adb shell "su 0 tail -50 /data/user/0/com.deepseek.harness.community/files/dsh-web.log"
```
