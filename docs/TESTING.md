# 测试方法（模拟器 / 真机 / 工具回归）

> 原则：**先取证再下结论**。每条“失败”都要有日志或截图证据；不确定就写“未验证”。

## 1. 模拟器测试（最常用）

```bash
# ① 起模拟器（AVD 名写死在脚本里；无窗口库时加 --no-libs）
bash tools/emu-start.sh Pixel_10_Pro --no-libs

# ② 装包（x86_64 包才装模拟器）
bash tools/build-apk.sh --emulator --out .cache/out/x64.apk
adb install -r .cache/out/x64.apk

# ③ 起 Shizuku（模拟器 su 只在 adb shell 可用，App 拿不到 root）
adb shell su 0 sh /data/local/tmp/start_shizuku.sh   # 该脚本由 tools/emu-shizuku.sh 推送

# ④ 开无障碍 + 悬浮窗权限
adb shell "su 0 settings put secure enabled_accessibility_services \
  'com.deepseek.harness.community/com.deepseek.harness.community.AccessibilityService'"
adb shell appops set com.deepseek.harness.community SYSTEM_ALERT_WINDOW allow
```

其它常用操作：

| 目的 | 命令 |
|---|---|
| 改分辨率（临时，重启失效） | `adb shell "wm size 640x1280"`；还原 `wm size reset` |
| 永久改分辨率/内存 | 改 `~/.android/avd/<AVD>.avd/config.ini` 的 `hw.lcd.*` / `hw.ramSize` 后重启模拟器 |
| 跳过首次引导（省 9 步） | `tools/emu-skip-setup.sh`（写 `setup_done=true` 后重启 App） |
| 关掉 App | `adb shell am force-stop com.deepseek.harness.community` |

> 模拟器内存建议 **4GB**（`hw.ramSize=4096`）：2GB 时引擎+虚拟屏并发容易被 lmkd 杀掉。

## 2. 日志取证（按标签过滤）

```bash
adb logcat -s DeepSeekHarness          # Java 外壳：引擎拉起/看门狗/控制台/插件同步
adb logcat -s DSHOverlay               # 悬浮窗可见性判定（show/fgHide/userHidden/坐标）
adb logcat -s VsreenBridge             # 虚拟屏预览窗与代理
adb logcat | grep -E 'node:|dsh'       # 引擎（node）侧日志
```

| 日志文件 | 内容 |
|---|---|
| `files/dsh-web.log`（+ `/sdcard/DeepSeekHarnessCommunity/dsh-web.log`） | 引擎 stdout/stderr，排查启动失败首选 |
| `files/web-notes.log` | 前端 `console.error` / `fetch` 非 2xx / 未捕获异常（`mobile.js` 埋点） |
| `files/integrity.txt` | 上次 payload 完整性结论（`OK` / `DRIFT …`） |

**页面内取证（CDP）**：WebView 开了 devtools socket，可以直接在页面里跑 JS 验证 Web API：

```bash
adb shell cat /proc/net/unix | grep webview_devtools     # 找到 socket 名
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
# 然后连 http://127.0.0.1:9222 用 CDP 执行 JS（验证 getUserMedia / window.open 之类）
```

## 3. 工具回归（AI 侧 44 个工具）

工具分 5 个插件（见 `plugins/README.md`）。回归时按能力分组，**每组至少一个正向用例 + 一个失败用例**：

| 组 | 关键点 |
|---|---|
| `android_*`（10） | 应用列表/启动、通知（应回 `visible:true`）、系统设置读写、剪贴板读写还原、APK 信息 |
| `android_accessibility_*`（15） | 截图/找文字/点击/滑动/返回桌面、`type`（原生 EditText 可写；**contenteditable 不行**，见下） |
| `shizuku_shell`（1） | `id` 应为 uid=0 |
| `android_vscreen_*`（8） | create → launch → see → tap/swipe/key → close；主屏不受影响 |
| `browser_*`（10） | open/snapshot/find/click/read/tabs/nav（页面区 DOM 操作） |

已知**设计限制**（不是 bug，别当回归失败报）：

- **`contenteditable`（DSH 聊天框、Slack 类）无法用无障碍写入** ✗：`ACTION_SET_TEXT`/`ACTION_PASTE` 都返回 false。
  有特权时用 `shizuku_shell input keyevent 279`（粘贴剪贴板，支持中文）或 `input text <ASCII>`，
  且**必须先 tap 让输入框获得真实键盘焦点**。普通 `<input>`/`<textarea>` 用 a11y 可以写。
- `android_accessibility_wait_stable` 对**流式界面必然超时**（聊天/日志/动画一直变），要看界面用 `see`/`find_text`。
- `android_vscreen_see` 没有 `region` 参数（传了会被忽略）；省内存请开小尺寸虚拟屏。

## 4. 关键专项测试清单

| 场景 | 步骤 | 通过标准 |
|---|---|---|
| 首次安装（解压） | 卸载 → 安装 → 启动 | 完成解压；`integrity.txt` 为 `OK`；能进主界面 |
| 引擎就绪 | 启动后发一条消息 | 引擎忙时控制台/悬浮窗状态不应误报“未运行” |
| 悬浮窗显隐 | 控制台关闭「本应用内显示」→ 回桌面 → 回 App | 桌面显示、回 App 隐藏（日志 `fgHide=true`） |
| 虚拟屏 | 控制台开启 → 预览窗 → 操作 App → 关闭 | 预览窗不闪烁；关闭后状态回「未开启」 |
| 登录 | 点「Sign in」 | 系统浏览器打开**真实授权地址**（不是 `about:blank`） |
| 麦克风 | 网页里 `getUserMedia({audio:true})` | 成功拿到 track（需 `RECORD_AUDIO` + 清单里的 `MODIFY_AUDIO_SETTINGS`） |

## 5. 真机测试注意

- 装 **arm64** 包（`--emulator` 的包只能装模拟器）；
- 首次启动解压 1–3 分钟，**别切后台/锁屏**；
- 电池优化设为「不限制」，否则系统会杀进程（引擎跟着死）；
- Shizuku 需自行安装并授权，或改用无障碍模式；
- 抓日志：`adb logcat -s DeepSeekHarness`（大多数问题这一条就够）。
