#!/usr/bin/env python3
"""Generate the closed AnalyticsEvent types from spec/analytics/events.json.

  python3 spec/tools/gen_analytics.py [--check]

Outputs
  android/core/analytics/src/main/kotlin/app/scanfit/core/analytics/AnalyticsEvent.kt
  ios/Packages/Core/Sources/Analytics/Generated/AnalyticsEvent.swift

The generated types make a privacy leak a compile error: a param can only be an enum case, a bounded
Int, a Bool or an ExamId (`[a-z0-9_]{1,40}`, public preset data). There is no String parameter.
Firebase limits are enforced here: event/param names <= 40 chars, [a-z][a-z0-9_]*, not reserved.
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import (ANDROID_ANALYTICS, IOS_CORE, KOTLIN_PKG_ANALYTICS, SPEC, Output, banner, camel, die)  # noqa: E402

EVENTS = os.path.join(SPEC, "analytics", "events.json")
NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,39}$")
RESERVED_PREFIXES = ("firebase_", "google_", "ga_")
EXAMPLE_RESERVED = {"app_clear_data", "app_exception", "app_remove", "app_store_refund", "app_store_subscription_cancel",
                    "app_update", "first_open", "in_app_purchase", "notification_dismiss", "notification_foreground",
                    "notification_open", "notification_receive", "os_update", "screen_view", "session_start"}
KOTLIN_KEYWORDS = {"object", "val", "var", "fun", "class", "in", "is", "as", "when", "if", "else", "true", "false"}


def validate(spec):
    errs = []
    enums = spec["enums"]
    for en, values in enums.items():
        if not NAME_RE.match(en):
            errs.append(f"enum '{en}': bad name")
        cases = [camel(v) for v in values]
        if len(cases) != len(set(cases)):
            errs.append(f"enum '{en}': values collide once camel-cased for Swift")
        if len(values) != len(set(values)):
            errs.append(f"enum '{en}': duplicate values")
        for v in values:
            if not re.match(r"^[a-z][a-z0-9_]{0,99}$", v):
                errs.append(f"enum '{en}': value '{v}' must be lower_snake (<= 100 chars)")
    if len(spec["events"]) > 500:
        errs.append("more than 500 distinct events (Firebase limit)")
    for ev, body in spec["events"].items():
        if not NAME_RE.match(ev):
            errs.append(f"event '{ev}': name must match {NAME_RE.pattern}")
        if ev in EXAMPLE_RESERVED or ev.startswith(RESERVED_PREFIXES):
            errs.append(f"event '{ev}': reserved by Firebase")
        if len(body["params"]) > 25:
            errs.append(f"event '{ev}': more than 25 params (Firebase limit)")
        for p, d in body["params"].items():
            if not NAME_RE.match(p) or p.startswith(RESERVED_PREFIXES):
                errs.append(f"{ev}.{p}: bad or reserved param name")
            t = d.get("type")
            if t == "enum":
                if d.get("enum") not in enums:
                    errs.append(f"{ev}.{p}: unknown enum '{d.get('enum')}'")
            elif t == "int":
                if not isinstance(d.get("max"), int) or d["max"] < 1:
                    errs.append(f"{ev}.{p}: int needs a positive 'max' (bounded numbers only)")
            elif t not in ("bool", "exam_id"):
                errs.append(f"{ev}.{p}: type '{t}' not allowed (enum | int | bool | exam_id)")
    return errs


def kt_enum_case(v):
    return v.upper()


def kotlin(spec):
    pkg = KOTLIN_PKG_ANALYTICS
    L = [banner("gen_analytics.py"), f"package {pkg}", "",
         "/** A parameter value Firebase accepts. Deliberately no free-form String type. */",
         "sealed interface ParamValue {",
         "    @JvmInline value class Text internal constructor(val value: String) : ParamValue",
         "    @JvmInline value class Whole internal constructor(val value: Long) : ParamValue",
         "    @JvmInline value class Flag internal constructor(val value: Boolean) : ParamValue", "}", "",
         "/** Closed set of analytics events. [name] and [params] are what gets sent; nothing else may be. */",
         "sealed interface AnalyticsEvent {", "    val name: String", "    val params: Map<String, ParamValue>", "",
         "    /** Public preset id (e.g. `ibps_po`). Public data, not personal data. */",
         "    @JvmInline", "    value class ExamId(val value: String) {",
         '        init { require(PATTERN.matches(value)) { "ExamId must match [a-z0-9_]{1,40}" } }',
         "        private companion object { val PATTERN = Regex(\"^[a-z0-9_]{1,40}$\") }", "    }", ""]
    for en, values in spec["enums"].items():
        cn = camel(en, True)
        L.append(f"    enum class {cn}(val wire: String) {{")
        L.append("        " + ",\n        ".join(f'{kt_enum_case(v)}("{v}")' for v in values) + ";")
        L.append("    }")
        L.append("")
    L.pop()
    for ev, body in spec["events"].items():
        cn = camel(ev, True)
        ps = body["params"]
        if not ps:
            L += ["", f"    data object {cn} : AnalyticsEvent {{", f'        override val name = "{ev}"',
                  "        override val params: Map<String, ParamValue> = emptyMap()", "    }"]
            continue
        args = []
        for p, d in ps.items():
            kt = {"enum": camel(d.get("enum", ""), True), "int": "Int", "bool": "Boolean", "exam_id": "ExamId"}[d["type"]]
            args.append(f"val {camel(p)}: {kt}")
        L += ["", f"    data class {cn}(" + ", ".join(args) + ") : AnalyticsEvent {", f'        override val name = "{ev}"',
              "        override val params: Map<String, ParamValue> = mapOf("]
        for p, d in ps.items():
            n = camel(p)
            if d["type"] == "enum":
                v = f"ParamValue.Text({n}.wire)"
            elif d["type"] == "int":
                v = f"ParamValue.Whole({n}.coerceIn(0, {d['max']}).toLong())"
            elif d["type"] == "bool":
                v = f"ParamValue.Flag({n})"
            else:
                v = f"ParamValue.Text({n}.value)"
            L.append(f'            "{p}" to {v},')
        L += ["        )", "    }"]
    L.append("}")
    return "\n".join(L)


def swift(spec):
    L = [banner("gen_analytics.py"), "import Foundation", "",
         "/// A parameter value Firebase accepts. Deliberately no free-form String case for callers.",
         "public enum ParamValue: Sendable, Equatable {", "    case text(String)", "    case whole(Int)", "    case flag(Bool)", "}", "",
         "/// Closed set of analytics events. `name` and `parameters` are what gets sent; nothing else may be.",
         "public enum AnalyticsEvent: Sendable, Equatable {",
         "    /// Public preset id (e.g. `ibps_po`). Public data, not personal data.",
         "    public struct ExamID: Sendable, Equatable, Hashable {", "        public let value: String", "",
         "        public init?(_ value: String) {",
         "            guard !value.isEmpty, value.count <= 40,",
         "                  value.allSatisfy({ $0.isASCII && ($0.isLowercase || $0.isNumber || $0 == \"_\") }) else { return nil }",
         "            self.value = value", "        }", "    }", ""]
    for en, values in spec["enums"].items():
        cn = camel(en, True)
        L.append(f"    public enum {cn}: String, Sendable, CaseIterable {{")
        L += [f"        case {camel(v)} = \"{v}\"" for v in values]
        L += ["    }", ""]
    for ev, body in spec["events"].items():
        ps = body["params"]
        if not ps:
            L.append(f"    case {camel(ev)}")
            continue
        args = []
        for p, d in ps.items():
            sw = {"enum": camel(d.get("enum", ""), True), "int": "Int", "bool": "Bool", "exam_id": "ExamID"}[d["type"]]
            args.append(f"{camel(p)}: {sw}")
        L.append(f"    case {camel(ev)}({', '.join(args)})")
    L += ["", "    public var name: String {", "        switch self {"]
    for ev, body in spec["events"].items():
        L.append(f"        case .{camel(ev)}: return \"{ev}\"")
    L += ["        }", "    }", "", "    public var parameters: [String: ParamValue] {", "        switch self {"]
    for ev, body in spec["events"].items():
        ps = body["params"]
        if not ps:
            L.append(f"        case .{camel(ev)}: return [:]")
            continue
        L.append(f"        case let .{camel(ev)}({', '.join(camel(p) for p in ps)}):")
        items = []
        for p, d in ps.items():
            n = camel(p)
            if d["type"] == "enum":
                items.append(f'                "{p}": .text({n}.rawValue)')
            elif d["type"] == "int":
                items.append(f'                "{p}": .whole(min(max({n}, 0), {d["max"]}))')
            elif d["type"] == "bool":
                items.append(f'                "{p}": .flag({n})')
            else:
                items.append(f'                "{p}": .text({n}.value)')
        L += ["            return ["] + [i + "," for i in items] + ["            ]"]
    L += ["        }", "    }", "}"]
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    with open(EVENTS, encoding="utf-8") as f:
        spec = json.load(f)
    errs = validate(spec)
    if errs:
        die(errs, "gen_analytics")
    out = Output(args.check)
    out.write(os.path.join(ANDROID_ANALYTICS, "src", "main", "kotlin", *KOTLIN_PKG_ANALYTICS.split("."),
                           "AnalyticsEvent.kt"), kotlin(spec))
    out.write(os.path.join(IOS_CORE, "Analytics", "Generated", "AnalyticsEvent.swift"), swift(spec))
    out.finish("gen_analytics.py")


if __name__ == "__main__":
    main()
