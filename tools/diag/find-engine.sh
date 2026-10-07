#!/system/bin/sh
# 精确找引擎进程（cmdline 含 dsh/lib/bin.js）并打印它的 cwd 与启动方式
for d in /proc/[0-9]*; do
  [ -r "$d/cmdline" ] || continue
  cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$cmd" in
    *"dsh/lib/bin.js"*) ;;
    *) continue ;;
  esac
  pid=${d##*/}
  echo "pid=$pid"
  echo "cmd=${cmd% }"
  echo -n "cwd="; readlink "$d/cwd"
  break
done
