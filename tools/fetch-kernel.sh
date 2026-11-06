#!/usr/bin/env bash
# 用**官方 npm registry + 仓库内 lock** 安装 DSH 内核树（唯一来源，本版 起）。
#
# 为什么不再从上游 APK 里抠 dshroot：
#   实测上游 payload 的 dshroot 是 **npm** 装的（`…/@deepseek-ai/dsh/node_modules/.package-lock.json`，
#   lockfileVersion=3，530 包全带 integrity，零 dev / 零 optional），而官方 tag 的 `pnpm-lock.yaml`
#   与它差 37 个第三方版本 —— 所以"从官方 tag 跑 pnpm frozen"会装出另一棵树。
#   本脚本改用那份**真实 lock 的等价物**（`android-app/kernel/package-lock.json`，一次性从 payload
#   提升 530 条 + 补 80 条平台可选依赖）直接从官方 registry 复刻：9 秒、零漂移、不需要上游 APK。
#
# 构建缓存（本版）：装好的树会打成一枚 tar 存在 `.cache/kernel/`，并写 sha256 sidecar：
#   · 第二次起直接解 tar（秒级、离线、不联网、不跑 npm）；
#   · lock 变了（sha 变）或改了下方的安装口径（RECIPE 版本号）→ 自动重装；
#   · 强制重装：DSH_KERNEL_REFRESH=1 或删掉 tar。
#
# 用法：
#   bash tools/fetch-kernel.sh <输出目录>        # 输出目录 = Android 侧的 dshroot/lib
#   环境变量：NPM_BIN（默认 npm）、NPM_FLAGS（追加参数）、DSH_KERNEL_REFRESH=1（忽略缓存）
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
KERNEL_DIR="$REPO/android-app/kernel"
OUT="${1:-}"
NPM_BIN="${NPM_BIN:-npm}"
# 安装口径的版本号：改了下面的 npm ci 参数就要 +1（会让缓存失效，避免用到口径不一致的旧 tar）
RECIPE="r1"

[ -n "$OUT" ] || { echo "用法：bash tools/fetch-kernel.sh <输出目录>" >&2; exit 1; }
[ -f "$KERNEL_DIR/package.json" ] || { echo "!! 缺 $KERNEL_DIR/package.json" >&2; exit 1; }
[ -f "$KERNEL_DIR/package-lock.json" ] || { echo "!! 缺 $KERNEL_DIR/package-lock.json" >&2; exit 1; }

LOCK="$KERNEL_DIR/package-lock.json"
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}';
  else echo ""; fi
}

# ── 1. lock 自检（纯 shell，不依赖 node/路径转换）────────────────────────────
N_ENTRIES=$(grep -c '"version":' "$LOCK" || true)
N_INTEG=$(grep -c '"integrity": "sha' "$LOCK" || true)
K_VER=$(sed -n '/"node_modules\/@deepseek-ai\/dsh": {/,/"dependencies"/p' "$LOCK" \
        | sed -n 's/.*"version": "\([^"]*\)".*/\1/p' | head -1)
N_REGISTRY=$(grep -c '"resolved": "https://registry.npmjs.org' "$LOCK" || true)
echo "   lock: version 行 $N_ENTRIES · integrity $N_INTEG · registry tarball $N_REGISTRY · 内核 $K_VER"
# 升级内核时只改 android-app/kernel/package.json + lock，这里以 pin 为准（不再写死版本号）
K_PIN=$(sed -n 's/.*"@deepseek-ai\/dsh": "\([^"]*\)".*/\1/p' "$REPO/android-app/kernel/package.json" | head -1)
[ -n "$K_PIN" ] || { echo "!! 读不到 android-app/kernel/package.json 的 @deepseek-ai/dsh pin" >&2; exit 1; }
[ "$K_VER" = "$K_PIN" ] || { echo "!! lock 里的内核版本($K_VER) 与 package.json pin($K_PIN) 不一致" >&2; exit 1; }
[ "$N_ENTRIES" -ge 600 ] || { echo "!! lock 条目过少（$N_ENTRIES），疑似被截断" >&2; exit 1; }
[ "$N_INTEG" -ge 600 ] || { echo "!! integrity 覆盖不足（$N_INTEG）" >&2; exit 1; }
[ "$N_REGISTRY" -ge 600 ] || { echo "!! 有 tarball 不是来自官方 registry（$N_REGISTRY）" >&2; exit 1; }

# ── 2. 缓存查找 ─────────────────────────────────────────────────────────────
LOCK_SHA="$(sha256_of "$LOCK")"
[ -n "$LOCK_SHA" ] || { echo "!! 缺少 sha256sum/shasum，无法做缓存校验" >&2; exit 1; }
LOCK_TAG="${LOCK_SHA:0:16}"
CACHE_DIR="$REPO/.cache/kernel"
TAR="$CACHE_DIR/dsh-$RECIPE-$LOCK_TAG.tar"
TAR_SHA_FILE="$TAR.sha256"
mkdir -p "$CACHE_DIR"

extract_tar() {  # 解到 OUT（OUT 会成为 dshroot/lib）
  mkdir -p "$OUT"
  tar xf "$TAR" -C "$OUT"
}

if [ -f "$TAR" ] && [ "${DSH_KERNEL_REFRESH:-0}" != "1" ]; then
  WANT="$(cat "$TAR_SHA_FILE" 2>/dev/null || true)"
  GOT="$(sha256_of "$TAR")"
  if [ -n "$WANT" ] && [ "$WANT" = "$GOT" ]; then
    echo "   内核缓存命中：$(basename "$TAR")（$(du -h "$TAR" | cut -f1)）—— 离线解包，不跑 npm"
    extract_tar
  else
    echo "   缓存 tar 校验不符（或缺 sidecar），重新安装"
    rm -f "$TAR" "$TAR_SHA_FILE"
  fi
