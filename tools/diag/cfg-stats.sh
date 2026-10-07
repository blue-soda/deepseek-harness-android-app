#!/system/bin/sh
# 统计 --dump-config 的合成结果（在设备上跑）
f=/data/local/tmp/cfg.yml
[ -f "$f" ] || { echo "先跑 dsh web --dump-config 生成 $f"; exit 1; }
echo "=== 合成出的 bundle 层（# == 注释行）==="
grep '^# ==' "$f"
echo
echo "entry 总数            : $(grep -c '^- id:' "$f")"
echo "静态禁用 disabled:true: $(grep -c 'disabled: true' "$f")"
echo "条件禁用(!!js 表达式)  : $(grep -c '!!js' "$f")"
echo "带 config 的条目       : $(grep -c '^  config:' "$f")"
echo
echo "=== 前 20 个 entry（id / name）==="
grep -E '^- id:|^  name:' "$f" | head -40 | paste - - 2>/dev/null | head -20
