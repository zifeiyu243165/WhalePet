#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
造一个"测试动画"：透明背景的旋转星星，用来验证 App 的导入功能。

为什么用星星：它整体在动（上下浮 + 自转 + 环绕的小光点），
包围盒每一帧都不一样 —— 正好检验"导入动画自动量包围盒"这条逻辑。
"""
import math
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter

W, H = 480, 270
SS = 2                      # 超采样倍数，缩回去自带抗锯齿
FPS = 30
SECONDS = 2.4
FRAMES = int(FPS * SECONDS)
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", ".tmp", "star")
OUT_FILE = os.path.join(os.path.dirname(__file__), "..", "dist", "import-demo-star.webm")


def star_points(cx, cy, r_out, r_in, n, rot):
    pts = []
    for i in range(n * 2):
        r = r_out if i % 2 == 0 else r_in
        a = rot + i * math.pi / n
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def render_frame(i):
    t = i / float(FRAMES)                       # 0..1，首尾相接
    ang = t * 2 * math.pi

    w, h = W * SS, H * SS
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))

    # 上下浮动：一个完整的正弦周期，保证循环无缝
    cx = w * 0.5
    cy = h * 0.5 - math.sin(ang) * h * 0.075
    rot = -ang * 0.5 + math.pi / 2              # 慢速自转
    scale = 1.0 + math.sin(ang * 2) * 0.06      # 呼吸

    r_out = min(w, h) * 0.30 * scale
    r_in = r_out * 0.46
    c1 = (52, 176, 255)                         # 青
    c2 = (150, 78, 240)                         # 紫

    # --- 外发光：先画一层，再模糊，叠在下面 ---
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.polygon(star_points(cx, cy, r_out * 1.05, r_in * 1.05, 5, rot), fill=c1 + (95,))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=9 * SS))
    img = Image.alpha_composite(img, glow)

    # --- 星体：竖直渐变 + 边缘稍亮 ---
    body = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    bd = ImageDraw.Draw(body)
    bd.polygon(star_points(cx, cy, r_out, r_in, 5, rot), fill=(255, 255, 255, 255))
    mask = body.split()[3]

    grad = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gp = grad.load()
    y0, y1 = int(cy - r_out), int(cy + r_out)
    for y in range(h):
        k = 0.0 if y <= y0 else (1.0 if y >= y1 else (y - y0) / float(max(1, y1 - y0)))
        col = lerp(c1, c2, k)
        for x in range(w):
            gp[x, y] = col + (255,)
    body = Image.composite(grad, Image.new("RGBA", (w, h), (0, 0, 0, 0)), mask)

    # 描一圈更亮的边，避免深色壁纸上糊成一团
    edge = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ed = ImageDraw.Draw(edge)
    ed.polygon(star_points(cx, cy, r_out, r_in, 5, rot), outline=(238, 250, 255, 190), width=2 * SS)
    body = Image.alpha_composite(body, edge)

    # --- 高光：偏左上的一团白 ---
    hl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.ellipse([cx - r_out * 0.46, cy - r_out * 0.62,
                cx - r_out * 0.02, cy - r_out * 0.18], fill=(255, 255, 255, 120))
    hl = hl.filter(ImageFilter.GaussianBlur(radius=5 * SS))
    body = Image.alpha_composite(body, hl)

    img = Image.alpha_composite(img, body)

    # --- 三颗环绕的小光点 ---
    d = ImageDraw.Draw(img)
    for k in range(3):
        a = ang * 2 + k * 2 * math.pi / 3
        rr = min(w, h) * 0.42
        px = cx + math.cos(a) * rr
        py = cy + math.sin(a) * rr * 0.52
        rad = min(w, h) * (0.020 + 0.006 * math.sin(a * 3))
        d.ellipse([px - rad, py - rad, px + rad, py + rad], fill=(255, 250, 220, 235))

    return img.resize((W, H), Image.LANCZOS)


def main():
    if os.path.isdir(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR)

    for i in range(FRAMES):
        render_frame(i).save(os.path.join(OUT_DIR, "f%03d.png" % i))
    print("frames:", FRAMES)

    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-v", "error",
        "-framerate", str(FPS),
        "-i", os.path.join(OUT_DIR, "f%03d.png"),
        "-c:v", "libvpx-vp9",
        "-pix_fmt", "yuva420p",
        "-b:v", "0", "-crf", "34",
        "-auto-alt-ref", "0",
        "-row-mt", "1",
        "-an",
        OUT_FILE,
    ]
    subprocess.check_call(cmd)
    size = os.path.getsize(OUT_FILE)
    print("out:", OUT_FILE, round(size / 1024.0, 1), "KB")


if __name__ == "__main__":
    sys.exit(main())
