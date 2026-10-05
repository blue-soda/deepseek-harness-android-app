#!/usr/bin/env python3
"""重新采 overlay：把 dsh-patches/overlay 与「我们当前的内核树」对齐。

## 为什么需要重采（以及为什么不能只抄上游的分层）

上游仓库有按内核版本分的 overlay 层（overlay / overlay-017 / overlay-020），但：

1. **上游的 apply.sh 只用默认层** `dsh-patches/overlay/lib`；overlay-017/020 是历史归档，
   不是自动选择的"最新版"（实测两层的同名文件 4/6 内容不同，是两个不同快照）。
2. **那是上游的补丁，不是我们的**：本 fork 有自己独有的改动，最典型的是
   `dsh-subprocess-local`（Android 平台门禁）—— 上游 overlay-020 里**根本没有这个包**；
   node-pty 这种塞进 payload 的原生模块上游也没有。
3. 内核包的**权威来源是上游 Release APK**，公共 npm 上没有
   （实测 `@deepseek-ai/dsh-*@0.2.0-rc.2` 全部 404），所以拿不到"未改动内核"来做三方 diff。
4. overlay 停在 0.1.5-rc.2 那代，会带来两类**静默失效**：
   · 路径在树里已不存在 → apply.sh 写了也没人加载（实测 10 个）；
   · 内容是旧版 → 覆盖回去等于把内核降级。
   这两类只有我们能修。

## 本工具做什么

以「当前树 = 我们实际在跑的、已打补丁的内核」为事实标准：

  · overlay 里**路径仍存在于树**的文件 → 用树里的内容刷新（= 重采这一代）
  · overlay 里**路径已不存在**的文件 → 丢弃（它们是上一代内核的残骸）
  · `ADD_PATHS` 里列出的文件 → 补进 overlay（我们改过但还没进 overlay 的）

跑完用 `bash tools/overlay-drift.sh` 自检：应当 stale=0 且 diff=0。

用法：
    python tools/overlay-recapture.py --check     # 只报告，不动文件
    python tools/overlay-recapture.py --write     # 重采（旧 overlay 备份到 .cache/overlay-backup-<时间>）
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_TREE = os.path.join(REPO, ".cache", "devhome", "dshroot", "lib")
DEFAULT_OVERLAY = os.path.join(REPO, "dsh-patches", "overlay", "lib")

# 我们改过、但历史 overlay 里没有对应文件的路径（相对 overlay/lib）。
# 新增补丁后往这里加一行；路径必须相对内核树的 lib/。
ADD_PATHS = [
    # v1.20：Android 平台门禁（createProcessInspector 认 android）——文件名是 0.2.0 的 bundle 名
    "node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/runner-launch-B2zsQ1Dz.js",
]


def rel_files(root: str) -> list[str]:
    out = []
    for dirpath, _d, filenames in os.walk(root):
        for fn in filenames:
            out.append(os.path.relpath(os.path.join(dirpath, fn), root).replace(os.sep, "/"))
    return sorted(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", default=DEFAULT_TREE)
    ap.add_argument("--overlay", default=DEFAULT_OVERLAY)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    if not os.path.isdir(args.tree):
        print(f"!! 找不到内核树 {args.tree}（先跑一次构建，或指定 --tree）", file=sys.stderr)
        return 1
    if not os.path.isdir(args.overlay):
        print(f"!! 找不到 overlay {args.overlay}", file=sys.stderr)
        return 1

    current = rel_files(args.overlay)
    keep, stale, missing_add = [], [], []
    for rel in current:
        if os.path.isfile(os.path.join(args.tree, rel)):
            keep.append(rel)
        else:
            stale.append(rel)
    for rel in ADD_PATHS:
        t = os.path.join(args.tree, rel)
        if not os.path.isfile(t):
            missing_add.append(rel)
        elif rel not in current:
            keep.append(rel)

    print("== overlay 重采体检 ==")
    print(f"  当前 overlay 文件数 : {len(current)}")
    print(f"  树里仍有（会被重采）: {len(keep)}")
    print(f"  树里已无（会丢弃）  : {len(stale)}")
    if missing_add:
        print(f"  !! ADD_PATHS 里这些在树里找不到（检查路径/内核版本是否变了）: {len(missing_add)}")
        for rel in missing_add:
            print(f"       {rel}")
    if stale:
        print("\n-- 将被丢弃的残骸（上一代内核的文件名）--")
        for rel in stale:
            print("   - " + rel.replace("node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/", "@"))

    if not args.write:
        print("\n（--check：未改动任何文件；加 --write 执行重采）")
        return 0
    if missing_add:
        print("\n!! ADD_PATHS 有缺失，先修好再加 --write", file=sys.stderr)
        return 2

    stamp = time.strftime("%Y%m%d-%H%M%S")
    backup = os.path.join(REPO, ".cache", f"overlay-backup-{stamp}")
    overlay_root = os.path.dirname(args.overlay)
    shutil.copytree(overlay_root, backup)
    print(f"\n== 旧 overlay 已备份到 {os.path.relpath(backup, REPO)} ==")

    written = 0
    # 只清掉将被刷新/丢弃的顶层包目录，避免把 ADD_PATHS 之外的东西误删
    for rel in stale:
        full = os.path.join(args.overlay, rel)
        if os.path.isfile(full):
            os.remove(full)
            # 顺手清理空目录
            d = os.path.dirname(full)
            while d.startswith(args.overlay) and d != args.overlay and not os.listdir(d):
                os.rmdir(d)
                d = os.path.dirname(d)
    for rel in keep:
        src = os.path.join(args.tree, rel)
        dst = os.path.join(args.overlay, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        written += 1
    print(f"== 重采完成：{written} 个文件来自当前内核树，丢弃 {len(stale)} 个残骸 ==")
    print("   自检：bash tools/overlay-drift.sh   # 期望 stale=0 且 diff=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
