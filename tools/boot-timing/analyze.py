#!/usr/bin/env python3
"""引擎启动分段计时分析：模块加载时间线 + CPU profile 归因。

输入（.cache/boot-prof/）：
  boot-hooks.log            每行: <epoch_ms> \t <load钩子耗时ms> \t <源码字节> \t <序号> \t <url>
  *.cpuprofile              node --cpu-prof 产物（可能是主线程 + hooks 线程两份）

输出：总时长、按插件包聚合（文件数/字节/时间跨度/最大空档）、
      以及 CPU 自耗时按"V8 解析编译 / 文件 I/O / 具体插件 / node 其他内部"分类的排行。
"""
from __future__ import annotations

import json
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
PROF = os.path.join(HERE, "..", "boot-prof")

PKG_RE = re.compile(r"node_modules/(?:@[^/]+/)?([^/]+)/")
# 取**最后一段** node_modules 下的包名：路径形如
#   .../node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/...
# 匹配第一个会把所有插件都归到最外层 dsh，必须取最后一个。
LAST_PKG_RE = re.compile(r"node_modules/(?:@([^/]+)/)?([^/]+)/")


def pkg_of(url: str) -> str:
    ms = list(LAST_PKG_RE.finditer(url))
    if ms:
        scope, name = ms[-1].group(1), ms[-1].group(2)
        return f"@{scope}/{name}" if scope else name
    if url.startswith("node:"):
        return "node:" + url[5:].split("/")[0]
    if not url:
        return "(native)"
    return "(其他)"


def load_hooks(path: str) -> list[dict]:
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            try:
                rows.append({
                    "t": int(parts[0]),
                    "hook_ms": float(parts[1]),
                    "bytes": int(parts[2]),
                    "seq": int(parts[3]),
                    "url": parts[4],
                })
            except ValueError:
                continue
    return rows


def report_hooks(rows: list[dict]) -> None:
    if not rows:
        print("!! hooks 日志为空")
        return
    t0, t1 = rows[0]["t"], rows[-1]["t"]
    builtin = [r for r in rows if r["url"].startswith("node:")]
    files = [r for r in rows if not r["url"].startswith("node:")]
    total_bytes = sum(r["bytes"] for r in files)
    hook_ms = sum(r["hook_ms"] for r in files)

    print("== 模块加载总览（hooks 日志）==")
    print(f"  加载次数        : {len(rows)}（内置 {len(builtin)} / 文件 {len(files)}）")
    print(f"  文件源码合计    : {total_bytes/1048576:.1f} MB")
    print(f"  load 钩子耗时合计(read+transform): {hook_ms:.0f} ms")
    print(f"  首个→末个加载跨度 : {t1 - t0} ms  ← 模块加载阶段的下限（不含之后的初始化/监听）")

    # 按包聚合：文件数、字节、时间跨度
    agg = defaultdict(lambda: {"n": 0, "bytes": 0, "first": None, "last": None, "hook": 0.0})
    for r in files:
        k = pkg_of(r["url"])
        a = agg[k]
        a["n"] += 1
        a["bytes"] += r["bytes"]
        a["hook"] += r["hook_ms"]
        if a["first"] is None:
            a["first"] = r["t"]
        a["last"] = r["t"]

    print("\n== 按包聚合：时间跨度最大的 15 个（跨度≈该包被逐文件加载+编译的整段墙钟）==")
    top = sorted(agg.items(), key=lambda kv: (kv[1]["last"] - kv[1]["first"]), reverse=True)[:15]
    print(f"  {'包':<38}{'文件':>5}{'KB':>8}{'跨度ms':>8}{'read合计ms':>11}")
    for name, a in top:
        span = a["last"] - a["first"]
        print(f"  {name:<38}{a['n']:>5}{a['bytes']/1024:>8.0f}{span:>8}{a['hook']:>11.0f}")

    print("\n== 主线程空档 Top 12（相邻两次加载之间的间隔——那段时间在解析/编译/求值/初始化）==")
    gaps = []
    for prev, cur in zip(rows, rows[1:]):
        gaps.append((cur["t"] - prev["t"], prev["url"], cur["url"]))
    gaps.sort(reverse=True)
    for dt, prev, cur in gaps[:12]:
        print(f"  {dt:>6} ms  加载完 {pkg_of(prev):<32} → 下一个 {pkg_of(cur)}")
    big = sum(g for g, _, _ in gaps if g >= 50)
    print(f"  ≥50ms 的空档合计 {big} ms（这些就是模块加载之外的 CPU 工作）")

    # 归属：把每个空档算到"它前面那个模块所属的包"头上。
    # 近似（空档也可能来自更早模块的异步初始化），但足以给出"谁最贵"的排序。
    attrib = defaultdict(lambda: {"gap": 0.0, "n": 0, "bytes": 0})
    for dt, prev, _cur in zip([g for g, _, _ in gaps], [p for _, p, _ in gaps], [c for _, _, c in gaps]):
        a = attrib[pkg_of(prev)]
        if dt >= 20:            # 小于 20ms 的抖动不计
            a["gap"] += dt
    for r in files:
        k = pkg_of(r["url"])
        attrib[k]["n"] += 1
        attrib[k]["bytes"] += r["bytes"]

    print("\n== 谁最贵：按「空档归属」排序的 Top 20（≈该包引入的解析/编译/初始化时间）==")
    print(f"  {'包':<40}{'文件':>5}{'KB':>8}{'归属ms':>9}")
    for name, a in sorted(attrib.items(), key=lambda kv: -kv[1]["gap"])[:20]:
        if a["gap"] <= 0:
            continue
        print(f"  {name:<40}{a['n']:>5}{a['bytes']/1024:>8.0f}{a['gap']:>9.0f}")

    # 进度分布：模块加载在时间轴上的分布（判断前重后重）
    span = max(1, t1 - t0)
    buckets = [0] * 10
    for r in rows:
        idx = min(9, int((r["t"] - t0) / span * 10))
        buckets[idx] += 1
    print("\n== 模块加载的时间分布（十分位，看启动是前重还是后重）==")
    print("  " + "  ".join(f"{b:>4}" for b in buckets))


