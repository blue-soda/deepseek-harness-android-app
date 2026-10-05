#!/usr/bin/env python3
"""生成 Android 图标资源 + 悬浮窗头像资源。

用法（仓库根目录）：
    python tools/gen-icons.py

两张源图，各司其职（都是维护者提供的素材）：
  · android-app/icon-src/icon-windows.png  —— **启动图标**（头像特写，1024→768 存库）
  · android-app/icon-src/source.png        —— **悬浮窗头像**（全身立绘）
  （icon-src/dsh-desktop.ico 是最早那版 Windows 图标的原件，未被本脚本使用，留作参考。）

产出：
    mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        传统图标 48/72/96/144/192
    mipmap-{...}/ic_launcher_foreground.png                        自适应图标前景（108dp 画布，图像铺满）
    mipmap-anydpi-v26/ic_launcher.xml                              自适应图标（前景 + 纯色背景）
    drawable-nodpi/overlay_avatar.png                              悬浮窗头像（悬浮窗里 40dp 显示）
    values/colors.xml 里的 ic_launcher_background                  背景色（纯白）

取景：
  · 启动图标按"直接使用素材"处理 —— 自适应前景**铺满** 108dp 画布（蒙版只裁到边角头发），
    背景纯白（素材自身顶部两角透明，白色与它的浅色底无缝）。
  · 悬浮窗头像先按 alpha 包围盒去掉透明边，再缩到 320px（40dp 在 xxxhdpi 下 160px，留 2 倍余量）。

注意：`drawable/ic_launcher.xml`（小鲸鱼矢量图）不在这里生成 —— 它仍是**通知小图标**与启动页 logo。
"""
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ICON = os.path.join(ROOT, "android-app", "icon-src", "icon-windows.png")
SRC_AVATAR = os.path.join(ROOT, "android-app", "icon-src", "source.png")
RES = os.path.join(ROOT, "android-app", "res")
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = "#FFFFFF"
AVATAR_PX = 320          # 悬浮窗头像边长（nodpi）

XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标（API 26+）：前景 = icon-src/icon-windows.png（铺满 108dp 画布），白色背景。
     传统图标见 mipmap-*/ic_launcher.png。重新生成：python tools/gen-icons.py -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def trim(im: Image.Image) -> Image.Image:
    box = im.getchannel("A").getbbox()
    return im.crop(box) if box else im


def main() -> int:
    for p in (SRC_ICON, SRC_AVATAR):
        if not os.path.isfile(p):
            print(f"找不到素材：{p}", file=sys.stderr)
            return 1

    icon = Image.open(SRC_ICON).convert("RGBA")
    print(f"启动图标素材 {os.path.basename(SRC_ICON)} {icon.size}（铺满 + 白底）")
    for name, size in DENS.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
        canvas = round(size * 108 / 48)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        fg.alpha_composite(icon.resize((canvas, canvas), Image.LANCZOS), (0, 0))
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        print(f"  mipmap-{name}: ic_launcher.png {size}px + ic_launcher_foreground.png {canvas}px")

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(XML)
    print("  mipmap-anydpi-v26/ic_launcher.xml")

    avatar = trim(Image.open(SRC_AVATAR).convert("RGBA"))
    w, h = avatar.size
    k = AVATAR_PX / max(w, h)
    avatar = avatar.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    nd = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nd, exist_ok=True)
    avatar.save(os.path.join(nd, "overlay_avatar.png"), optimize=True)
    print(f"悬浮窗头像 {os.path.basename(SRC_AVATAR)} {Image.open(SRC_AVATAR).size}"
          f" → drawable-nodpi/overlay_avatar.png {avatar.size}")

    cpath = os.path.join(RES, "values", "colors.xml")
    css = open(cpath, encoding="utf-8").read()
    marker = '<color name="ic_launcher_background">'
    if marker in css:
        import re
        css = re.sub(r'<color name="ic_launcher_background">#[0-9A-Fa-f]{6}</color>',
                     f'<color name="ic_launcher_background">{BG}</color>', css)
    else:
        css = css.replace("</resources>",
                          "    <!-- 应用图标（icon-src/icon-windows.png）：自适应图标的背景色 —— 纯白。 -->\n"
                          f'    <color name="ic_launcher_background">{BG}</color>\n</resources>')
    open(cpath, "w", encoding="utf-8", newline="\n").write(css)
    print(f"  values/colors.xml: ic_launcher_background={BG}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
