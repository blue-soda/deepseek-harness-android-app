#!/system/bin/sh
# 把 dsh-patches/overlay 下的补丁文件覆盖回 dshroot
# 用途：更新 DSH（npm 重装）后，重新应用 Android 所需的源码补丁 + Shizuku 插件。
# 前端 = DSH 原生界面 + 移动端适配（mobile-patch/，由 android-app/build.sh 打包时注入）。
# 用法：sh dsh-patches/apply.sh
set -e
# 开发环境主目录，可 export DSH_DEV_HOME 覆盖
H="${DSH_DEV_HOME:-/data/data/com.coomi.android/files/home}"
SRC="$H/dsh-patches/overlay/lib"
DST="$H/dshroot/lib"
RUNTIME="$H/runtime"
NODE="$RUNTIME/bin/node"

if [ ! -d "$SRC" ]; then
  echo "找不到补丁目录：$SRC" >&2
  exit 1
fi
if [ ! -d "$DST" ]; then
  echo "找不到 dshroot：$DST" >&2
  exit 1
fi

echo "== 应用补丁 overlay -> dshroot =="
( cd "$SRC" && tar cf - . ) | ( cd "$DST" && tar xf - )

# v1.20：漂移自检。overlay 是「按内核版本采集的快照」——如果它的文件路径在当前内核树里
# 根本不存在，说明 overlay 是上一代内核的残骸：tar 解出来的都是没人加载的文件，
# 补丁"看着打了、其实没生效"（本项目踩过：subprocess-local 的 bundle 换了文件名）。
missing=0
for rel in $( cd "$SRC" && find . -type f ); do
  if [ ! -f "$DST/${rel#./}" ]; then
    missing=$((missing+1))
    [ "$missing" -le 5 ] && echo "  !! 树里没有：${rel#./}"
  fi
done
if [ "$missing" -gt 0 ]; then
  echo "  !! 共 $missing 个 overlay 文件在当前内核树里不存在 —— overlay 与内核版本不匹配。" >&2
  echo "     处理：在开发机上跑 python tools/overlay-recapture.py --write 重新采一次。" >&2
else
  echo "  OK：overlay 的每个文件都能在目标树里找到（版本对得上）"
fi

export LD_LIBRARY_PATH="$RUNTIME/lib"
export DSHROOT_PKG="$DST/node_modules/@deepseek-ai/dsh/package.json"

echo "== 把 Shizuku 插件加入 dsh package.json 依赖（幂等）=="
"$NODE" -e '
const fs = require("fs");
const p = process.env.DSHROOT_PKG;
const m = JSON.parse(fs.readFileSync(p, "utf8"));
m.dependencies = m.dependencies || {};
if (!m.dependencies["@deepseek-ai/dsh-tool-shizuku"]) {
  m.dependencies["@deepseek-ai/dsh-tool-shizuku"] = "0.1.0";
  fs.writeFileSync(p, JSON.stringify(m, null, 2) + "\n");
  console.log("  已添加 @deepseek-ai/dsh-tool-shizuku 依赖");
} else {
  console.log("  依赖已存在，跳过");
}
'

echo "== 语法自检（5 个 JS 补丁）=="
for f in \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-bash-local/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js" \
  "$DST/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tool-shizuku/lib/index.js" ; do
  if [ -f "$f" ]; then
    if "$NODE" --check "$f" 2>/dev/null; then
      echo "  OK: ${f##*/dshroot/lib/}"
    else
      echo "  FAIL: ${f##*/dshroot/lib/}" >&2
      exit 1
    fi
  else
    echo "  MISSING: $f" >&2
    exit 1
  fi
done

echo "== 完成 =="
