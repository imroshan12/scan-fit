#!/usr/bin/env python3
"""Reference implementation of the match note summary (ALGORITHMS §4 "Match note on review" and §9.7 "Match note").

  python3 spec/tools/match_note.py --write-cases      # (re)compute the expectations of every match note case

A case's `entries` are a match result, already sorted (§9.7). Kotlin and Swift `MatchNote.of` implement the same rules
independently; both must pass `match_note_cases`.
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

PREVIEW = 3
ACCEPTING = ("exact", "accepted")


def need(entry):
    fix, size = entry["fix"], entry.get("size_kb") or {}
    if fix == "compress_to_target":
        return {"need": "at_most", "kb": math.floor(size["max"])}
    if fix == "enlarge_to_target":
        return {"need": "at_least", "kb": math.ceil(size["min"])}
    if fix == "convert_to_jpeg":
        return {"need": "jpeg"}
    if fix == "reencode_baseline":
        return {"need": "baseline"}
    raise SystemExit(f"unknown fix {fix!r}")


def note(entries):
    accepted_ids = []
    for e in entries:
        if not e["unverified"] and e["verdict"] in ACCEPTING and e["exam"] not in accepted_ids:
            accepted_ids.append(e["exam"])
    likely = []
    for e in entries:
        if e["unverified"] and e["verdict"] in ACCEPTING and e["exam"] not in accepted_ids and e["exam"] not in likely:
            likely.append(e["exam"])
    fixes = [{"exam": e["exam"], "doc": e["doc"], **need(e)}
             for e in entries if not e["unverified"] and e["verdict"] == "near_miss"]
    groups = []
    for e in entries:
        group = next((g for g in groups if g["body"] == e["body"]), None)
        if group is None:
            group = {"body": e["body"], "exams": []}
            groups.append(group)
        group["exams"].append(e["exam"])
    preview = accepted_ids[:PREVIEW]
    return {"accepted": len(accepted_ids), "preview": preview, "more": len(accepted_ids) - len(preview),
            "likely_ok": len(likely), "quick_fixes": fixes, "groups": groups}


def write_cases():
    """Recompute `expect` of every match note case in place, keeping every other section of cases.json byte for byte."""
    path = os.path.join(SPEC, "fixtures", "cases.json")
    text = open(path, encoding="utf-8").read()
    cases = json.loads(text)["match_note_cases"]
    for case in cases:
        case["expect"] = note(case["entries"])
    with open(path, "w", encoding="utf-8") as f:
        f.write(replace_json_array(text, "match_note_cases", cases))
    print(f"wrote expectations for {len(cases)} match note cases")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of every match note case")
    if ap.parse_args().write_cases:
        write_cases()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
