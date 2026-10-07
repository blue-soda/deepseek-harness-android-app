#!/usr/bin/env bash
# overlay 预检：**在把补丁推到设备/提交之前**必须跑这一关。
#
# 为什么需要（真实事故 2026-10-07）：
#   我给 dsh-tool-web 打懒加载补丁时漏了一个函数闭合括号，而且把 `turndown.addRule` 的三处调用
#   只包住了一处 → 运行期 `ReferenceError: turndown is not defined` → 插件 "never started" →
#   用户那边整个会话都建不起来（New session failed）。
#   当时我跑的 `node --check` 检查的是**设备上还没被替换的旧文件**，等于没检查。
#
# 本脚本做两关：
#   ① 主机侧语法检查：对 overlay 里每个 .js/.mjs/.cjs 跑 `node --check`（按包内 package.json 的
#      module 类型解析，ESM 也能正确检查）；
#   ② 设备侧真实 import：挑出"插件入口"（lib/index.js 等）在设备上用 payload 的 node 真跑一遍
#      `await import(...)` —— 这一步能抓到 --check 抓不到的"模块求值期错误"（ReferenceError 等）。
#
# 用法：
#   bash tools/overlay-preflight.sh                # ①+②（设备在线时）
#   bash tools/overlay-preflight.sh --no-device    # 只跑 ①
# 环境：ANDROID_SERIAL / PKG（默认 com.deepseek.harness.community）
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OV="$REPO/dsh-patches/overlay/lib"
NODE="${NODE:-node}"
PKG="${PKG:-com.deepseek.harness.community}"
NO_DEVICE=0
[ "${1:-}" = "--no-device" ] && NO_DEVICE=1

[ -d "$OV" ] || { echo "找不到 $OV"; exit 1; }

echo "== ① 主机侧语法检查（真 ESM 解析器，非 node --check）=="
# ⚠ 不要用 `node --check`：它不解析 ESM（对 .mjs / type:module 的 .js 按 CJS 解析），
#    既会漏掉真错误、也会把合法的 export 报成非法。用 vm.SourceTextModule 只解析不执行。
PARSE_CHECK="$REPO/tools/esm-parse-check.mjs"
fail=0; n=0
while IFS= read -r f; do
  n=$((n+1))
  if ! out="$("$NODE" --experimental-vm-modules "$PARSE_CHECK" "$f" 2>&1)"; then
    echo "  !! 语法错误：${f#$OV/}"
    echo "$out" | head -5 | sed 's/^/     /'
    fail=$((fail+1))
  fi
done < <(find "$OV" -type f \( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' \) ! -path '*/node-pty/*')
echo "  检查 $n 个文件，失败 $fail 个"
[ "$fail" -eq 0 ] || exit 1

if [ "$NO_DEVICE" = "1" ]; then echo "（--no-device：跳过设备侧 import 检查）"; exit 0; fi

echo
echo "== ② 设备侧真实 import（抓模块求值期错误）=="
P="/data/user/0/$PKG/files/payload"
if ! adb shell "test -f $P/runtime/bin/node" 2>/dev/null; then
  echo "  !! 设备上没有 payload（$P/runtime/bin/node 不存在）→ 跳过 ②"; exit 0
fi
if ! adb shell "test -f /data/local/tmp/import-test.mjs" 2>/dev/null; then
  cat > /tmp/import-test.mjs <<'EOF'
const targets = process.argv.slice(2);
let bad = 0;
for (const t of targets) {
  try { await import(t); console.log('IMPORT-OK  ', t.split('/node_modules/').pop()); }
  catch (e) { bad++; console.log('IMPORT-FAIL', t.split('/node_modules/').pop(), '→', (e && e.message) || e); }
}
process.exit(bad ? 1 : 0);
EOF
  adb push /tmp/import-test.mjs /data/local/tmp/import-test.mjs >/dev/null 2>&1
fi

# 只对"插件入口"做 import：lib/index.js / lib/client.js（其余是工具函数，不会单独求值）
# ⚠ 不把 URL 直接拼进 adb 命令行 —— Git Bash 的 MSYS 会把 /data/... 当 Windows 路径转换掉
#   （实测变成 \datauser0com... ）。改成：把清单写成文件推到设备，在**设备侧**展开。
# 只对**服务端插件入口**做 import：lib/index.js。
#   · lib/client.js 是浏览器侧代码（引用 window），Node 里必然 "window is not defined" —— 不是错误，跳过；
#   · 其余文件是被入口引用的工具函数，单独 import 没有意义。
list="$REPO/.cache/preflight-import-list.txt"
mkdir -p "$(dirname "$list")"
: > "$list"
while IFS= read -r f; do
  rel="${f#$OV/}"
  case "$rel" in
    */lib/index.js) echo "file://$P/dshroot/lib/$rel" >> "$list" ;;
  esac
done < <(find "$OV" -type f -name '*.js' ! -path '*/node-pty/*')
if [ ! -s "$list" ]; then echo "  （overlay 里没有插件入口文件）"; exit 0; fi
# adb 是 Windows 程序：推文件要用它认得懂的路径；设备侧的命令行则禁止 MSYS 转换
MSYS_NO_PATHCONV=1 adb push "$(cygpath -w "$list" 2>/dev/null || echo "$list")" /data/local/tmp/import-list.txt >/dev/null 2>&1 \
  || adb push "$list" /data/local/tmp/import-list.txt >/dev/null 2>&1
out="$(MSYS_NO_PATHCONV=1 adb shell "$P/runtime/bin/node /data/local/tmp/import-test.mjs \$(cat /data/local/tmp/import-list.txt)" 2>&1)"
echo "$out" | sed 's/^/  /'
if echo "$out" | grep -q 'IMPORT-FAIL'; then
  echo "!! 有模块在设备上求值失败 —— 不要推这个补丁" >&2
  exit 1
fi
echo "  设备侧 import 全部通过 ✅"
