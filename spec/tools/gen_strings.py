#!/usr/bin/env python3
"""Generate platform string resources from spec/strings/<lang>.json.

  python3 spec/tools/gen_strings.py            # write generated files
  python3 spec/tools/gen_strings.py --check    # CI: fail if generated files are stale
  python3 spec/tools/gen_strings.py --strict   # fail on any TODO_HI (use for release builds)

Outputs
  android/core/designsystem/src/main/res/values[-<lang>]/strings.xml
  android/app/src/main/res/xml/locales_config.xml           (per-app language list)
  ios/Packages/Core/Sources/DesignSystem/Resources/Localizable.xcstrings
  ios/Packages/Core/Sources/DesignSystem/Generated/Strings.swift   (typed accessors)

Source format (see en.json): flat dotted keys; {name:int} / {name:str} placeholders; a plural is
{"one": ..., "other": ...} and its first placeholder must be {count:int}. en is the source language;
every other <lang>.json must define exactly the same keys with the same placeholders.
A 'TODO_HI:' prefix marks a machine-drafted string awaiting native review (CLAUDE.md rule 9): it is
stripped from the app output, flagged needs_review on iOS, and counted as a warning.
"""
import argparse
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import (ANDROID_DESIGNSYSTEM, IOS_CORE, REPO, SPEC, Output, banner, camel, die)  # noqa: E402

STRINGS_DIR = os.path.join(SPEC, "strings")
SOURCE_LANG = "en"
KEY_RE = re.compile(r"^[a-z0-9_]+(\.[a-z0-9_]+)+$")
PH_RE = re.compile(r"\{([a-z][a-z0-9_]*):(int|str)\}")
PLURAL_FORMS = ("one", "other")
TODO_PREFIX = "TODO_HI:"


def load(lang):
    path = os.path.join(STRINGS_DIR, f"{lang}.json")
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    return {k: v for k, v in raw.items() if not k.startswith("_")}


def forms(value):
    """Return {form: text} for a string or plural value."""
    return {"other": value} if isinstance(value, str) else value


def placeholders(text):
    return [(m.group(1), m.group(2)) for m in PH_RE.finditer(text)]


def ordered_params(value):
    """Params by first appearance across the English forms (this fixes positional indexes)."""
    seen = []
    f = forms(value)
    for form in ("one", "other"):
        if form in f:
            for p in placeholders(f[form]):
                if p not in seen:
                    seen.append(p)
    return seen


def validate(langs, data):
    errors = []
    src = data[SOURCE_LANG]
    for k, v in src.items():
        if not KEY_RE.match(k):
            errors.append(f"{SOURCE_LANG}: bad key '{k}' (want lower_snake.dotted)")
        if isinstance(v, dict) and set(v) != set(PLURAL_FORMS):
            errors.append(f"{SOURCE_LANG}.{k}: plural needs exactly {PLURAL_FORMS}")
            continue
        for form, text in forms(v).items():
            if not isinstance(text, str) or not text.strip():
                errors.append(f"{SOURCE_LANG}.{k}.{form}: empty or non-string")
            elif "%" in text:
                errors.append(f"{SOURCE_LANG}.{k}.{form}: literal '%' is not allowed (breaks format strings)")
    for lang in langs:
        d = data[lang]
        for k in d:
            if k not in src:
                errors.append(f"{lang}: key '{k}' not in {SOURCE_LANG}.json")
        for k, sv in src.items():
            if k not in d:
                errors.append(f"{lang}: missing key '{k}' (add it, prefixed TODO_HI: if untranslated)")
                continue
            v = d[k]
            if isinstance(sv, str) != isinstance(v, str):
                errors.append(f"{lang}.{k}: plural/plain shape differs from {SOURCE_LANG}")
                continue
            if isinstance(sv, dict) and set(v) != set(PLURAL_FORMS) | set():
                errors.append(f"{lang}.{k}: plural needs exactly {PLURAL_FORMS}")
                continue
            want = sorted(ordered_params(sv))
            for form, text in forms(v).items():
                if not isinstance(text, str) or not text.strip():
                    errors.append(f"{lang}.{k}.{form}: empty or non-string")
                    continue
                got = sorted(set(placeholders(text)))
                if got != want:
                    errors.append(f"{lang}.{k}.{form}: placeholders {got} != source {want}")
                if "%" in text:
                    errors.append(f"{lang}.{k}.{form}: literal '%' is not allowed (breaks format strings)")
    for k, sv in src.items():
        if isinstance(sv, dict):
            params = ordered_params(sv)
            if not params or params[0] != ("count", "int"):
                errors.append(f"{SOURCE_LANG}.{k}: first placeholder of a plural must be {{count:int}}")
            elif sum(1 for _, t in params if t == "int") > 1:
                errors.append(f"{SOURCE_LANG}.{k}: a plural may have only one int placeholder ({{count:int}}); "
                              "pass other numbers as {name:str} (Xcode cannot infer the plural argument otherwise)")
    return errors


