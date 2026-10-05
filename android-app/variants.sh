#!/system/bin/sh
# ============================================================================
# 变体表（单一真源）—— 由 android-app/build.sh 与 tools/build-apk.sh source。
#
# 每个变体声明全部"随包名走"的量：appId / 引擎端口 / 外部目录名 / 显示名 /
# 版本后缀 / 默认签名密钥文件。构建期据此生成 AndroidManifest 与 BuildVariant.java，
# 不再靠 contains("beta")/contains("compat") 的硬编码分支（B11 + B12）。
#
# ⚠ 端口与目录必须全局唯一：同一设备上两个变体同时安装时，引擎端口、通知端口、
#    虚拟屏桥/核心端口、外部存储目录都不允许重叠（历史上 8999 互斥导致"虚拟屏二选一"）。
#    规则：引擎端口 = 3080 + 2*idx；通知端口 = 引擎端口 + 1；
#          虚拟屏桥 = 8999 + 10*idx；虚拟屏核心 = 8998 + 10*idx。
#
# 用法：
#   DSH_VARIANT=community bash android-app/build.sh
#   bash tools/build-apk.sh --variant community
# ============================================================================

DSH_VARIANT="${DSH_VARIANT:-official}"

case "$DSH_VARIANT" in
  official)
    V_APP_ID="com.deepseek.harness"
    V_PORT=3080
    V_EXT_DIR="DeepSeekHarness"
    V_LABEL="DeepSeek Harness"
    V_SUFFIX=""
    V_KEY_FILE="release.jks"
    V_VS_BRIDGE_PORT=8999
    V_VS_CORE_PORT=8998
    ;;
  lite)
    V_APP_ID="com.deepseek.harness.beta"
    V_PORT=3082
    V_EXT_DIR="DeepSeekHarnessLite"
    V_LABEL="DeepSeek Harness Lite"
    V_SUFFIX="-lite"
    V_KEY_FILE="release.jks"
    V_VS_BRIDGE_PORT=9009
    V_VS_CORE_PORT=9008
    ;;
  compat)
    V_APP_ID="com.deepseek.harness.compat"
    V_PORT=3084
    V_EXT_DIR="DeepSeekHarnessCompat"
    V_LABEL="DeepSeek Harness 兼容版"
    V_SUFFIX="-compat"
    V_KEY_FILE="release.jks"
    V_VS_BRIDGE_PORT=9019
    V_VS_CORE_PORT=9018
    ;;
  community)
    # 社区版：由公开的 community.jks 签名，与正式版/官方变体可共存（B12/D2）。
    # 密钥是【故意公开】的，只用于社区自助构建；绝不用于签名正式发布物。
    V_APP_ID="com.deepseek.harness.community"
    V_PORT=3086
    V_EXT_DIR="DeepSeekHarnessCommunity"
    V_LABEL="DeepSeek Harness 社区版"
    V_SUFFIX="-community"
    V_KEY_FILE="community.jks"
    V_VS_BRIDGE_PORT=9029
    V_VS_CORE_PORT=9028
    ;;
  *)
    echo "!! 未知变体 DSH_VARIANT=$DSH_VARIANT（可选：official | lite | compat | community）" >&2
    exit 1
    ;;
esac

V_NOTIFY_PORT=$((V_PORT + 1))
# Java 包路径（com.deepseek.harness.community → com/deepseek/harness/community）
V_PKG_PATH=$(printf '%s' "$V_APP_ID" | tr '.' '/')
# 变体主类前缀（供 app_process 拉起 vscreen 特权核心时拼类名）
V_VS_CORE_MAIN="$V_APP_ID.vscreen.Main"

export DSH_VARIANT V_APP_ID V_PORT V_NOTIFY_PORT V_EXT_DIR V_LABEL V_SUFFIX V_KEY_FILE \
       V_VS_BRIDGE_PORT V_VS_CORE_PORT V_PKG_PATH V_VS_CORE_MAIN
