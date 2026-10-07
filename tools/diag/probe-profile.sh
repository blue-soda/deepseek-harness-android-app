#!/system/bin/sh
# 从 profile 目录按名导入插件（复刻 loader 的解析视角）
P=/data/user/0/com.deepseek.harness.community/files/payload
cd "$P/dshhome/profiles/web" || exit 1
export LD_LIBRARY_PATH=$P/runtime/lib
cat > /data/local/tmp/probe-name.mjs <<'EOF'
try {
  const m = await import('ds-harness-remote');
  console.log('OK', Object.keys(m).slice(0, 6).join(','));
} catch (e) {
  console.log('ERR', e && e.code, e && e.message);
  if (e && e.stack) console.log(String(e.stack).split('\n').slice(0, 5).join('\n'));
}
EOF
exec "$P/runtime/bin/node" /data/local/tmp/probe-name.mjs
