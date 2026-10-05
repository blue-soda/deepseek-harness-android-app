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
  · 启动图标：素材里人物**偏左**（768 图里头部重心 x≈360，画布中心 384），且铺满时圆形蒙版会把
    发箍两端切掉。所以先按**头部包围盒**（实测 x 20..700 / y 55..660，见下）居中，再缩到
    "头部对角刚好落进圆里"的比例 × 0.95（=0.80），保证**脸与发箍完整**、人物居中。
    背景纯白（素材顶部两角透明，白色与它的浅色底无缝）。
  · 悬浮窗头像：先按 alpha 包围盒去掉透明边，再缩到 320px（40dp 在 xxxhdpi 下 160px，留 2 倍余量）。

注意：`drawable/ic_launcher.xml`（小鲸鱼矢量图）不在这里生成 —— 它仍是**通知小图标**与启动页 logo。
"""
import os
import sys
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ICON = os.path.join(ROOT, "android-app", "icon-src", "icon-windows.png")
SRC_AVATAR = os.path.join(ROOT, "android-app", "icon-src", "source.png")
RES = os.path.join(ROOT, "android-app", "res")
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG = "#FFFFFF"
# 启动图标的头部包围盒（在 768×768 源图里实测：发箍顶 ~55、下巴 ~660、头发左右 ~20/~700）
HEAD_BOX = (20, 55, 700, 660)
# ⚠ 关键：Android 自适应图标是 108×108dp，但系统**只显示中央 72×72dp**（宽度的 72/108 = 66.7%），
#   再放大到图标显示尺寸。所以"按整张画布预览"会严重高估可见范围 —— 之前就是踩了这个坑：
#   生成器按 0.80 缩放（看起来头在画布里完整），到设备上被裁到中央 66.7% 后头箍与下巴都被切掉。
ADAPTIVE_HEAD_SCALE = 0.72   # 自适应前景：头部占 108dp 画布的比例（按中央 72dp 可见区校准）
# 各元素"刚好被裁"的临界 k（实测，中心=头部重心）：耳朵 ≈0.78、头部周围的头发 ≈0.71、
# 发箍外角与下垂发梢 ≈0.62。当前取 **0.72**（维护者指定）：比旧图标 0.80 收敛，
# 耳朵完整、头部头发基本完整、发箍外角被裁一点。要发箍也完整用 0.56，要头发完全不被裁用 0.62。
LEGACY_HEAD_SCALE = 0.80     # 传统方形图标：没有裁切，填得满一些更好看
AVATAR_PX = 320              # 悬浮窗头像边长（nodpi）

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


def head_centered_square(art: Image.Image, k: float) -> Image.Image:
    """把素材按「头部居中」放到正方形画布上（白底），头部按比例 k 缩放。"""
    w, h = art.size
    x0, y0, x1, y1 = HEAD_BOX
    hcx, hcy = (x0 + x1) / 2, (y0 + y1) / 2
    tw, th = max(1, round(w * k)), max(1, round(h * k))
    canvas = Image.new("RGBA", (w, h), (255, 255, 255, 255))
    canvas.alpha_composite(art.resize((tw, th), Image.LANCZOS),
                           (round(w / 2 - hcx * k), round(h / 2 - hcy * k)))
    return canvas


def main() -> int:
    for p in (SRC_ICON, SRC_AVATAR):
        if not os.path.isfile(p):
            print(f"找不到素材：{p}", file=sys.stderr)
            return 1

    src_icon = Image.open(SRC_ICON).convert("RGBA")
    icon_adaptive = head_centered_square(src_icon, ADAPTIVE_HEAD_SCALE)
    icon_legacy = head_centered_square(src_icon, LEGACY_HEAD_SCALE)
    print(f"启动图标素材 {os.path.basename(SRC_ICON)}：头部居中；"
          f"自适应 k={ADAPTIVE_HEAD_SCALE}（按可见区 72/108 校准）、传统 k={LEGACY_HEAD_SCALE}")
    for name, size in DENS.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        icon_legacy.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
        canvas = round(size * 108 / 48)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        fg.alpha_composite(icon_adaptive.resize((canvas, canvas), Image.LANCZOS), (0, 0))
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
