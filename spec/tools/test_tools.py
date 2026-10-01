#!/usr/bin/env python3
"""Tests for the spec generators' validators and the signing vector. Run: python3 -m unittest spec/tools/test_tools.py -v
(stdlib unittest; needs `pip install -r spec/tools/requirements.txt`)."""
import base64
import copy
import glob
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_analytics  # noqa: E402
import gen_strings  # noqa: E402
import gen_tokens  # noqa: E402
from _common import SPEC  # noqa: E402

from cryptography.exceptions import InvalidSignature  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey  # noqa: E402


def load(*parts):
    with open(os.path.join(SPEC, *parts), encoding="utf-8") as f:
        return json.load(f)


class TokenContrast(unittest.TestCase):
    def test_shipped_palette_passes(self):
        self.assertEqual(gen_tokens.validate(load("tokens", "tokens.json")), [])

    def test_low_contrast_text_is_rejected(self):
        t = load("tokens", "tokens.json")
        t["color"]["light"]["onSurface"] = "#BBBBBB"  # light grey on white
        errs = gen_tokens.validate(t)
        self.assertTrue(any("onSurface" in e and "< 4.5" in e for e in errs), errs)

    def test_low_contrast_border_is_rejected(self):
        t = load("tokens", "tokens.json")
        t["color"]["dark"]["outline"] = "#222230"
        self.assertTrue(any("outline" in e and "< 3.0" in e for e in gen_tokens.validate(t)))

    def test_missing_role_is_rejected(self):
        t = load("tokens", "tokens.json")
        del t["color"]["dark"]["success"]
        self.assertTrue(gen_tokens.validate(t))

    def test_contrast_math(self):
        self.assertAlmostEqual(gen_tokens.contrast("#000000", "#FFFFFF"), 21.0, places=1)
        self.assertAlmostEqual(gen_tokens.contrast("#777777", "#FFFFFF"), 4.48, places=1)


class Strings(unittest.TestCase):
    def data(self):
        return {"en": gen_strings.load("en"), "hi": gen_strings.load("hi")}

    def test_shipped_strings_are_consistent(self):
        self.assertEqual(gen_strings.validate(["hi"], self.data()), [])

    def test_missing_translation_is_rejected(self):
        d = self.data()
        del d["hi"]["home.title"]
        self.assertTrue(any("missing key 'home.title'" in e for e in gen_strings.validate(["hi"], d)))

    def test_placeholder_mismatch_is_rejected(self):
        d = self.data()
        d["hi"]["flow.step_fit"] = "TODO_HI: {mb:int} MB"
        self.assertTrue(any("flow.step_fit" in e and "placeholders" in e for e in gen_strings.validate(["hi"], d)))

    def test_literal_percent_is_rejected(self):
        d = self.data()
        d["en"]["home.title"] = "100% sure"
        self.assertTrue(any("literal '%'" in e for e in gen_strings.validate(["hi"], d)))

    def test_plural_must_lead_with_count(self):
        d = self.data()
        d["en"]["match.accepted"] = {"one": "{exam:str} {count:int}", "other": "{exam:str} {count:int}"}
        d["hi"]["match.accepted"] = {"one": "{exam:str} {count:int}", "other": "{exam:str} {count:int}"}
        self.assertTrue(any("first placeholder of a plural" in e for e in gen_strings.validate(["hi"], d)))

    def test_plural_with_two_ints_is_rejected(self):
        d = self.data()
        for lang in ("en", "hi"):
            d[lang]["match.accepted"] = {"one": "{count:int} of {total:int}", "other": "{count:int} of {total:int}"}
        self.assertTrue(any("only one int placeholder" in e for e in gen_strings.validate(["hi"], d)))

    def test_positional_conversion_allows_reordering(self):
        params = [("count", "int"), ("folder", "str")]
        self.assertEqual(gen_strings.positional("{folder:str} में {count:int}", params, "android"), "%2$s में %1$d")
        self.assertEqual(gen_strings.positional("{folder:str} में {count:int}", params, "ios"), "%2$@ में %1$lld")

    def test_android_escaping(self):
        self.assertEqual(gen_strings.android_escape("Don't <b> & \"q\""), "Don\\'t &lt;b&gt; &amp; \\\"q\\\"")
        self.assertEqual(gen_strings.android_escape("@home"), "\\@home")

    def test_todo_prefix_is_stripped_for_output(self):
        self.assertEqual(gen_strings.strip_todo("TODO_HI: नमस्ते"), "नमस्ते")


