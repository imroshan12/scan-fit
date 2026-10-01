#!/usr/bin/env python3
"""Generate deterministic synthetic fixture images for the cross-platform conformance suite.
Real-world photos (faces, real signatures) must be added by a human with consent - see fixtures/README.md."""
import os, random, math
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
print("fixtures written to", OUT)
