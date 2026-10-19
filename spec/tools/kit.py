#!/usr/bin/env python3
"""Reference implementation of My Kit's pure rules (ALGORITHMS §13).

  python3 spec/tools/kit.py --write-cases      # (re)compute the expectations of every kit case

Kotlin and Swift implement the same rules independently; both must pass `kit_size_cases`, `kit_history_cases` and
`kit_id_cases`.
"""
import argparse
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

MAX_LONG_SIDE = 2400
SHARE_WINDOW_MS = 2_592_000_000
ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")


def stored_size(width, height):
    """§13.1: the long side is reduced to 2400 px; the other keeps the aspect, rounded half up, at least 1."""
    if max(width, height) <= MAX_LONG_SIDE:
        return {"width": width, "height": height}
    if width >= height:
        return {"width": MAX_LONG_SIDE, "height": max(1, math.floor(height * MAX_LONG_SIDE / width + 0.5))}
    return {"width": max(1, math.floor(width * MAX_LONG_SIDE / height + 0.5)), "height": MAX_LONG_SIDE}


def prune(case):
    """§13.3: newest first (later-recorded first on equal times), keep max_entries, shareable inside 30 days."""
    entries = list(enumerate(case["entries"]))
    entries.sort(key=lambda pair: (-pair[1]["created_at_ms"], -pair[0]))
    kept = [entry for _, entry in entries[: case["max_entries"]]]
    now = case["now_ms"]
    return {
        "kept": [e["id"] for e in kept],
        "shareable": [e["id"] for e in kept if 0 <= now - e["created_at_ms"] < SHARE_WINDOW_MS],
    }


def valid_id(case):
    return ID.fullmatch(case["id"]) is not None


SECTIONS = {
    "kit_size_cases": lambda c: stored_size(c["width"], c["height"]),
    "kit_history_cases": prune,
    "kit_id_cases": valid_id,
}


def write_cases():
    """Recompute `expect` of every kit case in place, keeping every other section of cases.json byte for byte."""
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of every kit case")
    if ap.parse_args().write_cases:
        write_cases()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
