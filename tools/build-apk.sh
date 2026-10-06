#!/usr/bin/env bash
# DeepSeek Harness 安卓版 —— 一键复现构建
#
# 目标：任何人 clone 本仓库后，一条命令构建出可用的 arm64 APK，无需手工准备 devhome。
#
#   bash tools/build-apk.sh                 # 全自动（第一次会下载约 150MB 上游 APK）
#   bash tools/build-apk.sh --clean         # 清缓存重来
#   bash tools/build-apk.sh --emulator      # 额外适配 x86_64 模拟器（ndk_translation）
#   bash tools/build-apk.sh --apk ~/x.apk   # 用本地 APK，不下载
#
# 原理：本仓库不含 node 运行时与已打补丁的 DSH 树。这两样是**上游 Release 的 APK**
# 里的 assets/payload.zip 提供的成品；本脚本把它取出来组装成 build.sh 需要的 devhome。
# 详见 BUILD.md 与 tools/LOCAL-BUILD.md。
set -euo pipefail

# ── 可调常量（上游更新时改这里）────────────────────────────────────────────
UPSTREAM_REPO="woaiys3/deepseek-harness-android-app"
UPSTREAM_TAG="v1.17.3"
UPSTREAM_ASSET="DeepSeekHarness-official-${UPSTREAM_TAG}.apk"
# 发布页公布的 md5；上游改动资产时这里会不匹配，从而拒绝对不上号的包
UPSTREAM_MD5="1341959ce4e347f8fc5e761966bbf3bd"
# payload.zip 顶层应有这些目录
PAYLOAD_TOP="bin dshhome dshroot git npm pnpm python rish runtime"
# 本地调试密钥的口令（仅本地自签用；正式分发请用 --keystore 指定自己的密钥）
LOCAL_KEY_PASS="dshandroid"
LOCAL_KEY_ALIAS="dsh"

# ── 参数解析 ──────────────────────────────────────────────────────────────
REPO="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$REPO/.cache"
OUT="$REPO/android-app/DeepSeekHarness.apk"
APK_IN=""
KEYSTORE=""
UPSTREAM_TAG_OPT=""
DO_CLEAN=0
DO_EMULATOR=0
DO_VERIFY=1
DO_SMOKE=0
ANDROID_JAR_OPT=""
DSH_VARIANT="${DSH_VARIANT:-official}"
VARIANT_SET=0

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

选项：
  --upstream <tag>    指定上游 Release tag（默认见脚本顶部 UPSTREAM_TAG）
  --apk <path>        用本地 APK（跳过下载与 md5 校验）
  --md5 <hex>         覆盖期望的 md5
  --variant <name>    变体：official | lite | compat | community
                      （真源 android-app/variants.sh：包名/端口/外部目录/显示名/默认密钥）
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
    --upstream) UPSTREAM_TAG_OPT="${2:?}"; shift 2 ;;
    --apk)      APK_IN="${2:?}"; shift 2 ;;
    --md5)      UPSTREAM_MD5="${2:?}"; shift 2 ;;
    --variant)  DSH_VARIANT="${2:?}"; VARIANT_SET=1; shift 2 ;;
    --keystore) KEYSTORE="${2:?}"; shift 2 ;;
    --android-jar) ANDROID_JAR_OPT="${2:?}"; shift 2 ;;
    --out)      OUT="${2:?}"; shift 2 ;;
    --cache)    CACHE="${2:?}"; shift 2 ;;
    --emulator) DO_EMULATOR=1; shift ;;
    --smoke)    DO_SMOKE=1; shift ;;
    --no-verify) DO_VERIFY=0; shift ;;
    --clean)    DO_CLEAN=1; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) die "未知参数：$1（用 --help 看用法）" ;;
  esac
done

if [ -n "$UPSTREAM_TAG_OPT" ]; then
  UPSTREAM_TAG="$UPSTREAM_TAG_OPT"
  UPSTREAM_ASSET="DeepSeekHarness-official-${UPSTREAM_TAG}.apk"
fi

# ── 变体（B11）：真源 android-app/variants.sh，build.sh 也会 source 同一份 ──
REPO_VARIANTS="$REPO/android-app/variants.sh"
[ -f "$REPO_VARIANTS" ] || die "缺少 $REPO_VARIANTS（变体表）"
. "$REPO_VARIANTS"
# 产物名：official 保持历史名（兼容既有文档/脚本），其余变体带后缀
if [ "$VARIANT_SET" = 1 ] && [ "$OUT" = "$REPO/android-app/DeepSeekHarness.apk" ]; then
  if [ "$DSH_VARIANT" != "official" ]; then
    OUT="$REPO/android-app/DeepSeekHarness-$DSH_VARIANT.apk"
  fi
