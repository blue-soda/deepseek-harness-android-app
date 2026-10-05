# android-app/ —— APK 构建工程

把 DSH 内核 + node 运行时 + 移动端适配打包成可直接安装的 Android APK（`DeepSeekHarness.apk`）。

## 目录结构

```
android-app/
├── build.sh              一键打包脚本（7 步：组装 payload → aapt → javac → d8 → 打包 → zipalign → 签名）
├── env.sh                编译工具链环境（可 export PREFIX 覆盖工具链位置）
├── AndroidManifest.xml   包名/targetSdk(28)/自由旋转/Shizuku 声明/版本号（versionCode/versionName）
├── libs/                 Shizuku 官方 aar（api/provider/aidl）
├── res/                  图标 + 字符串资源
├── sdk/                  放 platform android.jar（见 sdk/README.md，不入仓库）
├── src/.../MainActivity.java  Android 原生壳（权限引导页/加载页/引擎启动/看门狗）
└── release.jks           签名密钥（⚠️ 不入仓库，仅本机构建用）
```

## 构建环境（必备）

构建需要**完整开发环境**（不在本仓库内，见 `docs/开发指南.md` 第三节）：

- `runtime/` —— node v26 运行时（bionic 版：`bin/node` + `lib/*.so`）
- `dshroot/` —— DSH 内核（`@deepseek-ai/dsh`，含 Android 补丁）
- `build/` —— 编译工具链（aapt / javac / d8 / zipalign / apksigner）
- `.dsh/` —— DSH 配置（cordis.patch.yml / settings.yaml / profiles/web）
- `release.jks` —— 签名密钥

统一通过 `DSH_DEV_HOME` 指向（结构见 `docs/开发指南.md`）。App 内实例：`/data/user/0/com.deepseek.harness/files/buildenv/devhome`。

## 打包命令

```sh
export DSH_DEV_HOME=<devhome 路径>   # 含 runtime/dshroot/build/.dsh/rish
export JAVA_BIN=<java-17/bin 路径>   # javac 所在目录
export ANDROID_JAR=<android.jar 路径>
export KEYSTORE_PASS=<签名密码>
export KEYSTORE_ALIAS=dsh
sh build.sh
# 产物：android-app/DeepSeekHarness.apk
```

## build.sh 关键点

- **第 0 步**自动注入移动端适配（`sh ../mobile-patch/inject.sh`），payload 随 APK 打包
- **安全检查**：payload 里发现 `sk-` 密钥或 `.credentials.yaml` 立即中止
- **防坏包**：javac 失败立即中止（不再吞错）；`classes.dex` 必须含 `MainActivity` 才放行（v1.2.0 曾因缺校验产出安装即闪退的坏包）
- **soname 实体化**：按 `LINKS.txt` 把版本化 .so 复制成同名实体文件（jar 打包会压平软链，否则 node 起不来）
- **版本标记**：`dshroot_revision.txt`（assets）用于 App 判断外部 `/sdcard/DeepSeekHarness/dshroot` 是否需要补齐

## 版本号修改

改 `AndroidManifest.xml` 的 `android:versionCode` / `android:versionName` 后重新打包（小改动可走快速重打包：`jar uf` 就地更新 + zipalign + 重签）。

## 变体构建（B11/D2，v1.18）

**同一份源码**构建全部变体，包名/端口/外部目录/显示名/密钥全部来自 **`android-app/variants.sh`**（单一真源）：

| 变体 | 包名 | 引擎/通知端口 | 虚拟屏 桥/核心 | 外部目录 | 默认密钥 |
|---|---|---|---|---|---|
| `official` | `com.deepseek.harness` | 3080 / 3081 | 8999 / 8998 | `/sdcard/DeepSeekHarness` | `release.jks`（私有） |
| `lite` | `com.deepseek.harness.beta` | 3082 / 3083 | 9009 / 9008 | `/sdcard/DeepSeekHarnessLite` | `release.jks`（私有） |
| `compat` | `com.deepseek.harness.compat` | 3084 / 3085 | 9019 / 9018 | `/sdcard/DeepSeekHarnessCompat` | `release.jks`（私有） |
| `community` | `com.deepseek.harness.community` | 3086 / 3087 | 9029 / 9028 | `/sdcard/DeepSeekHarnessCommunity` | `community.jks`（**公开**） |

```sh
# 社区版（公开密钥签名，可与官方三个变体同时安装、互不干扰）
bash tools/build-apk.sh --variant community
# 其它变体
bash tools/build-apk.sh --variant official|lite|compat
# 直接调 build.sh 也行（变体表由 build.sh 自己 source）
DSH_VARIANT=community bash android-app/build.sh
```

构建期做的事（不再手改源码）：

- 把 `src/` 复制到 `out/src/`，按变体改写 `package` / `import` 并把整棵树搬进变体包目录；
- 生成 `<变体包>/BuildVariant.java`（appId / 端口 / 外部目录常量，壳与 vscreen 核心共用）；
- 改写 manifest 的 `package` / provider `authorities`（shizuku / logshare）/ 版本后缀 / 显示名，并**自检替换确实发生**；
- 产物：official 保持 `DeepSeekHarness.apk`，其余变体为 `DeepSeekHarness-<variant>.apk`。

**社区密钥 `community.jks`（别名 `community`，口令 `dsh-community`）是故意公开的**：它的用途是让任何人不持有维护者私钥也能构建出**可与官方包共存**的自己那一份。它**绝不**用于签名正式发布物 —— 正式包始终由私有 `release.jks` 签发。

⚠ 兼容版例外：`compat/` 仍是一套独立差异文件（内嵌 GeckoView），其 `build.sh`/`MainActivity.java` 尚未并入本变体机制（见 `compat/README.md` 与 TODO）。compat 的虚拟屏端口按本表应为 9019/9018。

## 注意

- ⚠️ **targetSdk 必须保持 28**（≥29 时 Android 把私有目录挂 noexec，node 起不来）
- `release.jks` 与密码**绝不提交仓库**（.gitignore 已排除）
- 安装包自测方法（防 ERR_CONNECTION_REFUSED）：抽出 payload 实测引擎 HTTP 200，见 `docs/开发指南.md` / `交接文档.md`
