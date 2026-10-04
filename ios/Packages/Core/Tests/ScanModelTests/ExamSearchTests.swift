import Foundation
@testable import ScanModel
import TestSupport
import Testing

/// ALGORITHMS §10 conformance: every `search_*` case in spec/fixtures/cases.json, against the source presets.
@Suite("Exam search")
struct ExamSearchTests {
    private static func sourceBundle() throws -> PresetBundle {
        try SpecPresets.bundle()
    }

    @Test("every search, browse and popular case matches the reference")
    func searchCases() throws {
        let search = try ExamSearch(bundle: Self.sourceBundle())
        let cases = try CasesFile.section("search_cases")
        #expect(cases.count >= 20)
        for testCase in cases {
            let id = testCase.string("id") ?? "?"
            let unverified = testCase.bool("show_unverified") ?? false
            let category = testCase.string("category").flatMap(ExamCategory.init(rawValue:))
            let got: [Exam]
            switch testCase.string("kind") {
            case "search": got = search.search(testCase.string("query") ?? "", category: category, showUnverified: unverified)
            case "browse": got = try search.browse(#require(category), showUnverified: unverified)
            case "popular": got = try search.popular(#require(testCase.int("n")), showUnverified: unverified)
            default: Issue.record("\(id): unknown kind"); continue
            }
            #expect(got.map(\.id) == testCase.strings("expect_ids"), "\(id)")
        }
    }

    @Test("every level case matches")
    func levelCases() throws {
        for testCase in try CasesFile.section("search_level_cases") {
            let level = ExamSearch.level(testCase.string("q") ?? "", testCase.string("t") ?? "")
            #expect(level == testCase.int("level"), "\(testCase.string("id") ?? "?")")
        }
    }

    @Test("every normalisation case matches")
    func normCases() throws {
        for testCase in try CasesFile.section("search_norm_cases") {
            #expect(ExamSearch.norm(testCase.string("text") ?? "") == testCase.strings("tokens"), "\(testCase.string("id") ?? "?")")
        }
    }

    @Test("snake_case category keys are found even after convertFromSnakeCase rewrites them")
    func camelCaseCategoryKeys() throws {
        let source = try Self.sourceBundle()
        let camel = Dictionary(uniqueKeysWithValues: (source.categories ?? [:]).map { key, value in
            (key == "state_psc" ? "statePsc" : key, value)
        })
        let bundle = PresetBundle(presetsVersion: 0, exams: source.exams, categories: camel, popular: source.popular ?? [])
        let hits = ExamSearch(bundle: bundle).search("राज्य लोक सेवा आयोग").map(\.category)
        #expect(!hits.isEmpty && hits.allSatisfy { $0 == .statePsc })
    }

    @Test("a decoded signed-bundle shape carries the search data")
    func decodedBundle() throws {
        let json = #"{"schema_version":2,"presets_version":3,"generated_on":"","disclaimer":"","exams":[],"#
            + #""categories":{"state_psc":{"aliases":["x"]}},"popular":["a"]}"#
        let bundle = try PresetBundle.decode(Data(json.utf8))
        #expect(bundle.popular == ["a"])
        #expect(bundle.categories?.values.first?.aliases == ["x"])
    }

    @Test("an older bundle without search data still searches by name")
    func olderBundle() throws {
        let plain = try PresetBundle(presetsVersion: 0, exams: Self.sourceBundle().exams)
        let search = ExamSearch(bundle: plain)
        #expect(search.search("ssc cgl").first?.id == "ssc_cgl")
        #expect(search.popular(3).isEmpty)
    }

    @Test("an adjacent swap is one edit")
    func osa() {
        #expect(ExamSearch.osa(Array("ibsp".unicodeScalars.map(\.value)), Array("ibps".unicodeScalars.map(\.value))) == 1)
        #expect(ExamSearch.osa([], Array("abc".unicodeScalars.map(\.value))) == 3)
    }
}