fi
echo "   变体        : $DSH_VARIANT（appId=$V_APP_ID 引擎=$V_PORT 外部目录=$V_EXT_DIR）"

# ── 0. 预检 ───────────────────────────────────────────────────────────────
info "0/7 预检环境"

need() { command -v "$1" >/dev/null 2>&1 || die "缺少 $1${2:+（$2）}"; }
need curl   "下载上游 APK 用"
need unzip  "解压 payload.zip 用"
need tar    "组装 dshroot 用"
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
echo "缓存状态（$CACHE）："
if [ -n "$APK_IN" ]; then
  echo "   上游 APK      使用 --apk 指定的本地文件（跳过下载与 md5 校验）"
else
  cache_state "$CACHE/$UPSTREAM_ASSET" "上游 APK"
fi
cache_state "$CACHE/payload.zip" "payload.zip" "$([ -f "$CACHE/.payload-from" ] && echo "标记 $(cat "$CACHE/.payload-from")")"
cache_state "$CACHE/devhome" "devhome" "$([ -f "$CACHE/devhome/.assembled-from" ] && echo "标记 $(cat "$CACHE/devhome/.assembled-from")")"
cache_state "$CACHE/local-debug.jks" "签名密钥"
echo "   （缓存失效时自动重建；--clean 强制重建，但保留签名密钥）"

# ── 1. 取上游 APK ─────────────────────────────────────────────────────────
mark "预检"
info "1/7 获取上游 APK（$UPSTREAM_TAG）"

# GitHub 的 release 直链以 github.com 开头。部分地区 github.com 不可达，
# 但 api.github.com 仍可用 —— 此时可用 API 端点（Accept: application/octet-stream）取资产。
API_BASE="https://api.github.com/repos/$UPSTREAM_REPO/releases"
UA="dsh-android-build"

resolve_asset_id() {
  local json id
  json="$(curl -sL --fail --max-time 60 -H "User-Agent: $UA" \
            "$API_BASE/tags/$UPSTREAM_TAG")" || return 1
  id="$(printf '%s' "$json" | tr '{' '\n' | awk -v want="$UPSTREAM_ASSET" '
      { id = ""
        if (match($0, /"id":[0-9]+/)) id = substr($0, RSTART+5, RLENGTH-5)
        if (index($0, "\"name\":\"" want "\"") && id != "") { print id; exit } }')"
  [ -n "$id" ] || return 1
  printf '%s' "$id"
}

download_apk() {  # $1=目标文件；成功后文件就位。失败返回非零
  local dest="$1" id
  # 方式 1：直连 release 直链（大多数地区可用）
  local direct="https://github.com/$UPSTREAM_REPO/releases/download/$UPSTREAM_TAG/$UPSTREAM_ASSET"
  echo "   下载（直连）：$direct"
  if curl -L --fail --retry 2 --retry-delay 2 -C - -o "$dest" "$direct" 2>/dev/null; then
    return 0
  fi
  warn "直连 github.com 失败，改用 GitHub API 端点（api.github.com）"
  # 方式 2：API 端点 —— 绕过被阻断的 github.com
  if ! id="$(resolve_asset_id)"; then
    warn "也无法通过 API 解析资产 id（api.github.com 可能同样不可达）"
    return 1
  fi
  echo "   asset id = $id"
  if curl -L --fail --retry 5 --retry-delay 3 --retry-all-errors -C - \
       -H "Accept: application/octet-stream" -H "User-Agent: $UA" \
       -o "$dest" "$API_BASE/assets/$id"; then
    return 0
  fi
  return 1
}

if [ -n "$APK_IN" ]; then
  [ -f "$APK_IN" ] || die "找不到 APK：$APK_IN"
  SRC_APK="$APK_IN"
  echo "   使用本地 APK：$SRC_APK"
