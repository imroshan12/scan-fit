#!/usr/bin/env python3
"""Generate the Ed25519 signing test vector in spec/fixtures/signing/ (ROADMAP Phase 0).

Both apps run `vector.json` in unit tests: the valid case must verify, every other case must be
rejected with the stated reason. The script is deterministic (fixed seeds, Ed25519 signatures are
deterministic), so re-running it must produce no diff. CI runs it with --check.

!! The DEV and OTHER seeds below are derived from public strings and live in this repo. Anyone can
!! sign with them. They are for tests and local dev builds ONLY. Release builds must embed the real
!! public key from spec/signing/prod_public_key.b64 (see spec/signing/README.md); the Android and iOS
!! release build steps refuse to build with the dev key.

Load order both apps implement (PresetBundleLoader), with the failure reason names used by the
`sync_failure_reason` analytics enum:
  1. signature   : base64(trimmed) must decode to 64 bytes and verify against the raw bytes -> bad_signature
  2. header      : JSON object with integer schema_version and presets_version               -> parse
  3. schema gate : schema_version <= 2 (the supported version)                               -> bad_schema
  4. monotonic   : presets_version > currently installed version                             -> stale_version
  5. full decode : the whole bundle decodes into the model                                   -> parse
"""
import argparse
import base64
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, require  # noqa: E402

require("cryptography")

from cryptography.hazmat.primitives import serialization as ser  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402

OUT = os.path.join(SPEC, "fixtures", "signing")
SIGNING = os.path.join(SPEC, "signing")


def seed(label: str) -> bytes:
    return hashlib.sha256(label.encode()).digest()


DEV_SEED = seed("scanfit/dev-signing-key/v1")
OTHER_SEED = seed("scanfit/other-signing-key/v1")


def key(seed_bytes):
    return Ed25519PrivateKey.from_private_bytes(seed_bytes)


def pub_b64(k):
    return base64.b64encode(k.public_key().public_bytes(ser.Encoding.Raw, ser.PublicFormat.Raw)).decode()


def canonical(obj) -> bytes:
    """Same serialisation as build_presets.py, so the vector exercises the real byte layout."""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()


def sample_bundle(version=7, schema=2):
    return {
        "schema_version": schema,
        "presets_version": version,
        "generated_on": "2026-01-01",
        "disclaimer": "Test vector. Not real exam data.",
        "exams": [{
            "id": "test_exam", "name": "Test Exam", "body": "TEST", "category": "banking", "portal": None,
            "live_photo_capture": False, "status": "active",
            "documents": [{
                "type": "photo", "required": True, "formats": ["jpg", "jpeg"],
                "size_kb": {"min": 20, "max": 50, "target": 38},
                "dimensions": {"mode": "preferred", "width": 200, "height": 230},
            }],
            "special_rules": [],
            "sources": [{"url": "https://example.com/notice.pdf", "kind": "official"}],
            "confidence": "high", "confidence_note": "Test vector", "last_verified": "2026-01-01",
        }],
    }


