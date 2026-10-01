#!/usr/bin/env python3
"""Generate deterministic synthetic fixture images for the cross-platform conformance suite.
Real-world photos (faces, real signatures) must be added by a human with consent - see fixtures/README.md."""
import os, random, math, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import require
require("PIL")
from PIL import Image, ImageDraw, ImageFilter
random.seed(42)
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fixtures", "images")
os.makedirs(OUT, exist_ok=True)

def paper(w, h, shadow=True):
    img = Image.new("RGB", (w, h), (236, 232, 222))
    px = img.load()
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            n = random.randint(-6, 6)
            s = int(55 * (x / w) * (y / h)) if shadow else 0   # corner shadow like a phone photo
            c = (236 + n - s, 232 + n - s, 222 + n - s)
            for dy in (0, 1):
                for dx in (0, 1):
                    if x + dx < w and y + dy < h: px[x + dx, y + dy] = c
    return img

def scribble(draw, box, strokes=5, width=6, color=(28, 34, 90)):
    x0, y0, x1, y1 = box
    for s in range(strokes):
        pts, x = [], x0 + s * (x1 - x0) / strokes
        for t in range(40):
            x += (x1 - x0) / (strokes * 40)
            y = (y0 + y1) / 2 + math.sin(t / 3 + s) * (y1 - y0) * 0.3 + random.uniform(-3, 3)
            pts.append((x, y))
        draw.line(pts, fill=color, width=width, joint="curve")

# 1. Large phone "portrait": synthetic head-and-shoulders on a busy background (tests downsample + crop + fit)
img = Image.new("RGB", (3000, 4000), (120, 140, 110))
d = ImageDraw.Draw(img)
for _ in range(400):
    x, y = random.randint(0, 3000), random.randint(0, 4000)
    d.rectangle([x, y, x + 120, y + 120], fill=tuple(random.randint(60, 200) for _ in range(3)))
d.ellipse([1000, 900, 2000, 2200], fill=(205, 160, 130))       # head
d.rectangle([700, 2300, 2300, 4000], fill=(40, 60, 120))       # shoulders
img.save(f"{OUT}/photo_phone_3000x4000.jpg", quality=92)

# 2. Signature photographed on paper with shadow (tests cleanup + auto-crop + fit)
p = paper(2400, 1400); scribble(ImageDraw.Draw(p), (500, 450, 1900, 950)); p.save(f"{OUT}/signature_paper_shadow.jpg", quality=90)

# 3. Tiny clean signature: cannot naturally reach a 10 KB minimum at 140x60 (tests the minimum-size strategy)
t = Image.new("RGB", (420, 180), "white"); scribble(ImageDraw.Draw(t), (20, 30, 400, 150), width=4, color=(0, 0, 0))
t.save(f"{OUT}/signature_clean_420x180.png")

# 4. Thumb impression: ridged ink blob on paper
p = paper(1600, 1600, shadow=False); d = ImageDraw.Draw(p)
for r in range(20, 420, 14):
    d.ellipse([800 - r * 0.8, 800 - r, 800 + r * 0.8, 800 + r], outline=(40, 50, 140), width=6)
p.filter(ImageFilter.GaussianBlur(1.2)).save(f"{OUT}/thumb_ink.jpg", quality=90)

# 5. Declaration: 4 handwritten-ish lines
p = paper(3000, 1800); d = ImageDraw.Draw(p)
for i in range(4): scribble(d, (250, 300 + i * 320, 2750, 500 + i * 320), strokes=9, width=5)
p.save(f"{OUT}/declaration_paper.jpg", quality=90)

# 6. Checker edge cases
Image.new("CMYK", (200, 230), (0, 40, 60, 10)).save(f"{OUT}/cmyk_photo_200x230.jpg", quality=90)
img.resize((200, 230)).save(f"{OUT}/progressive_200x230.jpg", quality=85, progressive=True)
img.resize((200, 230)).save(f"{OUT}/png_named_as_jpg.jpg", format="PNG")   # wrong extension on purpose

