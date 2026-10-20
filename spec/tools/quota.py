#!/usr/bin/env python3
"""Reference implementation of the free-saves rules (ALGORITHMS §14).

  python3 spec/tools/quota.py --write-cases      # (re)compute the expectations of every save_quota case

Kotlin and Swift implement the same rules independently; both must pass `save_quota_cases`.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC, replace_json_array  # noqa: E402

MAX_HASHES = 1000
SHOWS_LEFT_AT = 5


class Quota:
    def __init__(self, used=0, allowance=0, hashes=(), max_hashes=MAX_HASHES):
        self.used, self.allowance, self.hashes, self.max_hashes = used, allowance, list(hashes), max_hashes

    def total(self, t):
        if t >= 0:
            self.allowance = max(self.allowance, t)

    @property
    def remaining(self):
        return max(0, self.allowance - self.used)

    def new_files(self, files):
        out = []
        for f in files:
            if f not in self.hashes and f not in out:
                out.append(f)
        return out

    def decide(self, files, pro):
        new = self.new_files(files)
        return {"allowed": pro or not new or len(new) <= self.remaining, "new": len(new)}

    def record(self, file, pro):
        if file in self.hashes:
            self.hashes.remove(file)
            self.hashes.append(file)
            return
        self.hashes.append(file)
        del self.hashes[: max(0, len(self.hashes) - self.max_hashes)]
        if not pro:
            self.used += 1

    def merge(self, remote_used):
        if remote_used >= 0:
            self.used = max(self.used, remote_used)

    def state(self, pro):
        return {"used": self.used, "allowance": self.allowance, "remaining": self.remaining,
                "shows_left": not pro and self.remaining <= SHOWS_LEFT_AT}


def run(case):
    """Runs the steps in order; returns the list of expectations (`None` for steps that return nothing)."""
    start = case.get("start", {})
    q = Quota(start.get("used", 0), start.get("allowance", 0), start.get("hashes", []),
              case.get("max_hashes", MAX_HASHES))
    out = []
    for step in case["steps"]:
        op = step["op"]
        if op == "total":
            q.total(step["total"])
            out.append(None)
        elif op == "decide":
            out.append(q.decide(step["files"], step.get("pro", False)))
        elif op == "record":
            q.record(step["file"], step.get("pro", False))
            out.append(None)
        elif op == "merge":
            q.merge(step["remote_used"])
            out.append(None)
        elif op == "state":
            out.append(q.state(step.get("pro", False)))
        else:
            raise ValueError(f"unknown op {op}")
    return out


def expectations(case):
    return [step.get("expect") for step in case["steps"]]


def write_cases():
    """Recompute each step's `expect` in place, keeping every other section of cases.json byte for byte."""
    path = os.path.join(SPEC, "fixtures", "cases.json")
    text = open(path, encoding="utf-8").read()
    cases = json.loads(text)["save_quota_cases"]
    for case in cases:
        for step, result in zip(case["steps"], run(case)):
            step.pop("expect", None)
            if result is not None:
                step["expect"] = result
    text = replace_json_array(text, "save_quota_cases", cases)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote expectations for {len(cases)} save_quota_cases")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write-cases", action="store_true", help="recompute the expectations of every save_quota case")
    if ap.parse_args().write_cases:
        write_cases()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
