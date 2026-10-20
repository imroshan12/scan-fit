#!/usr/bin/env python3
"""
Validate every exam preset and bundle them.

Usage:
  python spec/tools/build_presets.py                 # validate + bundle to spec/dist/

Outputs (spec/dist/):
  presets.json      bundle consumed by both apps (also copied into each app as the offline snapshot, and published
                    on the website for the OTA sync). Not signed: the apps check its format (spec/README.md).
Exit code != 0 on any validation failure. CI must run this on every PR touching spec/.
"""
import glob, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import require
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXAMS = os.path.join(ROOT, "presets", "exams")
SCHEMA = os.path.join(ROOT, "schema", "exam.schema.json")
VERSION_FILE = os.path.join(ROOT, "presets", "VERSION")
CATEGORIES_FILE = os.path.join(ROOT, "presets", "categories.json")
POPULAR_FILE = os.path.join(ROOT, "presets", "popular.json")
DIST = os.path.join(ROOT, "dist")


def fail(errors):
    for e in errors:
        print(f"  ✗ {e}", file=sys.stderr)
    sys.exit(1)


def search_data(schema, exams):
    """Category search words and the default popularity order (ALGORITHMS §10). Returns (categories, popular, errors)."""
    errors = []
    category_ids = schema["properties"]["category"]["enum"]
    raw = {k: v for k, v in json.load(open(CATEGORIES_FILE, encoding="utf-8")).items() if not k.startswith("_")}
    if sorted(raw) != sorted(category_ids):
        errors.append(f"categories.json: keys must be exactly {sorted(category_ids)}, got {sorted(raw)}")
    categories = {}
    for cat, entry in raw.items():
        aliases = entry.get("aliases") if isinstance(entry, dict) else None
        if not aliases or not all(isinstance(a, str) and len(a.strip()) >= 2 for a in aliases):
            errors.append(f"categories.json: {cat}.aliases must be a non-empty list of strings (2+ characters)")
        elif len(set(aliases)) != len(aliases):
            errors.append(f"categories.json: {cat}.aliases has duplicates")
        else:
            categories[cat] = {"aliases": aliases}
    popular = json.load(open(POPULAR_FILE, encoding="utf-8")).get("popular", [])
    active = {e["id"] for e in exams if e["status"] == "active"}
    if len(set(popular)) != len(popular):
        errors.append("popular.json: duplicate ids")
    errors += [f"popular.json: '{i}' is not an active exam id" for i in popular if i not in active]
    return categories, popular, errors


def semantic_checks(exam, path):
    errs = []
    types = [d["type"] for d in exam["documents"]]
    if len(types) != len(set(types)):
        errs.append(f"{path}: duplicate document types")
    for d in exam["documents"]:
        s = d["size_kb"]
        tag = f"{exam['id']}.{d['type']}"
        if s["min"] is not None and s["max"] is not None and s["min"] >= s["max"]:
            errs.append(f"{tag}: min_kb >= max_kb")
        if s["target"] is not None:
            lo, hi = s["min"] or 0, s["max"]
            if hi is None or not (lo <= s["target"] <= hi):
                errs.append(f"{tag}: target {s['target']} outside [{lo}, {hi}]")
            else:
                margin = min((hi - lo) * 0.1, 25)  # 10% of window, capped at 25 KB for very wide windows
                if s["target"] - lo < margin or hi - s["target"] < margin:
                    errs.append(f"{tag}: target too close to window edge (need {margin:.0f} KB margin)")
        if s["max"] is None and exam["confidence"] != "low":
            errs.append(f"{tag}: no max_kb on a non-low-confidence preset")
        dims = d["dimensions"]
        if dims["mode"] == "range" and (dims["min_w"] > dims["max_w"] or dims["min_h"] > dims["max_h"]):
            errs.append(f"{tag}: inverted dimension range")
        if "pdf" in d["formats"] and dims["mode"] != "none":
            errs.append(f"{tag}: PDFs must use dimensions.mode = none")
    if exam["confidence"] == "high" and not any(s["kind"] == "official" for s in exam["sources"]) and len(exam["sources"]) < 2:
        errs.append(f"{exam['id']}: high confidence needs an official source or >=2 sources")
    if exam["last_verified"] > date.today().isoformat():
        errs.append(f"{exam['id']}: last_verified is in the future")
    return errs


def main():
    if len(sys.argv) > 1:
        fail([f"unknown arguments {sys.argv[1:]}: presets are no longer signed, so --sign/--verify/--genkey are gone"])
    require("jsonschema")
    import jsonschema
    schema = json.load(open(SCHEMA))
    validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())

    exams, errors, seen = [], [], set()
    for path in sorted(glob.glob(os.path.join(EXAMS, "**", "*.json"), recursive=True)):
        rel = os.path.relpath(path, ROOT)
        exam = json.load(open(path))
        schema_errs = [f"{rel}: {'/'.join(map(str, e.path))}: {e.message}" for e in validator.iter_errors(exam)]
        if schema_errs:
            errors += schema_errs
            continue
        if exam["id"] in seen:
            errors.append(f"{rel}: duplicate id {exam['id']}")
        if os.path.basename(path) != f"{exam['id']}.json":
            errors.append(f"{rel}: file name must equal id")
        seen.add(exam["id"])
        errors += semantic_checks(exam, rel)
        exams.append(exam)
    categories, popular, search_errors = search_data(schema, exams)
    errors += search_errors
    if errors:
        print(f"Preset validation failed ({len(errors)} errors):", file=sys.stderr)
        fail(errors)

    version = int(open(VERSION_FILE).read().strip())
    bundle = {
        "schema_version": 2,
        "presets_version": version,
        "generated_on": date.today().isoformat(),
        "disclaimer": "Independent tool data, not affiliated with any exam body. "
                      "Always verify against the current official notification before submitting.",
        "exams": sorted(exams, key=lambda e: (e["category"], e["name"])),
        "categories": categories,
        "popular": popular,
    }
    os.makedirs(DIST, exist_ok=True)
    raw = json.dumps(bundle, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    with open(os.path.join(DIST, "presets.json"), "wb") as f:
        f.write(raw)
    # A leftover signature from before 2026-10-20 would only confuse: nothing reads it any more.
    stale_sig = os.path.join(DIST, "presets.json.sig")
    if os.path.exists(stale_sig):
        os.remove(stale_sig)
    print(f"✓ {len(exams)} exams valid · presets_version {version} · {len(raw)/1024:.1f} KB")


if __name__ == "__main__":
    main()