else
  SRC_APK="$CACHE/$UPSTREAM_ASSET"
  if [ -f "$SRC_APK" ] && [ -n "$UPSTREAM_MD5" ] && \
     [ "$(file_md5 "$SRC_APK")" = "$UPSTREAM_MD5" ]; then
    echo "   命中缓存：$SRC_APK"
  else
    [ -f "$SRC_APK" ] && { warn "缓存 md5 不符，重新下载"; rm -f "$SRC_APK"; }
    download_apk "$SRC_APK.part" || die "下载失败（已尝试直连与 GitHub API 两条路）。
   若是网络原因，可：
     · 手动下载后 --apk <path>
     · 重跑本脚本（会从 $SRC_APK.part 断点续传）"
    mv "$SRC_APK.part" "$SRC_APK"
  fi
fi

if [ -n "$UPSTREAM_MD5" ]; then
  GOT_MD5="$(file_md5 "$SRC_APK")"
  if [ "$GOT_MD5" != "$UPSTREAM_MD5" ]; then
    warn "md5 不匹配"
    warn "  期望：$UPSTREAM_MD5"
    warn "  实际：$GOT_MD5"
    die "上游资产可能已变动。确认无误后用 --md5 $GOT_MD5 显式接受，或改用 --apk"
  fi
  echo "   md5 校验通过：$GOT_MD5"
fi
# 用于判断下游缓存是否还有效（换 APK 必须重新解压/组装）
SRC_MD5="$(file_md5 "$SRC_APK")"
SRC_ID="$UPSTREAM_TAG@$SRC_MD5"

# ── 2. 抽出 payload.zip ───────────────────────────────────────────────────
mark "获取上游 APK"
info "2/7 从 APK 提取 assets/payload.zip"
PAYLOAD="$CACHE/payload.zip"
PAYLOAD_STAMP="$CACHE/.payload-from"
if [ -f "$PAYLOAD" ] && [ -f "$PAYLOAD_STAMP" ] \
   && [ "$(cat "$PAYLOAD_STAMP" 2>/dev/null)" = "$SRC_ID" ]; then
  echo "   复用缓存（来自 $SRC_MD5）"
else
  rm -f "$PAYLOAD"
  unzip -o -q "$SRC_APK" "assets/payload.zip" -d "$CACHE/apkx" || die "APK 里找不到 assets/payload.zip"
  mv -f "$CACHE/apkx/assets/payload.zip" "$PAYLOAD"
  rm -rf "$CACHE/apkx"
  printf '%s\n' "$SRC_ID" > "$PAYLOAD_STAMP"
  echo "   已提取"
fi
echo "   payload.zip：$(du -h "$PAYLOAD" | cut -f1)"

# ── 3. 组装 devhome ───────────────────────────────────────────────────────
mark "提取 payload.zip"
info "3/7 组装 devhome（build.sh 需要的 runtime/dshroot/.dsh/rish 等）"
H="$CACHE/devhome"
STAMP="$H/.assembled-from"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$SRC_ID" ]; then
  echo "   复用缓存（$SRC_ID）—— 跳过 440MB 解压"
else
  rm -rf "$H"; mkdir -p "$H"
  echo "   解压 payload（约 440MB，稍候）…"
  unzip -q -o "$PAYLOAD" -d "$H" || die "解压 payload.zip 失败"

  # 顶层结构自检
  for d in $PAYLOAD_TOP; do
    [ -e "$H/$d" ] || die "payload 结构异常：缺少 $d/（上游打包方式可能变了）"
  done

  # 修正 1：payload 里叫 dshhome，build.sh 要的是 .dsh
  [ -d "$H/dshhome" ] && mv "$H/dshhome" "$H/.dsh"
  # 修正 2：payload 把 git 主程序放在 bin/git，build.sh 要 git/bin/git
  if [ -f "$H/bin/git" ] && [ ! -f "$H/git/bin/git" ]; then
    mkdir -p "$H/git/bin"; mv "$H/bin/git" "$H/git/bin/git"
  fi
  printf '%s\n' "$SRC_ID" > "$STAMP"
  echo "   组装完成：$H"
fi

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
info "4/7 签名密钥"
if [ -z "$KEYSTORE" ] && [ "$DSH_VARIANT" = "community" ]; then
  # 社区版：仓库内公开密钥（口令同样公开，故意如此）——与正式 release.jks 完全无关。
  KEYSTORE="$REPO/android-app/$V_KEY_FILE"
  [ -f "$KEYSTORE" ] || die "缺少社区密钥 $KEYSTORE（应随仓库分发，见 android-app/README.md）"
  KEYSTORE_PASS="${KEYSTORE_PASS:-dsh-community}"
  KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-community}"
  echo "   使用社区公开密钥：$KEYSTORE（口令公开：dsh-community）"
  warn "社区密钥是【公开】的，只用于社区自助构建；绝不用于签名正式发布物。"
