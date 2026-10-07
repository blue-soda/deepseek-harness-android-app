#!/usr/bin/env bash
# 把 overlay 的工作区副本按「内核树 = 事实来源」重新刷一遍（保证逐字节一致、纯 LF）。
# 为什么需要：Windows 上 core.autocrlf=true 会把工作区改成 CRLF，而 overlay 是字节级快照
# （apply.sh 直接 tar 覆盖回 dshroot），CRLF 会污染补丁并在设备上引发真问题。
set -u
R="$(cd "$(dirname "$0")/../.." && pwd)"
OV="$R/dsh-patches/overlay/lib"
TREE="${1:-$R/.cache/devhome/dshroot/lib}"

[ -d "$OV" ] || { echo "找不到 $OV"; exit 1; }
[ -d "$TREE" ] || { echo "找不到内核树 $TREE"; exit 1; }

n=0; missing=0
while IFS= read -r f; do
  rel="${f#$OV/}"
  if [ -f "$TREE/$rel" ]; then
    cp -f "$TREE/$rel" "$f"
    n=$((n + 1))
  else
    missing=$((missing + 1))
    echo "  !! 树里没有：$rel"
  fi
done < <(find "$OV" -type f)

echo "已从内核树重刷 $n 个 overlay 文件（树里缺失 $missing 个）"
