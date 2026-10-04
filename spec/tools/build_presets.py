#!/usr/bin/env python3
"""
Validate every exam preset, bundle them, and (optionally) sign the bundle.

Usage:
  python spec/tools/build_presets.py                 # validate + bundle to spec/dist/
  PRESETS_SIGNING_KEY=<base64 ed25519 seed> python spec/tools/build_presets.py --sign
  python spec/tools/build_presets.py --sign-dev      # sign with the public DEV key (local builds only, never release)
  python spec/tools/build_presets.py --verify spec/signing/dev_public_key.b64   # check dist/ against a public key
  python spec/tools/build_presets.py --genkey        # prints a new keypair (store the private key in CI secrets)

Outputs (spec/dist/):
  presets.json      bundle consumed by both apps (also copied into each app as the offline snapshot)
  presets.json.sig  base64 Ed25519 signature over the exact bytes of presets.json
Exit code != 0 on any validation failure. CI must run this on every PR touching spec/.
"""
import argparse, base64, glob, json, os, sys

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
    ap = argparse.ArgumentParser()
    ap.add_argument("--sign", action="store_true")
    ap.add_argument("--sign-dev", action="store_true", help="sign with the public DEV key (never ship)")
    ap.add_argument("--verify", metavar="PUBKEY_B64_FILE", help="verify dist/presets.json(.sig) and exit")
    ap.add_argument("--genkey", action="store_true")
    args = ap.parse_args()
    require("cryptography") if (args.verify or args.genkey) else require("cryptography", "jsonschema")

    if args.verify:
        verify_dist(args.verify)
        return

    if args.genkey:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives import serialization as ser
        k = Ed25519PrivateKey.generate()
        priv = k.private_bytes(ser.Encoding.Raw, ser.PrivateFormat.Raw, ser.NoEncryption())
        pub = k.public_key().public_bytes(ser.Encoding.Raw, ser.PublicFormat.Raw)
        print("PRIVATE (CI secret PRESETS_SIGNING_KEY):", base64.b64encode(priv).decode())
        print("PUBLIC  (embed in both apps):          ", base64.b64encode(pub).decode())
        return

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
    print(f"✓ {len(exams)} exams valid · presets_version {version} · {len(raw)/1024:.1f} KB")

    if args.sign or args.sign_dev:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        if args.sign_dev:
            from make_signing_vector import DEV_SEED
            seed = DEV_SEED
            print("! signed with the public DEV key: for local builds only, release builds reject it", file=sys.stderr)
        else:
            if not os.environ.get("PRESETS_SIGNING_KEY"):
                fail(["--sign needs the PRESETS_SIGNING_KEY environment variable (base64 Ed25519 seed)"])
            seed = base64.b64decode(os.environ["PRESETS_SIGNING_KEY"])
        sig = Ed25519PrivateKey.from_private_bytes(seed).sign(raw)
        with open(os.path.join(DIST, "presets.json.sig"), "w") as f:
            f.write(base64.b64encode(sig).decode())
        print("✓ signed")


def verify_dist(pubkey_file):
    """Exit 0 only if dist/presets.json.sig is a valid Ed25519 signature over dist/presets.json."""
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    try:
        pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(open(pubkey_file).read().strip()))
        raw = open(os.path.join(DIST, "presets.json"), "rb").read()
        sig = base64.b64decode(open(os.path.join(DIST, "presets.json.sig")).read().strip(), validate=True)
        pub.verify(sig, raw)
    except FileNotFoundError as e:
        fail([f"{e.filename} not found: run spec/tools/build_all.sh first"])
    except (InvalidSignature, ValueError) as e:
        fail([f"dist/presets.json does not verify against {pubkey_file} ({type(e).__name__}). "
              "Re-sign with the matching private key."])
    print(f"✓ dist/presets.json verifies against {os.path.relpath(pubkey_file, ROOT)}")


if __name__ == "__main__":
    main()
