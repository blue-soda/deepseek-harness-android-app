#!/system/bin/sh
# 在模拟器上用 root 拉起 Shizuku server（模拟器的 su 仅 adb shell 可用）。
# 在 PC 上这样用：
#   adb push tools/emu-shizuku.sh /data/local/tmp/start_shizuku.sh
#   adb shell "su 0 sh /data/local/tmp/start_shizuku.sh"
# 每次重启模拟器都要跑一次（Shizuku 在模拟器里无法自启）。
APK=$(pm path moe.shizuku.privileged.api 2>/dev/null | head -1 | sed 's/^package://')
if [ -z "$APK" ]; then echo "SHIZUKU_NOT_INSTALLED"; exit 1; fi
LIB="${APK%/base.apk}/lib/x86_64/libshizuku.so"
echo "lib=$LIB"
nohup "$LIB" >/data/local/tmp/shizuku_start.log 2>&1 &
sleep 6
if ps -A | grep -q shizuku_server; then
  echo "SERVER_RUNNING"
  ps -A | grep shizuku_server
else
  echo "SERVER_NOT_RUNNING"; tail -n 10 /data/local/tmp/shizuku_start.log 2>/dev/null
fi
