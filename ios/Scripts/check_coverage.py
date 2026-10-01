#!/usr/bin/env python3
"""Fail unless the engine modules reach the required line coverage (CLAUDE.md rule 10: >= 90%).

Usage (from ios/Packages/Core, after `swift test --enable-code-coverage`):
    python3 ../../Scripts/check_coverage.py [--min 90] [--module Inspect --module Imaging ...]

It runs `llvm-cov export` over every test binary and the shared profile, then sums per-file line counts per module. Only files under
Sources/<module>/ count; generated code (Sources/*/Generated) and tests are ignored. Prints one line per module and per weak file.
"""
import argparse
import glob
import json
import os
import subprocess
import sys

# CLAUDE.md rule 10: engine code (imaging, pdf, match, inspect) needs >= 90%. Add `--module` for others; `pdf` joins in Phase 3.
DEFAULT_MODULES = ["Inspect", "Imaging", "Match"]


def find(pattern):
    hits = glob.glob(pattern, recursive=True)
    return hits[0] if hits else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min", type=float, default=90.0)
    ap.add_argument("--module", action="append", dest="modules")
    ap.add_argument("--build-dir", default=".build")
    args = ap.parse_args()
    modules = args.modules or DEFAULT_MODULES

    # One shared profile, one test binary per package target: export coverage over all of them together.
    profile = find(os.path.join(args.build_dir, "**", "codecov", "default.profdata"))
    binaries = sorted(set(glob.glob(os.path.join(args.build_dir, "**", "*.xctest", "Contents", "MacOS", "*"), recursive=True)))
    if not profile or not binaries:
        sys.exit("coverage data not found: run `swift test --enable-code-coverage` first")
    cmd = ["xcrun", "llvm-cov", "export", binaries[0], f"-instr-profile={profile}", "-summary-only"]
    for extra in binaries[1:]:
        cmd += ["-object", extra]
    files = json.loads(subprocess.run(cmd, check=True, capture_output=True, text=True).stdout)["data"][0]["files"]

    totals = {m: [0, 0] for m in modules}  # covered, total
    weak = []
    for f in files:
        path = f["filename"]
        if "/Generated/" in path or "/Tests/" in path:
            continue
        for m in modules:
            if f"/Sources/{m}/" in path:
                lines = f["summary"]["lines"]
                totals[m][0] += lines["covered"]
                totals[m][1] += lines["count"]
                if lines["count"] and lines["percent"] < args.min:
                    weak.append((lines["percent"], os.path.relpath(path)))

    failed = False
    for m in modules:
        covered, total = totals[m]
        if total == 0:
            failed = True
            print(f"  --  {m:12s} no lines measured (module not found in the coverage data)")
            continue
        pct = 100.0 * covered / total
        flag = "ok " if pct >= args.min else "LOW"
        failed |= pct < args.min
        print(f"  {flag} {m:12s} {pct:5.1f}%  ({covered}/{total} lines)")
    for pct, path in sorted(weak):
        print(f"      below {args.min:.0f}%: {pct:5.1f}%  {path}")
    if failed:
        sys.exit(f"coverage below {args.min:.0f}% in at least one engine module")
    print(f"✓ every engine module is at or above {args.min:.0f}% line coverage")


if __name__ == "__main__":
    main()