def strip_todo(text):
    return text[len(TODO_PREFIX):].lstrip() if text.startswith(TODO_PREFIX) else text


def is_todo(value):
    return any(t.startswith(TODO_PREFIX) for t in forms(value).values())


def positional(text, params, platform):
    index = {p[0]: i + 1 for i, p in enumerate(params)}
    types = {p[0]: p[1] for p in params}
    spec = {"android": {"int": "d", "str": "s"}, "ios": {"int": "lld", "str": "@"}}[platform]

    def sub(m):
        return f"%{index[m.group(1)]}${spec[types[m.group(1)]]}"
    return PH_RE.sub(sub, text)


def android_escape(s):
    s = (s.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
         .replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n"))
    return "\\" + s if s[:1] in "@?" else s


def android_xml(lang, src, d):
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!--", banner("gen_strings.py", "  ").rstrip("\n"), "-->",
           "<resources>"]
    for k in sorted(src):
        name = k.replace(".", "_")
        params = ordered_params(src[k])
        v = d[k]
        if isinstance(v, str):
            out.append(f'    <string name="{name}">{android_escape(positional(strip_todo(v), params, "android"))}</string>')
        else:
            out.append(f'    <plurals name="{name}">')
            for form in PLURAL_FORMS:
                out.append(f'        <item quantity="{form}">'
                           f'{android_escape(positional(strip_todo(v[form]), params, "android"))}</item>')
            out.append("    </plurals>")
    out.append("</resources>")
    return "\n".join(out)


def locales_config(langs):
    rows = "\n".join(f'    <locale android:name="{l}"/>' for l in [SOURCE_LANG] + langs)
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!--\n' + banner("gen_strings.py", "  ").rstrip("\n") +
            '\n-->\n<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n' + rows +
            "\n</locale-config>")


def xcstrings(langs, data):
    src = data[SOURCE_LANG]
    strings = {}
    for k in sorted(src):
        params = ordered_params(src[k])
        locs = {}
        for lang in [SOURCE_LANG] + langs:
            v = data[lang][k]
            state = "needs_review" if is_todo(v) else "translated"
            if isinstance(v, str):
                locs[lang] = {"stringUnit": {"state": state,
                                             "value": positional(strip_todo(v), params, "ios")}}
            else:
                locs[lang] = {"variations": {"plural": {
                    form: {"stringUnit": {"state": state,
                                          "value": positional(strip_todo(v[form]), params, "ios")}}
                    for form in PLURAL_FORMS}}}
        strings[k] = {"extractionState": "manual", "localizations": locs}
    doc = {"sourceLanguage": SOURCE_LANG, "strings": strings, "version": "1.0"}
    return json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=True)


