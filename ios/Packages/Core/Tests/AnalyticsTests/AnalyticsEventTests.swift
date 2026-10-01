import Analytics
import Testing

@Suite("Analytics events")
struct AnalyticsEventTests {
    @Test("names and parameters are exactly the spec")
    func fitCompleted() {
        let event = AnalyticsEvent.fitCompleted(docType: .signature, strategy: .upscaleThenPad, msBucket: .lt500, result: .ok)
        #expect(event.name == "fit_completed")
        #expect(event.parameters == [
            "doc_type": .text("signature"),
            "strategy": .text("upscale_then_pad"),
            "ms_bucket": .text("lt_500"),
            "result": .text("ok"),
        ])
    }

    @Test("events without params have empty parameters")
    func noParams() {
        #expect(AnalyticsEvent.fixTapped.parameters.isEmpty)
        #expect(AnalyticsEvent.rewardedCompleted.name == "rewarded_completed")
    }

    @Test("bounded ints are clamped, so a bug cannot send a huge number")
    func clamping() {
        #expect(AnalyticsEvent.matchShown(nExactBucket: .n610, nFix: 5000).parameters["n_fix"] == .whole(99))
        #expect(AnalyticsEvent.checkerRun(issuesCount: -3).parameters["issues_count"] == .whole(0))
    }

    @Test("exam ids accept preset ids and refuse paths, URIs and spaces")
    func examIDs() {
        #expect(AnalyticsEvent.ExamID("ibps_po") != nil)
        #expect(AnalyticsEvent.ExamID("") == nil)
        #expect(AnalyticsEvent.ExamID("/Users/me/photo.jpg") == nil)
        #expect(AnalyticsEvent.ExamID("content://media/1") == nil)
        #expect(AnalyticsEvent.ExamID("Has Space") == nil)
        #expect(AnalyticsEvent.ExamID(String(repeating: "a", count: 41)) == nil)
    }

    @Test("no parameter value of any event can contain a path or URI")
    func noPathsInParameters() throws {
        let id = try #require(AnalyticsEvent.ExamID("neet_ug"))
        let events: [AnalyticsEvent] = [
            .examOpened(examId: id), .flowStarted(docType: .photo),
            .exportSaved(docType: .leftThumb, verified: true), .paywallShown(variant: .a, trigger: .freeLimit),
            .purchase(product: .proYearly), .presetSync(result: .updated), .presetSyncFailed(reason: .badSignature),
            .portalFeedback(accepted: false), .coachAllGreen(secondsBucket: .lt15),
        ]
        for event in events {
            for case let .text(value) in event.parameters.values {
                #expect(!value.contains("/") && !value.contains("content:"), "\(event.name)")
            }
        }
    }
}
