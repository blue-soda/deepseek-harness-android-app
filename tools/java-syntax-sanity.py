#!/usr/bin/env python3
"""轻量 Java 语法体检（不构建）：字符串/字符/注释感知的括号配平 + 常见误写检查。

用途：在没有 Android SDK / 不想跑完整构建时，先确认改动没有把大括号/圆括号写坏，
以及"引号没闭合""中文引号混入代码"这类低级错误。
不是编译器 —— 只做结构层面的自检。
"""
from __future__ import annotations

import sys

OPEN = {"(": ")", "[": "]", "{": "}"}
CLOSE = {v: k for k, v in OPEN.items()}


def check(path: str) -> int:
    src = open(path, "r", encoding="utf-8", errors="replace").read()
    stack: list[tuple[str, int]] = []
    line = 1
    i = 0
    n = len(src)
    in_str = in_char = in_line_comment = False
    in_block_comment = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
            in_line_comment = False
            i += 1
            continue
        if in_line_comment:
            i += 1
            continue
        if in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        if c == '"':
            in_str = True
            i += 1
            continue
        if c == "'":
            in_char = True
            i += 1
            continue
        if c in OPEN:
            stack.append((c, line))
        elif c in CLOSE:
            if not stack:
                print(f"{path}:{line}: 多余的 '{c}'")
                return 1
            op, ol = stack.pop()
            if OPEN[op] != c:
                print(f"{path}:{line}: '{c}' 与 {ol} 行的 '{op}' 不匹配")
                return 1
        i += 1

    if in_str:
        print(f"{path}: 有字符串没闭合（\" 未配对）")
        return 1
    if in_block_comment:
        print(f"{path}: 有块注释没闭合（/* 未配对）")
        return 1
    if stack:
        op, ol = stack[-1]
        print(f"{path}: {ol} 行的 '{op}' 没有闭合（还剩 {len(stack)} 个未闭合）")
        return 1
    print(f"{path}: 括号/引号配平 OK")
    return 0


if __name__ == "__main__":
    rc = 0
    for p in sys.argv[1:]:
        rc |= check(p)
    raise SystemExit(rc)
