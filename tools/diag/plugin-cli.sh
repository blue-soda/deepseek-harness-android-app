#!/system/bin/sh
# 以 App 的身份跑 DSH CLI（与引擎同环境：payload node + dshroot bin.js + DSH_HOME）
P=/data/user/0/com.deepseek.harness.community/files/payload
export LD_LIBRARY_PATH=$P/runtime/lib
export PATH=$P/bin:$P/runtime/bin:/system/bin:/system/xbin
export DSH_HOME=$P/dshhome
export DSH_BASH_PATH=$P/bin/bash
exec "$P/runtime/bin/node" "$P/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" "$@"
