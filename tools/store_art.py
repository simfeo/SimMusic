"""Play Store icon (512x512) and feature graphic (1024x500) drawn from the launcher icon's shapes.

    python tools/store_art.py <out_dir>

The icon artwork lives in the 108-unit adaptive icon viewport (res/drawable/ic_launcher_*.xml);
everything is drawn at 4x and downsampled for clean edges.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

ICON_C0, ICON_C1 = (0xFF, 0x5A, 0x7A), (0xE0, 0x30, 0x6A)
BANNER_C0, BANNER_C1 = (0xFF, 0x5A, 0x7A), (0x8E, 0x2D, 0xE2)
FONT = "/System/Library/Fonts/Helvetica.ttc"
SS = 4


def gradient(w, h, c0, c1):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = tuple(int(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
    return img


def stroke(d, pts, width, s, ox, oy):
    p = [(ox + x * s, oy + y * s) for x, y in pts]
    d.line(p, fill="white", width=round(width * s), joint="curve")
    r = width * s / 2
    for x, y in (p[0], p[-1]):
        d.ellipse((x - r, y - r, x + r, y + r), fill="white")


def draw_seven(d, s, ox=0, oy=0):
    """The white 7 with a note head, in 108-unit icon coordinates scaled by s."""
    stroke(d, [(38, 36), (66, 36), (52, 66)], 9, s, ox, oy)
    cx, cy, r = ox + 52 * s, oy + 66 * s, 9 * s
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill="white")
    stroke(d, [(66, 36), (66, 26), (74, 30)], 5, s, ox, oy)


def icon(size):
    big = size * SS
    img = gradient(big, big, ICON_C0, ICON_C1)
    draw_seven(ImageDraw.Draw(img), big / 108)
    return img.resize((size, size), Image.LANCZOS)


def banner():
    w, h = 1024 * SS, 500 * SS
    img = gradient(w, h, BANNER_C0, BANNER_C1)
    side = 300 * SS
    tile = icon(300).resize((side, side), Image.LANCZOS)
    mask = Image.new("L", (side, side), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, side - 1, side - 1), radius=70 * SS, fill=255)
    x0, y0 = 90 * SS, (h - side) // 2
    img.paste(tile, (x0, y0), mask)
    d = ImageDraw.Draw(img)
    tx = x0 + side + 70 * SS
    d.text((tx, 150 * SS), "Sim Music", font=ImageFont.truetype(FONT, 96 * SS, index=1), fill="white")
    d.text((tx, 285 * SS), "Play your music by folders", font=ImageFont.truetype(FONT, 40 * SS), fill="white")
    return img.resize((1024, 500), Image.LANCZOS)


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    icon(512).save(os.path.join(out, "icon-512.png"))
    banner().save(os.path.join(out, "feature-graphic.png"))
    print("done", flush=True)
