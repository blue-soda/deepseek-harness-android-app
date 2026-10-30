# 发版流程

> 发布物只有 **社区版**，每版三个 APK：`arm64`、`arm64-original-look`、`x86_64`（模拟器包，
> 视维护者要求可不上传）。

## 0. 前提

- 先在 **x64 模拟器**上验证过本次改动（见 `docs/TESTING.md`）；
- 维护者验收通过后再发 arm64（历史约定：未验收不构建 arm64）。

## 1. 改版本号

只改一个地方：`android-app/AndroidManifest.xml`

```xml
android:versionCode="63"          <!-- 每次发布 +1 -->
android:versionName="1.21.2"      <!-- 语义化；变体后缀 -community 由构建注入 -->
```

## 2. 构建三个包

```bash
bash tools/build-release.sh          # 一次出 arm64 / arm64-original-look / x86_64 到 .cache/out/
```

产物与哈希：

```bash
sha256sum .cache/out/v*-arm64.apk .cache/out/v*-orig.apk .cache/out/v*-x64.apk
```

> 单个包也可以手动出：`bash tools/build-apk.sh [--emulator|--original-look] --out <path>`。

## 3. 归档命名

仓库约定（Release 资产名）：

```
DeepSeekHarness-community-<版本>-arm64.apk
DeepSeekHarness-community-<版本>-arm64-original-look.apk
DeepSeekHarness-community-<版本>-x86_64.apk
```

每个包旁边放一个同名 `.sha256`（内容：`<hash>  <文件名>`）。

## 4. 建 Release 并上传

```bash
# 写发行说明（两三条即可，不要堆细节）
cat > /tmp/notes.md <<'EOF'
## 1.21.2-community

- 一句话说明本次修复/更新。
EOF

gh release create v1.21.2-community \
  android-app/DeepSeekHarness-community-1.21.2-arm64.apk \
  android-app/DeepSeekHarness-community-1.21.2-arm64-original-look.apk \
  -R blue-soda/deepseek-harness-android-app \
  --title v1.21.2-community --notes-file /tmp/notes.md
```

### 慢网 / 上传卡住时用 curl 直传

`gh` 上传 150MB 包在慢网下可能要十几分钟，容易以为“卡死”。用 `tools/release-upload.sh`：

```bash
bash tools/release-upload.sh v1.21.2-community \
  android-app/DeepSeekHarness-community-1.21.2-arm64.apk \
  android-app/DeepSeekHarness-community-1.21.2-arm64-original-look.apk
```

它做三件事：取 release id → 逐包 `curl --data-binary` 直传 `uploads.github.com` → 打印 HTTP 码与速率；
上传完用 `tools/release-check.sh <tag>` 核对资产列表与是否已发布（`draft=false`）。

> 首次若还没有 release，脚本会先 `gh release create --draft` 建草稿，最后再 `gh release edit --draft=false` 发布。

## 5. 发布后核对

```bash
bash tools/release-check.sh v1.21.2-community
# 期望：draft=false + 资产列表与本地文件一致（大小能对上）
```

再抽查一次版本号（不必装机）：

```bash
unzip -p android-app/DeepSeekHarness-community-1.21.2-arm64.apk AndroidManifest.xml >/dev/null 2>&1 || true
# 或直接看构建日志里 aapt 打出的 versionCode/versionName
```

## 6. 提交

```bash
git add -A
git commit -m "release: 1.21.2-community（<一句话>）"
git push origin master        # 本机历史上用的是 blue-soda 这个远端名
```

## 7. 注意

- **本仓库不含 APK**（`.gitignore` 忽略 `*.apk`）：发布物只走 Release；本地 `android-app/*.apk` 不要提交；
- arm64 包**不要**给模拟器装，x86_64 包**不要**给真机装；
- 社区密钥是公开的，签名仅代表“社区构建”，不代表官方。
