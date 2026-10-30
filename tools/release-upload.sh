#!/usr/bin/env bash
# 慢网/gh 卡住时的直传上传：把 APK 逐个 POST 到 GitHub Release 资产接口。
#
# 用法：
#   bash tools/release-upload.sh v1.21.2-community <file1.apk> [file2.apk ...]
#
# 行为：
#   · 若该 tag 的 release 不存在 → 先建 draft（notes 取 .cache/release-notes-*.md 或空）
#   · 同名资产已存在 → 先删除再传（等价 --clobber）
#   · 全部传完 → 把 draft 置为已发布
# 依赖：gh（已登录）+ curl
set -u
REPO="${DSH_REPO:-blue-soda/deepseek-harness-android-app}"
TAG="${1:?用法: release-upload.sh <tag> <apk> [apk...]}"
shift
[ "$#" -ge 1 ] || { echo "缺少 APK 文件参数"; exit 1; }

cd "$(dirname "$0")/.." || exit 1
TOKEN="$(gh auth token)" || { echo "NO_TOKEN（先 gh auth login）"; exit 1; }

ID="$(gh release view "$TAG" -R "$REPO" --json databaseId --jq .databaseId 2>/dev/null || true)"
if [ -z "$ID" ]; then
  NOTES="$(ls -t .cache/release-notes-*.md 2>/dev/null | head -1)"
  echo "== release 不存在，创建 draft =="
  if [ -n "$NOTES" ]; then
    gh release create "$TAG" -R "$REPO" --draft --title "$TAG" --notes-file "$NOTES" >/dev/null 2>&1
  else
    gh release create "$TAG" -R "$REPO" --draft --title "$TAG" --notes "" >/dev/null 2>&1
  fi
  ID="$(gh release view "$TAG" -R "$REPO" --json databaseId --jq .databaseId)"
fi
echo "release id=$ID  repo=$REPO"

for FILE in "$@"; do
  [ -f "$FILE" ] || { echo "跳过（不存在）：$FILE"; continue; }
  NAME="$(basename "$FILE")"
  AID="$(gh api "repos/$REPO/releases/$ID/assets" --jq ".[] | select(.name==\"$NAME\") | .id" | head -1)"
  if [ -n "$AID" ]; then
    echo "   删除同名旧资产 id=$AID"
    curl -sS -X DELETE -H "Authorization: token $TOKEN" \
      "https://api.github.com/repos/$REPO/releases/assets/$AID" >/dev/null
  fi
  echo "== 上传 $NAME ($(du -h "$FILE" | cut -f1)) =="
  curl --fail --retry 3 --retry-delay 5 --max-time 7200 -sS -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$FILE" \
    "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=$NAME" \
    -o /tmp/dsh-up.json -w "   HTTP %{http_code}  %{size_upload} bytes  %{speed_upload} B/s\n" \
    || { echo "   上传失败：$(head -c 200 /tmp/dsh-up.json 2>/dev/null)"; exit 1; }
done

echo "== 发布（draft=false） =="
gh release edit "$TAG" -R "$REPO" --draft=false >/dev/null 2>&1 || true
bash "$(dirname "$0")/release-check.sh" "$TAG"
