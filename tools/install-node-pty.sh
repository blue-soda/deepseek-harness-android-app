#!/bin/sh
# 把 Termux 里编译好的原生 node-pty 装进本项目 payload（构建输入 + 仓库 overlay）。
#
# 背景：DSH 0.2.0-rc.2 的内置终端走原生 PTY（node-pty），而上游只声明依赖、
#      我们的 payload 不含原生模块。仓库里现在放的是**纯 JS 替身**（管道，无真 TTY）——
#      本脚本用于换成**真 node-pty**。
#
# ⚠ ABI 必须匹配：payload 里的 node 是 v26.4.0 / NODE_MODULE_VERSION=147 / arm64-android。
#   所以必须用 **Node 26.x 的 arm64 Termux** 构建（`node -v` 先确认），否则加载时报
#   "was compiled against a different Node.js version"。
#
# 用法：
#   sh tools/install-node-pty.sh <node-pty 目录>        # 直接给解包后的目录
#   sh tools/install-node-pty.sh <node-pty.tgz> tar     # 给 tar 包
#
# Termux 侧怎么产出（在手机上跑）：
#   node -v                       # 必须是 v26.x
#   cd ~ && mkdir -p ptybuild && cd ptybuild && npm init -y && npm i node-pty
#   tar czf /sdcard/node-pty.tgz node_modules/node-pty
#   然后把 /sdcard/node-pty.tgz 交给我（或 adb push 进模拟器 /sdcard/）
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$1"
MODE="${2:-dir}"
OVERLAY="$REPO/dsh-patches/overlay/lib/node_modules/node-pty"
DEVHOME="$REPO/.cache/devhome/dshroot/lib/node_modules/node-pty"
TMP="$REPO/.cache/node-pty-incoming"

[ -n "$SRC" ] || { echo "用法：sh tools/install-node-pty.sh <node-pty 目录|tgz> [dir|tar]" >&2; exit 1; }
[ -e "$SRC" ] || { echo "找不到：$SRC" >&2; exit 1; }

rm -rf "$TMP"; mkdir -p "$TMP"
if [ "$MODE" = "tar" ]; then
  tar xzf "$SRC" -C "$TMP"
  # tar 里通常是 node_modules/node-pty 或 node-pty
  if   [ -d "$TMP/node_modules/node-pty" ]; then PKG="$TMP/node_modules/node-pty"
  elif [ -d "$TMP/node-pty" ]; then PKG="$TMP/node-pty"
  elif [ -f "$TMP/package.json" ]; then PKG="$TMP"
  else echo "tar 里找不到 node-pty 包目录" >&2; ls "$TMP" >&2; exit 1; fi
else
  PKG="$SRC"
fi

[ -f "$PKG/package.json" ] || { echo "不是 node-pty 包（缺 package.json）：$PKG" >&2; exit 1; }
VER="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$PKG/package.json" | head -1)"
echo "== 待装 node-pty 版本：$VER =="

# 必须有原生产物：prebuilds/android-arm64 或 build/Release
BIN=""
for c in "$PKG/prebuilds/android-arm64/pty.node" "$PKG/build/Release/pty.node"; do
  [ -f "$c" ] && BIN="$c" && break
done
if [ -z "$BIN" ]; then
  echo "!! 没找到原生产物（prebuilds/android-arm64/pty.node 或 build/Release/pty.node）" >&2
  echo "   现有 prebuilds：" >&2
  ls "$PKG/prebuilds" 2>/dev/null >&2 || echo "   （无 prebuilds 目录）" >&2
  exit 1
fi
echo "== 原生产物：${BIN#$PKG/}（$(wc -c < "$BIN") B）=="

# 装进仓库 overlay（替换纯 JS 替身）与构建输入 devhome
for DST in "$OVERLAY" "$DEVHOME"; do
  rm -rf "$DST"
  mkdir -p "$(dirname "$DST")"
  cp -r "$PKG" "$DST"
  echo "== 已安装到 $DST =="
done

cat <<'EOF'

下一步：
  1) 重建社区版：bash .cache/build-local.sh community    （tools/build-apk.sh --variant community）
  2) 设备上启动引擎（会走 dshroot-add 把新文件补上），再试「新建终端」
  3) 提交：git add dsh-patches && git commit -m "feat(terminal): 打包原生 node-pty（Termux 构建，ABI 147）"
EOF
