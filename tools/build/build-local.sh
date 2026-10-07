#!/bin/sh
# 本机构建任意变体（Windows / Git Bash）。唯一构建入口，路径由脚本自身位置推导。
# 用法：bash tools/build/build-local.sh [official|lite|compat|community]
set -e
V="${1:-${DSH_VARIANT:-official}}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"

export DSH_DEV_HOME="$REPO/.cache/devhome"
export ANDROID_JAR="$LOCALAPPDATA/Android/Sdk/platforms/android-36/android.jar"
export JAVA_BIN=/c/Programs/jdk-21/bin
export PATH="$(cygpath "$LOCALAPPDATA")/Android/Sdk/build-tools/35.0.0:$PATH"
export JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
# 模拟器（x86_64 + ndk_translation）：注入 x86_64 裸名库
# 注意：不再设 DSH_X64_NO_LINKS（v1.18 起 LINKS.txt 必须保留带版本号条目，由 App 硬链重建）
export DSH_X64_BARE_LIBS="$REPO/.cache/x64libs"
export DSH_VARIANT="$V"

if [ "$V" = "community" ]; then
  # 社区版：公开密钥（community.jks，口令公开）——与正式密钥完全无关
  KEYSTORE_PASS=dsh-community
  KEYSTORE_ALIAS=community
else
  export DSH_KEYSTORE="$REPO/.cache/local-debug.jks"
  KEYSTORE_PASS=dshandroid
  KEYSTORE_ALIAS=dsh
fi
export KEYSTORE_PASS KEYSTORE_ALIAS

# ⚠ v1.21：模拟器包**绝不能覆盖真机交付包**！
# 交付路径固定为 android-app/DeepSeekHarness-community.apk（arm64、102.83MiB）。
# 曾经因为模拟器构建写同一路径，把 x86_64 的库带进了交付文件（真机一装就崩，
# 日志：libz.so is for EM_X86_64 (62) instead of EM_AARCH64 (183)）。
# build.sh 支持 DSH_APK_OUT，这里让模拟器包写到带 -x64 后缀的另一个文件名。
export DSH_APK_OUT="$REPO/android-app/DeepSeekHarness-$V-x64.apk"

cd "$REPO/android-app"
sh build.sh
