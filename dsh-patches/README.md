# DSH 源码补丁说明

> DeepSeek Harness（DSH）在 Android 上的适配补丁归档。更新 DSH 内核后，用 `apply.sh` 重新应用。

## overlay 必须与内核版本对齐（v1.20 起有工具兜底）

`overlay/` 是**按内核版本采集的快照**：存的不是 diff，而是"改好的整份文件"。内核升级后会出现两种**静默失效**：

- **路径漂移**：bundle 文件名变了（如 `runner-launch-COYGu0Dl.js` → `runner-launch-B2zsQ1Dz.js`），
  `apply.sh` 照旧解压，但解出来的是**没人加载**的旧文件 → 补丁看着打了、其实没生效。
- **内容过期**：路径还在但内容是上一代内核的 → 覆盖回去等于**把内核降级**。

所以升级内核后请重采一次：

```bash
# 1) 体检：overlay 里的文件在当前内核树里是否存在、是否一致
bash tools/overlay-drift.sh                    # 期望：一致=N，不同=0，树里不存在=0
# 2) 重采：以「当前树 = 我们实际在跑的、已打补丁的内核」为准刷新 overlay
python tools/overlay-recapture.py --check      # 只报告
python tools/overlay-recapture.py --write      # 执行（旧 overlay 自动备份到 .cache/overlay-backup-<时间>）
# 3) 新补丁：把路径加进 tools/overlay-recapture.py 的 ADD_PATHS，再 --write
```

`apply.sh` 会在解压后**自检**每个 overlay 文件是否存在于目标树，缺了就报警并提示重采。

> 为什么不直接抄上游的分层（`overlay` / `overlay-017` / `overlay-020`）？
> ① 上游的 `apply.sh` 也只应用默认层 `overlay/`，分层目录是历史归档（两层同名文件实测 4/6 不同）；
> ② 那是**上游**的补丁，本 fork 独有的改动（如 `dsh-subprocess-local` 的 Android 平台门禁、
>    payload 里的 `node-pty`）上游没有 —— 它的 overlay-020 里根本没有 subprocess-local；
> ③ 内核包不在公共 npm 上（实测 `@deepseek-ai/dsh-*@0.2.0-rc.2` 全 404），拿不到"未改动内核"
>    做三方 diff，只能以我们自己这棵树为事实标准重采。


## 目录结构

```
dsh-patches/
├── README.md              本文件
├── apply.sh               重新应用源码补丁（带语法自检）
└── overlay/               改好后的源码文件
    └── lib/node_modules/@deepseek-ai/dsh/node_modules/
        ├── dsh-subprocess-local/          子进程模拟（适配 Android）
        ├── dsh-attachment-local/          附件处理（适配 Android）
        ├── dsh-bash-local/                bash 沙箱兼容
        ├── dsh-session-persistence-jsonl/ 会话持久化（适配 Android）
        ├── dsh-tool-shizuku/              Shizuku 特权插件（三层修复版）
        └── dsh-tool-android/              系统能力插件
```

## 架构

```
APK (com.deepseek.harness)
├── Android 壳（MainActivity：权限引导页 + WebView + 解压 payload + 拉起 node）
├── payload（首次解压到 files/）
│   ├── runtime/               node 二进制 + .so
│   ├── dshroot/               DSH 内核（含本补丁）
│   ├── dshhome/               DSH 配置（无凭证）
│   └── bin/bash               shim（/system/bin/sh）
└── 前端 = DSH 原生界面 + mobile-patch 移动端适配
    ├── 页面加载 http://127.0.0.1:3080
    ├── 发消息：POST /api/session.prompt
    └── 收流式回复：WebSocket ws://127.0.0.1:3080/api/events.mux
```

## Android 源码补丁

DSH 有 4 处需要适配 Android 的源码改动，更新 DSH 后需重新应用：

| 补丁 | 原因 | 改动 |
|---|---|---|
| dsh-subprocess-local | node-pty 原生模块 Android 无法编译 | 用 child_process 模拟 |
| dsh-attachment-local | sharp 原生模块 + Android 禁硬链接 | 纯 JS 头解析 + link→rename |
| dsh-bash-local | 原生沙箱禁用后需兼容 | 补 sandboxMode getter |
| dsh-session-persistence-jsonl | Android SELinux 禁硬链接 | link→rename |

## 如何升级 DSH 版本

```sh
# 1. 更新 DSH 包（开发环境，需 npm）
source $DEV_HOME/runtime/env.sh
cd $DEV_HOME/dshroot/lib/node_modules/@deepseek-ai/dsh
npm install @deepseek-ai/dsh@latest

# 2. 重新应用 Android 源码补丁（带语法自检，源码变了会报错提醒）
sh dsh-patches/apply.sh

# 3. 重新打包 APK（会自动注入 mobile-patch 移动端适配）
cd android-app && bash build.sh
```

## 如何打包 APK

```sh
cd android-app && bash build.sh
# 产物：android-app/DeepSeekHarness.apk
```

build.sh 会自动：

1. 组装 payload（runtime + dshroot + dshhome + bin）
2. 注入移动端适配（`mobile-patch/inject.sh`，mobile.css + mobile.js 到 DSH 前端 dist）
3. 安全检查（payload 里禁止出现 API Key / .credentials.yaml）
4. aapt/javac/d8/zipalign/apksigner 全流程

## 移动端适配（mobile-patch/）

- 纯 CSS/JS 注入，不覆盖 DSH 原生页面
- `mobile.css`：触摸优化 + 插件管理页 UI 适配
- `mobile.js`：软键盘适配（VisualViewport 方案）
- 改适配 = 改 `mobile-patch/` 里的文件，重新 `build.sh` 即可，不碰 DSH 源码

## 插件管理

DSH 插件系统（cordis）通过 `dsh plugin`（内部转 pnpm）安装：

```sh
source $DEV_HOME/runtime/env.sh
export DSH_HOME=$DEV_HOME/.dsh
node --expose-internals $DEV_HOME/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  plugin --profile web add <插件包名>
```

装完重新 `build.sh` 打进 APK。注意：插件若依赖原生模块，需像上面 4 个补丁一样做 Android 适配。

## 关键注意事项

- node 运行需要 `LD_LIBRARY_PATH=runtime/lib`（libz/libicu 等软链由 APK 首次解压后按 LINKS.txt 重建）
- DSH 启动必须 `--expose-internals`（否则 HMR 报错）
- Android 无 `/usr/bin/env`，所有 wrapper 用 `#!/system/bin/sh`
- API Key 通过页面 `credentials.set` 写入本机，**绝不打包进 APK**
