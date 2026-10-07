# 合并结果与验证（sync/upstream-v1.19.0）

> 工作区：`C:\Workspace\dsh-upstream-merge`（**独立本地克隆**，主仓库零改动）
> 分支：`sync/upstream-v1.19.0` · 合并提交：**`764becc`**

## 一、合并范围

| 项 | 值 |
|---|---|
| 来源 | `upstream/main` = `a770a55`（含 `3819e7d` "v1.19.0" 同步）|
| 共同祖先 | `0665962`（v1.17.3 同步点）|
| 吸收后相对我们 master | **33 文件变更，+9385 / −1170** |
| 冲突 | 6 文件 / 11 处 → **全部手工解决** |

## 二、吸收到的上游能力（自 `0665962` 以来）

1. **AI 浏览器**（`:browser` 独立进程 + 10 个 `browser_*` 工具 + 多页签 + 结构 DOM/稳定 ref/差分）
   → `BrowserHost/BrowserIpc/BrowserService.java` + `plugins/dsh-tool-browser`
2. **本地服务令牌闸门**（`X-DSH-Token`，fail-closed）+ 虚拟屏 jar SHA-256 校验 → `LocalAuth.java`
3. **内核自检 + 自愈账本 + 会话管理/自愈**
   → `KernelSelfCheck/PayloadScript/SessionHeal/SessionAdmin.java` + `tools/{manifest-gen,session-*,…}.mjs`
4. **控制台统一版式 + 深浅色调色板**（`appearance.dark` / `colors` / `perScheme.light|dark`、统一 `c*()` 出口）
   → `ConsoleTheme.java` + `console-theme/*` + 控制台页面
5. 插件小修（accessibility / android / shizuku / vscreen）+ 构建接线（manifest service、javac 源列表）

## 三、冲突处理明细（11 处）

| 文件 | 处数 | 处理 |
|---|---|---|
| `MainActivity.java` | 2 | ① `onDestroy`：**两边都留**（我们的 `hiddenPopups` 清理 + 他们的 `browserIpc.release()`）<br>② `renderConsolePerm`：**采用他们的分组版式**（`t()` 文案 + `cGrpTitle/cCardBox`），**插回**我们独有的「读取应用列表」行 |
| `build.sh` | 2 | ① **两边都留**（他们的内核清单/自愈/会话管理块 + 我们的真 bash 块）<br>② javac 源列表自动含全部新类 |
| `AndroidManifest.xml` | 2 | 保留我们的版本号/`QUERY_ALL_PACKAGES`/注释；`<service>`（`:browser`）由自动合并并入 |
| `compat/AndroidManifest.xml` | 1 | 保留我们的 |
| `README.md` | 3 | **全保留我们的**（社区分支视角）|
| `CHANGES.md` | 1 | 两边都留（我们的 v1.18/社区段 + 他们的 v1.19.x 段）|

## 四、明确不采纳（设计冲突）

- 上游 `versionCode/versionName`（54 / 1.19.0）→ 我们走自己的版本线
- 上游 `README.md` 全文
- 上游 `MainActivity` 中与我们重写部分重叠的设计（控制台骨架 / 引导页流程 / 悬浮窗布局 / 看门狗判定）
- 上游 Release 资产（official / lite / compat APK）

## 五、验证结果

| 验证 | 方法 | 结果 |
|---|---|---|
| Java 语法自检 | `tools/java-syntax-sanity.py`（10 个关键文件）| **全部 OK** ✅ |
| **真实编译** | 按 `build.sh` 的 staging 规则（`package`/`import` 改写为 community）后，用 `javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath android.jar(36) -classpath shizuku{api,provider,aidl}.jar` 编译 21 个源文件 | **0 错误 / 11 警告（均为无害）· 产出 430 个 class** ✅ |
| 产物核对 | 8 个新类 + 浏览器插件均在树内 | ✅ |

> 编译输出里的 11 条是警告：`源值/目标值 8 已过时`（JDK 21 提示）、`BrowserService.java:29 多余分号`（上游自带 `import …;;`）、6 条 `androidx.annotation.RestrictTo` 缺失（我的临时 classpath 少 androidx 注解包，正式构建的 classpath 里有）。

## 六、已完成：壳级 UI 统一（v1.21 · 第二轮）

> 提交 `e9d2ef2`（在合并 `764becc` 之后）

### 1. 壳级深浅色：三源合一
- 新增偏好 `ui_scheme`（`follow` / `light` / `dark`）
- `shellDark()` 判定顺序：① 用户显式选择 → ② **DSH 页面实测底色** → ③ 系统深浅色
- `conDark()`（控制台配色方案）改为走 `shellDark()`；`applyShellPalette()` 把壳配色应用到
  **启动页 / 引导页 / 主界面 + 系统栏**，两处 `pageBgColor` 回调都接上（页面换色调 → 壳跟着换）
- 修掉割裂：以前只看系统，"系统浅色 + DSH 用深色主题"时壳亮页面暗

### 2. 与 DSH 页面同色调
- `cBg()` 默认值改为 `pageBgColor`（已知时）→ 壳底色直接跟随网页实测底色；
  主题里显式写过的 `bg` 仍优先（`conColor` 保证）

### 3. 三个页面的打磨
| 页面 | 改动 |
|---|---|
| **引导页** | 顶部新增**细进度条**（轨道 + 强调色填充，按步数走）；权限页内容改**卡片式**（状态 + 授权按钮同卡片）；完成页新增**「界面外观」三选一** |
| **启动等待页** | 圆环颜色 `cAccent()`（原来写死 `#4d6bfe`）；状态文字改 `cText()`（与副提示分层次）；进入时刷新系统栏 |
| **控制台** | 「主题」页新增「界面外观（壳）」分组（与主题包 `appearance.dark` 区分）；次按钮底色改为从 `cAccent()` 推导 |
| 换方案时 | `refreshGuideUi()` **整页重建**引导页并保持当前步数（否则头部标题会留旧方案的颜色） |

### 4. 模拟器实测（截图见 `.cache/ui-*.png`）
- 引导页浅色：进度条 1/12 + 卡片式权限块 ✅
- 引导页深色：点「深色」后整页（含头部标题）同步换色 ✅
- 完成页：「界面外观」三选一显示正确、选中态强调色 ✅
- 启动等待页深色：状态文字主色 + 圆环强调色 + 系统栏一致 ✅
- 构建：**BUILD OK**（community x64，隔离工作区内）

### 5. 顺带修掉的合并遗留
- `build.sh` 的 javac 参数：合并时把"我们的 staged 源码 + 变体 R.java"与"上游的原始源码 +
  official R.java"两套都留下了 → 找不到 `out/gen/com/deepseek/harness/R.java` 而构建失败。
  上游那套是因为他们没有变体改名机制；本仓库用我们的写法（已删掉上游那两行）。
- 新增 `tools/build/build-local.sh`（路径由脚本自身位置推导）：`DSH_DEV_HOME` 用克隆内缓存副本、
  输出固定为克隆内 `-x64.apk`，**绝不写主仓库**。

## 七、尚未做

1. **版本号**：抬到 `1.17.6` / versionCode `55`
2. **真机 arm64 构建 + 验证**（隔离工作区里已有 x64 产物；arm64 需在克隆内再跑一次 arm64 入口）
3. **控制台在深色下的整页走查**（本轮验证了引导页/启动页与配色机制，控制台沿用同一套 `c*()`）
4. 决定何时把这套合并 + UI 改动**应用回主仓库**（需与做冷启动分析的 agent 协调，避免抢 `MainActivity`）