class Analytics(unittest.TestCase):
    def test_shipped_events_are_valid(self):
        self.assertEqual(gen_analytics.validate(load("analytics", "events.json")), [])

    def test_free_text_param_is_rejected(self):
        s = load("analytics", "events.json")
        s["events"]["exam_opened"]["params"]["file_name"] = {"type": "string"}
        self.assertTrue(any("not allowed" in e for e in gen_analytics.validate(s)))

    def test_unbounded_int_is_rejected(self):
        s = load("analytics", "events.json")
        s["events"]["checker_run"]["params"]["issues_count"] = {"type": "int"}
        self.assertTrue(any("bounded" in e for e in gen_analytics.validate(s)))

    def test_firebase_reserved_names_are_rejected(self):
        s = load("analytics", "events.json")
        s["events"]["first_open"] = {"params": {}}
        s["events"]["x"] = {"params": {"firebase_thing": {"type": "bool"}}}
        errs = gen_analytics.validate(s)
        self.assertTrue(any("reserved" in e for e in errs), errs)

    def test_overlong_event_name_is_rejected(self):
        s = load("analytics", "events.json")
        s["events"]["a" * 41] = {"params": {}}
        self.assertTrue(gen_analytics.validate(s))

    def test_no_event_has_a_string_param_except_exam_id(self):
        for ev, body in load("analytics", "events.json")["events"].items():
            for p, d in body["params"].items():
                self.assertIn(d["type"], ("enum", "int", "bool", "exam_id"), f"{ev}.{p}")


class SigningVector(unittest.TestCase):
    """Independent cross-check of vector.json using the cryptography library."""
    D = os.path.join(SPEC, "fixtures", "signing")

    def read(self, name):
        with open(os.path.join(self.D, name), "rb") as f:
            return f.read()

    def verifies(self, case):
        try:
            pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(self.read(case["public_key"]).strip()))
            sig = base64.b64decode(self.read(case["signature"]).strip(), validate=True)
            pub.verify(sig, self.read(case["bundle"]))
            return True
        except (InvalidSignature, ValueError):
            return False

    def test_every_verify_case_matches_its_expectation(self):
        vector = load("fixtures", "signing", "vector.json")
        self.assertGreaterEqual(len(vector["verify_cases"]), 8)
        for case in vector["verify_cases"]:
            self.assertEqual(self.verifies(case), case["valid"], case["id"])

    def test_valid_and_tampered_cases_both_exist(self):
        verdicts = {c["valid"] for c in load("fixtures", "signing", "vector.json")["verify_cases"]}
        self.assertEqual(verdicts, {True, False})

    def test_load_cases_cover_every_failure_reason(self):
        vector = load("fixtures", "signing", "vector.json")
        expected = {c["expect"] for c in vector["load_cases"]} - {"ok"}
        self.assertEqual(expected, set(vector["supported_failure_reasons"]))

    def test_dev_key_in_spec_signing_matches_the_vector(self):
        with open(os.path.join(SPEC, "signing", "dev_public_key.b64"), "rb") as f:
            self.assertEqual(f.read(), self.read("dev_public_key.b64"))


