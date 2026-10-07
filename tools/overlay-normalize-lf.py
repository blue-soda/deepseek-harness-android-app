#!/usr/bin/env python3
"""把 overlay 里自带 CRLF 的文本文件在内核树与 overlay 中统一成 LF（两者必须逐字节一致）。

背景：Windows 上 core.autocrlf=true 会把工作区改成 CRLF；.gitattributes 的 overlay 规则
在本机 git 上未生效（实测 check-attr 仍为 text:set/eol:lf），所以干脆把 overlay 涉及的文件
在**内核树里也统一成 LF** —— 这样 blob/工作区/树三者一致，不会再出现"检出后 drift"。
上游厂商自带的 CRLF（AWS/Anthropic SDK 等 172 个）**不在 overlay 里**，无需处理。
"""
from __future__ import annotations

import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OV = os.path.join(REPO, "dsh-patches", "overlay", "lib")
TREE = os.path.join(REPO, ".cache", "devhome", "dshroot", "lib")


def crlf_bytes(path: str) -> int:
    with open(path, "rb") as f:
        data = f.read()
    return data.count(b"\r\n")


def to_lf(path: str) -> bool:
    with open(path, "rb") as f:
        data = f.read()
    if b"\r\n" not in data:
        return False
    with open(path, "wb") as f:
        f.write(data.replace(b"\r\n", b"\n"))
    return True


def main() -> int:
    fixed = []
    for root, _dirs, files in os.walk(OV):
        for name in files:
            ovp = os.path.join(root, name)
            rel = os.path.relpath(ovp, OV)
            treep = os.path.join(TREE, rel)
            n_ov = crlf_bytes(ovp)
            if n_ov == 0:
                continue
            changed = to_lf(ovp)
            t = "树里没有该文件"
            if os.path.isfile(treep):
                changed = to_lf(treep) or changed
                t = f"树已同步（原 CRLF {crlf_bytes(treep)}）"
            fixed.append((rel, n_ov, t))
            print(f"  LF 化 {rel}（原 CRLF {n_ov} 行）；{t}")
    if not fixed:
        print("overlay 里没有 CRLF 文件，无需处理")
    else:
        print(f"\n共处理 {len(fixed)} 个文件。下一步：bash tools/overlay-drift.sh 应仍是 39/0/0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
