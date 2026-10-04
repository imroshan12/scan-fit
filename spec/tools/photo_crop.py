#!/usr/bin/env python3
"""Reference implementation of the photo crop math (ALGORITHMS §2.2 and §9.6: auto-framing and manual adjust).

  python3 spec/tools/photo_crop.py --write-cases      # (re)compute the expectations of every crop case

Kotlin (`AutoFraming`, `CropAdjust`) and Swift implement the same rules independently; both must pass `crop_cases`.
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

DEFAULT_COVERAGE = 0.675
CHIN_TO_HAIRLINE = 1.25
CROWN_ABOVE_FACE = 0.125
CROWN_FROM_TOP = 0.10
MIN_SHORT_SIDE = 64
MIN_FREE_SIDE = 32


def round_half_up(x):
    return math.floor(x + 0.5)


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def frame(face, img_w, img_h, aspect, coverage=DEFAULT_COVERAGE):
    """§9.6 auto-framing: returns (x, y, w, h, coverage_adjusted)."""
    fx, fy, fw, fh = face
    h = round_half_up(CHIN_TO_HAIRLINE * fh / coverage)
    w = round_half_up(h * aspect)
    shrunk = False
    if w > img_w or h > img_h:
        s = min(img_w / w, img_h / h)
        h = max(1, math.floor(h * s))
        w = round_half_up(h * aspect)
        if w > img_w:
            w = img_w
            h = max(1, round_half_up(w / aspect))
        shrunk = True
    crown = fy - CROWN_ABOVE_FACE * fh
    x = clamp(round_half_up(fx + fw / 2 - w / 2), 0, img_w - w)
    y = clamp(round_half_up(crown - CROWN_FROM_TOP * h), 0, img_h - h)
    return x, y, w, h, shrunk


def move(rect, dx, dy, img_w, img_h):
    x, y, w, h = rect
    return clamp(round_half_up(x + dx), 0, img_w - w), clamp(round_half_up(y + dy), 0, img_h - h), w, h


def zoom(rect, factor, aspect, img_w, img_h):
    x, y, w, h = rect
    max_h = min(img_h, math.floor(img_w / aspect))
    min_h = min(max_h, MIN_SHORT_SIDE if aspect >= 1 else math.ceil(MIN_SHORT_SIDE / aspect))
    nh = clamp(round_half_up(h / factor), min_h, max_h)
    nw = min(img_w, round_half_up(nh * aspect))
    nx = clamp(round_half_up(x + w / 2 - nw / 2), 0, img_w - nw)
    ny = clamp(round_half_up(y + h / 2 - nh / 2), 0, img_h - nh)
    return nx, ny, nw, nh


def resize(rect, corner, dx, dy, img_w, img_h):
    """§9.5 free crop (ink): the dragged corner moves, the opposite corner stays."""
    x, y, w, h = rect
    min_w, min_h = min(MIN_FREE_SIDE, img_w), min(MIN_FREE_SIDE, img_h)
    left, top, right, bottom = x, y, x + w, y + h
    if corner in ("tl", "bl"):
        left = clamp(round_half_up(x + dx), 0, right - min_w)
    else:
        right = clamp(round_half_up(x + w + dx), left + min_w, img_w)
    if corner in ("tl", "tr"):
        top = clamp(round_half_up(y + dy), 0, bottom - min_h)
    else:
        bottom = clamp(round_half_up(y + h + dy), top + min_h, img_h)
    return left, top, right - left, bottom - top


def expected(case):
    img_w, img_h = case["image"]["w"], case["image"]["h"]
    aspect = case["aspect"][0] / case["aspect"][1]
    if case["op"] == "frame":
        f = case["face"]
        x, y, w, h, shrunk = frame((f["x"], f["y"], f["w"], f["h"]), img_w, img_h, aspect,
                                   case.get("coverage", DEFAULT_COVERAGE))
        return {"rect": {"x": x, "y": y, "w": w, "h": h}, "coverage_adjusted": shrunk}
    r = case["rect"]
    rect = (r["x"], r["y"], r["w"], r["h"])
    if case["op"] == "move":
        x, y, w, h = move(rect, case["dx"], case["dy"], img_w, img_h)
    elif case["op"] == "zoom":
        x, y, w, h = zoom(rect, case["factor"], aspect, img_w, img_h)
    elif case["op"] == "resize":
        x, y, w, h = resize(rect, case["corner"], case["dx"], case["dy"], img_w, img_h)
    else:
        raise SystemExit(f"{case['id']}: unknown op {case['op']}")
    return {"rect": {"x": x, "y": y, "w": w, "h": h}}


def write_cases():
    """Recompute `expect` of every crop case in place, keeping every other section of cases.json byte for byte."""
    path = os.path.join(SPEC, "fixtures", "cases.json")
    text = open(path, encoding="utf-8").read()
    cases = json.loads(text)["crop_cases"]
    for case in cases:
        if "op" in case:
            case["expect"] = expected(case)
    with open(path, "w", encoding="utf-8") as f:
        f.write(replace_json_array(text, "crop_cases", cases))
    print(f"wrote expectations for {sum('op' in c for c in cases)} crop cases")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of every crop case")
    if ap.parse_args().write_cases:
        write_cases()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
