# 构建（从零到出包）

## 1. 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| OS | Windows（Git Bash）/ Linux / macOS | 本仓库脚本用 **bash** 写，Windows 下用 Git Bash 跑 `bash tools/...` |
| JDK | 8+（`javac` 在 PATH） | 构建脚本会打印 `javac -version` |
| Android SDK | 只需 **`android.jar`** | 放到 `android-app/sdk/android.jar`（见 `android-app/sdk/README.md` 的获取方式） |
| Android platform-tools | `adb`（仅测试/装机需要） | 模拟器调试必备 |
| 网络 | 首次构建要联网 | 运行时资产从 Release 拉（`tools/fetch-runtime.sh`，sha256 pin）；内核从 npm 拉（`tools/fetch-kernel.sh`） |

> 缓存：`.cache/`（已被 `.gitignore` 忽略）保存下载好的运行时/内核 tar，删掉只会让下次构建变慢。

## 2. 一条命令出包

```bash
bash tools/build-apk.sh                 # 社区版 arm64（默认）
bash tools/build-apk.sh --emulator      # x86_64 模拟器包（额外做 ndk_translation 适配）
bash tools/build-apk.sh --original-look # 用上游原版图标/悬浮窗形象
bash tools/build-apk.sh --out /tmp/x.apk
```

常用参数（完整列表看脚本头部注释）：

| 参数 | 作用 |
|---|---|
| `--emulator` | 构建 x86_64 模拟器包（**不要装真机**：node 的 OpenSSL 会 dlopen 失败） |
| `--original-look` | 原版外观（桌面图标/悬浮窗形象），代码行为完全一致 |
| `--out <path>` | 指定产物路径 |
| `--no-emu-libs` | 跳过自动补模拟器 guest 库（已手动补好时用） |
| `--keystore` / `--android-jar` / `--cache` | 覆盖签名库 / android.jar / 缓存目录 |

产物默认在 `android-app/DeepSeekHarness*.apk`，构建日志会打印 **SHA-256**。

## 3. 签名

- 仓库内 `android-app/community.jks` 是**公开的社区密钥**，只用于社区自助构建，
  **绝不用于签名正式发布物**（脚本启动时会打印这句警告）。
- 密钥口令与 alias 在 `tools/build-apk.sh` / `android-app/build.sh` 里（社区版固定值）。

## 4. 变体

本版**只保留社区版**（`DSH_VARIANT=community`）：

| 字段 | 值 |
|---|---|
| 包名 | `com.deepseek.harness.community` |
| 引擎/通知端口 | 3086 / 3087 |
| 外部目录 | `/sdcard/DeepSeekHarnessCommunity/` |
| 签名 | `community.jks` |

真源是 `android-app/variants.sh`；构建脚本 source 它生成 `AndroidManifest` 与 `BuildVariant.java`。

## 5. 构建过程做了什么（排查构建失败时按这个顺序看）

1. `tools/fetch-runtime.sh`：下载/解包运行时资产（`RUNTIME_TAG` / `RUNTIME_SHA256` 在脚本顶部 pin）；
2. `android-app/build.sh`：
   - `tools/fetch-kernel.sh` 取 `@deepseek-ai/dsh` 内核闭包（版本 pin 在 `android-app/kernel/`）；
   - 组装 `staging/`（dshroot + dshhome + runtime + bin …）；
   - 同步 `plugins/dsh-tool-*` 覆盖 staging 里的同名插件（**插件唯一生效路径**）；
   - 注入 `mobile-patch/mobile.js` / `mobile.css` 并改写 `index.html` 引用；
   - 生成 `AndroidManifest.xml`、`BuildVariant.java`；`javac` → `d8` → `aapt`/`apksigner`；
3. 输出 APK + 清单（`assets/kernel-manifest.tsv` 供设备端完整性自检）。

## 6. 常见构建失败

| 现象 | 原因 / 处理 |
|---|---|
| `缺少 android-app/sdk/android.jar` | 按 `android-app/sdk/README.md` 放好 android.jar（API 36 平台包） |
| `运行时资产获取/解包失败` | 网络问题；可先手动放好 `.cache/runtime-*.tar` 再重试 |
| `ESM 闸门` 报错 | 插件里有 ESM/CJS 语法问题；按提示改 `plugins/` 源码后重跑 |
| `Java 语法体检` 失败 | 用 `python tools/java-syntax-sanity.py <file>` 定位括号/大括号失衡 |
| staging 目录删不掉（Windows 权限） | 先 `rm -rf android-app/staging` 重试；脚本已内置重试 |
| 打完包但功能是旧的 | 检查 `MainActivity` 白名单是否包含你改的文件路径（见 `docs/STRUCTURE.md`） |

## 7. 模拟器上跑

```bash
bash tools/emu-start.sh Pixel_10_Pro --no-libs     # 起模拟器（写死 AVD 名）
bash tools/build-apk.sh --emulator --out .cache/out/x64.apk
adb install -r .cache/out/x64.apk
```

测试方法与日志取证见 [TESTING.md](TESTING.md)。
