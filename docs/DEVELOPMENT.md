# 开发迭代指南

## 1. 常见改动的落点

| 想改什么 | 改哪里 | 注意 |
|---|---|---|
| 启动页 / 控制台页面（原生 UI） | `android-app/src/.../MainActivity.java` | 控制台是**手写 View 树**：分组顺序在 `SIMPLE_GROUPS`，卡片工厂 `cCardBox/cCardRow/cButton`，页面常量 `PAGE_*` |
| 悬浮窗（小人/气泡/面板） | `OverlayService.java` | 可见性由 `applyVisibleNow()` 单点决定；停靠/透明度有偏好项 |
| 无障碍工具后端 | `AccessibilityService.java` | HTTP 端点：`/status /dump /screenshot /tap /input /scroll /touch /back /home …` |
| 虚拟屏预览 | `VsreenBridgeService.java` | 预览窗顶部小条=拖动搬窗，右侧圆形「—」=收起 |
| AI 工具（模型看到的 44 个工具） | `plugins/dsh-tool-*/lib/index.js` | 插件是**单一真源**，构建时覆盖进 payload；改完 `node --check` 再构建 |
| 移动端前端行为/样式 | `mobile-patch/mobile.js` / `mobile.css` | 见下方“前端补丁” |
| 控制台主题 | `console-theme/` | 规范见 `console-theme/THEME-PACK-SPEC.md` |
| 内核补丁 | `dsh-patches/` | `apply.sh` 在构建期打补丁；overlay 是快照式替换 |

## 2. 前端补丁（最容易踩坑）

`mobile-patch/mobile.js` 是**注入进页面执行**的脚本（`inject.sh` 复制进 web dist 并在 `index.html` 里加 `<script>`），
用“运行时 monkey patch”补移动端缺失能力，例如：

- `Iterator` polyfill（老 WebView 缺 ES2025，否则整个前端白页）；
- 软键盘适配、回车换行 / Ctrl+Enter 发送；
- 禁双指缩放（viewport 补丁）；
- `window.open` 接管：插件/登录的授权页交系统浏览器；
- `Intl` 时区兜底（ROM 报 `GMT` 会被内核拒收 → 会话建不起来）；
- 前端报错 `fetch`/`onerror` 埋点到 `web-notes.log`。

**两条铁律**：

1. **白名单**：`MainActivity` 里有一份“必须随 APK 覆盖”的路径白名单，必须包含你改的文件
   （`dsh-web-frontend/dist/mobile.js` 等）。**路径写错 → 补丁永远不生效**（真实事故：
   白名单多了一层 `dsh/node_modules`，改了三个月都没上设备）。
2. **不要随便返回 `null`**：`window.open` 之类的 API，页面会拿返回值继续用
   （`this.popup = window.open(...)` 后再 `popup.location.replace(...)`）；
   返回 `null` 等于让页面走“弹窗被拦截”分支。

## 3. 改 Java 的流程

```bash
# ① 语法体检（括号/大括号失衡最常见，javac 报错在生成文件里很难定位）
python tools/java-syntax-sanity.py android-app/src/com/deepseek/harness/MainActivity.java

# ② 构建 x64 并装模拟器验证
bash tools/build-apk.sh --emulator --out .cache/out/x64.apk
adb install -r .cache/out/x64.apk

# ③ 看日志
adb logcat -s DeepSeekHarness DSHOverlay VsreenBridge
```

构建脚本自带 **ESM 闸门** 与 **Java 语法体检**，失败会直接中止（日志里有具体文件与行号）。

## 4. 改插件（AI 工具）的流程

```bash
node --check plugins/dsh-tool-android/lib/index.js       # 先过语法
bash tools/build-apk.sh --emulator --out .cache/out/x64.apk
adb install -r .cache/out/x64.apk                        # 插件随 payload 一起换
adb shell "su 0 grep -c '<新代码里的独特字符串>' \
  /data/user/0/com.deepseek.harness.community/files/payload/dshhome/…/dsh-tool-android/lib/index.js"
```

约定：

- 工具名与描述要**说清适用边界**（例：`type` 明确写“WebView 用 mode=paste”）；
- 失败要**如实回报**（`method: "set(failed)"`），不要报“已写入”；
- 不要把“需要特权才能做的事”写成“不需要”。

## 5. 版本与提交

- 版本号只在 `android-app/AndroidManifest.xml`（`versionCode` / `versionName`）；
- 提交信息用 `<type>(<scope>): <中文简述>`，例如
  `fix(overlay): 修「在 App 内不隐藏」——前台判定改用 IMPORTANCE_VISIBLE`；
- 一次改动一个主题，便于回滚（`git revert <sha>`）。

## 6. 文档要求

- 改了行为 → 同步改 `docs/` 下对应文档；
- 修了真实事故 → 在 `docs/OPS.md` 或 `docs/TROUBLESHOOTING.md` 的“已知坑”里补一条（写根因，不写流水账）。
