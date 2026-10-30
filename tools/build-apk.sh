#!/usr/bin/env bash
# DeepSeek Harness 安卓版 —— 一键复现构建（单一构建路径）
#
#   bash tools/build-apk.sh                      # 构建（首次会装内核 + 取运行时资产，之后走缓存）
#   bash tools/build-apk.sh --emulator           # 额外适配 x86_64 模拟器（ndk_translation）
#   bash tools/build-apk.sh --original-look      # 用上游原版图标 + 原版悬浮窗形象
#   （本版：只构建社区版 community，无 --variant 参数）
#   bash tools/build-apk.sh --clean              # 清缓存重来（内核缓存也清，会重新联网装）
#   bash tools/build-apk.sh --out ~/x.apk        # 指定产物路径
#
# 两个输入都由**官方渠道**取得，且都 pin 住版本：
#   · 内核树：官方 npm registry 的 @deepseek-ai/dsh@0.2.1-rc.2 + 仓库内冻结 lock
#             （tools/fetch-kernel.sh，带 .cache/kernel/*.tar 构建缓存）
#   · 运行时：我们自己的 arm64 运行时资产（tools/fetch-runtime.sh，sha256 pin）
# 详见 BUILD.md 与 android-app/kernel/README.md。

# ── 可调常量 ──────────────────────────────────────────────────────────────
# 内核版本与运行时资产都 pin 在各自脚本里，本脚本不另设版本常量：
#   · 内核：android-app/kernel/package-lock.json（+ tools/fetch-kernel.sh 的安装参数）
#   · 运行时：tools/fetch-runtime.sh 顶部的 RUNTIME_TAG / RUNTIME_ASSET / RUNTIME_SHA256_DEFAULT
# ── 参数解析 ──────────────────────────────────────────────────────────────
REPO="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$REPO/.cache"
OUT="$REPO/android-app/DeepSeekHarness.apk"
KEYSTORE=""
DO_CLEAN=0
DO_EMULATOR=0
DO_EMU_LIBS=1
DO_VERIFY=1
DO_SMOKE=0
ANDROID_JAR_OPT=""
DSH_VARIANT="community"   # 本版：只保留社区版（见 android-app/variants.sh）
DO_ORIGINAL_LOOK=0
OUT_SET=0

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

选项：
  --original-look     用**上游原版形象**构建：桌面图标 = 品牌蓝方块+白鲸鱼（与上游官方 APK
                      同一份矢量），悬浮窗 = 上游那只鲸鱼。默认（不带此参数）仍是我们现在的
                      卡通形象（头像特写图标 + 全身立绘悬浮窗）。
                      产物名会自动加 -original 后缀，避免覆盖卡通包。
  --keystore <path>   签名密钥（默认 official/lite/compat 用 .cache 里自动生成的本地调试密钥，
                      community 用仓库内公开的 android-app/community.jks）
  --android-jar <p>   android.jar（默认自动从 Android SDK 里挑）
  --out <path>        产物路径（默认 android-app/DeepSeekHarness.apk）
  --cache <dir>       缓存目录（默认 <仓库>/.cache）
  --emulator          同时适配 x86_64 模拟器（ndk_translation）
  --smoke             构建后若连着设备，安装并检查引擎端口
  --no-verify         跳过构建后校验
  --clean             清空缓存后重建
  -h, --help          显示本帮助
EOF
}

die()  { echo "!! $*" >&2; exit 1; }
info() { echo "== $*"; }
warn() { echo "   !! $*" >&2; }

# 分阶段计时：构建结束后打印，便于在任意机器上一眼看出哪一步异常缓慢
START_TS=$(date +%s)
T0="$START_TS"
TIMINGS=""
mark() {  # $1=阶段名
  local now; now=$(date +%s)
  TIMINGS="${TIMINGS}$(printf '   %-26s %5d 秒' "$1" "$((now - T0))")"$'\n'
  T0="$now"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --keystore) KEYSTORE="${2:?}"; shift 2 ;;
    --android-jar) ANDROID_JAR_OPT="${2:?}"; shift 2 ;;
    --out)      OUT="${2:?}"; OUT_SET=1; shift 2 ;;
    --cache)    CACHE="${2:?}"; shift 2 ;;
    --emulator) DO_EMULATOR=1; shift ;;
    --no-emu-libs) DO_EMU_LIBS=0; shift ;;
    --original-look) DO_ORIGINAL_LOOK=1; shift ;;
    --smoke)    DO_SMOKE=1; shift ;;
    --no-verify) DO_VERIFY=0; shift ;;
    --clean)    DO_CLEAN=1; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) die "未知参数：$1（用 --help 看用法）" ;;
  esac
