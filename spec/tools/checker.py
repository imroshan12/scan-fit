#!/usr/bin/env python3
"""Reference implementation of the Checker status (ALGORITHMS §12).

  python3 spec/tools/checker.py --write-cases      # (re)compute the expectations of every checker case

Kotlin and Swift `CheckerStatus.of` implement the same rule independently; both must pass `checker_cases`.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

DECODABLE = ("jpeg", "png", "webp", "heic")


def status(case):
    verdict = case["verdict"]
    if verdict == "unknown":
        return "unknown"
    if verdict in ("exact", "accepted"):
        return "likely_ok" if case["unverified"] else "ok"
    if case["format"] in DECODABLE and any(f in ("jpg", "jpeg") for f in case["slot_formats"]):
        return "fix"
    return "no_fix"


def write_cases():
    path = os.path.join(SPEC, "fixtures", "cases.json")
    text = open(path, encoding="utf-8").read()
    cases = json.loads(text)["checker_cases"]
    for case in cases:
        case["expect"] = status(case)
    with open(path, "w", encoding="utf-8") as f:
        f.write(replace_json_array(text, "checker_cases", cases))
    print(f"wrote expectations for {len(cases)} checker cases")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of every checker case")
    if ap.parse_args().write_cases:
        write_cases()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
