#!/system/bin/sh
# 跳过首次引导（省掉 9 步向导）：给 App 偏好补 setup_done=true。
# 用法（PC 侧）：
#   adb push tools/emu-skip-setup.sh /data/local/tmp/skip_setup.sh
#   adb shell "su 0 sh /data/local/tmp/skip_setup.sh"
#   adb shell am force-stop com.deepseek.harness.community
#   adb shell am start -n com.deepseek.harness.community/.MainActivity
# 注意：只用于测试；正常流程应走完引导（否则权限没授，功能会缺）。
PKG=com.deepseek.harness.community
D=/data/user/0/$PKG/shared_prefs
for f in "$D"/*.xml; do
  [ -f "$f" ] || continue
  if ! grep -q 'setup_done' "$f"; then
    sed -i 's#</map>#<boolean name="setup_done" value="true" /></map>#' "$f"
    echo "patched: $f"
  else
    echo "already: $f"
  fi
done
grep -o 'setup_done[^/]*' "$D"/*.xml 2>/dev/null | head -3
