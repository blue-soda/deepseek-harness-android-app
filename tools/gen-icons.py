#!/usr/bin/env python3
"""从 android-app/icon-src/dsh-desktop.ico 生成 Android 启动图标资源。

用法（仓库根目录）：
    python tools/gen-icons.py

产出（覆盖式写入 android-app/res/）：
    mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png          传统图标 48/72/96/144/192
    mipmap-{...}/ic_launcher_foreground.png                          自适应图标前景（108dp 画布，图像铺满）
    mipmap-anydpi-v26/ic_launcher.xml                                自适应图标（前景 + 纯色背景）
    values/colors.xml 里的 ic_launcher_background                    背景色（纯白，与图像自身底色一致）

设计取舍（2026-10 定的，改图标时按需重选）：
  · 源图是 256×256 的立绘、顶部两角透明 → 自适应前景**铺满** 108dp 画布：
    圆形/方形蒙版只裁到边角头发，脸部始终完整；缩到 66/108 安全区会让脸变小、四周空一大圈，已对比否决。
  · 背景用纯白（对比过 #6B89B4 取色与浅灰），与图像自身白底无缝。

注意：`drawable/ic_launcher.xml`（旧的小鲸鱼矢量图）仍在用 —— 它是**通知小图标**与启动页 logo
（通知小图标会被系统按 alpha 蒙版渲染，彩色位图会变成白块），所以本次只换启动图标，不动它。
"""
import os
import re
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "android-app", "icon-src", "dsh-desktop.ico")
RES = os.path.join(ROOT, "android-app", "res")
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = "#FFFFFF"

XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标（API 26+）：前景 = dsh-desktop.ico 的图像（铺满 108dp 画布），白色背景补透明处。
     传统图标见 mipmap-*/ic_launcher.png。重新生成：python tools/gen-icons.py -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def main() -> int:
    if not os.path.isfile(SRC):
        print(f"找不到源图标：{SRC}", file=sys.stderr)
        return 1
    im = Image.open(SRC)
    sizes = sorted(im.ico.sizes()) if getattr(im, "ico", None) else []
    im.size = max(sizes) if sizes else im.size
    art = im.convert("RGBA")
    print(f"源图标 {os.path.basename(SRC)}：{art.size[0]}×{art.size[1]}，内含尺寸 {sizes}")

    for name, size in DENS.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        art.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
        canvas = round(size * 108 / 48)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        fg.alpha_composite(art.resize((canvas, canvas), Image.LANCZOS), (0, 0))
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        print(f"  mipmap-{name}: ic_launcher.png {size}px + ic_launcher_foreground.png {canvas}px")

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(XML)
    print("  mipmap-anydpi-v26/ic_launcher.xml")

    cpath = os.path.join(RES, "values", "colors.xml")
    src = open(cpath, encoding="utf-8").read()
    if "ic_launcher_background" in src:
        src = re.sub(r'<color name="ic_launcher_background">#[0-9A-Fa-f]{6}</color>',
                     f'<color name="ic_launcher_background">{BG}</color>', src)
    else:
        src = src.replace("</resources>",
                          "    <!-- 应用图标（dsh-desktop.ico）：自适应图标的背景色 —— 纯白，与图像自身底色一致；\n"
                          "         前景是 mipmap-*/ic_launcher_foreground.png（铺满画布）。 -->\n"
                          f'    <color name="ic_launcher_background">{BG}</color>\n</resources>')
    open(cpath, "w", encoding="utf-8", newline="\n").write(src)
    print(f"  values/colors.xml: ic_launcher_background={BG}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