def build():
    """Return {filename: bytes} for every vector file."""
    dev, other = key(DEV_SEED), key(OTHER_SEED)
    good = canonical(sample_bundle())
    sig = dev.sign(good)
    sig_b64 = base64.b64encode(sig)
    tampered = good.replace(b'"max":50', b'"max":500')
    assert tampered != good
    schema3 = canonical(sample_bundle(version=9, schema=3))
    malformed = b'{"schema_version":2,"presets_version":8,"exams":[{"id":'
    header_only = canonical({"schema_version": 2, "presets_version": 8, "exams": "not-a-list"})
    files = {
        "dev_public_key.b64": pub_b64(dev).encode(),
        "other_public_key.b64": pub_b64(other).encode(),
        "sample_presets.json": good,
        "sample_presets.json.sig": sig_b64,
        "sample_presets.sig_trailing_newline": sig_b64 + b"\n",
        "tampered_presets.json": tampered,
        "sig_truncated.b64": base64.b64encode(sig[:63]),
        "sig_not_base64.txt": b"!!!not base64!!!",
        "sig_empty.txt": b"",
        "other_key_signature.b64": base64.b64encode(other.sign(good)),
        "schema3_presets.json": schema3,
        "schema3_presets.json.sig": base64.b64encode(dev.sign(schema3)),
        "malformed_presets.json": malformed,
        "malformed_presets.json.sig": base64.b64encode(dev.sign(malformed)),
        "bad_shape_presets.json": header_only,
        "bad_shape_presets.json.sig": base64.b64encode(dev.sign(header_only)),
    }
    pk, ok = "dev_public_key.b64", "other_public_key.b64"
    verify = [
        ("valid", "sample_presets.json", "sample_presets.json.sig", pk, True),
        ("valid_signature_with_trailing_newline", "sample_presets.json", "sample_presets.sig_trailing_newline", pk, True),
        ("tampered_body", "tampered_presets.json", "sample_presets.json.sig", pk, False),
        ("signed_by_other_key", "sample_presets.json", "other_key_signature.b64", pk, False),
        ("verified_with_wrong_public_key", "sample_presets.json", "sample_presets.json.sig", ok, False),
        ("signature_truncated", "sample_presets.json", "sig_truncated.b64", pk, False),
        ("signature_not_base64", "sample_presets.json", "sig_not_base64.txt", pk, False),
        ("signature_empty", "sample_presets.json", "sig_empty.txt", pk, False),
    ]
    load = [  # current_version = what is installed; expect = "ok" or a failure reason
        ("newer_version_accepted", "sample_presets.json", "sample_presets.json.sig", 6, "ok"),
        ("first_install_accepted", "sample_presets.json", "sample_presets.json.sig", 0, "ok"),
        ("same_version_rejected", "sample_presets.json", "sample_presets.json.sig", 7, "stale_version"),
        ("older_version_rejected", "sample_presets.json", "sample_presets.json.sig", 9, "stale_version"),
        ("tampered_rejected", "tampered_presets.json", "sample_presets.json.sig", 0, "bad_signature"),
        ("unsupported_schema_rejected", "schema3_presets.json", "schema3_presets.json.sig", 0, "bad_schema"),
        ("signed_garbage_rejected", "malformed_presets.json", "malformed_presets.json.sig", 0, "parse"),
        ("signed_wrong_shape_rejected", "bad_shape_presets.json", "bad_shape_presets.json.sig", 0, "parse"),
    ]
    vector = {
        "_doc": "Run by Android (:core:presets) and iOS (Presets) unit tests. Files are in this directory. "
                "verify_cases test raw Ed25519 verification; load_cases test the full loader order. "
                "DEV KEY: derived from a public string, for tests and local dev builds only.",
        "supported_schema_version": 2,
        "supported_failure_reasons": ["bad_signature", "bad_schema", "stale_version", "parse"],
        "verify_cases": [{"id": i, "bundle": b, "signature": s, "public_key": p, "valid": v}
                         for i, b, s, p, v in verify],
        "load_cases": [{"id": i, "bundle": b, "signature": s, "public_key": pk, "current_version": c, "expect": e}
                       for i, b, s, c, e in load],
    }
    files["vector.json"] = (json.dumps(vector, indent=2, ensure_ascii=False) + "\n").encode()
    return files


def validate_sample_against_schema(good: bytes):
    import jsonschema
    schema = json.load(open(os.path.join(SPEC, "schema", "exam.schema.json")))
    v = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    for exam in json.loads(good)["exams"]:
        errs = list(v.iter_errors(exam))
        assert not errs, f"sample exam invalid: {errs[0].message}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    files = build()
    validate_sample_against_schema(files["sample_presets.json"])
    # The apps embed this key in debug builds (spec/signing/README.md).
    files_out = {os.path.join(OUT, n): d for n, d in files.items()}
    files_out[os.path.join(SIGNING, "dev_public_key.b64")] = files["dev_public_key.b64"]
    drift = []
    for path, data in files_out.items():
        cur = open(path, "rb").read() if os.path.exists(path) else None
        if cur != data:
            drift.append(os.path.relpath(path, os.path.dirname(SPEC)))
            if not args.check:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                open(path, "wb").write(data)
    if args.check and drift:
        print("✗ signing vector is stale:", *drift, sep="\n    ", file=sys.stderr)
        sys.exit(1)
    print(f"✓ signing vector {'up to date' if args.check or not drift else 'written (%d files)' % len(drift)}"
          f" · dev public key {pub_b64(key(DEV_SEED))}")


if __name__ == "__main__":
    main()
