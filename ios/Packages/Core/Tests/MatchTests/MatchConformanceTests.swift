import Foundation
import Inspect
import Match
import ScanModel
import TestSupport
import Testing

/// Runs every `match_cases` entry of spec/fixtures/cases.json against the real presets.
@Suite("Match conformance")
struct MatchConformanceTests {
    private static func loadExams() throws -> [Exam] {
        try SpecFiles.presetFiles().map { try PresetBundle.decodeExam(Data(contentsOf: $0)) }
    }

    private func facts(_ file: [String: Any]) throws -> FileFacts {
        let format: DetectedFormat = switch file.string("format") {
        case "jpeg": .jpeg
        case "png": .png
        case "pdf": .pdf
        default: .unknown
        }
        let kind = try #require(DocKind(rawValue: file.string("doc") ?? ""))
        let color: ColorKind = switch file.string("color") ?? "rgb" {
        case "gray": .gray
        case "cmyk": .cmyk
        default: .rgb
        }
        return FileFacts(format: format, kb: try #require(file.double("kb")), width: file.int("width"), height: file.int("height"),
                         color: color, progressive: file.bool("progressive") ?? false, docKind: kind)
    }

    @Test("every match case holds")
    func everyCase() throws {
        let exams = try Self.loadExams()
        let cases = try CasesFile.section("match_cases")
        #expect(cases.count >= 16)
        for c in cases {
            let id = try #require(c.string("id"))
            let opts = c.dict("options")
            let options = MatchOptions(allowGrayscale: opts?.bool("allow_grayscale") ?? false, popularity: opts?.strings("popularity") ?? [])
            let result = MatchEngine.match(try facts(try #require(c.dict("file"))), exams: exams, options: options)
            func verdicts(_ exam: String) -> [Verdict] { result.entries.filter { $0.examId == exam }.map(\.verdict) }

            for exam in c.strings("expect_exact_includes") ?? [] { #expect(verdicts(exam).contains(.exact), "\(id): \(exam) should be exact, was \(verdicts(exam))") }
            for exam in c.strings("expect_exact_excludes") ?? [] { #expect(!verdicts(exam).contains(.exact), "\(id): \(exam) must not be exact") }
            for exam in c.strings("expect_accepted_includes") ?? [] { #expect(verdicts(exam).contains(.accepted), "\(id): \(exam) should be accepted, was \(verdicts(exam))") }
            for exam in c.strings("expect_not_includes") ?? [] { #expect(verdicts(exam).isEmpty, "\(id): \(exam) must not be listed, was \(verdicts(exam))") }
            for exam in c.strings("expect_unverified_includes") ?? [] {
                #expect(result.entries.contains { $0.examId == exam && $0.unverified }, "\(id): \(exam) should be listed as unverified")
            }
            for exam in c.strings("expect_headline_excludes") ?? [] { #expect(!result.acceptedExamIds.contains(exam), "\(id): \(exam) must not count in the headline") }
            for case let nearMiss as [String: Any] in (c["expect_near_miss"] as? [Any]) ?? [] {
                let exam = try #require(nearMiss.string("exam"))
                let entry = result.entries.first { $0.examId == exam && $0.verdict == .nearMiss }
                #expect(entry != nil, "\(id): \(exam) should be a near miss")
                #expect(entry?.fix?.rawValue == nearMiss.string("fix"), "\(id): fix")
                #expect(entry?.failed.first?.rawValue == nearMiss.string("param"), "\(id): param")
            }
            if let expected = c.strings("expect_first_exact") {
                var seen = Set<String>()
                let exact = result.entries.filter { $0.verdict == .exact && !$0.unverified }.map(\.examId).filter { seen.insert($0).inserted }
                #expect(Array(exact.prefix(expected.count)) == expected, "\(id): first exact")
            }
        }
    }

    @Test("the IBPS-family photo headline is large and never counts unverified exams")
    func headline() throws {
        let photo = FileFacts(format: .jpeg, kb: 35, width: 200, height: 230, color: .rgb, progressive: false, docKind: .photo)
        let result = MatchEngine.match(photo, exams: try Self.loadExams())
        #expect(result.acceptedExamCount >= 10)
        for id in result.acceptedExamIds {
            #expect(result.entries.contains { $0.examId == id && !$0.unverified }, "\(id) is counted only through verified entries")
        }
    }
}
