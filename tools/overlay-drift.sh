#!/usr/bin/env bash
# overlay 漂移体检：overlay 里每个文件，在**当前 devhome 内核树**里是否存在、是否一致。
# 判定：
#   LOADED-IDENTICAL  路径存在且内容相同（补丁已在树里，且名字对得上）
#   LOADED-DIFF       路径存在但内容不同（补丁未生效 / 树里是上游版本）
#   STALE             路径在树里不存在 → apply.sh 只会写一个**没人加载**的文件（静默失效）
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OV="$REPO/dsh-patches/overlay/lib"
TREE="${1:-$REPO/.cache/devhome/dshroot/lib}"

[ -d "$OV" ] || { echo "找不到 $OV"; exit 1; }
[ -d "$TREE" ] || { echo "找不到内核树 $TREE"; exit 1; }

ident=0; diff=0; stale=0
stale_list=""; diff_list=""
while IFS= read -r f; do
  rel="${f#$OV/}"
  t="$TREE/$rel"
  if [ ! -e "$t" ]; then
    stale=$((stale+1)); stale_list="$stale_list$rel
"
  elif cmp -s "$f" "$t"; then
    ident=$((ident+1))
  else
    diff=$((diff+1)); diff_list="$diff_list$rel
"
  fi
done < <(find "$OV" -type f | sort)

echo "== overlay 漂移体检（对照 $TREE）=="
echo "  已加载且一致 : $ident"
echo "  已加载但不同 : $diff"
echo "  树里不存在（静默失效）: $stale"
if [ -n "$diff_list" ]; then
  echo
  echo "-- 已加载但内容不同（这些是"打了却没生效/树里是上游版"的补丁）--"
  printf '%s' "$diff_list" | sed 's|node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/|@|g' | head -30
fi
if [ -n "$stale_list" ]; then
  echo
  echo "-- 树里不存在（apply.sh 写了也没人加载）--"
  printf '%s' "$stale_list" | sed 's|node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/|@|g' | head -30
fi