done

# ── 变体（B11）：真源 android-app/variants.sh，build.sh 也会 source 同一份 ──
REPO_VARIANTS="$REPO/android-app/variants.sh"
[ -f "$REPO_VARIANTS" ] || die "缺少 $REPO_VARIANTS（变体表）"
. "$REPO_VARIANTS"
# 产物名：official 保持历史名（兼容既有文档/脚本），其余变体带后缀
# 产物命名：社区版 DeepSeekHarness-community.apk（必须在 -original 后缀之前算）
if [ "$OUT" = "$REPO/android-app/DeepSeekHarness.apk" ]; then
  OUT="$REPO/android-app/DeepSeekHarness$V_SUFFIX.apk"
fi
# 外观：原版形象单独一个产物名，绝不覆盖卡通包（两者包名相同，装哪个就是哪套形象）
# 调用方用 --out 显式指定路径时不加后缀（尊重调用方命名）
if [ "$DO_ORIGINAL_LOOK" = 1 ]; then
  [ "$OUT_SET" = 1 ] || OUT="${OUT%.apk}-original.apk"
  export DSH_LOOK=original
else
  export DSH_LOOK="${DSH_LOOK:-cartoon}"
fi
echo "   变体        : $DSH_VARIANT（唯一；appId=$V_APP_ID 引擎=$V_PORT 外部目录=$V_EXT_DIR）"
echo "   外观        : $DSH_LOOK（cartoon=卡通形象｜original=上游原版蓝鲸鱼）"

# ── 0. 预检 ───────────────────────────────────────────────────────────────
info "0/6 预检环境"

need() { command -v "$1" >/dev/null 2>&1 || die "缺少 $1${2:+（$2）}"; }
need curl   "下载运行时资产用"
need unzip  "读取产物内的 payload 做完整性核对用"
need tar    "解包内核缓存 / 运行时资产用"
need npm    "装内核树用（官方 registry 冻结安装）"
need javac  "JDK；请设 JAVA_HOME 或把 \$JAVA_HOME/bin 放进 PATH"
need keytool "JDK 自带；用于生成/查看签名密钥"

# md5：GNU(md5sum) / BSD(md5) 都支持
if command -v md5sum >/dev/null 2>&1; then
  file_md5() { md5sum "$1" | awk '{print $1}'; }
elif command -v md5 >/dev/null 2>&1; then
  file_md5() { md5 -q "$1"; }
else
  die "缺少 md5sum 或 md5（coreutils）"
fi

# sha256（B6）：与 md5 同源，用于公示产物哈希 —— 社区密钥是公开的，
# 来源信号靠"构建输入可核对 + 产物哈希可复现"，不再靠"谁持有私钥"。
if command -v sha256sum >/dev/null 2>&1; then
  file_sha256() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  file_sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  file_sha256() { echo "(缺少 sha256sum/shasum)"; }
fi

JAVA_BIN="$(dirname "$(command -v javac)")"
echo "   javac        : $(javac -version 2>&1)"
echo "   unzip/curl   : ok"

# Android SDK 探测
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for c in "$LOCALAPPDATA/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /usr/lib/android-sdk; do
    [ -d "$c" ] && { SDK="$c"; break; }
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "找不到 Android SDK。请设 ANDROID_HOME 指向 SDK 根目录"

# 工具链版本：优先用本项目已验证过的版本，找不到才退到最高版本并提示。
# （不直接用"最高版本"是因为那会让不同机器装了什么版本就编出什么产物，破坏可复现性）
PREF_BUILD_TOOLS="35.0.0"
PREF_PLATFORM="android-36"

pick_pref() {  # $1=目录；其后为优先名，按序取第一个存在的；都没有则取版本最高者
  local d="$1"; shift
  local p
  for p in "$@"; do [ -d "$d/$p" ] && { printf '%s' "$p"; return; }; done
  ls -1 "$d" 2>/dev/null | sort -V | tail -1
}

