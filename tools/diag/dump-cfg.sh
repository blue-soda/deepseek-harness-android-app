#!/system/bin/sh
# 导出 App 实际生效的 web profile 配置，查 terminal/vs 的 shellPath 到底来自哪一层
PKG=${1:-com.deepseek.harness.community}
P=/data/user/0/$PKG/files/payload
T=/data/local/tmp/dshcfg
rm -rf $T; mkdir -p $T/viewhome
cp -r $P/dshhome/. $T/viewhome/ 2>/dev/null
export DSH_HOME=$T/viewhome HOME=$T/viewhome
export LD_LIBRARY_PATH=$P/runtime/lib
export PATH=$P/bin:$P/runtime/bin:/system/bin
cd $T
$P/runtime/bin/node --expose-internals $P/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile web --dump-config > $T/dump.yml 2>$T/dump.err
echo "exit=$? bytes=$(wc -c < $T/dump.yml)"
echo "--- 含 terminal 的段 ---"
grep -n -i -B2 -A10 'terminal' $T/dump.yml | head -60
echo "--- 含 shellPath 的行 ---"
grep -n 'shellPath' $T/dump.yml | head -10
echo "--- dump.err 尾部 ---"
tail -3 $T/dump.err
