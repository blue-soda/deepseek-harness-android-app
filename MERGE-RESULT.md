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

## 六、尚未做（下一步）

1. **完整 APK 构建**：克隆里没有 `android-app/out`/`staging`/`.cache`（都 gitignore）→ 需搬工具链或在主仓库应用本合并
2. **版本号**：抬到 `1.17.6` / versionCode `55`
3. **模拟器 + 真机验证**：引擎启动、控制台（含新「AI 浏览器」页）、内核自检、浏览器工具、remote 登录
4. **UI 愿望**（引导页 / 启动等待页的优雅化 + 深/浅色 + 与 DSH 同色调）—— **上游没做**，需我们自己设计：
   - 可复用上游控制台的调色板体系（`colors`/`perScheme`）抽成壳级主题
   - 引导页与启动页补 `Configuration.UI_MODE_NIGHT_*` 跟随 + 手动切换入口
   - 色调对齐 DSH：复用现有 `refreshPageBackground()` 采样 DSH 页面底色的能力
