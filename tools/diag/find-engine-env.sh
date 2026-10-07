#!/system/bin/sh
# 找到真正跑 dsh-web 的 node 进程，打印它的 cmdline 与关键 env（排查 env 是否注入成功）
PKG=${1:-com.deepseek.harness.community}
found=0
for d in /proc/[0-9]*; do
  [ -r "$d/cmdline" ] || continue
  cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$cmd" in
    *dsh-web*|*dsh/bin*|*"dsh/app"*|*node*dsh*) ;;
    *) continue ;;
  esac
  pid=${d##*/}
  found=$((found+1))
  echo "== pid=$pid cmd=${cmd% }"
  tr '\0' '\n' < "$d/environ" 2>/dev/null | grep -E '^(SHELL|DSH_BASH_PATH|PATH|DSH_HOME|DSHROOT)=' | head -6
  [ "$found" -ge 2 ] && break
done
[ "$found" -eq 0 ] && echo "（没找到 dsh-web 进程）"
