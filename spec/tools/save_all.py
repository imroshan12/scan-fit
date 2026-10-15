#!/usr/bin/env python3
"""Reference implementation of the Save all plan (ALGORITHMS §1.6.2).

  python3 spec/tools/save_all.py --write-cases      # (re)compute the expectations of every save all case

Kotlin and Swift `SaveAllPlan` implement the same rule independently; both must pass `save_all_cases`.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

SIGNATURES = ("signature", "triple_signature")


def plan(case):
    included, held = [], []
    for doc in case["documents"]:
        if doc not in case["ready"] or doc in case["saved"]:
            continue
        if doc in SIGNATURES and not case["handwriting_confirmed"]:
            held.append(doc)
        else:
            included.append(doc)
    return {"included": included, "held": held}


def write_cases():
    """Recompute `expect` of every save all case in place, keeping every other section of cases.json byte for byte."""
    path = os.path.join(SPEC, "fixtures", "cases.json")
    text = open(path, encoding="utf-8").read()
    cases = json.loads(text)["save_all_cases"]
    for case in cases:
        case["expect"] = plan(case)
    with open(path, "w", encoding="utf-8") as f:
        f.write(replace_json_array(text, "save_all_cases", cases))
    print(f"wrote expectations for {len(cases)} save all cases")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of every save all case")
    if ap.parse_args().write_cases:
        write_cases()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