BT_NAME="$(pick_pref "$SDK/build-tools" "$PREF_BUILD_TOOLS")"
[ -n "$BT_NAME" ] || die "SDK 里没有 build-tools：$SDK/build-tools（用 sdkmanager 装 build-tools;$PREF_BUILD_TOOLS）"
[ "$BT_NAME" = "$PREF_BUILD_TOOLS" ] || \
  warn "本机没有已验证的 build-tools $PREF_BUILD_TOOLS，改用 $BT_NAME（产物可能与文档记录的略有差异）"
APK_TOOLS="$SDK/build-tools/$BT_NAME"
for t in aapt zipalign; do
  [ -x "$APK_TOOLS/$t" ] || [ -f "$APK_TOOLS/$t.exe" ] || die "build-tools 缺 $t：$APK_TOOLS"
done
# d8/apksigner 在 Windows 上是 .bat，由 android-app/build.sh 自己按 uname 选择
[ -f "$APK_TOOLS/d8" ] || [ -f "$APK_TOOLS/d8.bat" ] || die "build-tools 缺 d8：$APK_TOOLS"
[ -f "$APK_TOOLS/apksigner" ] || [ -f "$APK_TOOLS/apksigner.bat" ] || die "build-tools 缺 apksigner：$APK_TOOLS"
echo "   build-tools  : $BT_NAME"

# android.jar
if [ -n "$ANDROID_JAR_OPT" ]; then
  ANDROID_JAR="$ANDROID_JAR_OPT"
else
  PLAT="$(pick_pref "$SDK/platforms" "$PREF_PLATFORM")"
  [ "$PLAT" = "$PREF_PLATFORM" ] || \
    warn "本机没有已验证的 $PREF_PLATFORM，改用 $PLAT 编译（targetSdk 仍为 28，不影响安装）"
  ANDROID_JAR="$SDK/platforms/$PLAT/android.jar"
fi
[ -f "$ANDROID_JAR" ] || die "找不到 android.jar：$ANDROID_JAR（用 sdkmanager 装 platforms;android-36，或 --android-jar 指定）"
echo "   android.jar  : $ANDROID_JAR"

# 磁盘空间（payload 解压约 440MB，staging 再 440MB，产物 150MB）
if command -v df >/dev/null 2>&1; then
  AVAIL_KB="$(df -Pk "$REPO" | awk 'NR==2{print $4}')"
  if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt 2000000 ]; then
    warn "仓库所在盘可用空间仅 $((AVAIL_KB/1024)) MB，建议留 2 GB 以上"
  fi
fi

if [ "$DO_CLEAN" = 1 ]; then
  info "清理缓存 $CACHE"
  # 保留本地调试密钥：换了密钥就无法覆盖安装此前构建的版本（升级必须同一密钥）
  KEEP="$CACHE/local-debug.jks"
  if [ -f "$KEEP" ]; then
    mv "$KEEP" "$CACHE/../.keep-keystore.tmp"
    KEPT=1
  fi
  rm -rf "$CACHE"; mkdir -p "$CACHE"
  if [ "${KEPT:-0}" = 1 ]; then
    mv "$CACHE/../.keep-keystore.tmp" "$KEEP"
    echo "   已保留签名密钥：$KEEP"
  fi
fi
mkdir -p "$CACHE"

