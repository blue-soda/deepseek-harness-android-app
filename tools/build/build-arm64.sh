#!/usr/bin/env bash
# 真机（arm64）构建入口（tools/build/）—— 与 build-local.sh 相同，但不设 DSH_X64_BARE_LIBS。
#
# 为什么单独一个：DSH_X64_BARE_LIBS 是 **x86_64 模拟器适配**（把 x86_64 的
# libz/libssl/libcrypto 塞进 payload，供 ndk_translation 转译器按裸名解析）。
# 一旦打进 APK，真机 arm64 上 node 的 OpenSSL 会 dlopen 失败并**直接崩**：
#   libz.so is for EM_X86_64 (62) instead of EM_AARCH64 (183)
# 真机必须用本脚本构建。
set -e
V="${1:-${DSH_VARIANT:-community}}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
export DSH_DEV_HOME="$REPO/.cache/devhome"
export ANDROID_JAR="$LOCALAPPDATA/Android/Sdk/platforms/android-36/android.jar"
export JAVA_BIN=/c/Programs/jdk-21/bin
export PATH="$(cygpath "$LOCALAPPDATA")/Android/Sdk/build-tools/35.0.0:$PATH"
export JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
export DSH_VARIANT="$V"
if [ "$V" = "community" ]; then
  KEYSTORE_PASS=dsh-community
  KEYSTORE_ALIAS=community
else
  export DSH_KEYSTORE="$REPO/.cache/local-debug.jks"
  KEYSTORE_PASS=dshandroid
  KEYSTORE_ALIAS=dsh
fi
export KEYSTORE_PASS KEYSTORE_ALIAS
unset DSH_X64_BARE_LIBS
echo "== 真机 arm64 构建（未启用模拟器 x86_64 适配）=="
cd "$REPO/android-app"
sh build.sh
