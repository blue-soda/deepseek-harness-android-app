#!/system/bin/sh
# 引擎侧分段计时（模拟器；arm64 node 经 ndk_translation 翻译）
P=/data/user/0/com.deepseek.harness.community/files/payload
N=$P/runtime/bin/node
BIN=$P/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
export LD_LIBRARY_PATH=$P/runtime/lib
export DSH_HOME=$P/dshhome
export PATH=$P/bin:$PATH

# mksh 的 time 输出形如：0m03.89s real ...  → 只取 real 那段
bench() {
  label="$1"; shift
  t=$( ( time "$@" >/tmp/bench.out 2>/tmp/bench.err ) 2>&1 | grep real | head -1 )
  printf '%-26s %s\n' "$label" "$t"
}

bench "① 裸 node 启动" "$N" -e 1
bench "② 内核import+profile合成(--dump-config)" "$N" "$BIN" web --dump-config
bench "③ 仅 schema（不挂载）" "$N" "$BIN" web --dump-config-schema
echo "   合成出的条目数(id: 行) : $(grep -c 'id:' /tmp/bench.out 2>/dev/null)"
echo "   内核树文件数(设备上)   : $(find $P/dshroot/lib/node_modules -type f 2>/dev/null | wc -l)"
echo "   前端 dist 文件数       : $(find $P/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist -type f 2>/dev/null | wc -l)"
[ -s /tmp/bench.err ] && { echo "   (stderr 前 3 行)"; head -3 /tmp/bench.err; }
