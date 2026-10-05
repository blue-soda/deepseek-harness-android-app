#!/usr/bin/env python3
"""从 android-app/icon-src/source.png 生成 Android 启动图标资源。

用法（仓库根目录）：
    python tools/gen-icons.py

产出（覆盖式写入 android-app/res/）：
    mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png          传统图标 48/72/96/144/192
    mipmap-{...}/ic_launcher_foreground.png                          自适应图标前景（108dp 画布）
    mipmap-anydpi-v26/ic_launcher.xml                                自适应图标（前景 + 纯色背景）
    values/colors.xml 里的 ic_launcher_background                    背景色（纯白）

取景（2026-10 定的，改图标时按需重选）：
  · 源图是**全身立绘**（不是头像），先按 alpha 包围盒去掉透明边，再等比缩放居中：
    - 自适应前景：内容占画布 **0.80**（长边）。对比过 66/108 安全区（太小、四周一圈白）、
      1.00 铺满（头带与脚被圆形蒙版裁掉）—— 0.80 既完整又够大。
    - 传统图标：内容占 **0.96**，方形透明底，交给老启动器。
  · 背景纯白：与立绘自身的浅色底一致。

注意：`drawable/ic_launcher.xml`（小鲸鱼矢量图）不在这里生成 —— 它是**通知小图标**与启动页 logo。
"""
import os
import re
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "android-app", "icon-src", "source.png")
RES = os.path.join(ROOT, "android-app", "res")
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = "#FFFFFF"
LEGACY_SCALE = 0.96      # 传统图标：内容占方形画布的比例
ADAPTIVE_SCALE = 0.80    # 自适应前景：内容占 108dp 画布的比例（长边）

XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标（API 26+）：前景 = icon-src/source.png 的立绘（占画布 0.80，全身完整），白色背景。
     传统图标见 mipmap-*/ic_launcher.png。重新生成：python tools/gen-icons.py -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def fit(art: Image.Image, box: int, scale: float) -> Image.Image:
    """把 art 等比缩放到 box*scale 内，返回居中贴在透明 box 画布上的图。"""
    w, h = art.size
    k = (box * scale) / max(w, h)
    inner = (max(1, round(w * k)), max(1, round(h * k)))
    canvas = Image.new("RGBA", (box, box), (0, 0, 0, 0))
    canvas.alpha_composite(art.resize(inner, Image.LANCZOS),
                           ((box - inner[0]) // 2, (box - inner[1]) // 2))
    return canvas


def main() -> int:
    if not os.path.isfile(SRC):
        print(f"找不到源图：{SRC}", file=sys.stderr)
        return 1
    src = Image.open(SRC).convert("RGBA")
    box = src.getchannel("A").getbbox()
    art = src.crop(box) if box else src
    print(f"源图 {os.path.basename(SRC)} {src.size} → 去透明边后 {art.size}"
          f"（传统 {LEGACY_SCALE:.2f} / 自适应 {ADAPTIVE_SCALE:.2f}）")

    for name, size in DENS.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        fit(art, size, LEGACY_SCALE).save(os.path.join(d, "ic_launcher.png"))
        canvas = round(size * 108 / 48)
        fit(art, canvas, ADAPTIVE_SCALE).save(os.path.join(d, "ic_launcher_foreground.png"))
        print(f"  mipmap-{name}: ic_launcher.png {size}px + ic_launcher_foreground.png {canvas}px")

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(XML)
    print("  mipmap-anydpi-v26/ic_launcher.xml")

    cpath = os.path.join(RES, "values", "colors.xml")
    css = open(cpath, encoding="utf-8").read()
    if "ic_launcher_background" in css:
        css = re.sub(r'<color name="ic_launcher_background">#[0-9A-Fa-f]{6}</color>',
                     f'<color name="ic_launcher_background">{BG}</color>', css)
    else:
        css = css.replace("</resources>",
                          "    <!-- 应用图标（icon-src/source.png）：自适应图标的背景色 —— 纯白。 -->\n"
                          f'    <color name="ic_launcher_background">{BG}</color>\n</resources>')
    open(cpath, "w", encoding="utf-8", newline="\n").write(css)
    print(f"  values/colors.xml: ic_launcher_background={BG}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
