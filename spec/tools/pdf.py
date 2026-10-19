#!/usr/bin/env python3
"""Reference implementation of the PDF engine's pure rules (ALGORITHMS §6, §9.10).

  python3 spec/tools/pdf.py --write-cases      # (re)compute pdf_layout_cases, pdf_writer_cases, pdf_fit_cases
  python3 spec/tools/pdf.py --write-fixtures   # (re)write the sample PDFs in spec/fixtures/pdfs/ (needs Pillow)

Kotlin and Swift implement the same layout, number format, writer, padding and fit selection independently; the writer
must be byte-identical (`pdf_writer_cases` pin sha256). Rendering and JPEG encoding are platform code, checked by
`pdf_job_cases` and `page_cleanup_cases` on each platform.
"""
import argparse
import hashlib
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

A4_W, A4_H = 595.0, 842.0
MARGIN = 0.05 * 595
MM = 72 / 25.4
CARD_W = 85.6 * MM
CARD_H = 54 * MM
CARD_GAP = 15 * MM
DPI_LADDER = [200, 150, 120, 100]
QUALITY_GRID = [85, 75, 65, 55, 45, 40]
LOW_TAIL = [35, 30, 25, 20]  # the last rung only (100 DPI): ImageIO compresses less than libjpeg at equal quality


def grid(dpi):
    return QUALITY_GRID + LOW_TAIL if dpi == DPI_LADDER[-1] else QUALITY_GRID
MAX_PAGES = 20


def rnd(x):
    """§9.1 round half up."""
    return math.floor(x + 0.5)


# ---------------------------------------------------------------- layout (§9.10)

def image_page(w, h):
    """An image on its own A4 page: (page size, rect, scale)."""
    pw, ph = (A4_W, A4_H) if w <= h else (A4_H, A4_W)
    bw = pw - 2 * MARGIN
    bh = ph - 2 * MARGIN
    s = min(bw / w, bh / h)
    rw = w * s
    rh = h * s
    return (pw, ph), ((pw - rw) / 2, (ph - rh) / 2, rw, rh), s


def id_card_page(front, back):
    """Front above back, each contained in an 85.6 x 54 mm box, the pair centred on A4 portrait."""
    x = (A4_W - CARD_W) / 2
    y_front = (A4_H - (2 * CARD_H + CARD_GAP)) / 2
    y_back = y_front + CARD_H + CARD_GAP
    placed = []
    for (w, h), y in ((front, y_front), (back, y_back)):
        s = min(CARD_W / w, CARD_H / h)
        rw = w * s
        rh = h * s
        placed.append(((x + (CARD_W - rw) / 2, y + (CARD_H - rh) / 2, rw, rh), s))
    return (A4_W, A4_H), placed


def pixels(w, h, s, dpi):
    """Pixels of a placed image at `dpi`; never upscaled; by the long side."""
    k = s * dpi / 72
    if k >= 1:
        return w, h
    if w >= h:
        pw = max(1, rnd(w * k))
        return pw, max(1, rnd(h * pw / w))
    ph = max(1, rnd(h * k))
    return max(1, rnd(w * ph / h)), ph


def pdf_page_pixels(pw_pt, ph_pt, dpi):
    k = (dpi / 72) * min(1, A4_H / max(pw_pt, ph_pt))
    return max(1, rnd(pw_pt * k)), max(1, rnd(ph_pt * k))


# ---------------------------------------------------------------- writer (§9.10)

def num(v):
    c = math.floor(v * 100 + 0.5)
    whole, frac = c // 100, c % 100
    if frac == 0:
        return str(whole)
    if c % 10 == 0:
        return f"{whole}.{frac // 10}"
    return f"{whole}.{frac:02d}"


def _obj(n, body):
    return f"{n} 0 obj\n".encode() + body + b"\nendobj\n"


def _stream(dictionary, data):
    return dictionary.encode() + b"\nstream\n" + data + b"\nendstream"


