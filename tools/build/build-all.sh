#!/bin/sh
# 依次构建三个变体（tools/build/）
for v in community official lite; do
  echo "--- $v ---"
  bash "$(cd "$(dirname "$0")" && pwd)/build-local.sh" "$v" 2>&1 \
    | grep -E 'BUILD OK|error:|!!|application-icon|application:' | head -6
done
