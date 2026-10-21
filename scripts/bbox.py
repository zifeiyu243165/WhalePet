"""按分类抽样测出 95 个动作的公共 alpha 包围盒。

悬浮窗里那套裁剪参数（--vL / --vT / --vW / --vH 与 BODY_RATIO）就是这么量出来的：
素材是 640×360，角色在画面里偏左偏上，四周留白很多，直接用会把 96dp 的窗口占满却显得很小。

依赖 ffmpeg（解一帧到 rawvideo 后取 alpha 通道），用法：
    python scripts/bbox.py
结果写 .tmp/bbox.json，同时打印安全包围盒与宽高比。
"""
import glob
import json
import os
import random
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "android", "assets", "media", "thumb")
OUT = os.path.join(ROOT, ".tmp", "bbox.json")

files = glob.glob(os.path.join(SRC, "*", "*.webm"))
if not files:
    raise SystemExit("找不到素材，先跑 node scripts/fetch-assets.mjs")

random.seed(7)
bycat = {}
for f in files:
    bycat.setdefault(os.path.basename(os.path.dirname(f)), []).append(f)

samples = []
for fs in bycat.values():
    samples += random.sample(fs, min(2, len(fs)))

W, H = 640, 360
gx0, gy0, gx1, gy1 = W, H, 0, 0
detail = []

for f in samples:
    try:
        out = subprocess.run(
            ["ffmpeg", "-v", "error", "-ss", "1.2", "-c:v", "libvpx-vp9", "-i", f,
             "-frames:v", "1", "-pix_fmt", "rgba", "-f", "rawvideo", "-"],
            capture_output=True, timeout=40,
        ).stdout
    except Exception:
        continue
    if len(out) < W * H * 4:
        continue
    a = out[3::4]
    x0, y0, x1, y1 = W, H, -1, -1
    for y in range(H):
        row = a[y * W:(y + 1) * W]
        lo = next((i for i, v in enumerate(row) if v > 12), None)
        if lo is None:
            continue
        hi = next((i for i, v in enumerate(reversed(row)) if v > 12), None)
        hi = W - 1 - hi
        if y0 == H:
            y0 = y
        y1 = y
        if lo < x0:
            x0 = lo
        if hi > x1:
            x1 = hi
    if x1 < 0:
        continue
    detail.append((os.path.basename(f), x0, y0, x1, y1))
    gx0 = min(gx0, x0)
    gy0 = min(gy0, y0)
    gx1 = max(gx1, x1)
    gy1 = max(gy1, y1)

print("样本数", len(detail))
for d in detail[:8]:
    print("  ", d)
print("\n并集包围盒: x %d..%d (w=%d)  y %d..%d (h=%d)" % (gx0, gx1, gx1 - gx0 + 1, gy0, gy1, gy1 - gy0 + 1))

bx, by, bw, bh = gx0, gy0, gx1 - gx0 + 1, gy1 - gy0 + 1
bx -= 4
by -= 4
bw += 8
bh += 8
bx = max(0, bx)
by = max(0, by)
bw = min(W - bx, bw)
bh = min(H - by, bh)
print("安全包围盒: x=%d y=%d w=%d h=%d  宽高比=%.4f" % (bx, by, bw, bh, bh / bw))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as fh:
    json.dump({"x": bx, "y": by, "w": bw, "h": bh, "videoW": W, "videoH": H}, fh)
print("写出", OUT)
