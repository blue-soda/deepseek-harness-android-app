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
