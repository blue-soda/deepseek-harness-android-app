#!/usr/bin/env bash
# 生成一个「桩 devhome」：只包含 android-app/build.sh 要求的目录骨架与占位文件，
# 用来在**没有真实 payload**（runtime / dshroot / .dsh）的机器上跑通打包链。
#
# 产物 APK **不可用**（payload 是假的，引擎起不来），用途只有一个：
# 验证工具链与 build.sh 的 7 步打包流程，并把「还缺什么」隔离出来。
#
# 用法：
#   bash tools/make-devhome-stub.sh [目标目录]        # 默认 build/devhome-stub
# 可用环境变量覆盖工具链探测：
#   JAVA_HOME=... APK_TOOLS=... bash tools/make-devhome-stub.sh
set -euo pipefail

# Windows 路径（C:\a\b）转 Git Bash 形式（/c/a/b）
posix_path() {
  local p="$1"
  case "$p" in
    [A-Za-z]:[\\/]*)
      local d
      d="$(printf '%s' "${p:0:1}" | tr 'A-Z' 'a-z')"
      p="/$d$(printf '%s' "${p:2}" | tr '\\' '/')"
      ;;
  esac
  printf '%s' "$p"
}

OUT="${1:-build/devhome-stub}"
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && mkdir -p "$(basename "$OUT")" && cd "$(basename "$OUT")" && pwd)"

JAVA_HOME_P="$(posix_path "${JAVA_HOME:-/c/Programs/jdk-21}")"
_apk_tools="${APK_TOOLS:-}"
if [ -z "$_apk_tools" ] && [ -n "${LOCALAPPDATA:-}" ]; then
  _apk_tools="$LOCALAPPDATA\\Android\\Sdk\\build-tools\\35.0.0"
fi
[ -n "$_apk_tools" ] || _apk_tools="/c/Android/Sdk/build-tools/35.0.0"
APK_TOOLS_P="$(posix_path "$_apk_tools")"

DIST_REL="dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist"

mkdir -p \
  "$OUT/build" \
  "$OUT/runtime/bin" "$OUT/runtime/lib" \
  "$OUT/$DIST_REL" \
  "$OUT/rish" \
  "$OUT/.dsh/profiles/web"

# 占位可执行文件与库（build.sh 只复制它们，不执行）
printf '#!/system/bin/sh\necho stub-node\n' > "$OUT/runtime/bin/node"
printf 'stub\n' > "$OUT/runtime/lib/libstub.so"
printf 'stub-dex\n' > "$OUT/rish/rish_shizuku.dex"

# 内核版本标记的来源（build.sh 会从中提取 version）
printf '{"name":"@deepseek-ai/dsh","version":"0.2.0-rc.2-stub"}\n' \
  > "$OUT/dshroot/lib/node_modules/@deepseek-ai/dsh/package.json"

# 前端 dist：mobile-patch/inject.sh 要求它存在，且 index.html 里要有 viewport 串
cat > "$OUT/$DIST_REL/index.html" <<'HTML'
<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1" />
</head><body><div id="root"></div></body></html>
HTML

# .dsh 配置（build.sh 无条件复制这几项）
printf '# stub patch\n[]\n' > "$OUT/.dsh/cordis.patch.yml"
printf '# stub settings\n'   > "$OUT/.dsh/settings.yaml"
printf '[]\n'                > "$OUT/.dsh/profiles/web/cordis.patch.yml"
printf '[]\n'                > "$OUT/.dsh/profiles/web/cordis.yml"
printf '{"name":"dsh-profile-web","private":true}\n' > "$OUT/.dsh/profiles/web/package.json"
printf 'packages:\n  - .\n\nnodeLinker: hoisted\n'    > "$OUT/.dsh/profiles/web/pnpm-workspace.yaml"

# 工具链：build.sh 会 source 这个文件
cat > "$OUT/build/env.sh" <<ENVEOF
# 桩 devhome 的工具链（由 make-devhome-stub.sh 探测生成）
export JAVA_HOME="\${JAVA_HOME:-$JAVA_HOME_P}"
export PATH="\$JAVA_HOME/bin:\$PATH"
export APK_TOOLS="\${APK_TOOLS:-$APK_TOOLS_P}"
export PATH="\$APK_TOOLS:\$PATH"
ENVEOF

echo "桩 devhome 已生成：$OUT"
echo "其中 env.sh："
sed 's/^/    /' "$OUT/build/env.sh"
echo
echo "下一步："
echo "    export ANDROID_JAR=\"\$(posix 形式的 platforms/android-36/android.jar)\""
echo "    export DSH_DEV_HOME=\"$OUT\" JAVA_BIN=\"$JAVA_HOME_P/bin\" KEYSTORE_PASS=android KEYSTORE_ALIAS=dsh"
echo "    bash android-app/build.sh"
