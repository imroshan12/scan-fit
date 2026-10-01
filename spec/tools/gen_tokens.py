#!/usr/bin/env python3
"""Generate the Compose and SwiftUI themes from spec/tokens/tokens.json.

  python3 spec/tools/gen_tokens.py [--check]

Outputs
  android/core/designsystem/src/main/kotlin/app/scanfit/core/designsystem/theme/ScanFitTheme.kt
  ios/Packages/Core/Sources/DesignSystem/Generated/Theme.swift

Fails (exit 1) if any contrast rule from UI_UX §4 is violated, in light or dark:
  text pairs (onX on X, onSurface on surface/background, ...) >= 4.5:1
  graphic pairs (outline / success / warning / error / primary icons against surface) >= 3:1
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import (ANDROID_DESIGNSYSTEM, IOS_CORE, KOTLIN_PKG_DESIGNSYSTEM, SPEC, Output, banner,  # noqa: E402
                     camel, die)

TOKENS = os.path.join(SPEC, "tokens", "tokens.json")
ROLES = ["primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "background", "onBackground",
         "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant",
         "success", "onSuccess", "successContainer", "onSuccessContainer",
         "warning", "onWarning", "warningContainer", "onWarningContainer",
         "error", "onError", "errorContainer", "onErrorContainer", "scrim"]

TEXT_PAIRS = [("onPrimary", "primary"), ("onPrimaryContainer", "primaryContainer"),
              ("onBackground", "background"), ("onSurface", "surface"), ("onSurface", "surfaceVariant"),
              ("onSurfaceVariant", "surfaceVariant"), ("onSurfaceVariant", "surface"),
              ("onSuccess", "success"), ("onSuccessContainer", "successContainer"),
              ("onWarning", "warning"), ("onWarningContainer", "warningContainer"),
              ("onError", "error"), ("onErrorContainer", "errorContainer"),
              ("primary", "surface"), ("success", "surface"), ("error", "surface"), ("warning", "surface")]
# Icons, borders and focus indicators only need 3:1.
GRAPHIC_PAIRS = [("outline", "background"), ("outline", "surface"), ("success", "surface"),
                 ("warning", "surface"), ("error", "surface"), ("primary", "surface")]
# Text-coloured status roles are also used as text on surface, so they are in TEXT_PAIRS too.
WEIGHTS_KT = {400: "Normal", 500: "Medium", 600: "SemiBold", 700: "Bold"}
WEIGHTS_SW = {400: "regular", 500: "medium", 600: "semibold", 700: "bold"}


def luminance(hex_):
    def ch(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (int(hex_[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def contrast(a, b):
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def validate(t):
    errs = []
    for theme in ("light", "dark"):
        c = t["color"][theme]
        if sorted(c) != sorted(ROLES):
            errs.append(f"{theme}: roles differ from the expected set: {sorted(set(c) ^ set(ROLES))}")
            continue
        for k, v in c.items():
            if not (len(v) == 7 and v[0] == "#"):
                errs.append(f"{theme}.{k}: '{v}' is not #RRGGBB")
        for fg, bg in TEXT_PAIRS:
            r = contrast(c[fg], c[bg])
            if r < 4.5:
                errs.append(f"{theme}: text {fg} {c[fg]} on {bg} {c[bg]} = {r:.2f}:1 (< 4.5)")
        for fg, bg in GRAPHIC_PAIRS:
            r = contrast(c[fg], c[bg])
            if r < 3.0:
                errs.append(f"{theme}: graphic {fg} {c[fg]} on {bg} {c[bg]} = {r:.2f}:1 (< 3.0)")
    return errs


def kt_color(h):
    return f"Color(0xFF{h[1:].upper()})"


def kotlin(t):
    pkg = f"{KOTLIN_PKG_DESIGNSYSTEM}.theme"
    c = t["color"]
    L = [banner("gen_tokens.py"), f"package {pkg}", "",
         "import androidx.compose.foundation.isSystemInDarkTheme",
         "import androidx.compose.foundation.shape.RoundedCornerShape",
         "import androidx.compose.material3.MaterialTheme", "import androidx.compose.material3.Shapes",
         "import androidx.compose.material3.Typography", "import androidx.compose.material3.darkColorScheme",
         "import androidx.compose.material3.lightColorScheme",
         "import androidx.compose.runtime.CompositionLocalProvider", "import androidx.compose.runtime.Composable",
         "import androidx.compose.runtime.Immutable", "import androidx.compose.runtime.ReadOnlyComposable",
         "import androidx.compose.runtime.staticCompositionLocalOf", "import androidx.compose.animation.core.CubicBezierEasing",
         "import androidx.compose.ui.graphics.Color", "import androidx.compose.ui.text.TextStyle",
         "import androidx.compose.ui.text.font.FontFamily", "import androidx.compose.ui.text.font.FontWeight",
         "import androidx.compose.ui.unit.dp", "import androidx.compose.ui.unit.sp", "",
         "/** Semantic colours, same role names on both platforms. Status roles (success, warning) extend Material. */",
         "@Immutable", "data class ScanFitColors("]
    L += [f"    val {r}: Color," for r in ROLES] + ["    val isDark: Boolean,", ")", ""]
    for theme, name in (("light", "ScanFitLightColors"), ("dark", "ScanFitDarkColors")):
        L.append(f"val {name} = ScanFitColors(")
        L += [f"    {r} = {kt_color(c[theme][r])}," for r in ROLES]
        L += [f"    isDark = {'true' if theme == 'dark' else 'false'},", ")", ""]
    L += ["/** Raw neutral ramp, for illustration and decorative use. Prefer the semantic roles. */", "object ScanFitNeutral {"]
    L += [f"    val n{k} = {kt_color(v)}" for k, v in c["neutral"].items()] + ["}", ""]
    L.append("object ScanFitSpacing {")
    L += [f"    val {k} = {v}.dp" for k, v in t["spacing"].items()]
    L += [f"    val screenMargin = {t['layout']['screenMargin']}.dp",
          f"    val minTouchTarget = {t['layout']['minTouchTargetAndroidDp']}.dp", "}", ""]
    L.append("object ScanFitRadius {")
    L += [f"    val {k} = {v}.dp" for k, v in t["radius"].items()] + ["}", ""]
    L.append("object ScanFitMotion {")
    L += [f"    const val {k.upper()}_MS = {v}" for k, v in t["motion"]["durationMs"].items()]
    for k, v in t["motion"]["easing"].items():
        L.append(f"    val {k}Easing = CubicBezierEasing({', '.join(str(x) + 'f' for x in v)})")
    L += ["}", ""]
    L += ["/**", " * Type scale. FontFamily.Default is Roboto on Android; Roboto Flex + Noto Sans Devanagari",
          " * (UI_UX §4) is not bundled yet (APK size budget, ARCHITECTURE §11).", " */", "object ScanFitType {"]
    for k, v in t["type"].items():
        if k.startswith("_"):
            continue
        extra = ', fontFeatureSettings = "tnum"' if v.get("tabularNumbers") else ""
        L.append(f"    val {k} = TextStyle(fontFamily = FontFamily.Default, fontSize = {v['size']}.sp, "
                 f"fontWeight = FontWeight.{WEIGHTS_KT[v['weight']]}, lineHeight = {v['lineHeight']}.sp{extra})")
    L += ["}", ""]
    L += ["private val ScanFitMaterialTypography = Typography(",
          "    displayMedium = ScanFitType.display,", "    titleLarge = ScanFitType.title,",
          "    titleMedium = ScanFitType.headline,", "    bodyLarge = ScanFitType.body,",
          "    bodyMedium = ScanFitType.body,", "    labelLarge = ScanFitType.label,",
          "    bodySmall = ScanFitType.caption,", "    labelSmall = ScanFitType.caption,", ")", "",
          "private val ScanFitShapes = Shapes(",
          "    medium = RoundedCornerShape(ScanFitRadius.button),",
          "    large = RoundedCornerShape(ScanFitRadius.card),", ")", ""]
    mat = ["primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "background", "onBackground",
           "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant",
           "error", "onError", "errorContainer", "onErrorContainer", "scrim"]
    for theme, fn in (("light", "lightColorScheme"), ("dark", "darkColorScheme")):
        L.append(f"private fun ScanFitColors.to{theme.capitalize()}Scheme() = {fn}(")
        L += [f"    {r} = {r}," for r in mat] + [")", ""]
    L += ["val LocalScanFitColors = staticCompositionLocalOf { ScanFitLightColors }", "",
          "/** Access semantic colours that Material lacks (success, warning): `ScanFitTheme.colors.success`. */",
          "object ScanFitTheme {", "    val colors: ScanFitColors",
          "        @Composable @ReadOnlyComposable get() = LocalScanFitColors.current", "}", "",
          "@Composable", "fun ScanFitTheme(", "    darkTheme: Boolean = isSystemInDarkTheme(),",
          "    content: @Composable () -> Unit,", ") {",
          "    val colors = if (darkTheme) ScanFitDarkColors else ScanFitLightColors",
          "    CompositionLocalProvider(LocalScanFitColors provides colors) {",
          "        MaterialTheme(",
          "            colorScheme = if (darkTheme) colors.toDarkScheme() else colors.toLightScheme(),",
          "            typography = ScanFitMaterialTypography,", "            shapes = ScanFitShapes,",
          "            content = content,", "        )", "    }", "}"]
    return "\n".join(L)


def swift_hex(h):
    return "0x" + h[1:].upper()


def swift(t):
    c = t["color"]
    L = [banner("gen_tokens.py"), "import SwiftUI", "#if canImport(UIKit)", "import UIKit", "#elseif canImport(AppKit)",
         "import AppKit", "#endif", "",
         "extension Color {",
         "    /// A colour that resolves to `light` or `dark` with the current appearance.",
         "    init(light: UInt32, dark: UInt32) {",
         "        #if canImport(UIKit)",
         "        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })",
         "        #else",
         "        self.init(nsColor: NSColor(name: nil) { appearance in",
         "            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(rgb: dark) : NSColor(rgb: light)",
         "        })",
         "        #endif",
         "    }",
         "}", "",
         "#if canImport(UIKit)",
         "private extension UIColor {",
         "    convenience init(rgb: UInt32) {",
         "        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,",
         "                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)",
         "    }",
         "}",
         "#else",
         "private extension NSColor {",
         "    convenience init(rgb: UInt32) {",
         "        self.init(srgbRed: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,",
         "                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)",
         "    }",
         "}",
         "#endif", "",
         "/// Semantic colours (same role names as Android). Dynamic: they follow light/dark automatically.",
         "public enum ScanFitColor {"]
    L += [f"    public static let {r} = Color(light: {swift_hex(c['light'][r])}, dark: {swift_hex(c['dark'][r])})"
          for r in ROLES]
    L += ["}", "", "/// Raw neutral ramp. Prefer the semantic roles.", "public enum ScanFitNeutral {"]
    L += [f"    public static let n{k} = Color(light: {swift_hex(v)}, dark: {swift_hex(v)})" for k, v in c["neutral"].items()]
    L += ["}", "", "public enum ScanFitSpacing {"]
    L += [f"    public static let {k}: CGFloat = {v}" for k, v in t["spacing"].items()]
    L += [f"    public static let screenMargin: CGFloat = {t['layout']['screenMargin']}",
          f"    public static let minTouchTarget: CGFloat = {t['layout']['minTouchTargetIosPt']}", "}", ""]
    L += ["/// iOS uses platform-default control shapes; these are for cards, chips and custom containers.",
          "public enum ScanFitRadius {"]
    L += [f"    public static let {k}: CGFloat = {v}" for k, v in t["radius"].items()] + ["}", ""]
    L += ["public enum ScanFitMotion {", "    // Durations in seconds."]
    L += [f"    public static let {k}: Double = {v / 1000}" for k, v in t["motion"]["durationMs"].items()]
    L += ["", "    /// Returns nil (no animation) when Reduce Motion is on.",
          "    public static func animation(_ duration: Double, reduceMotion: Bool) -> Animation? {",
          "        reduceMotion ? nil : .easeInOut(duration: duration)", "    }", "}", ""]
    L += ["public enum ScanFitTextToken: CaseIterable, Sendable {"]
    L += [f"    case {k}" for k in t["type"] if not k.startswith("_")]
    L += ["", "    var size: CGFloat {", "        switch self {"]
    L += [f"        case .{k}: return {v['size']}" for k, v in t["type"].items() if not k.startswith("_")]
    L += ["        }", "    }", "", "    var weight: Font.Weight {", "        switch self {"]
    L += [f"        case .{k}: return .{WEIGHTS_SW[v['weight']]}" for k, v in t["type"].items() if not k.startswith("_")]
    L += ["        }", "    }", "", "    /// The Dynamic Type style this token scales with.", "    var relativeTo: Font.TextStyle {", "        switch self {"]
    L += [f"        case .{k}: return .{v['iosStyle']}" for k, v in t["type"].items() if not k.startswith("_")]
    L += ["        }", "    }", "", "    var tabularNumbers: Bool {", "        switch self {"]
    tab = [k for k, v in t["type"].items() if not k.startswith("_") and v.get("tabularNumbers")]
    L += [f"        case {', '.join('.' + k for k in tab)}: return true", "        default: return false", "        }", "    }", "}", ""]
    L += ["/// Applies a type token with Dynamic Type scaling: `Text(...).scanFitText(.body)`.",
          "struct ScanFitTextModifier: ViewModifier {",
          "    let token: ScanFitTextToken", "    @ScaledMetric private var size: CGFloat", "",
          "    init(_ token: ScanFitTextToken) {", "        self.token = token",
          "        _size = ScaledMetric(wrappedValue: token.size, relativeTo: token.relativeTo)", "    }", "",
          "    func body(content: Content) -> some View {",
          "        let font = Font.system(size: size, weight: token.weight)",
          "        if token.tabularNumbers {", "            content.font(font.monospacedDigit())",
          "        } else {", "            content.font(font)", "        }", "    }", "}", "",
          "public extension View {",
          "    func scanFitText(_ token: ScanFitTextToken) -> some View { modifier(ScanFitTextModifier(token)) }", "}"]
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    with open(TOKENS, encoding="utf-8") as f:
        t = json.load(f)
    errs = validate(t)
    if errs:
        die(errs, "gen_tokens")
    out = Output(args.check)
    out.write(os.path.join(ANDROID_DESIGNSYSTEM, "src", "main", "kotlin", *KOTLIN_PKG_DESIGNSYSTEM.split("."),
                           "theme", "ScanFitTheme.kt"), kotlin(t))
    out.write(os.path.join(IOS_CORE, "DesignSystem", "Generated", "Theme.swift"), swift(t))
    worst = min(contrast(t["color"][th][a], t["color"][th][b]) for th in ("light", "dark") for a, b in TEXT_PAIRS)
    print(f"  contrast OK (lowest text pair {worst:.2f}:1)")
    out.finish("gen_tokens.py")


if __name__ == "__main__":
    main()
