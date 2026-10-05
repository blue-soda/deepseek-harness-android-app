# vendor/ —— 构建期内置的 DSH 插件

这里放**要随 APK 一起出厂**的 DSH 插件（设备端不联网安装）。

与 `dsh-patches/overlay/` 的区别：
- `dsh-patches/overlay/` —— **替换** DSH 已有的文件（快照式补丁，靠 overlay-recapture 维护）；
- `vendor/` —— **新增**一个插件包（连它的运行时依赖一起带上）。

## 为什么不用运行时 `dsh plugin add`

维护者要求 Android 版默认就装好 `ds-harness-remote`（等价于
`dsh plugin --profile web add github:blue-soda/ds-harness-remote`）。实测这条运行时装的路
在 Android 上有多处坑：

1. `git ls-remote` 在 payload 里报 `Error running …/payload/bin/git: Unable to get realpath of git`
   （该字符串不在 git 二进制里，`git --version` / `git-remote-https` 单独跑都正常 → Android/Termux 执行层问题）；
2. pnpm 对 `github:` 规格默认走 **ssh**（`git+ssh://git@github.com/…`），Android 上没 ssh key 必失败；
3. 该插件是 git-hosted，安装时要跑 `prepare` 构建，pnpm 会拦下来要求先在
   `profiles/web/pnpm-workspace.yaml` 写 `allowBuilds`。

所以改成构建期内置：在能连 GitHub 的机器上装/拉好，产物直接打进 payload。

## 目录约定

```
vendor/<插件名>/                 # 就是 npm 包的根（package.json 在这一层）
  index.js  packages/  locale/ … # 插件自带的运行时文件（仓库里已含 dist 构建产物）
  node_modules/                  # 该插件的运行时依赖（自带齐全，避免设备端解析不到）
```

`android-app/build.sh` 会把它整棵拷到
`staging/dshhome/profiles/web/node_modules/<插件名>`。

⚠ **必须放 profile 的 node_modules**：实测 bundle 名是从 profile 解析的
（`dsh plugin add` 也是装到那儿）；放到 DSH 树里引擎会报
`<插件名> (<插件名>): failed to import`。

App 侧还负责把插件名写进 `profiles/web/package.json` 的 `dsh.profile.bundles`
（见 `MainActivity.ensureDefaultPluginRegisteredSync`，幂等、引擎启动前完成）——
DSH 只有看到它在 bundles 里才会组合进插件树。

## 当前内置

| 插件 | 版本 | 来源 | 说明 |
|---|---|---|---|
| `ds-harness-remote` | 0.4.27 | `github:blue-soda/ds-harness-remote` | 端到端加密的远程访问（桌面/网页/安卓互连）。自带 `dist/index.js` + `dist/client.github.js`；运行时依赖 `qrcode`/`werift`/`ws`/`zod` 等一并内置（源文件约 22MB，APK 实测 +11MB）。 |

## 更新步骤（人工）

```bash
# 1) 拉新版源码（在有 GitHub 访问的机器上）
git clone --depth 1 https://github.com/blue-soda/ds-harness-remote.git /tmp/dsr

# 1b) 若本机 git 连不上 github.com（实测会 Connection reset / 连接超时），改走 tarball：
#     先查最新提交：https://api.github.com/repos/blue-soda/ds-harness-remote/commits?per_page=5
#     再拉整包：
#     curl -L -o /tmp/dsr.tar.gz https://codeload.github.com/blue-soda/ds-harness-remote/tar.gz/refs/heads/main
#     mkdir -p /tmp/dsr && tar -xzf /tmp/dsr.tar.gz -C /tmp/dsr --strip-components=1
#     （2026-10 实测：api.github.com 与 codeload.github.com 可达，而 github.com 的 git 端口被挡）

# 2) 铺包本体（只保留运行时需要的）
cd /tmp/dsr && cp -r index.js packages dsh-plugin.json cordis.patch.yml locale \
  public.d.ts package.json LICENSE README.md <repo>/vendor/ds-harness-remote/

# 3) 铺运行时依赖（ignore-scripts 避免跑原生构建）
cd <repo>/vendor/ds-harness-remote && npm install --omit=dev --ignore-scripts \
  --no-audit --no-fund qrcode@^1.5.4 werift@0.24.4 ws@^8.21.3 zod@^3.24.1
# 上一步会生成 node_modules/，即内置依赖

# 4) 重新构建 APK，装到设备上确认引擎日志出现 [dsh-remote] 行
bash .cache/build-local.sh community
```

## 已验证 / 未验证（2026-10-05，模拟器 community）

- ✅ 插件随 APK 出厂、被同步到设备、注册进 profile bundles；
- ✅ 引擎加载成功：日志出现
  `[dsh-remote] host identity ready {…, "server":"https://dsh.r2049.cn"}`、
  `[dsh-remote] client remote-mode identity ready {…}`；
- ⚠ 未验证完：本环境 DNS 解析不了 `dsh.r2049.cn`，所以中继连接一直
  `{"code":"CONNECTION_FAILED","retryable":true}`；客户端面板也未在界面里确认
  （需要能连通中继的环境再看一次）。
- 预期内的噪音：`CODEX_BINARY_UNAVAILABLE`（Android 上没有 Codex 二进制）。