def write(pages, pad=None):
    """pages: [{"size": (PW, PH), "images": [{"jpeg": bytes, "w": int, "h": int, "rect": (rx, ry, rw, rh)}]}].
    pad: the padding object's length in spaces, or None for no padding object."""
    bodies = []  # (number, body) in order
    kids = []
    n = 3
    page_objects = []
    for page in pages:
        page_no, content_no = n, n + 1
        image_nos = list(range(n + 2, n + 2 + len(page["images"])))
        n = n + 2 + len(page["images"])
        kids.append(page_no)
        page_objects.append((page, page_no, content_no, image_nos))
    bodies.append((1, b"<< /Type /Catalog /Pages 2 0 R >>"))
    kid_refs = " ".join(f"{k} 0 R" for k in kids)
    bodies.append((2, f"<< /Type /Pages /Kids [{kid_refs}] /Count {len(pages)} >>".encode()))
    for page, page_no, content_no, image_nos in page_objects:
        pw, ph = page["size"]
        xobjects = " ".join(f"/Im{j + 1} {no} 0 R" for j, no in enumerate(image_nos))
        bodies.append((page_no, (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {num(pw)} {num(ph)}] "
            f"/Resources << /XObject << {xobjects} >> >> /Contents {content_no} 0 R >>").encode()))
        data = b""
        for j, image in enumerate(page["images"]):
            rx, ry, rw, rh = image["rect"]
            data += f"q {num(rw)} 0 0 {num(rh)} {num(rx)} {num(ph - ry - rh)} cm /Im{j + 1} Do Q\n".encode()
        bodies.append((content_no, _stream(f"<< /Length {len(data)} >>", data)))
        for image, no in zip(page["images"], image_nos):
            jpeg = image["jpeg"]
            bodies.append((no, _stream(
                f"<< /Type /XObject /Subtype /Image /Width {image['w']} /Height {image['h']} "
                f"/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length {len(jpeg)} >>", jpeg)))
    if pad is not None:
        bodies.append((n, _stream(f"<< /Length {pad} >>", b" " * pad)))
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for number, body in bodies:
        offsets.append(len(out))
        out += _obj(number, body)
    xref = len(out)
    count = len(bodies) + 1
    out += f"xref\n0 {count}\n0000000000 65535 f \n".encode()
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {count} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


def write_padded(pages, target):
    """§9.10 Pad: the smallest padding whose PDF is at least `target` bytes (none if already there)."""
    plain = write(pages)
    if len(plain) >= target:
        return plain
    base = len(write(pages, pad=0))
    p = max(0, target - base)
    while len(write(pages, pad=p)) < target:
        p += 1
    while p > 0 and len(write(pages, pad=p - 1)) >= target:
        p -= 1
    return write(pages, pad=p)


# ---------------------------------------------------------------- fit (§9.10)

def window(size_kb):
    """§9.4 window, goal and effective target, in KB; None when there is no maximum."""
    mx = size_kb.get("max")
    if mx is None:
        return None
    mn = size_kb.get("min") or 0
    t = size_kb.get("target")
    if t is None:
        t = (mn + mx) / 2
    margin = max(1, 0.05 * (mx - mn))
    lo, hi = mn + margin, mx - margin
    if lo > hi:
        lo, hi = mn, mx
    return {"lo": lo, "hi": hi, "target": min(max(t, lo), hi)}


def choose(case):
    """sizes: {"200": [size at q 85, 75, 65, 55, 45, 40], ...} in bytes, every page included."""
    w = window(case["size_kb"])
    if w is None:
        return {"error": "unknown_limit"}
    hi = w["hi"] * 1024
    for dpi in DPI_LADDER:
        sizes = case["sizes"].get(str(dpi))
        if sizes is None or sizes[-1] > hi:
            continue
        assert len(sizes) == len(grid(dpi)), f"{dpi}: {len(sizes)} sizes"
        for q, size in zip(grid(dpi), sizes):
            if size <= hi:
                padded = (case["size_kb"].get("min") or 0) > 0 and size < w["lo"] * 1024
                return {
                    "dpi": dpi,
                    "quality": q,
                    "pad_to": rnd(w["target"] * 1024) if padded else None,
                    "strategy": "pad" if padded else ("quality" if dpi == 200 else "lower_dpi"),
                }
    return {"error": "cannot_reach"}


