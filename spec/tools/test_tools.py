#!/usr/bin/env python3
"""Tests for the spec generators' validators and the signing vector. Run: python3 -m unittest spec/tools/test_tools.py -v
(stdlib unittest; needs `pip install -r spec/tools/requirements.txt`)."""
import base64
import copy
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
