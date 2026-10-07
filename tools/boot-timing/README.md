# 引擎启动分段计时（boot-timing）

**目的**：回答"引擎启动的 ~30 s 到底花在哪、哪一段值得优化"，并且**不改内核树**（dshroot 一根毫毛都不动），
全部靠 Node 自带能力：同线程模块钩子 + CPU profile。

## 工具

| 文件 | 跑在哪 | 作用 |
|---|---|---|
| `boot-run.sh` | **设备**（root） | 复刻 App 的 argv/env 起一次引擎，按模式加探针；轮询端口得到"spawn → HTTP 就绪"的墙钟 |
| `preload.mjs` | 设备（`--import`） | 注册**同线程** `module.registerHooks()`，每次模块加载记一行（时间/耗时/字节/url） |
| `hooks.mjs` | 设备 | 旧版 `module.register()`（跨线程）回退用 —— ⚠ 见下面"踩坑" |
| `analyze.py` | 主机 | 解析 hooks 日志 + cpuprofile：按包聚合、主线程空档、CPU 自耗时分类排行 |

## 用法

```bash
# 1) 推工具（root；模拟器需 -writable-system，并先补 guest 库：tools/emu-guestlibs.sh）
adb root
adb push tools/boot-timing/preload.mjs      /data/local/tmp/boot-preload.mjs
adb push tools/boot-timing/boot-run.sh      /data/local/tmp/boot-run.sh

# 2) 跑三种模式（每次约 30-40 s；先 force-stop 应用避免抢 CPU）
adb shell am force-stop com.deepseek.harness.community
adb shell sh /data/local/tmp/boot-run.sh com.deepseek.harness.community 3099 plain       # 真实墙钟
adb shell sh /data/local/tmp/boot-run.sh com.deepseek.harness.community 3099 hooks       # 模块时间线
adb shell sh /data/local/tmp/boot-run.sh com.deepseek.harness.community 3099 profhooks   # 模块时间线 + CPU 采样

# 3) 拉回产物并分析
for f in boot-hooks-hooks.log boot-hooks-profhooks.log boot-summary-hooks.json boot-engine-plain.log; do
  adb pull /data/local/tmp/$f .cache/boot-prof/
done
adb pull /data/local/tmp/prof .cache/boot-prof/
python tools/boot-timing/analyze.py > .cache/boot-prof/report.txt
```

## 踩坑（很重要）

- **不要用 `module.register()`（跨线程钩子）做启动计时**：每次模块加载都要和钩子线程同步通信，
  实测主线程 `makeSyncRequest` 自耗 **11.2 s**、`waitForWorker` 1.6 s —— 把 33 s 的启动测成 44 s。
  Node 26 的 `module.registerHooks()`（同线程同步）只多 ~1 s，用它。
- **`--cpu-prof` 只在正常退出时落盘**：裸跑时我们是 `SIGTERM` 直接杀 → profile 是空的。
  所以 `profhooks` 模式带 preload，由它的 SIGTERM 处理器 `process.exit(0)` 走正常退出。
- **`date +%s%N` 在 Android toybox 上不可用**（没有 `%N`）→ 毫秒计时请用 Node 里的 `Date.now()`（hooks 日志就是这么来的）。

## 2026-10-07 实测结论（模拟器 community，1.17.5）

| 模式 | spawn → HTTP 就绪 |
|---|---|
| `plain`（无探针，3 次） | **33 / 26 / 32 s**（≈30 s ±3） |
| `hooks`（同线程钩子） | 34 s |
| `profhooks`（钩子 + 采样） | 35 s |
| 旧 `register`（跨线程钩子） | 44 s ← 探针污染，勿用 |

时间线（1519 次模块加载 / 14.5 MB 源码）：模块加载跨度 29-31 s，其中
**load 钩子（真实读盘+转换）合计仅 944 ms**，而相邻加载之间的"空档"合计 **17.7 s** —— 那才是主线程在
解析/编译/求值/插件初始化。空档是**长尾**（2.4 / 2.3 / 2.1 / 1.6 s，然后一堆 300-900 ms），没有单一热区。

CPU profile（36 s 采样）分类：cordis + cordis-plugin-loader ≈ **6.4 s**；模块解析/编译
（`compileSourceTextModule` 2.6 s + `compileForInternalLoader` 1.1 s + `wrapSafe` 1.0 s + 已归类的 V8 解析 0.8 s + 各种 `getPackageScopeConfig`/`URL`/`deserializePackageJSON`/`finalizeResolution`）≈ **5-8 s**；
`js-yaml` + `schemastery` + `zod` + `dsh-app-boot` ≈ **3.8 s**；文件 I/O ≈ 3.7 s（其中 `realpathSync` 属模块解析路径）。
 
**推论**：瓶颈是 **CPU（模块解析/编译 + cordis 挂载 + schema 校验）**，不是磁盘 I/O（真实读盘 ~1 s）。
所以①裁插件/profile（少 import 少挂载）最直接；②把第三方依赖打成一个文件收益有限；
③V8 快照架构上不可行（见 CHANGES）；④清 `.ts`/`.md` 只影响安装体积。
另外模拟器是 arm64 经 ndk_translation 翻译执行，CPU 成本被放大，真机需复核。

