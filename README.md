# DeepSeek Harness for Android

> 把 **DeepSeek Harness（DSH）** 打包成可直接安装的 Android APK：装上就能用，还能让 AI **免 Root 操作手机**。
>
> 本仓库是原上游 `woaiys3/deepseek-harness-android-app` 的社区分支。**上游仓库已被作者删除**，
> 因此本仓库是该项目目前唯一仍在维护的公开分支（历史与实现脉络见 git 历史）。

**📚 文档区：[`docs/`](docs/README.md) —— 新 agent 请从这里开始。**

## 特性

| 方面 | 说明 |
|---|---|
| 🐟 卡通形象 | 悬浮球/头像换成卡通大肥鱼立绘，配套应用图标与启动页 |
| 🫧 悬浮窗 | 小人常驻屏幕边缘，头顶状态气泡（思考中 / 正在调用工具 / 任务已完成 / 摸鱼中…）；面板精简为「打开 / 控制台 / 退出」 |
| 🖥️ 终端可用 | 打包 Android 预编译的 node-pty，换用真 bash，终端端到端可用 |
| 🎨 UI | 控制台 / 引导页 / 启动页跟随同一套深浅色，并与 DSH 页面同色调 |
| 🧰 工具增强 | 内置 44 个安卓工具（应用 / 通知 / 设置 / 剪贴板 / 无障碍 / 虚拟屏 / 特权 / 浏览器，见 [`plugins/README.md`](plugins/README.md)） |
| 🧩 自开发技能 | 随包带 `dsh-self-customization` 技能，让 AI 知道「该怎么改自己」 |
| 🔌 内置 remote 插件 | 默认内置 [ds-harness-remote](https://github.com/blue-soda/ds-harness-remote/)（远程访问 + DeepSeek 账号登录） |

## 安装

下载 [Releases](https://github.com/blue-soda/deepseek-harness-android-app/releases)：

| 包 | 用于 |
|---|---|
| `DeepSeekHarness-community-<版本>-arm64.apk` | **真机**（arm64，Android 7.0+） |
| `DeepSeekHarness-community-<版本>-arm64-original-look.apk` | 同上，但用原版图标 / 悬浮窗形象 |
| `DeepSeekHarness-community-<版本>-x86_64.apk` | **仅模拟器**（含 `ndk_translation`，装真机会起不来） |

包名 `com.deepseek.harness.community`，可与其它变体共存；每个包旁边有同名 `.sha256` 可校验。

**首次启动**会解压运行环境（1–3 分钟，2 万+ 文件），期间**别切后台 / 别锁屏**。

## 镜像与网络

单一真源是 [`config/mirrors.env`](config/mirrors.env)，构建脚本、设备端 helper 与引擎都读它：

```bash
curl -L -o app.apk https://ghfast.top/https://github.com/blue-soda/deepseek-harness-android-app/releases/download/<tag>/<asset>.apk
```

设备内 npm/pnpm 走 `registry.npmmirror.com`、pip 走清华源；如何关掉见该文件注释。

## 构建 / 测试 / 发版

```bash
bash tools/build-apk.sh --emulator --out .cache/out/x64.apk   # 模拟器包（x86_64）
bash tools/build-apk.sh                                        # 真机包（arm64）
bash tools/build-release.sh                                    # 一次出三包
```

需要 JDK、`android-app/sdk/android.jar`（见 [`android-app/sdk/README.md`](android-app/sdk/README.md)）与网络。

| 主题 | 文档 |
|---|---|
| 仓库结构与运行时布局 | [docs/STRUCTURE.md](docs/STRUCTURE.md) |
| 构建（从零到出包） | [docs/BUILD.md](docs/BUILD.md) |
| **测试方法**（模拟器 / 真机 / 日志取证 / 工具回归） | [docs/TESTING.md](docs/TESTING.md) |
| **运维手册**（端口 / 令牌 / 完整性自愈 / 重启） | [docs/OPS.md](docs/OPS.md) |
| **发版流程**（含慢网 curl 直传） | [docs/RELEASE.md](docs/RELEASE.md) |
| 迭代落点（Java / 插件 / 前端补丁） | [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) |
| 排障（用户版 + 开发侧已知坑） | [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) |

## 致谢与许可

- 历史上游：`woaiys3/deepseek-harness-android-app`（**已被作者删除**；本仓库保留其代码与实现脉络）
- 内置插件：[ds-harness-remote](https://github.com/blue-soda/ds-harness-remote/)
- [Operit](https://github.com/AAswordman/Operit) —— 虚拟屏（vscreen）移植来源，LGPL-3.0，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— 免 Root 特权通道
- [AOSP apksig](https://android.googlesource.com/platform/tools/apksig) —— 设备内 APK 签名 / 校验（Apache-2.0）
- Termux 分发的 GNU 工具集与 Java 工具（aapt2 / ecj / dx 等），许可随包在 `runtime/share/doc/*/COPYING*`
- 本仓库源码主体为 [MIT](LICENSE)（例外与依赖许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）

> 本仓库是**源码与配置仓库**，不含 APK 二进制与私有签名密钥（社区公开密钥除外）；安装包见 Releases。
