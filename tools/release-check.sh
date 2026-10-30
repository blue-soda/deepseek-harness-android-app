#!/usr/bin/env bash
# 核对某个 Release：是否已发布、资产列表与大小。
# 用法：bash tools/release-check.sh v1.21.2-community
set -u
REPO="${DSH_REPO:-blue-soda/deepseek-harness-android-app}"
TAG="${1:?用法: release-check.sh <tag>}"
cd "$(dirname "$0")/.." || exit 1

echo "=== $TAG ==="
gh release view "$TAG" -R "$REPO" --json isDraft,url,assets \
  --jq '"draft=\(.isDraft)", "url=\(.url)", (.assets[] | "  asset: \(.name)  \(.size) bytes")' \
  || { echo "  release 不存在（可能是草稿还没建）"; exit 1; }

echo
echo "=== 仍处于 draft 的 release ==="
gh api "repos/$REPO/releases" --jq '.[] | select(.draft) | "  id=\(.id) tag=\(.tag_name) assets=\(.assets|length)"' 2>/dev/null || true