# ---------- CPU profile ----------

def classify(url: str, fn: str) -> str:
    if url.startswith("node:") or url.startswith("internal/"):
        if re.search(r"Compile|Parse|Scope|Instantiate|PreParse|CompileLazy|Bytecode|Serialize|Deserialize", fn):
            return "V8 解析/编译"
        if re.search(r"read|open|stat|access|fs|FileHandle|Dirent|scandir", fn + url):
            return "文件 I/O"
        return "node 内部（其他）"
    return pkg_of(url)


def report_profile(path: str) -> None:
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        prof = json.load(f)
    nodes = {n["id"]: n for n in prof.get("nodes", [])}
    samples = prof.get("samples", [])
    deltas = prof.get("timeDeltas", [])
    self_us = defaultdict(int)
    for i, sid in enumerate(samples):
        if i < len(deltas):
            self_us[sid] += deltas[i]

    total_ms = sum(self_us.values()) / 1000.0
    by_cat = defaultdict(float)
    by_file = defaultdict(float)
    for sid, us in self_us.items():
        n = nodes.get(sid)
        if not n:
            continue
        cf = n.get("callFrame", {})
        url = cf.get("url", "") or ""
        fn = cf.get("functionName", "") or ""
        cat = classify(url, fn)
        by_cat[cat] += us / 1000.0
        by_file[(pkg_of(url) if url else "(native)", fn)] += us / 1000.0

    print(f"\n== CPU profile: {os.path.basename(path)} ==")
    print(f"  采样总时长（含采样间隙）: {total_ms:.0f} ms")
    print("  按类别：")
    for cat, ms in sorted(by_cat.items(), key=lambda kv: -kv[1])[:12]:
        print(f"    {cat:<32}{ms:>9.0f} ms  {ms/total_ms*100:>5.1f}%")
    print("  自耗时 Top 18（包/函数）：")
    for (pkg, fn), ms in sorted(by_file.items(), key=lambda kv: -kv[1])[:18]:
        print(f"    {ms:>8.0f} ms  {pkg:<34}{fn[:38]}")


def main() -> int:
    # 支持多种模式的文件名：boot-hooks.log / boot-hooks-plain.log / boot-hooks-hooks.log …
    hooks_files = []
    for f in sorted(os.listdir(PROF)):
        if f.startswith("boot-hooks") and f.endswith(".log"):
            hooks_files.append(os.path.join(PROF, f))
    for h in hooks_files:
        print(f"\n######## {os.path.basename(h)} ########")
        report_hooks(load_hooks(h))
    for f in sorted(os.listdir(PROF)):
        if f.startswith("boot-summary") and f.endswith(".json"):
            try:
                with open(os.path.join(PROF, f), "r", encoding="utf-8", errors="replace") as fh:
                    s = json.load(fh)
                print(f"\n== {f} ==")
                print(f"  node={s.get('node')} 钩子模式={s.get('hooksMode')} 结束方式={s.get('endTag')}")
                for m in s.get("marks", []):
                    print(f"    t+{m['t']:>6} ms  {m['name']}")
            except Exception as exc:
                print(f"  !! 读 {f} 失败: {exc}")
    # --cpu-prof 产物可能在子目录里（adb pull 目录会保留目录名），递归找
    profiles = []
    for root, _dirs, files in os.walk(PROF):
        for f in files:
            if f.endswith(".cpuprofile"):
                profiles.append(os.path.join(root, f))
    if not profiles:
        print("\n!! 没有 cpuprofile")
        return 0
    for p in sorted(profiles):
        report_profile(p)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
