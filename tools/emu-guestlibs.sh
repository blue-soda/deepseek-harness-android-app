#!/system/bin/sh
# 把 payload 里 arm64 的 soname 库补进 ndk_translation 的 guest 目录。
#
# 用途：x86_64 模拟器上，payload 的 node 是 arm64、靠 ndk_translation 转译。
#   guest 链接器**只搜 /system/lib64/arm64**，不认 LD_LIBRARY_PATH（实测：
#   把同名实体文件放进 LD_LIBRARY_PATH 依然报 "library libz.so.1 not found"），
#   所以 node 需要的 soname（libz.so.1 / libsqlite3.so / libicui18n.so.78 / libicuuc.so.78 …）
#   必须补到 /system/lib64/arm64/ 下。
#
# 前提：模拟器必须带 **-writable-system** 启动，且 `adb root` + `adb remount` 成功。
#   ⚠ 不带 -writable-system 启动时，AVD 的可写 system 覆盖层不会被挂载，
#     补进去的库会“消失”（表现为引擎起不来、主界面打不开）——Android Studio 默认就是不带。
#     在 Android Studio 里加：Device Manager → 编辑该 AVD → Additional emulator command line options
#     填入 `-writable-system`；或直接用命令行启动。
#
# 用法（设备上，root）：
#   adb push tools/emu-guestlibs.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/emu-guestlibs.sh com.deepseek.harness.community
#
# 幂等：已存在的库会跳过，可重复执行（每次模拟器（重新）启动后跑一次即可）。
PKG=${1:-com.deepseek.harness.community}
L=/data/user/0/$PKG/files/payload/runtime/lib
G=/system/lib64/arm64

[ -f "$L/LINKS.txt" ] || { echo "找不到 $L/LINKS.txt：先启动一次 App（解压 payload）再跑本脚本"; exit 1; }
mkdir -p "$G"
n=0; skip=0; fail=0
while read -r alias target rest; do
  [ -z "$alias" ] && continue
  case "$alias" in \#*) continue ;; esac
  [ -z "$target" ] && continue
  if [ -e "$G/$alias" ]; then skip=$((skip+1)); continue; fi
  if cp -Lf "$L/$target" "$G/$alias"; then
    chmod 644 "$G/$alias"                  # 必须是可被 App uid 读取的普通文件
    n=$((n+1)); echo "  push $alias <- $target ($(stat -c %s "$G/$alias") B)"
  else
    fail=$((fail+1)); echo "  !! FAIL $alias"
  fi
done < "$L/LINKS.txt"
echo "已补 $n 个，原本已有 $skip 个，失败 $fail 个"

# 自检：node 能跑起来才算成功
N=/data/user/0/$PKG/files/payload/runtime/bin/node
if [ -x "$N" ] || [ -f "$N" ]; then
  echo -n "node 自检："
  LD_LIBRARY_PATH=$L "$N" -v 2>&1 | head -1
fi
