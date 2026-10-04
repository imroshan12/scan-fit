import DesignSystem
import Foundation
import TestSupport
import Testing

/// The generated String Catalog works in English and Hindi, with positional args and plurals.
@Suite("Strings")
struct StringsTests {
    private let en = Strings(languageCode: "en")
    private let hi = Strings(languageCode: "hi")

    @Test("plain strings resolve per language")
    func plain() {
        #expect(en.tabHome == "Home")
        #expect(hi.tabHome == "होम")
        #expect(en.examConfidenceLow == "Unverified — check notice", "CLAUDE.md rule 6 wording")
        #expect(hi.commonSave == "सेव करें")
    }

    @Test("the TODO_HI marker never reaches the UI")
    func todoPrefixStripped() {
        #expect(!hi.homeTitle.contains("TODO_HI"))
        #expect(hi.homeTitle == "आप किसके लिए आवेदन कर रहे हैं?")
    }

    @Test("placeholders are filled")
    func placeholders() {
        #expect(en.homeSearchHint(count: 55) == "Search 55+ exams — IBPS, SSC, NEET…")
        #expect(hi.homeSearchHint(count: 55).contains("55"))
        #expect(en.flowStepFit(kb: 16) == "Fitting to 16 KB")
        #expect(en.issueWrongDimensions(w: 100, h: 50, targetW: 140, targetH: 60) == "Size is 100×50; this slot needs 140×60.")
        #expect(en.matchFixNeedsSize(exam: "SSC CGL", doc: "signature", maxKb: 20) == "SSC CGL signature needs ≤ 20 KB")
    }

    @Test("plurals pick one/other")
    func plurals() {
        #expect(en.matchAccepted(count: 1) == "Accepted by 1 exam")
        #expect(en.matchAccepted(count: 14) == "Accepted by 14 exams")
        #expect(en.matchQuickFix(count: 1) == "1 quick fix")
        #expect(en.matchQuickFix(count: 3) == "3 quick fixes")
        #expect(hi.matchAccepted(count: 14) == "14 परीक्षाओं में मान्य")
    }

    @Test("the presets status line is a plural, never \"1 exams\"")
    func presetsStatusPlural() {
        #expect(en.homePresetsStatus(count: 1, version: "3") == "1 exam · specs v3")
        #expect(en.homePresetsStatus(count: 55, version: "3") == "55 exams · specs v3")
        #expect(hi.homePresetsStatus(count: 55, version: "3") == "55 परीक्षाएँ · स्पेक्स v3")
    }

    @Test("plural with a second argument keeps both (reordered in Hindi)")
    func pluralWithTwoArgs() {
        #expect(en.flowSavedTo(count: 1, folder: "ScanFit/IBPS PO") == "1 file saved to ScanFit/IBPS PO")
        #expect(en.flowSavedTo(count: 4, folder: "ScanFit/IBPS PO") == "4 files saved to ScanFit/IBPS PO")
        let hindi = hi.flowSavedTo(count: 4, folder: "IBPS PO")
        #expect(hindi.contains("4") && hindi.contains("IBPS PO"))
    }

    @Test("every key in en.json is localised in both languages (no raw keys leak)")
    func noRawKeysLeak() throws {
        let data = try SpecFiles.data("strings/en.json")
        let keys = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
            .keys.filter { !$0.hasPrefix("_") && !$0.hasPrefix("infoplist.") } // Info.plist values: app target only
        #expect(keys.count > 100)
        for lang in ["en", "hi"] {
            let bundle = try #require(Self.languageBundle(lang), "no \(lang).lproj in the DesignSystem bundle")
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: "⟦missing⟧", table: nil)
                #expect(value != "⟦missing⟧", "\(lang): \(key) missing")
                #expect(!value.contains("TODO_HI"), "\(lang): \(key) still has the marker")
            }
        }
    }

    static func languageBundle(_ code: String) -> Bundle? {
        // Strings.init resolves Bundle.module internally; mirror it through a probe string to find the bundle.
        for bundle in Bundle.allBundles + Bundle.allFrameworks {
            if let path = bundle.path(forResource: code, ofType: "lproj"), bundle.bundlePath.contains("DesignSystem") {
                return Bundle(path: path)
            }
        }
        return nil
    }
}
