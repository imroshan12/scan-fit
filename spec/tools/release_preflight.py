#!/usr/bin/env python3
"""Tell you what still blocks a release build (docs/RELEASE_CHECKLIST.md). Read-only: it changes nothing.

    python3 spec/tools/release_preflight.py                  # both platforms
    python3 spec/tools/release_preflight.py --platform android
    python3 spec/tools/release_preflight.py --final          # a production release: machine-drafted Hindi blocks too

Exit 1 when a blocker is listed. Warnings (placeholder ids, TODO_HI strings on a pre-release) do not fail the run.
It never prints a secret: only whether each credential is present.
"""
import argparse
import base64
import binascii
import json
import os
import re
import sys

from _common import REPO

PLACEHOLDER_ANDROID_ID = "app.scanfit"
PLACEHOLDER_IOS_ID = "app.scanfit.ios"
TEAM_ID_EXAMPLE = "ABCDE12345"
UPLOAD_ENV = ("SCANFIT_UPLOAD_STORE_FILE", "SCANFIT_UPLOAD_STORE_PASSWORD", "SCANFIT_UPLOAD_KEY_ALIAS", "SCANFIT_UPLOAD_KEY_PASSWORD")
KEYSTORE_FIELDS = ("storeFile", "storePassword", "keyAlias", "keyPassword")
SEMVER = re.compile(r"\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?")


class Report:
    def __init__(self):
        self.blockers, self.warnings, self.oks = [], [], []

    def block(self, msg):
        self.blockers.append(msg)

    def warn(self, msg):
        self.warnings.append(msg)

    def ok(self, msg):
        self.oks.append(msg)


