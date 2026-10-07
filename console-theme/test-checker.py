#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验器自测：造三个包（正常 / 有错 / 极简），跑 theme_pack_check.py 看结果是否符合预期。
用法: python test-checker.py            （在仓库根运行；脚本自己找 release-src/console-theme/）
"""
import json, os, subprocess, sys, zipfile, base64, shutil

ROOT = os.path.dirname(os.path.abspath(__file__))
while not os.path.isdir(os.path.join(ROOT, "release-src", "console-theme")) and ROOT != os.path.dirname(ROOT):
    ROOT = os.path.dirname(ROOT)
CT = os.path.join(ROOT, "release-src", "console-theme")
PY = sys.executable
CHECK = os.path.join(CT, "theme_pack_check.py")
TMP = os.path.join(ROOT, "tmp-diag", "v1173", "_checker-test")
shutil.rmtree(TMP, ignore_errors=True); os.makedirs(TMP)

PNG = base64.b64decode(  # 1×1 透明 PNG
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

def mk(name, cfg, theme=None, img=True, extra=False):
    p = os.path.join(TMP, name)
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("console.json", json.dumps(cfg, ensure_ascii=False, indent=2))
        z.writestr("theme.json", json.dumps(theme or {"schema": 1, "name": "自测主题", "author": "test"}, ensure_ascii=False))
        if img: z.writestr("bg.jpg", PNG)
        if extra: z.writestr("README.md", "打包时多塞的文件，应被忽略")
    return p

ENV = {**os.environ, "PYTHONIOENCODING": "utf-8", "PYTHONUTF8": "1"}

def run(p, extra_args=()):
    r = subprocess.run([PY, CHECK, p, *extra_args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=ENV)
    return r.returncode, (r.stdout or "") + (r.stderr or "")

base = json.load(open(os.path.join(CT, "console.example.json"), encoding="utf-8"))

print("=" * 72, "\n① 正常包（示例主题 + bg.jpg + 多余 README）")
p1 = mk("good.zip", base, extra=True)
rc, out = run(p1); print(out.strip()); print(f"  → 退出码 {rc}（期望 0）")

print("=" * 72, "\n② 坏包：非法颜色 + 未知卡片 id + 未知动作 + 缺图 + 试图隐藏救援卡")
bad = json.loads(json.dumps(base))
bad["appearance"]["colors"]["bg"] = "深蓝"                      # 非法颜色
bad["appearance"]["background"]["image"] = "bg.png"             # 包里没有这张图
bad["layout"]["order"][3] = "pluginns"                          # 未知卡片 id
bad["layout"]["hidden"] = ["update", "rescue", "theme"]          # 试图移除救援面
bad["actions"] = [{"type": "shel", "cmd": "ls"},                 # 未知动作类型
                  {"type": "shell", "label": "重启服务器", "cmd": "svc nginx restart"}]
rc, out = run(p1 := mk("bad.zip", bad)); print(out.strip()); print(f"  → 退出码 {rc}（期望 1）")

print("=" * 72, "\n③ 极简包：只有 console.json（无 theme.json / 无图），且用绝对路径背景")
mini = {"schema": 1, "name": "极简", "appearance": {"radius": 8, "fontScale": 1.0}}
p3 = os.path.join(TMP, "mini.zip")
with zipfile.ZipFile(p3, "w") as z: z.writestr("mytheme/console.json", json.dumps(mini, ensure_ascii=False))
rc, out = run(p3); print(out.strip()); print(f"  → 退出码 {rc}（期望 0：允许带一层目录、允许没有 theme.json）")

print("=" * 72, "\n④ 机器可读输出（--json，给 AI 自我修正用）")
rc, out = run(mk("good2.zip", base), ("--json",))
print(out.strip()[:420], "…")

print("=" * 72, "\n⑤ 只校验一份 console.json（不打包也能验）")
r = subprocess.run([PY, CHECK, os.path.join(CT, "console.example.json")], capture_output=True, text=True, encoding="utf-8")
print(r.stdout.strip()); print(f"  → 退出码 {r.returncode}（期望 0）")

print("=" * 72, "\n⑥ 带 layout.style 的包：校验器**不许自己崩**（v1.19.6 修的真缺陷）")
# 背景：`note('$.layout.style', '…')` 曾按 err/warn 的签名传两个参数，而 note() 只收一个
#      → TypeError → Traceback 退出。layout.style 是 v1.19.6 起唯一的风格切换方式，不冷门。
styled = json.loads(json.dumps(base))
styled["layout"]["style"] = "classic"
rc, out = run(mk("styled.zip", styled))
print(out.strip()[:600])
crash = ("Traceback" in out) or ("TypeError" in out)
print(f"  → 退出码 {rc}（期望 0）；崩溃痕迹={'有 ❌' if crash else '无 ✅'}")
if rc != 0 or crash:
    print("  ❌ ⑥ 未通过：带 layout.style 的配置必须能正常校验")
    sys.exit(1)
print("  ✅ ⑥ 通过")
