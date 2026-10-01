#!/usr/bin/env python3
"""Fail unless the engine modules reach the required line coverage (CLAUDE.md rule 10: >= 90%).

Usage (from android/, after the tests have run with coverage):
    ./gradlew :core:inspect:test :core:match:test :core:imaging:createDebugUnitTestCoverageReport
    python3 config/coverage/check_coverage.py [--min 90]

Reads the JaCoCo XML each engine module produced: `jacocoTestReport.xml` for the pure-JVM modules, the AGP
`createDebugUnitTestCoverageReport` XML for Android modules. Prints one line per module and per weak class.
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

# module -> glob of its report. Engines only (CLAUDE.md rule 10: imaging, pdf, match, inspect); `core:pdf` joins in Phase 3.
# UI, DI wiring, presets loading and generated code are not part of the gate.
MODULES = {
    "core:inspect": "core/inspect/build/reports/jacoco/test/jacocoTestReport.xml",
    "core:match": "core/match/build/reports/jacoco/test/jacocoTestReport.xml",
    "core:imaging": "core/imaging/build/reports/coverage/test/debug/**/*.xml",
}
# Classes that are platform glue or test-only and are exercised by instrumented tests later (Phase 2+).
EXCLUDE_CLASSES = ("AndroidStripRenderer$",)


def counters(path):
    root = ET.parse(path).getroot()
    covered = missed = 0
    weak = []
    for pkg in root.iter("package"):
        for cls in pkg.findall("class"):
            name = cls.get("name", "")
            if any(name.endswith(e) for e in EXCLUDE_CLASSES):
                continue
            for c in cls.findall("counter"):
                if c.get("type") == "LINE":
                    cv, ms = int(c.get("covered")), int(c.get("missed"))
                    covered += cv
                    missed += ms
                    if cv + ms and 100.0 * cv / (cv + ms) < 90.0:
                        weak.append((100.0 * cv / (cv + ms), name.replace("/", ".")))
    return covered, missed, weak


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min", type=float, default=90.0)
    args = ap.parse_args()
    failed = False
    for module, pattern in MODULES.items():
        reports = [p for p in glob.glob(pattern, recursive=True) if os.path.isfile(p)]
        if not reports:
            print(f"  --  {module:14s} no coverage report ({pattern})")
            failed = True
            continue
        covered, missed, weak = counters(reports[0])
        total = covered + missed
        pct = 100.0 * covered / total if total else 100.0
        failed |= pct < args.min
        print(f"  {'ok ' if pct >= args.min else 'LOW'} {module:14s} {pct:5.1f}%  ({covered}/{total} lines)")
        for p, n in sorted(weak):
            print(f"      class below 90%: {p:5.1f}%  {n}")
    if failed:
        sys.exit(f"coverage below {args.min:.0f}% (or missing) in at least one engine module")
    print(f"✓ every engine module is at or above {args.min:.0f}% line coverage")


if __name__ == "__main__":
    main()
