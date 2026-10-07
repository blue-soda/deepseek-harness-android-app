# DeepSeek Harness 手机版（Android）· 社区构建

> 把 DeepSeek Harness（DSH）打包成**可直接安装的 Android APK**：装上就能用，还能让 AI **免 Root 操作手机**。
>
> 本仓库是 [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) 的社区分支。

## 🐟 与上游的区别

| 方面 | 我们做了什么 |
|---|---|
| 🐟 **卡通大肥鱼形象** | 悬浮球与头像换成**卡通大肥鱼**全身立绘，配套应用图标与启动页 |
| 🫧 **实用悬浮窗** | 小人常驻屏幕边缘（可拖动、靠边半藏），头顶**状态气泡**：`思考中…` / `正在 <工具>…` / `正在向用户提问...` / `任务已完成` / `会话已结束` / `下班啦` / `摸鱼中…`；面板精简为「打开 / 控制台 / 退出」 |
| 🖥️ **终端可用** | 打包 Android 预编译的 **node-pty** 并换用**真 bash**，终端端到端可用（DSH rcfile 生效） |
| 🎨 **UI 优化** | 控制台 / 引导页 / 启动页跟随同一套深浅色，并与 DSH 页面同色调 |
| 🧰 **工具增强** | 按安卓 Agent 真机实测反馈持续优化工具逻辑与工具描述，并补齐缺失能力 |
| 🧩 **内置自开发技能** | 随包带 `dsh-self-customization` 技能，让 AI 知道「该怎么改自己」（附源码仓库与社区签名说明） |
| 🔌 **内置 remote 插件** | 默认内置 **[ds-harness-remote](https://github.com/blue-soda/ds-harness-remote/)**：可远程访问 DSH 与 Codex，支持 DeepSeek 账号一键登录 |

## 📦 安装

下载本仓库 [Releases](https://github.com/blue-soda/deepseek-harness-android-app/releases)：

- **`DeepSeekHarness-community-<版本>-arm64.apk`** —— 社区构建（包名 `com.deepseek.harness.community`，与上游各变体**可共存**）
- 要求：**arm64 真机**、Android 7.0（API 24）及以上

## 🚀 快速上手

1. 安装 APK → 按引导授权（**存储 / 所有文件访问**必给；悬浮窗、电池优化、通知建议给）
2. 首次启动会解压运行环境（**1–3 分钟**，期间别切后台）
3. 填写 API Key 开始对话；需要「操作手机」时，再按引导开启 **Shizuku / root** 或**无障碍屏幕助手**

## 🔗 上游项目

本分支的绝大部分能力来自上游：

**https://github.com/woaiys3/deepseek-harness-android-app**

（APK 一键安装、免 Root 特权（Shizuku / root）、无障碍屏幕助手、虚拟屏 vscreen、内置 Python/npm 等都在上游实现。）
上游的完整功能说明、各变体（正式版 / Lite / 兼容版）与更新日志请见上游仓库。

## 🔨 构建

```bash
bash tools/build-apk.sh --variant community
```

需要 `android.jar` 与 JDK；社区版使用仓库内的**公开密钥** `android-app/community.jks`。
详见 [BUILD.md](BUILD.md) 与 [android-app/README.md](android-app/README.md)。

## 🙏 致谢与许可

- 上游项目：[woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app)
- 内置插件：[ds-harness-remote](https://github.com/blue-soda/ds-harness-remote/)
- [Operit](https://github.com/AAswordman/Operit) —— 虚拟屏（vscreen）移植来源，LGPL-3.0，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— 免 Root 特权通道
- 本仓库源码主体为 [MIT](LICENSE)（例外与依赖许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）

> 本仓库是源码与配置仓库，**不含 APK 二进制与私有签名密钥**（社区公开密钥除外）；安装包见上方 Releases。