# ---------------------------------------------------------------- cases

def layout(case):
    dpis = case.get("dpis", DPI_LADDER)
    if case["kind"] == "image":
        size, rect, s = image_page(case["w"], case["h"])
        return {"page": [num(v) for v in size], "rects": [[num(v) for v in rect]],
                "pixels": {str(d): [list(pixels(case["w"], case["h"], s, d))] for d in dpis}}
    if case["kind"] == "id_card":
        size, placed = id_card_page(tuple(case["front"]), tuple(case["back"]))
        sides = (case["front"], case["back"])
        return {"page": [num(v) for v in size], "rects": [[num(v) for v in rect] for rect, _ in placed],
                "pixels": {str(d): [list(pixels(side[0], side[1], s, d)) for side, (_, s) in zip(sides, placed)]
                           for d in dpis}}
    return {"page": [num(case["w_pt"]), num(case["h_pt"])],
            "rects": [["0", "0", num(case["w_pt"]), num(case["h_pt"])]],
            "pixels": {str(d): [list(pdf_page_pixels(case["w_pt"], case["h_pt"], d))] for d in dpis}}


def writer_pages(case):
    """A writer case: pages of fixture JPEGs placed by the layouts (`image`, `id_card` or a full `pdf_page`)."""
    def jpeg(name):
        with open(os.path.join(SPEC, "fixtures", name), "rb") as f:
            return f.read()

    pages = []
    for page in case["pages"]:
        if page["kind"] == "image":
            img = page["images"][0]
            size, rect, _ = image_page(img["w"], img["h"])
            pages.append({"size": size, "images": [{"jpeg": jpeg(img["file"]), "w": img["w"], "h": img["h"],
                                                     "rect": rect}]})
        elif page["kind"] == "id_card":
            front, back = page["images"]
            size, placed = id_card_page((front["w"], front["h"]), (back["w"], back["h"]))
            pages.append({"size": size, "images": [
                {"jpeg": jpeg(img["file"]), "w": img["w"], "h": img["h"], "rect": rect}
                for img, (rect, _) in zip((front, back), placed)]})
        else:
            img = page["images"][0]
            pages.append({"size": (page["w_pt"], page["h_pt"]), "images": [
                {"jpeg": jpeg(img["file"]), "w": img["w"], "h": img["h"], "rect": (0, 0, page["w_pt"], page["h_pt"])}]})
    return pages


def writer(case):
    pages = writer_pages(case)
    data = write_padded(pages, case["pad_to"]) if case.get("pad_to") else write(pages)
    return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "pages": len(pages)}


def exam_short(name):
    """§9.8: cut at ` / ` or ` (`, runs of non-ASCII-alphanumerics become one `-`, trimmed."""
    for separator in (" / ", " ("):
        name = name.split(separator)[0]
    return re.sub(r"[^A-Za-z0-9]+", "-", name).strip("-")


def name(case):
    """§9.8 PDF names: `<filename>.pdf`, else `<docType>_<examShort>_<K>kb.pdf`."""
    if case.get("filename"):
        return f"{case['filename']}.pdf"
    return f"{case['doc_type']}_{exam_short(case['exam_name'])}_{rnd(case['bytes'] / 1024)}kb.pdf"


SECTIONS = {
    "pdf_layout_cases": layout,
    "pdf_writer_cases": writer,
    "pdf_fit_cases": choose,
    "pdf_name_cases": name,
}


