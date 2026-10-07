# DeepSeek Harness 手机版（Android）· 社区构建

> 把 DeepSeek Harness（DSH）打包成**可直接安装的 Android APK**：装上就能用，还能让 AI **免 Root 操作手机**。
>
> 本仓库是 [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) 的社区分支：**已合并上游 v1.19.0**（带来 AI 浏览器、内核自检 / 自修复、控制台「一切皆自定义」、会话管理与回收站、会话级自愈等能力），并在其之上做界面与体验改动、以及面向「AI 真机操作」的工具增强。

## 🐟 与上游的区别

| 方面 | 我们做了什么 |
|---|---|
| 🐟 **卡通大肥鱼形象** | 悬浮球与头像换成**卡通大肥鱼**全身立绘，配套应用图标与启动页 |
| 🫧 **实用悬浮窗** | 小人常驻屏幕边缘（可拖动、靠边半藏），头顶**状态气泡**：`思考中…` / `正在 <工具>…` / `正在向用户提问...` / `任务已完成` / `会话已结束` / `下班啦` / `摸鱼中…`；气泡消息可指定显示时长（1~600 秒）；点开面板有「打开 / 控制台 / 退出」（退出为两步确认） |
| ⚡ **启动体验** | 进 App **不必先等引擎起来**（界面先可用），引导期间后台预热运行环境；慢启动时**继续等待**而不是判失败；默认工作区开箱可用 |
| 🔐 **remote 插件登录体验** | 点「DS 登录」**直接打开系统浏览器**完成授权，回到 App 后**自动刷新状态** |
| 🖥️ **终端可用** | 打包 Android 预编译的 **node-pty** 并换用**真 bash**，终端端到端可用（DSH rcfile 生效） |
| 🎨 **界面外观统一** | 控制台 / 引导页 / 启动页跟随同一套深浅色，并与 DSH 页面同色调 |
| 🧰 **工具增强** | 免特权手机操作：手势注入与多指、输入一次调用（聚焦 → 写入 → 提交 → 读回自证）、不截图的界面查找 / 等待 / 对比、打开网址与深链、查看与解出已安装应用 APK、读日志 |
| 🧩 **内置自开发技能** | 随包带 `dsh-self-customization` 技能，让 AI 知道「该怎么改自己」（附源码仓库与社区签名说明） |
| 🔌 **预装 remote 插件** | 默认内置 **[ds-harness-remote](https://github.com/blue-soda/ds-harness-remote/)**（npm 发布版），安装后即可用远程访问，不必手动装插件 |

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
