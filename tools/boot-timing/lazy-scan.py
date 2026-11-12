#!/usr/bin/env python3
"""懒加载候选检测器（每个 DSH 版本跑一次，5 分钟）。

输入：boot-timing 的 hooks 日志（`boot-run.sh <pkg> 3099 hooks` 产出）
用途：把「启动期实际加载的包」按加载文件数排序，并标出哪些属于
      `lazy-candidates.yml` 里的“偶然使用”库 —— 用来发现“又重又只需要偶尔用”的新包。

用法：
    python tools/boot-timing/lazy-scan.py .cache/boot-prof/boot-hooks-hooks.log
"""
import collections
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

RE_PKG = re.compile(r"node_modules/(@[^/]+/[^/]+|[^@/][^/]*)")
HERE = os.path.dirname(os.path.abspath(__file__))
CANDIDATES = os.path.join(HERE, "lazy-candidates.yml")


def load_candidates():
    """极简 YAML 解析：`包名: 说明` 一行一条（避免引入 PyYAML）。"""
    out = {}
    if not os.path.exists(CANDIDATES):
        return out
    for line in open(CANDIDATES, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if ":" in line:
            name, note = line.split(":", 1)
            out[name.strip()] = note.strip()
    return out


def main():
    log = sys.argv[1] if len(sys.argv) > 1 else ".cache/boot-prof/boot-hooks-hooks.log"
    counts = collections.Counter()
    for line in open(log, encoding="utf-8", errors="replace"):
        for m in RE_PKG.finditer(line):
            counts[m.group(1)] += 1
    cands = load_candidates()
    total = sum(counts.values())
    print(f"# 启动期模块加载：{total} 次 / {len(counts)} 个包")
    print("# 判定：[cand]=已登记为「偶然使用」的懒加载候选；[done]=已懒加载；其余为启动必需\n")
    print(f"{'文件数':>7}  {'包':<44} 判定")
    for pkg, n in counts.most_common(40):
        mark = "   "
        if pkg in cands:
            mark = "[done] 已懒加载" if "已懒" in cands[pkg] else "[cand] 候选（" + cands[pkg] + "）"
        print(f"{n:>7}  {pkg:<44} {mark}")
    missing = [p for p in cands if p not in counts]
    if missing:
        print("\n# 清单里这些包本版启动期**没有加载**（说明上游已改掉，可以从清单删除）：")
        for p in missing:
            print("   -", p)


if __name__ == "__main__":
    main()