def write_cases():
    path = os.path.join(SPEC, "fixtures", "cases.json")
    text = open(path, encoding="utf-8").read()
    for section, rule in SECTIONS.items():
        cases = json.loads(text)[section]
        for case in cases:
            case["expect"] = rule(case)
        text = replace_json_array(text, section, cases)
        print(f"wrote expectations for {len(cases)} {section}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


# ---------------------------------------------------------------- sample PDFs (Phase 3 exit gate)

def write_fixtures():
    """Deterministic sample PDFs: scanned certificates (image pages), a text-only PDF and an encrypted one."""
    import io
    import random
    from _common import require
    require("PIL")
    from PIL import Image, ImageDraw, ImageFilter

    out_dir = os.path.join(SPEC, "fixtures", "pdfs")
    os.makedirs(out_dir, exist_ok=True)
    rng = random.Random(7)

    def certificate(w, h, seed_lines):
        img = Image.new("RGB", (w, h), (232, 226, 210))
        px = img.load()
        for y in range(0, h, 3):
            for x in range(0, w, 3):
                shade = int(40 * (x / w) * (y / h))
                n = rng.randint(-10, 10)
                c = (232 + n - shade, 226 + n - shade, 210 + n - shade)
                for dy in range(3):
                    for dx in range(3):
                        if x + dx < w and y + dy < h:
                            px[x + dx, y + dy] = c
        d = ImageDraw.Draw(img)
        d.rectangle([w // 20, h // 30, w - w // 20, h - h // 30], outline=(60, 50, 40), width=max(2, w // 300))
        y = h // 6
        for i in range(seed_lines):
            x = w // 10
            while x < w - w // 10:
                word = rng.randint(w // 40, w // 12)
                d.rectangle([x, y, min(x + word, w - w // 10), y + h // 90], fill=(30, 30, 40))
                x += word + w // 60
            y += h // 30
        d.rectangle([w - w // 4, h // 12, w - w // 10, h // 12 + h // 6], fill=(110, 100, 95))  # photo
        d.ellipse([w // 10, h - h // 5, w // 10 + w // 6, h - h // 5 + w // 6], outline=(170, 30, 40), width=w // 120)
        return img.filter(ImageFilter.GaussianBlur(0.6))

    def as_jpeg(img, quality):
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=quality)
        return buf.getvalue()

    def image_pdf(images):
        pages = []
        for img in images:
            data = as_jpeg(img, 92)
            pages.append({"size": (A4_W, A4_H), "images": [{"jpeg": data, "w": img.width, "h": img.height,
                                                            "rect": (0, 0, A4_W, A4_H)}]})
        return write(pages)

    files = {
        "certificate_scan_1p.pdf": image_pdf([certificate(1240, 1754, 30)]),
        "marksheet_scan_2p.pdf": image_pdf([certificate(1240, 1754, 34), certificate(1240, 1754, 22)]),
    }
    # A small text-only PDF (fonts, no images): far below JEE's 50 KB minimum, so it exercises padding.
    content = b"BT /F1 18 Tf 72 760 Td (Sample certificate - ScanFit test fixture) Tj ET\n"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        _stream(f"<< /Length {len(content)} >>", content),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += _obj(i, body)
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    files["text_letter_1p.pdf"] = bytes(out)
    # Password protected: the Inspector's /Encrypt flag is enough for the app to refuse it before rendering.
    files["encrypted_1p.pdf"] = bytes(out).replace(b"/Root 1 0 R >>", b"/Root 1 0 R /Encrypt 6 0 R >>")
    for name, data in files.items():
        with open(os.path.join(out_dir, name), "wb") as f:
            f.write(data)
        print(f"wrote {name} ({len(data)} bytes)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of the pdf cases")
    ap.add_argument("--write-fixtures", action="store_true", help="rewrite the sample PDFs")
    args = ap.parse_args()
    if args.write_fixtures:
        write_fixtures()
    if args.write_cases:
        write_cases()
    if not (args.write_cases or args.write_fixtures):
        ap.print_help()


if __name__ == "__main__":
    main()
