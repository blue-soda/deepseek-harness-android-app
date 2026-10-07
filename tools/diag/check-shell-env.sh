#!/system/bin/sh
# 打印引擎（node）进程的 SHELL/PREFIX/PATH —— 用于确认终端默认 shell 的来源
found=0
for p in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
  cmd=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)
  case "$cmd" in
    *payload/runtime/bin/node*)
      echo "pid=$p"
      echo "cmdline=$(echo $cmd | cut -c1-120)"
      tr '\0' '\n' < /proc/$p/environ 2>/dev/null | grep -E '^(SHELL|PREFIX|PATH|HOME|DSH_HOME)=' | head -6
      found=1
      break
      ;;
  esac
done
[ $found -eq 0 ] && echo "未找到 node 进程（引擎可能没在跑）"
echo "--- 当前 uid ---"; id
echo "--- userInfo 相关：/etc/passwd 是否存在 ---"; ls -l /etc/passwd 2>&1 | head -2