# 缓存状态摘要：让人一眼看出这次会复用哪些、跳过哪些
cache_state() {  # $1=路径 $2=名称 $3=附加说明
  if [ -e "$1" ]; then
    printf '   %-14s 已有 %-7s %s\n' "$2" "$(du -sh "$1" 2>/dev/null | cut -f1)" "${3:-}"
  else
    printf '   %-14s （无）\n' "$2"
  fi
}
RT_ASSET_NAME="$(sed -n 's/^RUNTIME_ASSET="\(.*\)"/\1/p' "$REPO/tools/fetch-runtime.sh" | head -1)"
RT_SHA_PIN="$(sed -n 's/^RUNTIME_SHA256_DEFAULT="\(.*\)"/\1/p' "$REPO/tools/fetch-runtime.sh" | head -1)"
RT_ASSET_STAMP="runtime-asset@$(printf '%s' "$RT_SHA_PIN" | cut -c1-16)"
KERNEL_TAR="$(ls -1 "$CACHE"/kernel/*.tar 2>/dev/null | head -1 || true)"
echo "缓存状态（$CACHE）："
cache_state "$CACHE/kernel" "内核缓存" "$( [ -n "$KERNEL_TAR" ] && echo "标记 $(basename "$KERNEL_TAR")（命中则离线、不重装）" || echo "（首次构建从官方 registry 安装）" )"
cache_state "$CACHE/$RT_ASSET_NAME" "运行时资产" "$( [ -f "$CACHE/$RT_ASSET_NAME" ] && echo "sha256 $(printf '%s' "$RT_SHA_PIN" | cut -c1-16)…" )"
cache_state "$CACHE/devhome" "devhome" "$([ -f "$CACHE/devhome/.assembled-from" ] && echo "标记 $(cat "$CACHE/devhome/.assembled-from")")"
cache_state "$REPO/android-app/$V_KEY_FILE" "签名密钥（仓库内公开密钥）"
echo "   （缓存失效时自动重建；--clean 强制重建，但保留签名密钥）"

# ── 1. 组装 devhome（build.sh 的输入：arm64 运行时 + profile 脚手架）──────────
# 内核树**不在这里**：android-app/build.sh 会调 tools/fetch-kernel.sh 从官方 npm registry
# 按仓库内冻结 lock 安装它（自带 tar 缓存）。本脚本只负责运行时与工具链。
mark "预检"
info "1/3 组装 devhome（arm64 运行时资产 → $CACHE/devhome）"
H="$CACHE/devhome"
STAMP="$H/.assembled-from"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$RT_ASSET_STAMP" ]; then
  echo "   复用缓存（$RT_ASSET_STAMP）—— 跳过解包"
else
  rm -rf "$H"
  mkdir -p "$H"
  bash "$REPO/tools/fetch-runtime.sh" "$H" || die "运行时资产获取/解包失败"
  printf '%s\n' "$RT_ASSET_STAMP" > "$STAMP"
fi
SRC_ID="$RT_ASSET_STAMP"

# 工具链 env.sh（build.sh 第 7 行 source 它）
# Windows 路径（C:\a\b）转 Git Bash 形式（/c/a/b）
POSIX() {
  local p="$1"
  case "$p" in
    [A-Za-z]:[\\/]*)
      p="/$(printf '%s' "${p:0:1}" | tr 'A-Z' 'a-z')$(printf '%s' "${p:2}" | tr '\\' '/')" ;;
  esac
  printf '%s' "$p"
}
mkdir -p "$H/build"
cat > "$H/build/env.sh" <<EOF
# 由 tools/build-apk.sh 生成
export JAVA_HOME="\${JAVA_HOME:-$(POSIX "$JAVA_BIN")/..}"
export PATH="\$JAVA_HOME/bin:\$PATH"
export APK_TOOLS="\${APK_TOOLS:-$(POSIX "$APK_TOOLS")}"
export PATH="\$APK_TOOLS:\$PATH"
EOF

# 内核版本标记自检
DSH_PKG="$H/dshroot/lib/node_modules/@deepseek-ai/dsh/package.json"
if [ -f "$DSH_PKG" ]; then
  echo "   内核版本：$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$DSH_PKG" | head -1)"
fi

# ── 4. 签名密钥 ───────────────────────────────────────────────────────────
mark "组装 devhome"
info "2/3 签名密钥"
if [ -z "$KEYSTORE" ]; then
  # 社区版：仓库内公开密钥（口令同样公开，故意如此）——与正式 release.jks 完全无关。
  KEYSTORE="$REPO/android-app/$V_KEY_FILE"
  [ -f "$KEYSTORE" ] || die "缺少社区密钥 $KEYSTORE（应随仓库分发，见 android-app/README.md）"
  KEYSTORE_PASS="${KEYSTORE_PASS:-dsh-community}"
  KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-community}"
  echo "   使用社区公开密钥：$KEYSTORE（口令公开：dsh-community）"
  warn "社区密钥是【公开】的，只用于社区自助构建；绝不用于签名正式发布物。"