## 谁最贵（按"空档归属"排序，Top 10）

把每个空档算到它**前面那个模块所属的包**头上（近似：空档也可能来自更早模块的异步初始化）：

| 包 | 文件数 | 体积 | 归属耗时 |
|---|---|---|---|
| `@mixmark-io/domino` | 52 | 533 KB | **2,545 ms** |
| `brotli` | 10 | **804 KB** | **2,367 ms** |
| `@deepseek-ai/dsh-cmdline` | 1 | 7 KB | 2,137 ms（可疑：自身很小，多半是别处的异步工作被算到它头上） |
| `node:internal` | – | – | 1,712 ms |
| `node:crypto` | – | – | 1,417 ms（OpenSSL 初始化，翻译执行下更贵） |
| `@deepseek-ai/dsh-agent-preset-registry` | 2 | 83 KB | 934 ms |
| `picomatch` / `mime-types` / `otlp-exporter-base` / `commander` / `semver` | 6-46 | 6-123 KB | 300-820 ms |

**最值得做的两件小事**（下一轮可验证）：
1. **`brotli`（804 KB）与 `@mixmark-io/domino`（533 KB）改成懒加载**（真要解压/转 HTML 时再 `import()`）
   —— 这两个是启动期最大的静态依赖，按模拟器口径合计 ~4.9 s；真机预计省 1-2 s。
2. **`@deepseek-ai/dsh-cmdline` 那 2.1 s 空档要单独查**：它自己只有 7 KB，八成是别处的异步初始化
   （引擎日志里能看到 `[dsh-remote] Codex App Server communication failed` 这类启动期探测）。

模块加载的时间分布（十分位）：`79 15 296 270 202 153 26 369 55 54` —— 加载分散在整个启动过程中，
20-50% 与 70-80% 各有一个高峰，说明"插件挂载"与"主线程编译"是交替进行的，没有单一可优化的停顿点。

## 为什么"最大的耗时在主线程空档"

ESM 的加载分四步：**resolve → load(读文件) → link/instantiate → evaluate**。
本工具只在 `load` 处打点，所以第 1、3、4 步（尤其第 4 步的**顶层求值**）全部表现为"两次加载之间的空档"。
按 CPU profile 拆开（36 s 采样）：

| 空档成分 | 估算 | 说明 |
|---|---|---|
| V8 解析/编译 | ~5.5 s | `compileSourceTextModule` 2.6 s + `compileForInternalLoader` 1.1 s + `wrapSafe` 1.0 s + V8 解析 0.8 s |
| 模块解析（找 package.json/realpath/URL） | ~2.5 s | `getPackageScopeConfig` 0.7 s + `URL` 0.6 s + `realpathSync` 0.5 s + `deserializePackageJSON` 0.4 s + `finalizeResolution` 0.3 s |
| 插件挂载 / 依赖注入 | ~6.4 s | `@deepseek-ai/cordis` 4.8 s + `cordis-plugin-loader` 1.6 s |
| profile 合成 / schema 校验 | ~3.8 s | `js-yaml` 1.0 s + `schemastery` 0.8 s + `zod` 0.6 s + `dsh-app-boot` 1.4 s |
| 文件 I/O（真实读盘） | ~0.9-3.7 s | 其中 `realpathSync` 属上一条的"解析" |
| GC / native | ~1.8 s | |

**为什么这里特别大**：① 1378 个文件 / 14.5 MB 都要 link+evaluate；② 模拟器是 arm64 经
ndk_translation **翻译执行**，CPU 成本放大数倍；③ 插件在 import/apply 阶段就做真事（建 schema、注册服务、YAML 解析）；
④ 与功能无关的重库（turndown/domino、fontkit/brotli）被**静态导入**，白白参与 link+evaluate。

**怎么办（含实测）**：

| 手段 | 实测/预期 | 结论 |
|---|---|---|
| 把重库改成懒加载（`await import()`） | 微基准：`import turndown` **984-1088 ms**、`import fontkit` **1330-1577 ms** | ✅ **最划算**：~2.7 s，改动小、有 overlay 机制 |
| 裁 profile / 插件 | cordis+loader 6.4 s 与插件数强相关 | ✅ 量级最大，但要逐个验证功能 |
| `NODE_COMPILE_CACHE`（V8 字节码缓存） | 热缓存后 turndown 984→990 ms、fontkit 1458→1330 ms（~9%，两次噪声级别） | ❌ 实测基本无效，别做 |
| V8 快照 / SEA | 见 CHANGES：Node 快照只支持单入口、不能加载额外用户模块 | ❌ 与"插件运行时动态挂载"架构冲突 |
| 合并第三方依赖成单文件 | 省的是 resolution（~2.5 s）的一部分，不省编译 | 🔸 收益有限 |
| 清 `.ts`/`.md`（20 MB） | 不影响启动（只读了 1378 个文件） | 🔸 只减安装体积 |
| 首启预热引擎（向导期间就起） | 感知上把 30 s 藏进读向导的时间 | ✅ 不改引擎成本但体验最好 |


