#!/usr/bin/env python3
"""Reference implementation of ALGORITHMS §10 (exam search and browse).

It computes the expected results in `fixtures/cases.json` (`search_cases`), which the Kotlin and Swift engines must reproduce
exactly. Keep it a literal transcription of the spec: when this and the spec disagree, fix this file.

    python3 spec/tools/exam_search.py "ssc cgl"          # try a query against the source presets
    python3 spec/tools/exam_search.py --write-cases      # (re)compute the expected ids of every search case
"""
import argparse
import glob
import json
import os
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import SPEC  # noqa: E402

DELETED = {"़", "‌", "‍"}  # Devanagari nukta, zero-width non-joiner, zero-width joiner


def norm(text):
    """§10 normalisation: NFKC, default lower-casing, drop nukta/ZWNJ/ZWJ, non letter/mark/digit -> space, split."""
    s = unicodedata.normalize("NFKC", text).lower()
    out = []
    for ch in s:
        if ch in DELETED:
            continue
        cat = unicodedata.category(ch)
        out.append(ch if cat[0] in "LM" or cat == "Nd" else " ")
    return "".join(out).split()


def osa(a, b):
    """Optimal string alignment distance: Levenshtein plus adjacent transposition, each costing 1."""
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[len(a)][len(b)]


def level(q, t):
    if q == t:
        return 3
    if t.startswith(q):
        return 2
    if len(q) >= 4:
        for k in (len(q) - 1, len(q), len(q) + 1):
            if k <= len(t) and osa(q, t[:k]) <= 1:
                return 1
    return 0


def index_tokens(exam, categories):
    tokens = []
    phrases = [exam["name"]] + list(exam.get("aliases", []))
    for field in [exam["name"], exam["body"], exam["id"], exam["category"]]:
        tokens += norm(field)
    for alias in categories.get(exam["category"], {}).get("aliases", []):
        tokens += norm(alias)
    for alias in exam.get("aliases", []):
        tokens += norm(alias)
    for phrase in phrases:
        tokens.append("".join(norm(phrase)))
    return [t for t in tokens if t]


def visible(exams, show_unverified):
    return [e for e in exams if e["status"] == "active" and (show_unverified or e["confidence"] != "low")]


def popularity_rank(popular):
    ranks = {exam_id: i for i, exam_id in enumerate(popular)}
    return lambda exam: ranks.get(exam["id"], len(popular))


def name_key(exam):
    return " ".join(norm(exam["name"]))


def search(bundle, query, category=None, show_unverified=False):
    q_tokens = norm(query)
    if not q_tokens:
        return []
    rank = popularity_rank(bundle.get("popular", []))
    categories = bundle.get("categories", {})
    q_joined = " ".join(q_tokens)
    scored = []
    for exam in visible(bundle["exams"], show_unverified):
        if category and exam["category"] != category:
            continue
        tokens = index_tokens(exam, categories)
        best = [max((level(q, t) for t in tokens), default=0) for q in q_tokens]
        if min(best) >= 1:
            starts = name_key(exam).startswith(q_joined)
            scored.append((-sum(best), 0 if starts else 1, rank(exam), name_key(exam), exam["id"], exam))
    scored.sort(key=lambda row: row[:5])
    return [row[5]["id"] for row in scored]


def browse(bundle, category, show_unverified=False):
    rank = popularity_rank(bundle.get("popular", []))
    exams = [e for e in visible(bundle["exams"], show_unverified) if e["category"] == category]
    return [e["id"] for e in sorted(exams, key=lambda e: (rank(e), name_key(e), e["id"]))]


def popular(bundle, n, show_unverified=False):
    by_id = {e["id"]: e for e in visible(bundle["exams"], show_unverified)}
    return [i for i in bundle.get("popular", []) if i in by_id][:n]


def source_bundle():
    """The bundle's search inputs, read from the spec sources (not spec/dist, which may be stale or release-signed)."""
    exams = [json.load(open(p, encoding="utf-8"))
             for p in sorted(glob.glob(os.path.join(SPEC, "presets", "exams", "**", "*.json"), recursive=True))]
    categories = {k: v for k, v in json.load(open(os.path.join(SPEC, "presets", "categories.json"), encoding="utf-8")).items()
                  if not k.startswith("_")}
    pop = json.load(open(os.path.join(SPEC, "presets", "popular.json"), encoding="utf-8"))["popular"]
    return {"exams": exams, "categories": categories, "popular": pop}


def expected(bundle, case):
    kind = case.get("kind", "search")
    unverified = case.get("show_unverified", False)
    if kind == "search":
        return search(bundle, case["query"], case.get("category"), unverified)
    if kind == "browse":
        return browse(bundle, case["category"], unverified)
    if kind == "popular":
        return popular(bundle, case["n"], unverified)
    raise ValueError(f"unknown search case kind {kind}")


SEARCH_SECTIONS = ("search_cases", "search_level_cases", "search_norm_cases")


def case_line(case):
    """One case per line, in the file's existing `{ "id": ... }` style."""
    return "    { " + json.dumps(case, ensure_ascii=False)[1:-1] + " }"


def write_cases(bundle):
    """Recompute the expectations of the search sections in place, keeping every other byte of cases.json as it is."""
    path = os.path.join(SPEC, "fixtures", "cases.json")
    text = open(path, encoding="utf-8").read()
    cases = json.loads(text)
    for case in cases["search_cases"]:
        case["expect_ids"] = expected(bundle, case)
    for case in cases["search_level_cases"]:
        case["level"] = level(case["q"], case["t"])
    for case in cases["search_norm_cases"]:
        case["tokens"] = norm(case["text"])
    head = text[:text.index(',\n  "search_cases"')]
    sections = ",\n".join(
        f'  "{name}": [\n' + ",\n".join(case_line(c) for c in cases[name]) + "\n  ]" for name in SEARCH_SECTIONS)
    with open(path, "w", encoding="utf-8") as f:
        f.write(head + ",\n" + sections + "\n}\n")
    print(f"wrote expectations for {len(cases['search_cases'])} search cases")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("query", nargs="?")
    ap.add_argument("--category")
    ap.add_argument("--unverified", action="store_true")
    ap.add_argument("--write-cases", action="store_true", help="recompute expect_ids for every search case")
    args = ap.parse_args()
    bundle = source_bundle()
    if args.write_cases:
        write_cases(bundle)
        return
    print(search(bundle, args.query or "", args.category, args.unverified))


if __name__ == "__main__":
    main()
