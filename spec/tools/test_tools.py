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
import release_preflight  # noqa: E402
from _common import SPEC, require  # noqa: E402

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


class ReleasePreflight(unittest.TestCase):
    """release_preflight.run on a throwaway repo layout: each blocker appears, and a complete setup is clean."""

    def setUp(self):
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.addCleanup(self.tmp.cleanup)

    def put(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def keypair(self):
        from cryptography.hazmat.primitives import serialization as ser
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        k = Ed25519PrivateKey.generate()
        return k, base64.b64encode(k.public_key().public_bytes(ser.Encoding.Raw, ser.PublicFormat.Raw)).decode()

    def complete_repo(self):
        key, pub = self.keypair()
        self.put("spec/signing/dev_public_key.b64", "ZGV2ZGV2ZGV2ZGV2ZGV2ZGV2ZGV2ZGV2ZGV2ZGV2ZGU=\n")
        self.put("spec/signing/prod_public_key.b64", pub + "\n")
        self.put("spec/dist/presets.json", '{"presets_version":1}')
        self.put("spec/dist/presets.json.sig", base64.b64encode(key.sign(b'{"presets_version":1}')).decode())
        self.put("android/gradle.properties", "scanfit.versionName=1.2.0\n")
        self.put("ios/project.yml", '    MARKETING_VERSION: "1.2.0"   # shared\n    APP_BUNDLE_ID: com.example.scanfit\n')
        self.put("android/app/build.gradle.kts", 'applicationId = "com.example.scanfit"\n')
        self.put("android/keystore.properties", "storeFile=upload.jks\nstorePassword=x\nkeyAlias=a\nkeyPassword=y\n")
        self.put("android/upload.jks", "not really a keystore")
        self.put("ios/Config/Local.xcconfig", "DEVELOPMENT_TEAM = QWERTY1234\n")
        self.put("spec/strings/hi.json", '{"_doc": "TODO_HI: ignored", "a": "ठीक"}')

    def run_pre(self, **kw):
        kw.setdefault("env", {})
        return release_preflight.run(self.root, **kw)

    def test_a_complete_setup_has_no_blockers_or_warnings(self):
        self.complete_repo()
        r = self.run_pre()
        self.assertEqual([], r.blockers)
        self.assertEqual([], r.warnings)

    def test_an_empty_repo_reports_every_missing_piece(self):
        r = self.run_pre()
        text = " | ".join(r.blockers)
        for needle in ("prod_public_key.b64 is missing", "keystore", "signing team", "scanfit.versionName"):
            self.assertIn(needle, text)

    def test_the_dev_key_is_never_accepted_as_the_production_key(self):
        self.complete_repo()
        with open(os.path.join(self.root, "spec/signing/dev_public_key.b64")) as f:
            self.put("spec/signing/prod_public_key.b64", f.read())
        self.assertTrue(any("DEV key" in b for b in self.run_pre().blockers))

    def test_presets_signed_with_another_key_block(self):
        self.complete_repo()
        other, _ = self.keypair()
        self.put("spec/dist/presets.json.sig", base64.b64encode(other.sign(b'{"presets_version":1}')).decode())
        self.assertTrue(any("not signed with the production key" in b for b in self.run_pre().blockers))

    def test_a_malformed_public_key_blocks(self):
        self.complete_repo()
        self.put("spec/signing/prod_public_key.b64", "not base64 at all!!")
        self.assertTrue(any("not a base64 32-byte" in b for b in self.run_pre().blockers))

    def test_keystore_placeholders_block(self):
        self.complete_repo()
        self.put("android/keystore.properties", "storeFile=/ABSOLUTE/PATH/TO/scanfit-upload.jks\nstorePassword=REPLACE_ME\nkeyAlias=a\nkeyPassword=REPLACE_ME\n")
        self.assertTrue(any("placeholder" in b for b in self.run_pre().blockers))

    def test_environment_credentials_count_like_the_properties_file(self):
        self.complete_repo()
        os.remove(os.path.join(self.root, "android/keystore.properties"))
        env = dict(zip(release_preflight.UPLOAD_ENV, ("upload.jks", "x", "a", "y")))
        self.assertEqual([], self.run_pre(platform="android", env=env).blockers)

    def test_the_example_team_id_blocks(self):
        self.complete_repo()
        self.put("ios/Config/Local.xcconfig", "DEVELOPMENT_TEAM = ABCDE12345\n")
        self.assertTrue(any("example value" in b for b in self.run_pre().blockers))

    def test_android_and_ios_versions_must_agree(self):
        self.complete_repo()
        self.put("android/gradle.properties", "scanfit.versionName=1.3.0\n")
        self.assertTrue(any("differs" in b for b in self.run_pre().blockers))

    def test_a_prerelease_suffix_on_android_is_compatible_with_the_ios_numeric_version(self):
        self.complete_repo()
        self.put("android/gradle.properties", "scanfit.versionName=1.2.0-rc1\n")
        self.assertEqual([], self.run_pre().blockers)

    def test_yaml_quote_styles_do_not_matter(self):
        self.complete_repo()
        for quoted in ("'1.2.0'", '"1.2.0"', "1.2.0"):
            self.put("ios/project.yml", f"    MARKETING_VERSION: {quoted}   # shared\n    APP_BUNDLE_ID: 'com.example.scanfit'\n")
            self.assertEqual([], self.run_pre().blockers, quoted)

    def test_machine_hindi_warns_for_a_prerelease_and_blocks_a_final_release(self):
        self.complete_repo()
        self.put("spec/strings/hi.json", '{"a": "TODO_HI: x", "p": {"one": "TODO_HI: y", "other": "z"}}')
        self.assertEqual([], self.run_pre().blockers)
        self.assertTrue(any("2 Hindi" in w for w in self.run_pre().warnings))
        self.assertTrue(any("TODO_HI" in b for b in self.run_pre(final=True).blockers))

    def test_placeholder_app_ids_warn_but_do_not_block(self):
        self.complete_repo()
        self.put("android/app/build.gradle.kts", 'applicationId = "app.scanfit"\n')
        self.put("ios/project.yml", '    MARKETING_VERSION: "1.2.0"\n    APP_BUNDLE_ID: app.scanfit.ios\n')
        r = self.run_pre()
        self.assertEqual([], r.blockers)
        self.assertEqual(2, sum("placeholder" in w for w in r.warnings))


class DependencyGuard(unittest.TestCase):
    """A missing package must print the setup steps and exit 2, not raise a traceback."""

    def test_missing_package_exits_with_setup_steps(self):
        import contextlib
        import io
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as cm:
            require("definitely_not_installed_pkg", "json")
        self.assertEqual(2, cm.exception.code)
        text = err.getvalue()
        self.assertIn("definitely_not_installed_pkg", text)
        self.assertIn("python3 -m venv .venv", text)
        self.assertIn("pip install -r spec/tools/requirements.txt", text)

    def test_present_packages_pass_silently(self):
        require("json", "os")

    def test_the_pip_name_differs_from_the_module_for_pillow(self):
        import contextlib
        import io
        import importlib.util
        from unittest import mock
        err = io.StringIO()
        with mock.patch.object(importlib.util, "find_spec", return_value=None), \
                contextlib.redirect_stderr(err), self.assertRaises(SystemExit):
            require("PIL")
        self.assertIn("pillow", err.getvalue())


class ExamSearchCases(unittest.TestCase):
    """search_* sections of cases.json must equal what the §10 reference computes from the current source presets."""

    @classmethod
    def setUpClass(cls):
        import exam_search
        cls.ref = exam_search
        cls.bundle = exam_search.source_bundle()
        cls.cases = load("fixtures", "cases.json")

    def test_search_expectations_match_the_reference(self):
        for case in self.cases["search_cases"]:
            with self.subTest(case["id"]):
                self.assertEqual(case["expect_ids"], self.ref.expected(self.bundle, case),
                                 "stale: run python3 spec/tools/exam_search.py --write-cases")

    def test_level_and_norm_expectations_match_the_reference(self):
        for case in self.cases["search_level_cases"]:
            self.assertEqual(case["level"], self.ref.level(case["q"], case["t"]), case)
        for case in self.cases["search_norm_cases"]:
            self.assertEqual(case["tokens"], self.ref.norm(case["text"]), case)

    def test_every_case_is_meaningful(self):
        ids = {e["id"] for e in self.bundle["exams"]}
        for case in self.cases["search_cases"]:
            for exam_id in case["expect_ids"]:
                self.assertIn(exam_id, ids, case["id"])
        non_empty = [c for c in self.cases["search_cases"] if c["expect_ids"]]
        self.assertGreaterEqual(len(non_empty), 15, "most cases must find something, or they test nothing")

    def test_osa_counts_an_adjacent_swap_as_one_edit(self):
        self.assertEqual(1, self.ref.osa("ibsp", "ibps"))
        self.assertEqual(2, self.ref.osa("ab", "ba") + 1)
        self.assertEqual(3, self.ref.osa("", "abc"))

    def test_bundle_carries_categories_and_popular(self):
        self.assertEqual(10, len(self.bundle["categories"]))
        self.assertTrue(set(self.bundle["popular"]) <= {e["id"] for e in self.bundle["exams"]})
