import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1080, 1920
C0, C1 = (0xFF, 0x5A, 0x7A), (0x8E, 0x2D, 0xE2)
FONT = "/System/Library/Fonts/Helvetica.ttc"


def gradient():
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        for x in range(W):
            t = (x / W + y / H) / 2
            px[x, y] = tuple(int(C0[i] + (C1[i] - C0[i]) * t) for i in range(3))
    return img


def frame(src, dst, caption, base):
    img = base.copy()
    shot = Image.open(src).convert("RGB")
    sh = 1540
    sw = round(shot.width * sh / shot.height)
    shot = shot.resize((sw, sh), Image.LANCZOS)
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw - 1, sh - 1), radius=44, fill=255)
    x, y = (W - sw) // 2, 320
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x, y + 14, x + sw, y + sh + 14), radius=44, fill=110)
    shadow = shadow.filter(ImageFilter.GaussianBlur(24))
    img.paste((20, 10, 90), (0, 0), shadow)
    img.paste(shot, (x, y), mask)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 64)
    tw = d.textlength(caption, font=font)
    d.text(((W - tw) / 2, 150), caption, font=font, fill="white")
    img.save(dst)


if __name__ == "__main__":
    base = gradient()
    args = sys.argv[1:]
    for i in range(0, len(args), 3):
        frame(args[i], args[i + 1], args[i + 2], base)
        print("wrote", args[i + 1], flush=True)
