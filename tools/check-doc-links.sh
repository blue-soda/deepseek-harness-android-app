#!/usr/bin/env bash
# 检查 docs/ 与根 README 里的相对链接目标是否存在
cd /c/Workspace/deepseek-harness-android-app || exit 1
bad=0
for f in README.md docs/*.md CONTRIBUTING.md; do
  [ -f "$f" ] || continue
  dir="$(dirname "$f")"
  grep -oE '\]\([^)#]+\.(md|sh|py|env|json|yml|jpg|png)\)' "$f" 2>/dev/null | sed -E 's/^\]\(//; s/\)$//' | while read -r link; do
    case "$link" in http*|mailto:*) continue ;; esac
    target="$dir/$link"
    [ -e "$target" ] || { echo "   ✗ $f → $link"; }
  done
done
echo "   （无输出=链接全部有效）"