else
  [ -f "$KEYSTORE" ] || die "找不到密钥：$KEYSTORE"
  KEYSTORE_PASS="${KEYSTORE_PASS:?请 export KEYSTORE_PASS=密钥口令}"
  KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-dsh}"
  echo "   使用指定密钥：$KEYSTORE"
fi
echo "   指纹：$(keytool -list -v -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" 2>/dev/null \
  | sed -n 's/.*SHA256: //p' | head -1)"

# ── 5. 构建 ───────────────────────────────────────────────────────────────
mark "签名密钥"
info "3/3 构建 APK"
export DSH_DEV_HOME="$H"
export JAVA_BIN
export ANDROID_JAR
export KEYSTORE_PASS KEYSTORE_ALIAS
export DSH_VARIANT
# 外观（cartoon | original）：build.sh 据此选图标与悬浮窗资源（见其「外观」段）
export DSH_LOOK
# build.sh 默认用 android-app/release.jks；这里指向我们实际选择的密钥
export DSH_KEYSTORE="$KEYSTORE"

if [ "$DO_EMULATOR" = 1 ]; then
  X64="$CACHE/x64libs"
  if [ ! -f "$X64/libz.so" ]; then
    # v1.29：干净 clone 没有 .cache → 回退到仓库内已入库的 guest 库
    X64_REPO="$REPO/android-app/emu-libs/x86_64"
    if [ -f "$X64_REPO/libz.so" ]; then
      X64="$X64_REPO"
      echo "   x64 guest 库 : 使用仓库内 android-app/emu-libs/x86_64（.cache 里没有）"
    else
      die "--emulator 需要 x86_64 的 libz.so/libssl.so/libcrypto.so 放在 $CACHE/x64libs/
   （从模拟器取：adb pull /system/lib64/libz.so 等；或放一份到 android-app/emu-libs/x86_64/）"
    fi
  fi
  export DSH_X64_BARE_LIBS="$X64"
  # v1.21：模拟器包**不要**写成交付文件名 —— 曾因此把 x86_64 库带进交付 APK，
  # 真机 arm64 装上一启动就崩（libz.so is for EM_X86_64 (62) instead of EM_AARCH64 (183)）。
  # 除非调用方用 --out 显式指定，否则自动加 -x64 后缀区分。
  case "$OUT" in
    *-x64.apk) ;;
    *) OUT="${OUT%.apk}-x64.apk" ;;
  esac
  echo "⚠ 模拟器(x86_64)构建，输出改为：$OUT（不要装到真机 arm64）" >&2
  # v1.18：不再设置 DSH_X64_NO_LINKS（清空 LINKS.txt）。App 侧 applyLinks() 现在是**硬链优先**
  # （Os.link → 软链 → 复制兜底），带版本号的 soname 必须由 LINKS.txt 重建，
  # 清空反而会让 node 报 "library libz.so.1 not found"。
  echo "   已启用模拟器适配（DSH_X64_BARE_LIBS；LINKS.txt 保留带版本号条目）"
fi

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
( cd "$REPO/android-app" && bash build.sh ) || die "构建失败（上面有日志）"
BUILT="$REPO/android-app/DeepSeekHarness$V_SUFFIX.apk"
[ -f "$BUILT" ] || die "构建脚本报成功但没有产物"
if [ "$BUILT" != "$OUT" ]; then
  mv -f "$BUILT" "$OUT"
fi
echo "   产物：$OUT（$(du -h "$OUT" | cut -f1)）"