def read(root, *path):
    try:
        with open(os.path.join(root, *path), encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return None


def first(pattern, text):
    m = re.search(pattern, text or "", re.MULTILINE)
    return m.group(1) if m else None


def parse_properties(text):
    out = {}
    for line in (text or "").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def public_key_bytes(text):
    try:
        raw = base64.b64decode((text or "").strip(), validate=True)
    except (binascii.Error, ValueError):
        return None
    return raw if len(raw) == 32 else None


def check_presets_key(root, r):
    prod = read(root, "spec", "signing", "prod_public_key.b64")
    dev = read(root, "spec", "signing", "dev_public_key.b64")
    if prod is None:
        r.block("spec/signing/prod_public_key.b64 is missing: run `python3 spec/tools/build_presets.py --genkey` inside the venv (spec/signing/README.md)")
        return
    key = public_key_bytes(prod)
    if key is None:
        r.block("spec/signing/prod_public_key.b64 is not a base64 32-byte Ed25519 public key (one line, base64)")
    elif prod.strip() == (dev or "").strip():
        r.block("spec/signing/prod_public_key.b64 is the public DEV key: anyone can sign presets with it")
    else:
        r.ok("production presets key present and different from the dev key")
        check_dist_signature(root, key, r)


def check_dist_signature(root, key, r):
    presets, sig = read(root, "spec", "dist", "presets.json"), read(root, "spec", "dist", "presets.json.sig")
    if presets is None or sig is None:
        r.block("spec/dist/presets.json(.sig) not built: run `PRESETS_SIGNING_KEY=<seed> spec/tools/build_all.sh --release`")
        return
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError:
        r.warn("cannot verify spec/dist against the production key (pip install -r spec/tools/requirements.txt)")
        return
    try:
        Ed25519PublicKey.from_public_bytes(key).verify(base64.b64decode(sig.strip(), validate=True), presets.encode("utf-8"))
        r.ok("spec/dist/presets.json is signed with the production key")
    except (InvalidSignature, ValueError, binascii.Error):
        r.block("spec/dist/presets.json is not signed with the production key: re-run build_all.sh --release with PRESETS_SIGNING_KEY")


def check_versions(root, r):
    android = parse_properties(read(root, "android", "gradle.properties")).get("scanfit.versionName")
    ios = first(r'^\s*MARKETING_VERSION:\s*"?([^"\s#]+)', read(root, "ios", "project.yml"))
    for label, v in (("android/gradle.properties scanfit.versionName", android), ("ios/project.yml MARKETING_VERSION", ios)):
        if v is None or not SEMVER.fullmatch(v):
            r.block(f"{label} is missing or not semver (got {v!r})")
    if android and ios and SEMVER.fullmatch(android) and SEMVER.fullmatch(ios):
        # iOS only accepts x.y.z, so a pre-release suffix lives in the git tag, not in project.yml.
        if android.split("-")[0] != ios:
            r.block(f"the shared release version differs: Android {android}, iOS {ios} (ARCHITECTURE section 12)")
        else:
            r.ok(f"shared release version {ios}")


def check_android(root, env, r):
    props = parse_properties(read(root, "android", "keystore.properties"))
    values = {f: env.get(e) or props.get(f) for f, e in zip(KEYSTORE_FIELDS, UPLOAD_ENV)}
    missing = [f for f, v in values.items() if not v]
    placeholders = [f for f, v in values.items() if v and (v == "REPLACE_ME" or "/ABSOLUTE/" in v)]
    if missing:
        r.block("Android upload key not configured (missing " + ", ".join(missing) + "): copy android/keystore.properties.example "
                "to android/keystore.properties, or set SCANFIT_UPLOAD_*")
    elif placeholders:
        r.block("android/keystore.properties still has placeholder values: " + ", ".join(placeholders))
    elif not os.path.isfile(os.path.join(root, "android", values["storeFile"])):
        r.block("the Android upload keystore file does not exist (storeFile)")
    else:
        r.ok("Android upload key configured")
    app_id = first(r'applicationId\s*=\s*"([^"]+)"', read(root, "android", "app", "build.gradle.kts"))
    if app_id == PLACEHOLDER_ANDROID_ID:
        r.warn(f"Android applicationId is still the placeholder '{app_id}': permanent once published on Google Play")


def check_ios(root, env, r):
    team = env.get("DEVELOPMENT_TEAM") or first(r"^DEVELOPMENT_TEAM\s*=\s*([A-Z0-9]{10})\b", read(root, "ios", "Config", "Local.xcconfig"))
    if not team:
        r.block("iOS signing team not set: DEVELOPMENT_TEAM=<id> or ios/Config/Local.xcconfig (Local.xcconfig.example)")
    elif team == TEAM_ID_EXAMPLE:
        r.block("iOS signing team is still the example value " + TEAM_ID_EXAMPLE)
    else:
        r.ok("iOS signing team set")
    bundle_id = first(r"^\s*APP_BUNDLE_ID:\s*(\S+)", read(root, "ios", "project.yml"))
    if bundle_id == PLACEHOLDER_IOS_ID:
        r.warn(f"iOS bundle id is still the placeholder '{bundle_id}': permanent once published on the App Store")


def check_strings(root, final, r):
    hi = {k: v for k, v in json.loads(read(root, "spec", "strings", "hi.json") or "{}").items() if not k.startswith("_")}
    todo = sum(1 for v in hi.values() if isinstance(v, str) and v.startswith("TODO_HI:"))
    todo += sum(1 for v in hi.values() if isinstance(v, dict) and any(str(x).startswith("TODO_HI:") for x in v.values()))
    if not todo:
        r.ok("no machine-drafted Hindi left")
    elif final:
        r.block(f"{todo} Hindi string(s) still marked TODO_HI: a final release needs the native-speaker review (ROADMAP Phase 3)")
    else:
        r.warn(f"{todo} Hindi string(s) still marked TODO_HI: fine for internal/closed testing "
               "(tag -rc/-beta, build_all.sh --release --allow-todo-hi), a blocker for a final release (--final)")


def run(root, platform="all", final=False, env=None):
    env = os.environ if env is None else env
    r = Report()
    check_presets_key(root, r)
    check_versions(root, r)
    if platform in ("all", "android"):
        check_android(root, env, r)
    if platform in ("all", "ios"):
        check_ios(root, env, r)
    check_strings(root, final, r)
    return r


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--platform", choices=["all", "android", "ios"], default="all")
    ap.add_argument("--final", action="store_true", help="production release: TODO_HI strings are blockers")
    args = ap.parse_args()
    r = run(REPO, args.platform, args.final)
    for m in r.oks:
        print(f"  ok       {m}")
    for m in r.warnings:
        print(f"  warning  {m}")
    for m in r.blockers:
        print(f"  BLOCKER  {m}")
    if r.blockers:
        sys.exit(f"{len(r.blockers)} blocker(s) before a release build")
    print("✓ nothing blocks a release build" + (f" ({len(r.warnings)} warning(s))" if r.warnings else ""))


if __name__ == "__main__":
    main()
