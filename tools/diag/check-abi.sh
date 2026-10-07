#!/usr/bin/env bash
# 校验交付 APK 的 payload 里有没有混进 x86_64 的库（真机 arm64 必须为 0）
# 用法：bash tools/diag/check-abi.sh [apk 路径]
set -e
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
APK="${1:-$REPO/android-app/DeepSeekHarness-community.apk}"
TMP="$REPO/.cache/abi-check"
rm -rf "$TMP"; mkdir -p "$TMP/x"
unzip -p "$APK" assets/payload.zip > "$TMP/p.zip"
cd "$TMP/x" && unzip -o -q "$TMP/p.zip" 'runtime/lib/*'
mach() { od -An -t x1 -j 18 -N 2 "$1" 2>/dev/null | tr -d ' \n'; }
a=0; x=0; bad=""
for f in runtime/lib/*; do
  [ -f "$f" ] || continue
  m=$(mach "$f")
  [ "$m" = "b700" ] && a=$((a+1))
  if [ "$m" = "3e00" ]; then x=$((x+1)); bad="$bad $(basename "$f")"; fi
done
echo "payload runtime/lib: arm64=$a x86_64=$x"
[ "$x" = "0" ] && echo "✅ 干净（可给真机）" || { echo "❌ 含 x86_64：$bad"; exit 1; }
# 顺带确认内置插件版本与提问状态是否在包内
echo -n "内置插件版本: "
unzip -p "$TMP/p.zip" "dshhome/profiles/web/node_modules/ds-harness-remote/package.json" 2>/dev/null | grep -o '"version": "[^"]*"' | head -1
echo -n "提问气泡状态(次数): "
unzip -p "$TMP/p.zip" "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-android/lib/index.js" 2>/dev/null | grep -c "正在向用户提问"
