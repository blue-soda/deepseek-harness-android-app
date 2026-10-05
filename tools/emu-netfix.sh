#!/usr/bin/env bash
# 模拟器网络自检 / 修复（DSH 引擎报 "DeepSeek Messages transport failed: fetch failed" 时先跑这个）
#
# 背景（2026-10 实测）：AVD 的 guest 网络有时会进入"半死"状态 ——
#   · ConnectivityService 里网络是 CONNECTED、LinkProperties 里有默认路由和 DNS，
#     但**内核路由表里没有 default 路由**（`ip route` 只有 10.0.2.0/24）→ 出网段流量全丢；
#   · 模拟器内置 DNS 代理 10.0.2.3 也不应答 → Node 的 fetch 直接 "fetch failed"（dns 层面 ENOTFOUND）。
# 症状：App 里发消息立刻/很快失败，日志里是 transport failed；而宿主机上同一个 key 一切正常。
#
# 本脚本：① 自检并补默认路由；② 自检 DNS；③ DNS 修不好时用 /system/etc/hosts 兜底
#        （需要 root —— 本项目的模拟器测试一直用 -writable-system + adb root）。
#
# 用法：
#   bash tools/emu-netfix.sh                 # 自检 + 自动修复（路由/必要的 hosts）
#   bash tools/emu-netfix.sh --check         # 只自检不改动
#   HOSTS_DOMAIN=api.deepseek.com bash tools/emu-netfix.sh
set -u

ADB=${ADB:-adb}
DOMAIN=${HOSTS_DOMAIN:-api.deepseek.com}
GATEWAY=${EMU_GATEWAY:-10.0.2.2}
# 公共 DNS：把 guest 的 DNS 查询重定向到它（默认阿里 DNS，国内快且稳）
PUBLIC_DNS=${EMU_PUBLIC_DNS:-223.5.5.5}
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

say() { printf '%s\n' "$*"; }
sh_() { $ADB shell "$@" 2>&1; }

say "== 1) 接口与默认路由 =="
sh_ "ip -4 addr show 2>/dev/null | grep -E 'inet ' | head -3"
routes=$(sh_ "ip route 2>/dev/null")
printf '%s\n' "$routes" | sed 's/^/   /'
if printf '%s' "$routes" | grep -q '^default'; then
  say "   ✅ 默认路由存在"
else
  if [ "$CHECK_ONLY" = 1 ]; then
    say "   ❌ 缺少默认路由（出网段流量会被丢弃）→ 去掉 --check 可自动补"
  else
    say "   ⚠ 缺少默认路由 → 补 $GATEWAY"
    sh_ "ip route add default via $GATEWAY dev eth0" >/dev/null
    sh_ "ip route | head -3" | sed 's/^/   /'
  fi
fi

say "== 1.5) DNS 可用性：把查询重定向到 $PUBLIC_DNS =="
# 为什么需要：AVD 的 DNS 常常整个坏掉 —— 内置代理 10.0.2.3 不应答，
# 而 ndc resolver / setprop net.dns1 在现在的 Android 上都不接受改 DNS。
# 结果：除了 /system/etc/hosts 里写死的域名，其它一律解析失败（Chrome 里就是白屏/卡加载）。
# 用 iptables 把 guest 的 DNS 查询（UDP/TCP 53）DNAT 到公共 DNS，一劳永逸。
# ⚠ 规则在内存里，模拟器重启即失效 → 每次开机后重跑本脚本即可。
if [ "$CHECK_ONLY" = 1 ]; then
  say "   (--check：不改动) 现有 53 端口 NAT 规则："
  sh_ "iptables -t nat -L OUTPUT -n | grep -m2 53" | sed 's/^/   /'
else
  sh_ "iptables -t nat -C OUTPUT -p udp --dport 53 -j DNAT --to-destination $PUBLIC_DNS 2>/dev/null || iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination $PUBLIC_DNS" >/dev/null
  sh_ "iptables -t nat -C OUTPUT -p tcp --dport 53 -j DNAT --to-destination $PUBLIC_DNS 2>/dev/null || iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination $PUBLIC_DNS" >/dev/null
  sh_ "iptables -t nat -L OUTPUT -n | grep -m2 53" | sed 's/^/   /'
fi

say "== 2) DNS 解析（用 App 自带 node，与引擎同一条解析路径）=="
PAYLOAD=$($ADB shell 'ls -d /data/user/0/*/files/payload 2>/dev/null | head -1' | tr -d '\r')
if [ -z "$PAYLOAD" ]; then
  say "   ⚠ 找不到 payload 目录（App 没装 / 没解压），跳过 DNS 检测"
  exit 0
fi
dns_probe() {
  $ADB shell "LD_LIBRARY_PATH=$PAYLOAD/runtime/lib $PAYLOAD/runtime/bin/node -e \"require('node:dns').lookup('$DOMAIN',{all:true},(e,a)=>{console.log(e?('FAIL '+e.code):('OK '+a.map(x=>x.address).join(',')))})\"" 2>&1 | tr -d '\r' | tail -1
}
out=$(dns_probe)
say "   $out"
case "$out" in
  OK*) say "   ✅ 解析正常"; exit 0 ;;
esac

say "== 3) DNS 兜底：写 /system/etc/hosts =="
# 宿主机侧解析（模拟器 DNS 不可用时唯一可靠来源）
IPS=$(nslookup "$DOMAIN" 2>/dev/null | awk '/^Address: /{print $2}' | grep -E '^[0-9.]+$' | head -3)
[ -z "$IPS" ] && IPS=$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1}' | head -3)
if [ -z "$IPS" ]; then
  say "   ❌ 宿主机也解析不了 $DOMAIN —— 宿主机网络/DNS 有问题，先修宿主机"
  exit 1
fi
say "   宿主机解析: $(echo $IPS | tr '\n' ' ')"
if [ "$CHECK_ONLY" = 1 ]; then
  say "   （--check：不写入）"
  exit 1
fi
sh_ "mount -o rw,remount /system" >/dev/null
for ip in $IPS; do
  sh_ "grep -q ' $DOMAIN\$' /system/etc/hosts || echo '$ip $DOMAIN' >> /system/etc/hosts"
done
sh_ "tail -3 /system/etc/hosts" | sed 's/^/   /'
say "== 复检 =="
say "   $(dns_probe)"
say ""
say "提示：这是**模拟器环境**问题，不是 App bug。最彻底的办法是重启模拟器"
say "      （slirp 会重新下发 10.0.2.3 这个 DNS 代理，通常就恢复正常）；"
say "      长期压测建议用 -gpu host + 正常网络配置。"