fi

# ── 3. 未命中：从官方 registry 冻结安装，并打成缓存 tar ──────────────────────
if [ ! -f "$TAR" ]; then
  command -v "$NPM_BIN" >/dev/null 2>&1 || { echo "!! 找不到 npm（内核树由官方 registry 冻结安装，需要 npm）" >&2; exit 1; }
  BUILD="$CACHE_DIR/.build-$LOCK_TAG"
  rm -rf "$BUILD"
  mkdir -p "$BUILD"
  cp "$KERNEL_DIR/package.json" "$KERNEL_DIR/package-lock.json" "$BUILD/"
  # ── registry 选择（本版）：默认用 config/mirrors.env 里的国内源，失败回落官方 ──
  #   为什么换源安全：lock 里每个包都带 integrity（sha512），npm 会逐包校验；
  #   实测 npmmirror 与官方逐项相同（667 包 / 25,849 文件 / 222.4 MB）。
  #   缓存 tar 命中时**完全不联网**，这里的顺序只影响冷启动那次。
  NPM_REG_PRIMARY="${DSH_NPM_REGISTRY:-}"
  NPM_REG_FALLBACK=""
  if [ -f "$REPO/config/mirrors.env" ]; then
    # shellcheck disable=SC1091
    . "$REPO/config/mirrors.env"
    NPM_REG_PRIMARY="${DSH_NPM_REGISTRY:-${NPM_REGISTRY_PRIMARY:-}}"
    NPM_REG_FALLBACK="${NPM_REGISTRY_FALLBACK:-}"
  fi
  npm_ci() {  # $1 = registry（空 = 用 npm 默认）
    if [ -n "$1" ]; then
      ( cd "$BUILD" && "$NPM_BIN" ci \
          --os=android --cpu=arm64 \
          --omit=optional --omit=dev --ignore-scripts \
          --registry="$1" \
          --prefer-offline --no-audit --no-fund --loglevel=error ${NPM_FLAGS:-} )
    else
      ( cd "$BUILD" && "$NPM_BIN" ci \
          --os=android --cpu=arm64 \
          --omit=optional --omit=dev --ignore-scripts \
          --prefer-offline --no-audit --no-fund --loglevel=error ${NPM_FLAGS:-} )
    fi
  }
  echo "   冻结安装：npm ci --os=android --cpu=arm64 --omit=optional --omit=dev --ignore-scripts"
  echo "   registry：${NPM_REG_PRIMARY:-（npm 默认）}$( [ -n "$NPM_REG_FALLBACK" ] && echo "（失败回落 $NPM_REG_FALLBACK）" )"
  if ! npm_ci "$NPM_REG_PRIMARY"; then
    if [ -n "$NPM_REG_FALLBACK" ] && [ "$NPM_REG_FALLBACK" != "$NPM_REG_PRIMARY" ]; then
      echo "   !! ${NPM_REG_PRIMARY:-默认 registry} 安装失败，回落 $NPM_REG_FALLBACK 重试"
      rm -rf "$BUILD/node_modules"
      npm_ci "$NPM_REG_FALLBACK" || { echo "!! 两个 registry 都装不动（检查网络/代理）" >&2; exit 1; }
    else
      echo "!! npm ci 失败（没有可回落的 registry）" >&2; exit 1
    fi
  fi
  # 安装用的两份元数据不进产物（留在 lib/ 会让它变成 package root，干扰 Node 解析）
  rm -f "$BUILD/package.json" "$BUILD/package-lock.json"
  [ -d "$BUILD/node_modules/@deepseek-ai/dsh" ] || { echo "!! 没装出 node_modules/@deepseek-ai/dsh" >&2; exit 1; }
  echo "   打缓存：$(basename "$TAR")"
  ( cd "$BUILD" && tar cf "$TAR" node_modules )
  sha256_of "$TAR" > "$TAR_SHA_FILE"
  extract_tar
  rm -rf "$BUILD"
fi

# ── 4. 结果自检 ─────────────────────────────────────────────────────────────
KROOT="$OUT/node_modules/@deepseek-ai/dsh"
[ -d "$KROOT" ] || { echo "!! 没装出 $KROOT" >&2; exit 1; }
VER=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$KROOT/package.json" | head -1)
# v1.20.1（内核 0.2.1-rc.2）：依赖改为 npm 扁平安装（hoisted），不再嵌套在
# @deepseek-ai/dsh/node_modules 下。两个坑：
#   ① 该目录不存在时 find 返回 1，配合 `set -euo pipefail` 会让脚本**静默退出** →
#      必须 (find … || true) 把失败吞掉；
#   ② 嵌套为 0 时要改数扁平根，否则误判成"依赖包 0 个"。
N_PKG=$( (find "$KROOT/node_modules" -name package.json 2>/dev/null || true) | wc -l | tr -d ' ')
if [ "$N_PKG" -eq 0 ]; then
  N_PKG=$( (find "$OUT/node_modules" -name package.json 2>/dev/null || true) | wc -l | tr -d ' ')
fi
echo "   内核树: node_modules/@deepseek-ai/dsh（版本 $VER，依赖包 $N_PKG 个）"
[ "$VER" = "$K_PIN" ] || { echo "!! 内核版本异常：$VER（期望 $K_PIN）" >&2; exit 1; }
[ "$N_PKG" -ge 450 ] || { echo "!! 依赖包数量异常：$N_PKG" >&2; exit 1; }
echo "== fetch-kernel: OK =="
