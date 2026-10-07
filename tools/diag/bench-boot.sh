#!/system/bin/sh
# 测 Q2：DSH 启动耗时构成（裸 node / 内核加载+配置合成 / V8 编译缓存效果）
PKG=com.deepseek.harness.community
P=/data/user/0/$PKG/files/payload
T=/data/local/tmp/dshtest
H=$T/home
BIN=$P/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
NODE=$P/runtime/bin/node

export DSH_HOME=$H HOME=$H
export LD_LIBRARY_PATH=$P/runtime/lib
export PATH=$P/bin:$P/runtime/bin:/system/bin
export DSH_ENGINE_PORT=3099

echo "== 0) 裸 node 启动 =="
time $NODE -e 'process.exit(0)'

echo "== 1) 内核加载 + web profile 配置合成（无编译缓存，第 1 次）=="
time $NODE --expose-internals $BIN --profile web --dump-config > /dev/null 2>$T/e1

echo "== 2) 同上，第 2 次（文件缓存已热）=="
time $NODE --expose-internals $BIN --profile web --dump-config > /dev/null 2>$T/e2

echo "== 3) 开 V8 编译缓存（NODE_COMPILE_CACHE），第 1 次会写缓存 =="
rm -rf $T/cc
export NODE_COMPILE_CACHE=$T/cc
time $NODE --expose-internals $BIN --profile web --dump-config > /dev/null 2>$T/e3

echo "== 4) 开编译缓存后的第 2 次（命中缓存）=="
time $NODE --expose-internals $BIN --profile web --dump-config > /dev/null 2>$T/e4
ls $T/cc 2>/dev/null | wc -l

echo "== 5) 真正起 web 服务到端口就绪（含 HTTP 监听）=="
rm -rf $T/cc2; export NODE_COMPILE_CACHE=$T/cc2
$NODE --expose-internals $BIN --profile web > $T/web.log 2>&1 &
pid=$!
t0=$(date +%s)
i=0
while [ $i -lt 600 ]; do
  if ss -ltn 2>/dev/null | grep -q ":3099"; then break; fi
  sleep 0.2; i=$((i+1))
done
t1=$(date +%s)
echo "端口就绪用时约 $((t1-t0)) 秒（轮询 $i 次）"
kill $pid 2>/dev/null
grep -iE 'listening|ready|http://' $T/web.log | head -3
