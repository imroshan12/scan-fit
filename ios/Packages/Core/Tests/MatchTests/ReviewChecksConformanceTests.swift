import Match
import TestSupport
import Testing

@Suite("Review checks conformance")
struct ReviewChecksConformanceTests {
    @Test("all eight shared review check cases hold")
    func everyCase() throws {
        let cases = try CasesFile.section("review_check_cases")
        #expect(cases.count == 8)
        let verdicts: [String: Verdict] = [
            "EXACT": .exact, "ACCEPTED": .accepted, "NEAR_MISS": .nearMiss, "NO": .no, "UNKNOWN": .unknown,
        ]
        for fixture in cases {
            let id = try #require(fixture.string("id"))
            let rawVerdict = try #require(fixture.string("verdict"))
            let verdict = try #require(verdicts[rawVerdict])
            let failed = try #require(fixture.strings("failed")).map { raw in
                try #require(Constraint(rawValue: raw))
            }
            let expected = try #require(fixture["expect"] as? [Bool])
            #expect(expected.count == 3, "\(id): three checks")
            let checks = ReviewChecks.of(SlotEvaluation(verdict: verdict, failed: failed))
            #expect([checks.size, checks.dimensions, checks.jpeg] == expected, "\(id)")
        }
    }
}
