#!/usr/bin/env bash
# 看 overlay 的构成：哪些是"我们真的改过的代码"，哪些只是跟着进来的文档/类型定义
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OV="$REPO/dsh-patches/overlay/lib"
SRC="$REPO/android-app/src/com/deepseek/harness/MainActivity.java"

[ -d "$OV" ] || { echo "没有 overlay"; exit 1; }
total=$(find "$OV" -type f | wc -l)
docs=$(find "$OV" -type f \( -name 'README*' -o -name 'LICENSE*' -o -name '*.d.ts' -o -name '*.js.map' \) | wc -l)
code=$(find "$OV" -type f -name '*.js' ! -name '*.d.ts' ! -name '*.test.js' | wc -l)
tests=$(find "$OV" -type f -name '*.test.js' | wc -l)
other=$(find "$OV" -type f ! -name '*.js' ! -name '*.d.ts' ! -name 'README*' ! -name 'LICENSE*' ! -name '*.map' | wc -l)

echo "== overlay 构成（共 $total 个文件）=="
echo "  代码(*.js，非 d.ts/测试) : $code"
echo "  测试(*.test.js)          : $tests"
echo "  文档/类型/映射(README/LICENSE/*.d.ts/*.map): $docs"
echo "  其它（package.json / .node / .cjs 等）: $other"

echo
echo "== 非测试的 *.js（= 真正承载补丁的文件）=="
find "$OV" -type f -name '*.js' ! -name '*.test.js' ! -name '*.d.ts' | sed "s|$OV/||" \
  | sed 's|node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/|@|; s|node_modules/@deepseek-ai/|@|' | sort

echo
echo "== 这些路径里，哪些在 App 的强制覆盖白名单里（= 必须随 APK 走的补丁）=="
if [ -f "$SRC" ]; then
  find "$OV" -type f -name '*.js' ! -name '*.test.js' | sed "s|$OV/||" | while read -r rel; do
    # 白名单里既有具体文件也有目录（以 / 结尾）；逐级往上试（最多 8 级，避免死循环）
    hit=""
    cur="$rel"
    i=0
    while [ -n "$cur" ] && [ "$i" -lt 8 ]; do
      if grep -qF "\"dshroot/lib/$cur\"" "$SRC"; then hit="$cur"; break; fi
      case "$cur" in
        */*) cur="${cur%/*}" ;;
        *) break ;;
      esac
      i=$((i+1))
    done
    if [ -n "$hit" ]; then
      echo "  ✓ ${rel#node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/}   （白名单命中：${hit##*/}）"
    else
      echo "  · ${rel#node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/}（不在白名单）"
    fi
  done
fi