class CasesFile(unittest.TestCase):
    """spec/fixtures/cases.json is the conformance contract: it must never reference things that do not exist."""

    @classmethod
    def setUpClass(cls):
        cls.cases = load("fixtures", "cases.json")
        cls.exams = {}
        for path in glob.glob(os.path.join(SPEC, "presets", "exams", "**", "*.json"), recursive=True):
            with open(path, encoding="utf-8") as f:
                e = json.load(f)
            cls.exams[e["id"]] = e

    def all_cases(self):
        for section, items in self.cases.items():
            if isinstance(items, list):
                for c in items:
                    yield section, c

    def test_ids_are_unique_per_section(self):
        for section, items in self.cases.items():
            if isinstance(items, list):
                ids = [c["id"] for c in items]
                self.assertEqual(len(ids), len(set(ids)), f"duplicate ids in {section}")

    def test_every_input_fixture_exists(self):
        for section, c in self.all_cases():
            if "input" in c:
                path = os.path.join(SPEC, "fixtures", "images", c["input"])
                self.assertTrue(os.path.exists(path), f"{section}.{c['id']}: missing fixture {c['input']}")

    def test_every_preset_and_doc_reference_exists(self):
        for section, c in self.all_cases():
            if "preset" in c:
                self.assertIn(c["preset"], self.exams, f"{section}.{c['id']}")
                if "doc" in c:
                    types = [d["type"] for d in self.exams[c["preset"]]["documents"]]
                    self.assertIn(c["doc"], types, f"{section}.{c['id']}: {c['preset']} has no {c['doc']}")

    def test_every_exam_named_in_match_expectations_exists(self):
        for c in self.cases["match_cases"]:
            names = []
            for k, v in c.items():
                if k.startswith("expect_") and isinstance(v, list):
                    names += [x["exam"] if isinstance(x, dict) else x for x in v]
            for n in names:
                self.assertIn(n, self.exams, f"match_cases.{c['id']}: unknown exam {n}")

    def test_a_case_names_a_preset_or_an_inline_spec(self):
        for c in self.cases["fit_cases"] + self.cases["geometry_cases"]:
            self.assertTrue(("preset" in c) != ("spec" in c), c["id"])

    def test_geometry_expectations_follow_the_rounding_rules(self):
        """Re-derive the crop/pad numbers from ALGORITHMS 9.1 so a typo in the file cannot become the contract."""
        def rnd(x):
            return int(x + 0.5)

        def crop(W, H, a):
            if W / H > a:
                w, h = rnd(H * a), H
            else:
                w, h = W, rnd(W / a)
            return {"x": (W - w) // 2, "y": (H - h) // 2, "w": w, "h": h}

        def pad(W, H, a):
            cw, ch = (rnd(H * a), H) if W / H < a else (W, rnd(W / a))
            return {"w": cw, "h": ch, "x": (cw - W) // 2, "y": (ch - H) // 2}

        for c in self.cases["geometry_cases"]:
            doc = next(d for d in self.exams[c["preset"]]["documents"] if d["type"] == c["doc"])
            dims, src = doc["dimensions"], c["source"]
            if "crop" in c["expect"]:
                a = (dims["width"] / dims["height"]) if dims["mode"] != "range" else sum(dims["aspect_w_over_h"].values()) / 2
                self.assertEqual(crop(src["w"], src["h"], a), c["expect"]["crop"], c["id"])
            if "pad" in c["expect"]:
                self.assertEqual(pad(src["w"], src["h"], dims["width"] / dims["height"]), c["expect"]["pad"], c["id"])


class PresetsDist(unittest.TestCase):
    def test_every_exam_has_a_known_category_string(self):
        """The UI maps category ids to strings (category.<id>): every category in the schema needs a string."""
        schema = load("schema", "exam.schema.json")
        en = gen_strings.load("en")
        for cat in schema["properties"]["category"]["enum"]:
            self.assertIn(f"category.{cat}", en)

    def test_every_document_type_has_a_string(self):
        schema = load("schema", "exam.schema.json")
        en = gen_strings.load("en")
        for t in schema["$defs"]["document"]["properties"]["type"]["enum"]:
            self.assertIn(f"doc.{t}", en)

    def test_every_inspector_issue_has_a_string(self):
        """ALGORITHMS §5 issue enum: each needs an EN/HI message."""
        issues = ["EXTENSION_MISMATCH", "CMYK_COLOR", "PROGRESSIVE_JPEG", "HAS_GPS_EXIF", "ROTATED_BY_EXIF",
                  "HEIC_NOT_ACCEPTED", "PDF_ENCRYPTED", "TOO_SMALL_KB", "TOO_LARGE_KB", "WRONG_DIMENSIONS",
                  "WRONG_ASPECT", "LOW_DPI_METADATA", "GRAYSCALE_NOT_ALLOWED"]
        en, hi = gen_strings.load("en"), gen_strings.load("hi")
        for i in issues:
            self.assertIn(f"issue.{i.lower()}", en)
            self.assertIn(f"issue.{i.lower()}", hi)


if __name__ == "__main__":
    unittest.main()