# ---- Phase 1 fixtures ------------------------------------------------------------------------------------
# 7. Eight EXIF-orientation images (ALGORITHMS 1.1). Every file, once its EXIF orientation is applied, must show
#    the same upright picture: 4 coloured quadrants (TL red, TR green, BL blue, BR yellow) and a white corner mark.
#    The stored pixels are the *inverse* transform, so a decoder that ignores EXIF shows a rotated/mirrored image.
from PIL import ImageOps
UP_W, UP_H = 200, 120
QUAD = {"TL": (220, 30, 30), "TR": (30, 180, 60), "BL": (30, 60, 220), "BR": (240, 220, 30)}

def upright():
    im = Image.new("RGB", (UP_W, UP_H))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, UP_W // 2 - 1, UP_H // 2 - 1], fill=QUAD["TL"])
    d.rectangle([UP_W // 2, 0, UP_W - 1, UP_H // 2 - 1], fill=QUAD["TR"])
    d.rectangle([0, UP_H // 2, UP_W // 2 - 1, UP_H - 1], fill=QUAD["BL"])
    d.rectangle([UP_W // 2, UP_H // 2, UP_W - 1, UP_H - 1], fill=QUAD["BR"])
    d.rectangle([4, 4, 19, 19], fill=(255, 255, 255))      # mark in the top-left corner
    return im

# EXIF orientation k -> the PIL op that DISPLAYS it upright; the stored image is that op's inverse.
INVERSE = {1: None, 2: Image.Transpose.FLIP_LEFT_RIGHT, 3: Image.Transpose.ROTATE_180,
           4: Image.Transpose.FLIP_TOP_BOTTOM, 5: Image.Transpose.TRANSPOSE, 6: Image.Transpose.ROTATE_90,
           7: Image.Transpose.TRANSVERSE, 8: Image.Transpose.ROTATE_270}
for k in range(1, 9):
    base = upright()
    stored = base.transpose(INVERSE[k]) if INVERSE[k] is not None else base
    ex = Image.Exif(); ex[0x0112] = k
    path = f"{OUT}/orientation_{k}.jpg"
    stored.save(path, quality=95, subsampling=0, exif=ex)
    # self-check: applying EXIF must give back the upright picture (JPEG tolerance)
    back = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    assert back.size == (UP_W, UP_H), (k, back.size)
    for name, (cx, cy) in {"TL": (60, 40), "TR": (140, 40), "BL": (60, 90), "BR": (140, 90)}.items():
        got, want = back.getpixel((cx, cy)), QUAD[name]
        assert all(abs(a - b) < 30 for a, b in zip(got, want)), (k, name, got, want)

# 8. A phone photo that carries GPS + orientation 1 (ALGORITHMS 1.5: must be stripped, HAS_GPS_EXIF reported)
gps_img = Image.new("RGB", (400, 300), (90, 120, 150))
ImageDraw.Draw(gps_img).ellipse([120, 60, 280, 240], fill=(205, 160, 130))
ex = Image.Exif(); ex[0x0112] = 1; ex[0x010F] = "FixtureMake"; ex[0x0110] = "FixtureModel"
gps = ex.get_ifd(0x8825)
gps[1] = "N"; gps[2] = (12.0, 57.0, 3.0); gps[3] = "E"; gps[4] = (77.0, 35.0, 10.0)
ex[0x8825] = gps
gps_img.save(f"{OUT}/photo_exif_gps_400x300.jpg", quality=90, exif=ex)

# 9. ICC profiles: sRGB (strippable) and a non-sRGB one (must be refused: pipeline bug guard, ALGORITHMS 1.5)
from PIL import ImageCms
icc_img = Image.new("RGB", (100, 100), (150, 90, 200))
icc_img.save(f"{OUT}/icc_srgb_100x100.jpg", quality=90, icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())
icc_img.save(f"{OUT}/icc_not_srgb_100x100.jpg", quality=90, icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile("LAB")).tobytes())

# 10. Greyscale signature JPEG (1 component): allowed only when the allow_grayscale_docs flag is on
gray = Image.new("L", (140, 60), 255); scribble(ImageDraw.Draw(gray), (10, 10, 130, 50), width=3, color=0)
gray.save(f"{OUT}/gray_signature_140x60.jpg", quality=90)

print("fixtures written to", OUT)
