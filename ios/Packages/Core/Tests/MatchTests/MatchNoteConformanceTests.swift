import Foundation
import Inspect
import Match
import ScanModel
import TestSupport
import Testing

/// Runs every `match_note_cases` entry of spec/fixtures/cases.json (ALGORITHMS 9.7 "Match note").
@Suite("Match note conformance")
struct MatchNoteConformanceTests {
    private func entry(_ e: [String: Any]) throws -> MatchEntry {
        let verdict: Verdict = switch e.string("verdict") {
        case "exact": .exact
        case "accepted": .accepted
        case "near_miss": .nearMiss
        default: .no
        }
        let size = e.dict("size_kb")
        return MatchEntry(
            examId: try #require(e.string("exam")), examName: try #require(e.string("name")),
            body: try #require(e.string("body")), docType: try #require(DocType(rawValue: e.string("doc") ?? "")),
            verdict: verdict, unverified: e.bool("unverified") ?? false,
            fix: e.string("fix").flatMap(FixAction.init(rawValue:)), failed: [], issues: [],
            sizeKb: SizeKB(min: size?.double("min"), max: size?.double("max"))
        )
    }

    private func note(_ c: [String: Any]) throws -> MatchNote {
        let entries = try ((c["entries"] as? [[String: Any]]) ?? []).map(entry)
        return MatchNote.of(MatchResult(entries: entries))
    }

    private func need(_ f: [String: Any]) -> Need? {
        switch f.string("need") {
        case "at_most": f.int("kb").map(Need.atMost)
        case "at_least": f.int("kb").map(Need.atLeast)
        case "jpeg": .jpeg
        case "baseline": .baseline
        default: nil
        }
    }

    @Test("every match note case holds")
    func everyCase() throws {
        let cases = try CasesFile.section("match_note_cases")
        #expect(cases.count >= 5)
        for c in cases {
            let id = try #require(c.string("id"))
            let note = try note(c)
            let expect = try #require(c.dict("expect"))
            #expect(note.accepted == expect.int("accepted"), "\(id): accepted")
            #expect(note.preview.map(\.id) == expect.strings("preview"), "\(id): preview")
            #expect(note.more == expect.int("more"), "\(id): more")
            #expect(note.likelyOk == expect.int("likely_ok"), "\(id): likely_ok")
            let fixes = (expect["quick_fixes"] as? [[String: Any]]) ?? []
            #expect(note.quickFixes.map(\.exam.id) == fixes.compactMap { $0.string("exam") }, "\(id): quick fix exams")
            #expect(note.quickFixes.map(\.docType.rawValue) == fixes.compactMap { $0.string("doc") }, "\(id): quick fix docs")
            #expect(note.quickFixes.map(\.need) == fixes.compactMap(need), "\(id): quick fix needs")
            let groups = (expect["groups"] as? [[String: Any]]) ?? []
            #expect(note.groups.map(\.body) == groups.compactMap { $0.string("body") }, "\(id): group bodies")
            #expect(note.groups.map { $0.entries.map(\.examId) } == groups.compactMap { $0.strings("exams") }, "\(id): group exams")
        }
    }

    @Test("the headline falls back from accepted to likely OK to none")
    func headline() throws {
        let cases = Dictionary(uniqueKeysWithValues: try CasesFile.section("match_note_cases").map { ($0.string("id") ?? "", $0) })
        #expect(try note(try #require(cases["preview_takes_three_and_counts_the_rest"])).headline == .accepted)
        #expect(try note(try #require(cases["unverified_exams_are_likely_ok_not_accepted"])).headline == .likelyOk)
        #expect(try note(try #require(cases["nothing_matches"])).headline == .none)
        #expect(MatchNote.empty.headline == .none)
    }

    @Test("a near miss without a usable limit is not offered as a quick fix")
    func unusableNearMiss() {
        let noMax = MatchEntry(examId: "a", examName: "A", body: "B", docType: .signature, verdict: .nearMiss, unverified: false,
                               fix: .compressToTarget, failed: [.sizeKb], issues: [], sizeKb: SizeKB(min: 10))
        let noFix = MatchEntry(examId: "b", examName: "B", body: "B", docType: .signature, verdict: .nearMiss, unverified: false,
                               fix: nil, failed: [], issues: [])
        #expect(MatchNote.of(MatchResult(entries: [noMax, noFix])).quickFixes.isEmpty)
    }

    @Test("a fitted IBPS signature is accepted by IBPS PO")
    func realFit() throws {
        let exams = try SpecFiles.presetFiles().map { try PresetBundle.decodeExam(Data(contentsOf: $0)) }
        let facts = FileFacts(format: .jpeg, kb: 16, width: 140, height: 60, color: .rgb, progressive: false, docKind: .signature)
        let note = MatchNote.of(MatchEngine.match(facts, exams: exams))
        #expect(note.accepted > 1)
        #expect(note.groups.contains { $0.body == "IBPS" && $0.entries.contains { $0.examId == "ibps_po" } })
    }
}