# ── 6. 校验 ───────────────────────────────────────────────────────────────
mark "构建 APK"
if [ "$DO_VERIFY" = 1 ]; then
  # 完整性核对对象 = **产物自己**的 assets/payload.zip（自洽即可；不再依赖任何本地缓存树）
  PAYLOAD="$(mktemp)"
  unzip -p "$OUT" assets/payload.zip > "$PAYLOAD" || die "产物里没有 assets/payload.zip"
  info "校验"
  FAIL=0

  # 6.1 payload 结构
  N_TOP="$(unzip -Z1 "$PAYLOAD" | awk -F/ '{print $1}' | sort -u | grep -c . || true)"
  echo "   payload 顶层目录：$N_TOP 个"
  [ "$N_TOP" -ge 9 ] || { warn "payload 顶层目录数异常"; FAIL=1; }

  # 6.1b payload 完整性清单（B9）：manifest 的 entries/bytes 必须与 payload.zip 实际一致
  MAN_TMP="$(mktemp)"
  if unzip -p "$OUT" assets/payload_manifest.txt > "$MAN_TMP" 2>/dev/null && [ -s "$MAN_TMP" ]; then
    M_ENTRIES="$(sed -n 's/^entries=//p' "$MAN_TMP" | head -1)"
    M_BYTES="$(sed -n 's/^bytes=//p' "$MAN_TMP" | head -1)"
    Z_FILES="$(unzip -Z1 "$PAYLOAD" | grep -vc '/$' || true)"
    # ⚠ 必须要求 NF>=4：unzip -l 末尾那行汇总（"292410597  15701 files"）的第一个字段也是数字，
    #   不加这个条件会把总量算成两倍（本地实测踩过）。
    Z_BYTES="$(unzip -l "$PAYLOAD" | awk 'NR>3 && $1 ~ /^[0-9]+$/ && NF>=4 {s+=$1} END {print s+0}')"
    echo "   清单 entries=$M_ENTRIES bytes=$M_BYTES ／ 实际 files=$Z_FILES bytes=$Z_BYTES"
    if [ "$M_ENTRIES" = "$Z_FILES" ] && [ "$M_BYTES" = "$Z_BYTES" ]; then
      echo "   ✅ payload 清单与 payload.zip 一致"
    else
      warn "payload 清单与 payload.zip 不一致（B9 检查失败）"
      FAIL=1
    fi
  else
    warn "APK 内缺少 assets/payload_manifest.txt（B9 清单，旧构建脚本？）"
    FAIL=1
  fi
  rm -f "$MAN_TMP"

  # 6.2 补丁特征串（证明拿到的是「已打补丁」的内核树）
  #   rel 支持 shell 通配（内核某些 bundle 名带哈希，如 subprocess-local 的 runner-launch-*.js）
  PATCHES="