def swift_accessors(src):
    lines = [banner("gen_strings.py"), "import Foundation", "",
             "/// Typed accessors for every string in `spec/strings`. Create `Strings()` for the system language,",
             "/// or `Strings(languageCode: \"hi\")` to force one (previews, snapshot tests).",
             "public struct Strings: Sendable {",
             "    private let bundle: Bundle",
             "    private let locale: Locale",
             "",
             "    public init(languageCode: String? = nil) {",
             "        let base = Bundle.module",
             "        if let code = languageCode,",
             "           let path = base.path(forResource: code, ofType: \"lproj\"),",
             "           let forced = Bundle(path: path) {",
             "            bundle = forced",
             "            locale = Locale(identifier: code)",
             "        } else {",
             "            bundle = base",
             "            locale = Locale.current",
             "        }",
             "    }",
             "",
             "    private func tr(_ key: String) -> String {",
             "        bundle.localizedString(forKey: key, value: key, table: nil)",
             "    }",
             "",
             "    private func fmt(_ key: String, _ args: [CVarArg]) -> String {",
             "        String(format: tr(key), locale: locale, arguments: args)",
             "    }",
             ""]
    names = {}
    for k in sorted(src):
        name = camel(k.replace(".", "_"))
        if name in names:
            die([f"Swift accessor collision: {k} and {names[name]} -> {name}"], "gen_strings")
        names[name] = k
        params = ordered_params(src[k])
        if isinstance(src[k], str):
            doc = src[k].replace("\n", " ")
        else:
            doc = src[k]["other"]
        lines.append(f"    /// \"{doc}\"")
        if not params:
            lines.append(f"    public var {name}: String {{ tr(\"{k}\") }}")
        else:
            sig = ", ".join(f"{camel(p)}: {'Int' if t == 'int' else 'String'}" for p, t in params)
            args = ", ".join(camel(p) for p, _ in params)
            lines.append(f"    public func {name}({sig}) -> String {{ fmt(\"{k}\", [{args}]) }}")
    lines.append("}")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--strict", action="store_true", help="fail if any TODO_HI remains")
    args = ap.parse_args()

    langs = sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(STRINGS_DIR, "*.json"))
                   if os.path.basename(p) != f"{SOURCE_LANG}.json")
    for lang in langs:
        if not re.fullmatch(r"[a-z]{2}", lang):
            die([f"language '{lang}': only 2-letter codes supported (Android folder naming)"], "gen_strings")
    data = {l: load(l) for l in [SOURCE_LANG] + langs}
    errors = validate(langs, data)
    if errors:
        die(errors, "gen_strings")

    todo = {l: sum(1 for v in data[l].values() if is_todo(v)) for l in langs}
    total = len(data[SOURCE_LANG])
    for l in langs:
        if todo[l]:
            msg = f"warning: {todo[l]}/{total} '{l}' strings still marked TODO_HI (native review pending)"
            print(msg, file=sys.stderr)
    if args.strict and any(todo.values()):
        die([f"{l}: {n} TODO_HI string(s)" for l, n in todo.items() if n], "gen_strings --strict")

    out = Output(args.check)
    src = data[SOURCE_LANG]
    res = os.path.join(ANDROID_DESIGNSYSTEM, "src", "main", "res")
    out.write(os.path.join(res, "values", "strings.xml"), android_xml(SOURCE_LANG, src, src))
    for l in langs:
        out.write(os.path.join(res, f"values-{l}", "strings.xml"), android_xml(l, src, data[l]))
    out.write(os.path.join(REPO, "android", "app", "src", "main", "res", "xml", "locales_config.xml"),
              locales_config(langs))
    ds = os.path.join(IOS_CORE, "DesignSystem")
    out.write(os.path.join(ds, "Resources", "Localizable.xcstrings"), xcstrings(langs, data))
    out.write(os.path.join(ds, "Generated", "Strings.swift"), swift_accessors(src))
    out.finish("gen_strings.py")


if __name__ == "__main__":
    main()
