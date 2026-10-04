#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 THEME-PACK-SPEC.md 扮演一次"生成主题包的 AI"：造一个全新主题（含真实生成的背景图）→ 打包 → 自检。
用法: python make-demo-theme.py
"""
import json, os, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
while not os.path.isdir(os.path.join(ROOT, "release-src", "console-theme")) and ROOT != os.path.dirname(ROOT):
    ROOT = os.path.dirname(ROOT)
CT = os.path.join(ROOT, "release-src", "console-theme")
OUT = os.path.join(ROOT, "tmp-diag", "v1173", "ai-theme-demo.zip")
os.makedirs(os.path.dirname(OUT), exist_ok=True)

# ---- 1) 生成一张背景图（1080×2400，暗色竖向渐变 + 一点噪点纹理感）----
bg_path = os.path.join(os.path.dirname(OUT), "_demo-bg.jpg")
try:
    from PIL import Image, ImageDraw
    W, H = 1080, 2400
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    top, bot = (12, 16, 24), (28, 24, 40)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(top[0]+(bot[0]-top[0])*t),
                                       int(top[1]+(bot[1]-top[1])*t),
                                       int(top[2]+(bot[2]-top[2])*t)))
    d.ellipse([-260, 260, 620, 1140], fill=(34, 46, 74))      # 一块柔和的光斑
    d.ellipse([420, 1180, 1320, 2080], fill=(44, 34, 62))
    img = img.filter(__import__("PIL.ImageFilter", fromlist=["ImageFilter"]).GaussianBlur(48))
    img.save(bg_path, "JPEG", quality=80, optimize=True)
    print(f"背景图已生成：{os.path.basename(bg_path)}  {os.path.getsize(bg_path)//1024} KB")
except Exception as e:
    print("Pillow 不可用，退化为 1×1 占位图：", e)
    import base64
    open(bg_path, "wb").write(base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="))

# ---- 2) 按 SPEC 写 console.json（"极简黑白"：等宽、直角、几乎无彩）----
cfg = {
    "schema": 1,
    "name": "极简黑白",
    "author": "AI（按 THEME-PACK-SPEC 生成）",
    "app": "1.17.3+",
    "appearance": {
        "dark": "dark",
        "mono": True,
        "radius": 4,
        "fontScale": 0.98,
        "cardsAlpha": 0.88,
        "perScheme": {
            "dark":  {"bg": "#08090b", "card": "#101215", "text": "#f4f5f7", "sub": "#8f949c", "line": "#1e2126"},
            "light": {"bg": "#fafafa", "card": "#ffffff", "text": "#0b0c0e", "sub": "#6b7076", "line": "#e6e7e9"}
        },
        "colors": {"accent": "#e5e7eb", "green": "#a3e635", "red": "#fb7185", "track": "#1c1f24"},
        "background": {"image": "bg.jpg", "fit": "cover", "anchor": "center",
                       "opacity": 0.85, "dim": 0.35, "blur": 0, "parallax": False}
    },
    "layout": {
        "order": ["extract", "engine", "rescue", "perm", "plugins", "log"],
        "hidden": ["update"],
        "defaultPage": 0,
        "compact": True
    },
    "text": {
        "title.brand": "HARNESS / MIN",
        "card.extract": "状态",
        "card.engine": "推理",
        "btn.engine.start": "运行",
        "btn.engine.restart": "重载",
        "btn.engine.stop": "停止",
        "btn.extract.run": "重建",
        "status.ready": "就绪"
    }
}
theme = {"schema": 1, "name": "极简黑白", "author": "AI（按 THEME-PACK-SPEC 生成）", "app": "1.17.3+"}

# ---- 3) 打包 ----
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("theme.json", json.dumps(theme, ensure_ascii=False, indent=2))
    z.writestr("console.json", json.dumps(cfg, ensure_ascii=False, indent=2))
    z.write(bg_path, "bg.jpg")
print(f"主题包已生成：{OUT}  {os.path.getsize(OUT)//1024} KB")

# ---- 4) 自检 ----
env = {**os.environ, "PYTHONIOENCODING": "utf-8", "PYTHONUTF8": "1"}
r = subprocess.run([sys.executable, os.path.join(CT, "theme_pack_check.py"), OUT, "--json"],
                   capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
res = json.loads(r.stdout)
print(f"\n自检：ok={res['ok']}  错误={len(res['errors'])}  警告={len(res['warnings'])}")
for e in res["errors"]:   print("  ✗", e["path"], e["msg"])
for w in res["warnings"]: print("  ⚠", w["path"], w["msg"])
for n in res["notes"]:    print("  ·", n)
sys.exit(0 if res["ok"] else 1)
