# android-app/ —— APK 构建工程

把 DSH 内核 + node 运行时 + 移动端适配打包成可直接安装的 Android APK（默认社区版 `DeepSeekHarness-community.apk`）。

## 目录结构

```
android-app/
├── build.sh              一键打包脚本（7 步：组装 payload → aapt → javac → d8 → 打包 → zipalign → 签名）
├── variants.sh           变体表（单一真源；**本版 起只有 community**）
├── kernel/               内核树来源：package.json + 冻结 lock（由 tools/fetch-kernel.sh 安装）
├── AndroidManifest.xml   包名/targetSdk(28)/自由旋转/Shizuku 声明/版本号（versionCode/versionName）
├── libs/                 Shizuku 官方 aar（api/provider/aidl）
├── res/                  图标 + 字符串资源
├── sdk/                  放 platform android.jar（见 sdk/README.md，不入仓库）
├── src/.../MainActivity.java  Android 原生壳（权限引导页/加载页/引擎启动/看门狗）
├── assets/               随包资源（payload.zip 等，构建期生成）
├── community.jks         社区版签名密钥（**公开**，随仓库分发）
└── tools/                manifest-gen.mjs 等构建期脚本
```

## 构建环境（必备）

构建输入由 `tools/build-apk.sh` 自动准备（本版 起无需手工搭 devhome）：

- **内核树** —— 官方 npm registry 的 `@deepseek-ai/dsh@0.2.1-rc.2` + 仓库内冻结 lock
  （`android-app/kernel/`，`tools/fetch-kernel.sh` 安装，带 `.cache/kernel/*.tar` 缓存）；
- **arm64 运行时** —— 我们自己的 sha256 pin 资产（`tools/fetch-runtime.sh`），解到 `.cache/devhome`：
  `runtime/`（node v26 + `.so`）、`python/`、`npm/`、`pnpm/`、`git/`、`bash/`、`bin/`、`rish/`、`.dsh/`；
- **工具链** —— JDK 17+ 与 Android SDK 的 `build-tools`（aapt/d8/zipalign/apksigner）+ 任一 `android.jar`；
- **签名密钥** —— 默认用仓库内公开的 `community.jks`（`--keystore` 可换成你自己的）。

统一通过 `DSH_DEV_HOME` 指向（结构见 `docs/legacy/dev-guide-2026-09.md`）。App 内实例：`/data/user/0/com.deepseek.harness.community/files/buildenv/devhome`。

## 打包命令

```sh
export DSH_DEV_HOME=<devhome 路径>   # 含 runtime/dshroot/build/.dsh/rish
export JAVA_BIN=<java-17/bin 路径>   # javac 所在目录
export ANDROID_JAR=<android.jar 路径>
export KEYSTORE_PASS=<签名密码>
export KEYSTORE_ALIAS=dsh
sh build.sh
# 产物：android-app/DeepSeekHarness-community.apk
```

## build.sh 关键点

- **第 0 步**自动注入移动端适配（`sh ../mobile-patch/inject.sh`），payload 随 APK 打包
- **安全检查**：payload 里发现 `sk-` 密钥或 `.credentials.yaml` 立即中止
- **防坏包**：javac 失败立即中止（不再吞错）；`classes.dex` 必须含 `MainActivity` 才放行（v1.2.0 曾因缺校验产出安装即闪退的坏包）
- **soname 实体化**：按 `LINKS.txt` 把版本化 .so 复制成同名实体文件（jar 打包会压平软链，否则 node 起不来）
- **版本标记**：`dshroot_revision.txt`（assets）用于 App 判断外部 `/sdcard/DeepSeekHarness/dshroot` 是否需要补齐

## 版本号修改

改 `AndroidManifest.xml` 的 `android:versionCode` / `android:versionName` 后重新打包（小改动可走快速重打包：`jar uf` 就地更新 + zipalign + 重签）。

## 变体构建（本版：只有社区版）

包名/端口/外部目录/显示名/密钥全部来自 **`android-app/variants.sh`**（单一真源）：

| 变体 | 包名 | 引擎/通知端口 | 虚拟屏 桥/核心 | 外部目录 | 密钥 |
|---|---|---|---|---|---|
| `community` | `com.deepseek.harness.community` | 3086 / 3087 | 9029 / 9028 | `/sdcard/DeepSeekHarnessCommunity` | `community.jks`（**公开**） |

> 本版 起只保留社区版：`official` / `lite` 两个变体与 `compat`（GeckoView 兼容版）、
> `android-app-fix`（救援版）整条线已删除（连同 `legacy-patch/`）。此前它们各自的构建脚本
> 是早期 build.sh 的分支，未跟上 v1.18~本版 的变体表/插件同步/node-pty/清单闸门等改动。

```sh
bash tools/build-apk.sh        # 一键构建（唯一入口；默认且只构建社区版）
bash android-app/build.sh      # 直接调也可以（变体表由 build.sh 自己 source）
```

构建期做的事（不再手改源码）：

- 把 `src/` 复制到 `out/src/`，按变体改写 `package` / `import` 并把整棵树搬进变体包目录；
- 生成 `<变体包>/BuildVariant.java`（appId / 端口 / 外部目录常量，壳与 vscreen 核心共用）；
- 改写 manifest 的 `package` / provider `authorities`（shizuku / logshare）/ 版本后缀 / 显示名，并**自检替换确实发生**；
- 产物：`DeepSeekHarness-community.apk`。

**社区密钥 `community.jks`（别名 `community`，口令 `dsh-community`）是故意公开的**：它的用途是让任何人不持有维护者私钥也能构建出**可与官方包共存**的自己那一份。它**绝不**用于签名正式发布物 —— 正式包始终由私有 `release.jks` 签发。

## 注意

- ⚠️ **targetSdk 必须保持 28**（≥29 时 Android 把私有目录挂 noexec，node 起不来）
- 私有签名密钥（`release.jks`）与密码**绝不提交仓库**（.gitignore 已排除）
- 安装包自测方法（防 ERR_CONNECTION_REFUSED）：抽出 payload 实测引擎 HTTP 200，见 `docs/legacy/dev-guide-2026-09.md` / `交接文档.md`
