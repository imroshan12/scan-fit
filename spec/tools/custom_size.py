#!/usr/bin/env python3
"""Reference implementation of Custom size (ALGORITHMS §11): validation, the synthetic slot and the canonical text.

  python3 spec/tools/custom_size.py --write-cases      # (re)compute the expectations of every custom size case

Kotlin (`CustomSize`) and Swift implement the same rules independently; both must pass `custom_size_cases`.
"""
import argparse
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

DOCS = ("photo", "signature", "thumb_impression", "handwritten_declaration")
MAX_KB = 5120
MIN_PX, MAX_PX = 16, 2000
MIN_DPI, MAX_DPI = 72, 1200
DEFAULT_DPI = 200
NUMBER = r"(0|[1-9][0-9]*)"
TEXT = re.compile(rf"^([a-z_]+);(?:{NUMBER}x{NUMBER}|-);{NUMBER}-{NUMBER};{NUMBER}$")


def round_half_up(x):
    return math.floor(x + 0.5)


def to_px(value, unit, dpi):
    if value is None:
        return None
    return round_half_up(value * dpi / 2.54) if unit == "cm" else round_half_up(value)


def validate(inp):
    """Returns (codes, size) — size is None when any code applies."""
    dpi = DEFAULT_DPI if inp.get("dpi") is None else inp["dpi"]
    max_kb, min_kb = inp.get("max_kb"), inp.get("min_kb")
    unit = inp.get("unit", "px")
    w, h = to_px(inp.get("width"), unit, dpi), to_px(inp.get("height"), unit, dpi)
    codes = []
    if max_kb is None:
        codes.append("max_kb_missing")
    elif not 1 <= max_kb <= MAX_KB:
        codes.append("max_kb_range")
    if min_kb is not None and not (0 <= min_kb and (max_kb is None or min_kb < max_kb)):
        codes.append("min_kb_range")
    if (w is None) != (h is None):
        codes.append("size_pair")
    if w is not None and not MIN_PX <= w <= MAX_PX:
        codes.append("width_range")
    if h is not None and not MIN_PX <= h <= MAX_PX:
        codes.append("height_range")
    if not MIN_DPI <= dpi <= MAX_DPI:
        codes.append("dpi_range")
    if inp["doc"] not in DOCS:
        raise SystemExit(f"unknown doc {inp['doc']!r}")
    if codes:
        return codes, None
    return [], {"doc": inp["doc"], "width": w, "height": h, "min_kb": min_kb or 0, "max_kb": max_kb, "dpi": dpi}


def text(size):
    dims = f"{size['width']}x{size['height']}" if size["width"] is not None else "-"
    return f"{size['doc']};{dims};{size['min_kb']}-{size['max_kb']};{size['dpi']}"


def slot(size):
    exact = size["width"] is not None
    return {"type": size["doc"], "mode": "exact" if exact else "none", "width": size["width"], "height": size["height"],
            "min_kb": size["min_kb"] or None, "max_kb": size["max_kb"], "dpi": size["dpi"]}


def parse(t):
    m = TEXT.match(t)
    if not m or m.group(1) not in DOCS:
        return None
    w = int(m.group(2)) if m.group(2) is not None else None
    h = int(m.group(3)) if m.group(3) is not None else None
    codes, size = validate({"doc": m.group(1), "unit": "px", "width": w, "height": h,
                            "min_kb": int(m.group(4)), "max_kb": int(m.group(5)), "dpi": int(m.group(6))})
    return None if codes else size


def expected(case):
    if "parse" in case:
        size = parse(case["parse"])
        return {"text": text(size) if size else None}
    codes, size = validate(case["fields"])
    return {"errors": codes} if codes else {"text": text(size), "slot": slot(size)}


def write_cases():
    """Recompute `expect` of every custom size case in place, keeping every other section of cases.json byte for byte."""
    path = os.path.join(SPEC, "fixtures", "cases.json")
    src = open(path, encoding="utf-8").read()
    cases = json.loads(src)["custom_size_cases"]
    for case in cases:
        case["expect"] = expected(case)
    with open(path, "w", encoding="utf-8") as f:
        f.write(replace_json_array(src, "custom_size_cases", cases))
    print(f"wrote expectations for {len(cases)} custom size cases")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of every custom size case")
    if ap.parse_args().write_cases:
        write_cases()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