@deepseek-ai/dsh-fs-local/lib/index.js:isHardlinkUnsupported
@deepseek-ai/dsh-fs-local/lib/index.js:publishCreateWithoutHardlink
@deepseek-ai/dsh-attachment-local/lib/index.js:sharpShim
@deepseek-ai/dsh-attachment-local/lib/sharp-shim.js:sharp
@deepseek-ai/node-addon-system/lib/flock.js:noopBinding
@deepseek-ai/dsh-storage-json/lib/index.js:fsyncDirectory
@deepseek-ai/dsh-client-ui-layout/lib/client.js:dsh-mobile-menu-btn
@deepseek-ai/dsh-app-boot/lib/index.js:DSH_ANDROID_PLUGIN_LOG
@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js:Android
@deepseek-ai/dsh-ptc-runtime-node/lib/index.js:OPENSSL_CONF
@deepseek-ai/dsh-tool-fs-search/lib/index.js:android
@deepseek-ai/dsh-llm-deepseek/lib/index.js:attachment layer
@deepseek-ai/dsh-bash-local/lib/index.js:sandboxMode
# 本 fork 的补丁（v1.20 起）：
@deepseek-ai/dsh-bash-local/lib/index.js:DSH_BASH_PATH
@deepseek-ai/dsh-subprocess-local/lib/runner-launch-*.js:dsh-android-patch
# W4（本版）：Android 环境陷阱写进 bash 工具自己的描述（overlay 里改的是 dsh-tool-bash）
@deepseek-ai/dsh-tool-bash/lib/index.js:Android 环境（本仓库注入
"
  # 校验对象必须是**真正进包的那棵树**（staging），不是构建用的 devhome 缓存 ——
  # 否则会出现"检查过了、包里没有"（本仓库踩过：overlay 只贴了 devhome，staging 是干净的）。
  # 依赖布局：0.2.0-rc.2 嵌套在 dsh/node_modules/ 下；0.2.1-rc.2 起 npm 扁平安装（hoisted）。
  _KN="$REPO/android-app/staging/dshroot/lib/node_modules/@deepseek-ai/dsh"
  if [ -d "$_KN/node_modules/@deepseek-ai" ]; then
    LAYER="$REPO/android-app/staging/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules"
    LAYOUT="nested"
  else
    LAYER="$REPO/android-app/staging/dshroot/lib/node_modules"
    LAYOUT="hoisted"
  fi
  if [ ! -d "$LAYER/@deepseek-ai" ]; then
    warn "找不到 staging 内核树（$LAYER），退回检查 devhome —— 结论不代表产物"
    LAYER="$H/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules"
    LAYOUT="devhome"
  fi
  TOTAL=0
  MISSING="$(printf '%s\n' "$PATCHES" | while IFS=: read -r rel sig; do
      [ -z "$rel" ] && continue
      case "$rel" in \#*) continue ;; esac
      matched=0
      for f in $LAYER/$rel; do            # 通配展开（无通配时就是它自己）
        [ -f "$f" ] || continue
        if grep -qF -- "$sig" "$f" 2>/dev/null; then matched=1; break; fi
      done
      [ "$matched" = "1" ] || echo "$rel  ($sig)"
    done)"
  TOTAL=$(printf '%s\n' "$PATCHES" | grep -c '[^ 	]' || true)
  TOTAL=$(( TOTAL - $(printf '%s\n' "$PATCHES" | grep -c '^#' || true) ))
  if [ -z "$MISSING" ]; then
    echo "   ✅ 补丁特征串：$TOTAL/$TOTAL 命中（确认拿到的是已打补丁的内核树，布局 $LAYOUT）"
  else
    echo "$MISSING" | sed 's/^/   !! 补丁特征缺失：/'
    warn "上游补丁集可能已变化，请对照 dsh-patches/ 更新本脚本的 PATCHES 清单"
    FAIL=1
  fi

  # 6.3 APK 结构与签名
  ENTRIES="$(unzip -Z1 "$OUT" | grep -c . || true)"
  NATIVE="$(unzip -Z1 "$OUT" | grep -c '^lib/' || true)"
  echo "   APK 条目：$ENTRIES（原生库目录 lib/：$NATIVE —— 本方案不含原生库，故 APK 不声明 ABI）"
  APKSIGNER="$APK_TOOLS/apksigner"
  [ -f "$APKSIGNER.bat" ] && APKSIGNER="$APKSIGNER.bat"
  "$APKSIGNER" verify --print-certs "$OUT" >/dev/null 2>&1 \
    && echo "   ✅ 签名校验通过" || { warn "签名校验失败"; FAIL=1; }

  BADGING="$("$APK_TOOLS/aapt" dump badging "$OUT" 2>/dev/null | head -3 || true)"
  echo "$BADGING" | sed 's/^/   /'

  [ "$FAIL" = 0 ] || die "校验未通过（见上面的 !! 行）"
else
  info "校验（已用 --no-verify 跳过）"
fi

