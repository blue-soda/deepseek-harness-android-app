#!/usr/bin/env bash
# 一次构建发布用的三个包（arm64 / arm64-original-look / x86_64）。
# 用法：
#   bash tools/build-release.sh                 # 默认产物名 v<版本>-*
#   bash tools/build-release.sh myprefix        # 自定义前缀
# 产物：.cache/out/<前缀>-arm64.apk / -orig.apk / -x64.apk
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO" || exit 1

PREFIX="${1:-v$(sed -n 's/.*android:versionName="\([0-9.]*\)".*/\1/p' android-app/AndroidManifest.xml | head -1 | tr -d '.')}"
OUT="$REPO/.cache/out"
mkdir -p "$OUT"

run() {   # $1=名字 $2=额外参数
  local n="$1" extra="$2"
  rm -rf android-app/staging 2>/dev/null || true
  # shellcheck disable=SC2086
  bash tools/build-apk.sh $extra --out "$OUT/$n.apk" > ".cache/$n.log" 2>&1
  local rc=$?
  echo "=== $n exit=$rc ==="
  grep -E '外观: |清单 entries|产物大小|产物 SHA-256|!!' ".cache/$n.log" | head -6
  return $rc
}

run "$PREFIX-arm64" ""
run "$PREFIX-orig"  "--original-look"
run "$PREFIX-x64"   "--emulator"

echo
echo "== 结果 =="
for f in "$OUT/$PREFIX-arm64.apk" "$OUT/$PREFIX-orig.apk" "$OUT/$PREFIX-x64.apk"; do
  [ -f "$f" ] && echo "   $(basename "$f")  $(sha256sum "$f" | cut -d' ' -f1)"
done