elif [ -z "$KEYSTORE" ]; then
  KEYSTORE="$CACHE/local-debug.jks"
  if [ ! -f "$KEYSTORE" ]; then
    echo "   生成本地调试密钥：$KEYSTORE"
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$LOCAL_KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$LOCAL_KEY_PASS" -keypass "$LOCAL_KEY_PASS" \
      -dname "CN=DSH Android Local Debug, O=Local, C=CN" >/dev/null 2>&1 \
      || die "keytool 生成密钥失败"
  fi
  KEYSTORE_PASS="$LOCAL_KEY_PASS"
  KEYSTORE_ALIAS="$LOCAL_KEY_ALIAS"
  warn "使用本地调试密钥签名（口令固定为 '$LOCAL_KEY_PASS'）。"
  warn "  它不能用于覆盖安装官方版或他人构建的版本 —— 升级必须同一密钥。"
  warn "  正式分发请用 --keystore <你的.jks> 指定，并在安全处备份。"
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
info "5/7 构建 APK"
export DSH_DEV_HOME="$H"
export JAVA_BIN
export ANDROID_JAR
export KEYSTORE_PASS KEYSTORE_ALIAS
export DSH_VARIANT
# build.sh 默认用 android-app/release.jks；这里指向我们实际选择的密钥
export DSH_KEYSTORE="$KEYSTORE"

if [ "$DO_EMULATOR" = 1 ]; then
  X64="$CACHE/x64libs"
  if [ ! -f "$X64/libz.so" ]; then
    die "--emulator 需要 x86_64 的 libz.so/libssl.so/libcrypto.so 放在 $X64/
   （从模拟器取：adb pull /system/lib64/libz.so $X64/ 等三个；详见 tools/LOCAL-BUILD.md 第四节）"
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
if [ "$DSH_VARIANT" = "official" ]; then
  BUILT="$REPO/android-app/DeepSeekHarness.apk"
else
  BUILT="$REPO/android-app/DeepSeekHarness-$DSH_VARIANT.apk"
fi
[ -f "$BUILT" ] || die "构建脚本报成功但没有产物"
if [ "$BUILT" != "$OUT" ]; then
  mv -f "$BUILT" "$OUT"
fi
echo "   产物：$OUT（$(du -h "$OUT" | cut -f1)）"

# ── 6. 校验 ───────────────────────────────────────────────────────────────
mark "构建 APK"
if [ "$DO_VERIFY" = 1 ]; then
  info "6/7 校验"
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
"
  LAYER="$H/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules"
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
    echo "   ✅ 补丁特征串：$TOTAL/$TOTAL 命中（确认拿到的是已打补丁的内核树）"
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
  info "6/7 校验（已用 --no-verify 跳过）"
fi

# ── 7. 构建信息 ───────────────────────────────────────────────────────────
mark "校验"
info "7/7 记录构建信息"
INFO="$OUT.build-info.txt"
{
  echo "构建时间        : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "仓库提交        : $(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo '（非 git 仓库）')"
  echo "分支            : $(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '-')"
  echo "上游 Release    : $UPSTREAM_TAG"
  echo "上游 APK md5    : $(file_md5 "$SRC_APK")"
  echo "payload.zip     : $(du -h "$PAYLOAD" | cut -f1)"
  echo "产物            : $OUT"
  echo "产物大小        : $(du -h "$OUT" | cut -f1)"
  echo "产物 SHA-256    : $(file_sha256 "$OUT")"
  echo "签名指纹 SHA256 : $(keytool -list -v -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" 2>/dev/null | sed -n 's/.*SHA256: //p' | head -1)"
  echo "JDK             : $(javac -version 2>&1)"
  echo "build-tools     : $BT_NAME"
  echo "android.jar     : $ANDROID_JAR"
  echo "模拟器适配      : $([ "$DO_EMULATOR" = 1 ] && echo yes || echo no)"
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

echo
echo "✅ 构建完成：$OUT"
echo "   构建信息：$INFO"
echo
echo "各阶段耗时："
printf '%s' "$TIMINGS"
echo "   总计                        $(($(date +%s) - START_TS)) 秒"
echo "   （若「构建 APK」异常缓慢，多半是杀软实时扫描刚写出的 payload ——"
echo "     见 BUILD.md 第 5 节「构建很慢」）"
