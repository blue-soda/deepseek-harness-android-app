#!/system/bin/sh
# 引擎启动分段计时 —— 设备侧运行器（不改内核树）
#
# 三种模式，分开跑以免互相污染：
#   plain  纯基线：不加任何探针 → 真实墙钟启动时间
#   prof   只开 --cpu-prof（V8 采样，开销很小）→ CPU 归因（解析/编译 vs I/O vs 插件初始化）
#   hooks  只挂同线程模块钩子（module.registerHooks）→ 每次模块加载的时间线
#
# 用法（设备上，root）：
#   sh /data/local/tmp/boot-run.sh <pkg> <port> <plain|prof|hooks>
PKG=${1:-com.deepseek.harness.community}
PORT=${2:-3099}
MODE=${3:-plain}
P=/data/user/0/$PKG/files/payload

export LD_LIBRARY_PATH=$P/runtime/lib
export PATH=$P/bin:$P/runtime/bin:/system/bin
export DSH_HOME=$P/dshhome
export SHELL=$P/bin/bash
export DSH_BASH_PATH=$P/bin/bash
export DSH_TIMER_LOG=/data/local/tmp/boot-hooks-$MODE.log
export DSH_TIMER_SUMMARY=/data/local/tmp/boot-summary-$MODE.json

rm -f "$DSH_TIMER_LOG" "$DSH_TIMER_SUMMARY" /data/local/tmp/boot-engine-$MODE.log
rm -rf /data/local/tmp/prof; mkdir -p /data/local/tmp/prof

ARGS=""
case "$MODE" in
  prof)  ARGS="--cpu-prof --cpu-prof-dir=/data/local/tmp/prof" ;;
  hooks) ARGS="--import /data/local/tmp/boot-preload.mjs" ;;
  # profhooks：CPU 采样 + preload。为什么要带 preload —— --cpu-prof 只在**正常退出**时落盘，
  # 而裸跑时我们是 SIGTERM 直接杀；preload 里的 SIGTERM 处理器会 process.exit(0) 走正常退出，
  # profile 才会写出来（实测：不带 preload 的 prof 模式 profile 是空的）。
  profhooks) ARGS="--cpu-prof --cpu-prof-dir=/data/local/tmp/prof --import /data/local/tmp/boot-preload.mjs" ;;
esac

# 秒级时间戳（toybox 的 date 不支持 %N，别用纳秒）
S=$(date +%s)
$P/runtime/bin/node $ARGS --expose-internals \
  $P/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  web --host 127.0.0.1 --port $PORT --no-open >> /data/local/tmp/boot-engine-$MODE.log 2>&1 &
NODE_PID=$!
echo "[$MODE] spawn pid=$NODE_PID port=$PORT"

i=0
while [ $i -lt 240 ]; do
  if ss -ltn 2>/dev/null | grep -q ":$PORT "; then break; fi
  if ! kill -0 $NODE_PID 2>/dev/null; then echo "[$MODE] !! node 已退出"; break; fi
  sleep 1; i=$((i+1))
done
E=$(date +%s)
echo "[$MODE] PORT_READY_S=$((E-S)) (轮询 $i 次)"

sleep 2
kill $NODE_PID 2>/dev/null
sleep 3
kill -9 $NODE_PID 2>/dev/null

echo "[$MODE] hooks 行数: $(wc -l < $DSH_TIMER_LOG 2>/dev/null || echo 0)"
echo "[$MODE] cpuprofile: $(ls /data/local/tmp/prof 2>/dev/null | head -2 | tr '\n' ' ')"
echo "[$MODE] --- 引擎日志尾 ---"
tail -4 /data/local/tmp/boot-engine-$MODE.log 2>/dev/null
