#!/usr/bin/env python3
"""Tell you what still blocks a release build (docs/RELEASE_CHECKLIST.md). Read-only: it changes nothing.

    python3 spec/tools/release_preflight.py                  # both platforms
    python3 spec/tools/release_preflight.py --platform android
    python3 spec/tools/release_preflight.py --final          # a production release: machine-drafted Hindi blocks too

Exit 1 when a blocker is listed. Warnings (placeholder ids, TODO_HI strings on a pre-release) do not fail the run.
It never prints a secret: only whether each credential is present.
"""
import argparse
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
PLACEHOLDER_DOMAIN = "mrdoc.example"
LINK_FILES = (("android", "core", "config", "src", "main", "kotlin", "app", "scanfit", "core", "config", "AppLinks.kt"),
              ("ios", "Packages", "Core", "Sources", "Config", "Config.swift"))
BILLING_FILES = (("android", "app", "src", "main", "kotlin", "app", "scanfit", "AppModule.kt"),
                 ("ios", "ScanFit", "AppContainer.swift"))
WEB_PAGES = (("web", "index.html"), ("web", "privacy", "index.html"), ("web", "terms", "index.html"),
             ("web", "help", "index.html"), ("web", "delete-account", "index.html"))
# Left in the website drafts on purpose until the user and a lawyer fill them in (docs/LAUNCH_PLAN.md week 1).
WEB_PLACEHOLDERS = (PLACEHOLDER_DOMAIN, "[OPERATOR", "[DEVELOPER NAME", "[CITY", "REVIEW:")


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


def check_presets_dist(root, r):
    """The snapshot both apps embed: present and passing the apps' header checks (spec/README.md "Loading presets")."""
    text = read(root, "spec", "dist", "presets.json")
    if text is None:
        r.block("spec/dist/presets.json not built: run spec/tools/build_all.sh")
        return
    try:
        bundle = json.loads(text)
    except ValueError:
        bundle = None
    ints = isinstance(bundle, dict) and all(
        isinstance(bundle.get(k), int) and not isinstance(bundle.get(k), bool) for k in ("schema_version", "presets_version"))
    if not (ints and bundle["schema_version"] <= 2 and bundle["presets_version"] >= 1
            and isinstance(bundle.get("exams"), list) and bundle["exams"]):
        r.block("spec/dist/presets.json fails the format checks (JSON object, schema_version <= 2, presets_version >= 1, "
                "exams): rebuild it with spec/tools/build_all.sh")
        return
    r.ok(f"spec/dist/presets.json passes the format checks (presets_version {bundle['presets_version']})")


def check_versions(root, r):
    android = parse_properties(read(root, "android", "gradle.properties")).get("scanfit.versionName")
    ios = first(r'^\s*MARKETING_VERSION:\s*["\']?([^"\'\s#]+)', read(root, "ios", "project.yml"))
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
    bundle_id = first(r"^\s*APP_BUNDLE_ID:\s*[\"']?([^\"'\s#]+)", read(root, "ios", "project.yml"))
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


def check_launch_wiring(root, final, r):
    """Website links (privacy policy, terms: required by both stores) and real billing (ALGORITHMS 14)."""
    def flag(msg):
        (r.block if final else r.warn)(msg)

    links = [os.path.join(*f) for f in LINK_FILES if PLACEHOLDER_DOMAIN in (read(root, *f) or "")]
    if links:
        flag(f"AppLinks still point at the placeholder {PLACEHOLDER_DOMAIN} ({', '.join(links)}): set the real privacy "
             "policy, terms, help and support address once the domain is picked")
    else:
        r.ok("website links are set")
    pages = [os.path.join(*f) for f in WEB_PAGES if any(m in (read(root, *f) or "") for m in WEB_PLACEHOLDERS)]
    if pages:
        flag(f"website pages still have placeholders or REVIEW notes ({', '.join(pages)}): fill in the operator, "
             "city, developer name and support address, have the policies reviewed, and remove the REVIEW comments")
    else:
        r.ok("website pages are filled in")
    stand_in = [os.path.join(*f) for f in BILLING_FILES if "LocalBilling(" in (read(root, *f) or "")]
    if stand_in:
        flag(f"the stand-in LocalBilling is still wired ({', '.join(stand_in)}): release builds cannot sell Pro "
             "until RevenueCat replaces it (LAUNCH_PLAN week 1)")
    else:
        r.ok("billing is wired")


def run(root, platform="all", final=False, env=None):
    env = os.environ if env is None else env
    r = Report()
    check_presets_dist(root, r)
    check_versions(root, r)
    if platform in ("all", "android"):
        check_android(root, env, r)
    if platform in ("all", "ios"):
        check_ios(root, env, r)
    check_strings(root, final, r)
    check_launch_wiring(root, final, r)
    return r


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--platform", choices=["all", "android", "ios"], default="all")
    ap.add_argument("--final", action="store_true",
                    help="production release: TODO_HI strings, placeholder links and stand-in billing are blockers")
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
