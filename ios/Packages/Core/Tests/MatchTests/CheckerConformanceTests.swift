import Foundation
import Inspect
import Match
import ScanModel
import TestSupport
import Testing

/// Runs every `checker_cases` entry (ALGORITHMS 12) and checks the exam-slot list against the real presets.
@Suite("Checker conformance")
struct CheckerConformanceTests {
    @Test("every checker case holds")
    func everyCase() throws {
        let cases = try CasesFile.section("checker_cases")
        #expect(cases.count >= 8)
        for c in cases {
            let id = try #require(c.string("id"))
            let formats = (c.strings("slot_formats") ?? []).compactMap(FileFormat.init(rawValue:))
            let slot = try decodeSlot(formats)
            let verdict: Verdict = switch c.string("verdict") {
            case "exact": .exact
            case "accepted": .accepted
            case "near_miss": .nearMiss
            case "unknown": .unknown
            default: .no
            }
            let status = CheckerStatus.of(
                try #require(DetectedFormat(rawValue: c.string("format") ?? "")), slot: slot, verdict: verdict,
                unverified: c.bool("unverified") ?? false,
                encrypted: c.bool("encrypted") ?? false
            )
            #expect(status.rawValue == c.string("expect"), "\(id)")
        }
    }

    private func decodeSlot(_ formats: [FileFormat]) throws -> DocSpec {
        let json = """
        {"type":"signature","required":true,"formats":[\(formats.map { "\"\($0.rawValue)\"" }.joined(separator: ","))],
         "size_kb":{"max":20},"dimensions":{"mode":"none"}}
        """
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(DocSpec.self, from: Data(json.utf8))
    }

    @Test("targets list every slot of the kind, popular first; a query uses the exam search")
    func targets() throws {
        let bundle = try SpecPresets.bundle()
        let signatures = CheckerTargets.of(bundle, kind: .signature)
        #expect(signatures.first?.exam.id == bundle.popular?.first)
        #expect(signatures.allSatisfy { $0.slot.type == .signature || $0.slot.type == .tripleSignature })
        #expect(signatures.contains { $0.exam.id == "upsc_cse" }, "a triple signature is a signature slot")
        let neet = CheckerTargets.of(bundle, kind: .fingers).filter { $0.exam.id == "neet_ug" }.map(\.slot.type)
        #expect(neet == [.leftHandFingersThumb, .rightHandFingersThumb], "an exam with two matching slots appears twice")
        #expect(CheckerTargets.of(bundle, kind: .photo, query: "sbi po").first?.exam.id == "sbi_po")
    }
}