# ── 7. 构建信息 ───────────────────────────────────────────────────────────
mark "校验"
info "记录构建信息"
INFO="$OUT.build-info.txt"
{
  echo "构建时间        : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "仓库提交        : $(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo '（非 git 仓库）')"
  echo "分支            : $(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '-')"
  echo "运行时来源      : arm64 运行时资产（tools/fetch-runtime.sh，sha256 pin）"
  echo "内核来源        : 官方 npm registry + android-app/kernel/package-lock.json（$(sed -n 's/.*"@deepseek-ai\/dsh": "\([^"]*\)".*/\1/p' "$REPO/android-app/kernel/package.json" | head -1)）"
  echo "产物            : $OUT"
  echo "产物大小        : $(du -h "$OUT" | cut -f1)"
  echo "产物 SHA-256    : $(file_sha256 "$OUT")"
  echo "签名指纹 SHA256 : $(keytool -list -v -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" 2>/dev/null | sed -n 's/.*SHA256: //p' | head -1)"
  echo "JDK             : $(javac -version 2>&1)"
  echo "build-tools     : $BT_NAME"
  echo "android.jar     : $ANDROID_JAR"
  echo "模拟器适配      : $([ "$DO_EMULATOR" = 1 ] && echo yes || echo no)"
  echo "外观(look)      : $DSH_LOOK（cartoon=卡通形象 / original=上游原版蓝鲸鱼）"
} > "$INFO"
# B6：产物哈希单独落一个 sha256sum 兼容的 sidecar，便于 `sha256sum -c` 核对
( cd "$(dirname "$OUT")" && printf '%s  %s\n' "$(file_sha256 "$OUT")" "$(basename "$OUT")" ) > "$OUT.sha256" 2>/dev/null || true
sed 's/^/   /' "$INFO"
[ -f "$OUT.sha256" ] && echo "   产物哈希：$OUT.sha256（$(cat "$OUT.sha256" | awk '{print substr($1,1,16)}')…）"

# ── 可选：装机冒烟 ────────────────────────────────────────────────────────
if [ "$DO_SMOKE" = 1 ]; then
  info "附加：装机冒烟测试"
  ADB="${ANDROID_HOME:-$SDK}/platform-tools/adb"
  [ -f "$ADB.exe" ] && ADB="$ADB.exe"
  if ! "$ADB" devices 2>/dev/null | grep -qE 'device$'; then
    warn "没有已连接设备，跳过"
  else
    "$ADB" install -r "$OUT" || die "安装失败"
    "$ADB" shell am start -n "$V_APP_ID/$V_APP_ID.MainActivity" >/dev/null 2>&1 || true
    echo "   已安装并拉起（变体 $DSH_VARIANT，包名 $V_APP_ID）；请在 App 内走完权限向导并点「启动引擎」。"
    echo "   引擎就绪判据：adb shell netstat -tlnp | grep $V_PORT"
  fi
fi

mark "记录构建信息"

# ── 本版：模拟器包**自动**补 guest 库（让 `--emulator` 真的"一个参数就够"）──────────────
# 为什么必须做：x86_64 模拟器靠 ndk_translation 跑 arm64 的 node，而**guest 加载器不认
# LD_LIBRARY_PATH**（实测三种形态同样报 `library "libz.so.1" not found`），只搜镜像的
# guest 库目录 /system/lib64/arm64/ —— 那里只有 libz.so，没有 libz.so.1 / libc++_shared.so /
# libicu*.so.78。只打 `--emulator` 包**不足以**在模拟器上跑起来（历史流程里这一步是
# `tools/emu-guestlibs.sh` 手工做的；2026-10 的脚本清理误把它当"重复脚本"删掉，
# 于是"以前加个参数就行"失效 —— 这次把它并回构建流程）。
# 失败**只告警不中断**：构建产物本身是好的，环境不满足（未 -writable-system 启动等）时
# 打印确切修法即可。想跳过：--no-emu-libs
if [ "$DO_EMULATOR" = 1 ] && [ "$DO_EMU_LIBS" = 1 ]; then
  EADB="${ANDROID_HOME:-$SDK}/platform-tools/adb"
  [ -f "$EADB.exe" ] && EADB="$EADB.exe"
  # python 探活（Store 版 python3 是 stub，会以非 0 退出；必须 -c 试一下）
  EPY=""
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys' >/dev/null 2>&1; then EPY="$c"; break; fi
  done
  echo
  if [ -z "$EPY" ]; then
    warn "--emulator：本机没有可用的 python，跳过 guest 库安装（手动跑：python tools/emu-arm64libs.py）"
  elif "$EADB" devices 2>/dev/null | grep -qE '^emulator-[0-9]+\s+device'; then
    info "附加：把 node 的 arm64 依赖补进模拟器 guest 库目录（/system/lib64/arm64/）"
    if "$EPY" "$REPO/tools/emu-arm64libs.py" --adb "$EADB" 2>&1 | sed 's/^/   /'; then
      echo "   ✓ 模拟器已具备运行条件（App 内点「启动引擎」即可）"
    else
      warn "补库未成功。模拟器必须用 -writable-system 启动，并先 adb root && adb remount；"
      warn "详见 BUILD.md §6（也可手动跑：python tools/emu-arm64libs.py）"
    fi
  else
    echo "   （--emulator：未检测到已连接的模拟器，跳过 guest 库安装）"
    echo "    接上模拟器后跑一次：python tools/emu-arm64libs.py"
  fi
fi

echo
echo "✅ 构建完成：$OUT"
echo "   构建信息：$INFO"
echo
echo "各阶段耗时："
printf '%s' "$TIMINGS"
echo "   总计                        $(($(date +%s) - START_TS)) 秒"
echo "   （若「构建 APK」异常缓慢，多半是杀软实时扫描刚写出的 payload ——"
echo "     见 BUILD.md 第 5 节「构建很慢」）"
