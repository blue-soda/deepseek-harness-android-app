# 上游 v1.19.0 合并计划（独立工作区）

> 本文件位于**独立克隆** `C:\Workspace\dsh-upstream-merge`，不在主仓库里 —— 避免与正在做冷启动分析的 agent 冲突。

## 0. 基本事实

| 项 | 值 |
|---|---|
| 我们的基线 | `master` @ `9412847`（v1.17.5 / versionCode 54）|
| 共同祖先 | `0665962`（上游 v1.17.3 同步点）|
| 上游新提交 | `3819e7d`（v1.19.0：同屏可拖可点 + 控制台全自定义全适配 + 会话管理/自愈）、`a770a55`（README 下载页链接）|
| 上游最新 Release | `v1.19.0`（内核仍是 DSH 0.2.0-rc.2，与我们相同）|
| 我们相对祖先 | 87 个提交 |

## 1. 干跑合并结论（`git merge --no-commit --no-ff upstream/main`）

- 变更文件 **34 个**，其中 **28 个可自动合并** ✅
- **冲突 6 个文件 / 共 11 处冲突块**，其中**只有 2 处在真正代码里**（都在 `MainActivity.java`）

### 1.1 可自动合并（28 个，全部吃下）

| 分类 | 文件 |
|---|---|
| 新类 | `BrowserHost.java`、`BrowserIpc.java`、`BrowserService.java`、`KernelSelfCheck.java`、`LocalAuth.java`、`PayloadScript.java`、`SessionAdmin.java`、`SessionHeal.java` |
| 工具脚本 | `android-app/tools/{manifest-gen,session-admin,session-heal}.mjs`、`tools/manifest-gen.mjs` |
| 插件 | `plugins/dsh-tool-browser/*`（新）、`plugins/dsh-tool-{accessibility,android,shizuku,vscreen}/lib/index.js`（小改）|
| 控制台主题 | `console-theme/{console.schema.json,test-checker.py,theme_pack_check.py,THEME-PACK-SPEC.md}` |
| 其它 | `config/cordis.patch.yml`、`android-app/src/com/deepseek/harness/{AccessibilityService,ConsoleTheme,VsreenBridgeService}.java`、`vscreen/Main.java`、`android-app/tools/…` |

### 1.2 冲突清单与处理决定（**这里就是"明确列出"的部分**）

| 文件 | 冲突块 | 处理 | 理由 |
|---|---|---|---|
| `android-app/src/com/deepseek/harness/MainActivity.java` | **2** | **手工合并**：<br>① `onDestroy()`（行 ~5663）：保留我们"清理插件弹窗隐形 WebView"的清理逻辑，同时接纳他们的销毁顺序<br>② 控制台"授予权限"页（行 ~9810）：以他们的**统一版式**为准，但保留我们的**工作区/诊断行** | 我们重写过控制台/引导页/悬浮窗/看门狗，这是唯一真正交叠的地方 |
| `CHANGES.md` | 1 | 保留我们的结构，**追加**他们 v1.19.0（v1.18.0+v1.19.0+v1.19.1）条目 | 我们的是社区分支视角 |
| `README.md` | 3 | **全保留我们的**（社区分支视角 + 切 master 提示） | 刚按维护者要求重写 |
| `android-app/AndroidManifest.xml` | 2 | 保留我们的版本号/主题/变体配置，**新增**他们的 `<service>`（浏览器服务，`:browser` 进程）| 版本号我们保持自己的编号 |
| `android-app/compat/AndroidManifest.xml` | 1 | 保留我们的 | 同上 |
| `android-app/build.sh` | 2 | 保留我们的变体/arm64 校验/防 x64 污染逻辑，**追加**他们打包浏览器与插件资源的步骤 | 我们的构建保护是踩坑换来的 |

## 2. 设计冲突（**不做**的部分，明确列出）

| 不采纳项 | 原因 |
|---|---|
| 上游的 **`versionCode/versionName`**（54 / 1.19.0）| 我们走自己的版本线（当前 1.19.0 / 55 —— 与上游最新 Release 对齐）|
| 上游 **README.md** 全文 | 与我们的社区分支定位冲突 |
| 上游 `MainActivity` 里**与我们重写部分重叠的设计**（控制台骨架、引导页流程、悬浮窗布局、看门狗判定）| 保留我们的实现；只吸收他们的**独立新增**（新类/新页面/新工具）|
| 上游 `.github/workflows` 之类 CI（若有）| 不影响 APK，暂不引入 |
| 上游 Release 资产（official/lite/compat APK）| 与我们无关 |

## 3. 顺带要抄的 3 个 UI bug 修复（我们**已确认存在**）

| 修复 | 我们的现状 | 位置 |
|---|---|---|
| `console.json` **缺 `appearance`** 时 `layout/text/behavior/actions` 被静默丢弃 | 确认存在：`ConsoleTheme.parse()` 里 `Object av = o.opt("appearance"); if (av == null) return;` | `ConsoleTheme.java` |
| 控制台**每秒刷新链**异常后断掉（无 try/catch 续链）| 确认存在：`consoleTick` 末尾才 `postDelayed` | `MainActivity.java` ~236 |
| 换页**继承上一页滚动位置** | 很可能存在：全仓无 `scrollTo/fullScroll/setScrollY` | `MainActivity.java` 控制台换页处 |

（另外他们修了"深色主题下日志弹窗浅底浅字"——我们需真机确认；以及"长正文弹窗把按钮挤出屏幕"——我们弹窗内容都在 ScrollView 里，大概率没有。）

## 4. 关于维护者的 UI 愿望

| 页面 | 上游是否已做 | 结论 |
|---|---|---|
| **控制台** | ✅ **做了**：`appearance.dark: follow/light/dark`（与控制台方案解耦，不跟系统）、`colors` + `perScheme.light/dark` 两套调色板、统一的 `c*()` 配色出口、修了"浅底浅字" | **直接吃上游**（含 `ConsoleTheme.java` + 控制台配色调用点）|
| **权限引导页** | ❌ 基本没做（无深浅色逻辑）| **我们自己做** |
| **启动等待页** | ❌ 只支持"主题 logo 覆盖"（`conThemeApplyLogo`），配色不跟控制台调色板 | **我们自己做** |
| "与 DSH 页面同色调" | 上游无此目标 | **我们自己做**（可复用现有 `refreshPageBackground()` 采样 DSH 页面底色的能力，取主色/底色做壳的主题）|

→ 规划：**先合并（本轮）**，再做一次**独立 UI 改版**：把控制台的调色板（`perScheme`）抽成壳级主题，让**控制台 / 引导页 / 启动等待页**共用同一套 light/dark 色，并与 DSH 页面的色调对齐；引导页与启动页补上 `Configuration.UI_MODE_NIGHT_*` 跟随 + 手动切换入口。

## 5. 合并后的 TODO（按顺序）

1. 解析 11 处冲突（只有 2 处在代码里）
2. **编译**：`bash android-app/build.sh`（社区变体）→ 逐个修编译错误（预期集中在我们与他们的控制台入口差异）
3. **版本号**：抬到 1.19.0 / versionCode 55（与上游最新一致）
4. **包内校验**：`tools/diag/check-abi.sh`（payload 架构干净、内置插件版本、提问气泡状态）
5. **模拟器验证**：引擎起得来、控制台五页能进、内核自检能跑、浏览器工具出现（如启用）
6. **真机验证**：权限引导、悬浮窗、退出、remote 登录
