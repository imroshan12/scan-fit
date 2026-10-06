import Foundation
import Imaging
@testable import InkFlow
import Inspect
import Match
import ScanModel
import Testing
import TestSupport

@MainActor
@Suite("Ink review checks")
struct InkReviewChecksTests {
    private func model(_ tools: FakeInkTools, unknown: Bool = false) async throws -> InkFlowViewModel {
        let bundle: PresetBundle
        if unknown {
            let data = try SpecFiles.data("presets/exams/banking/ibps_po.json")
            var object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
            var documents = try #require(object["documents"] as? [[String: Any]])
            let index = try #require(documents.firstIndex { $0["type"] as? String == "signature" })
            documents[index]["size_kb"] = [String: Double]()
            documents[index]["dimensions"] = ["mode": "none"]
            object["documents"] = documents
            let exam = try PresetBundle.decodeExam(JSONSerialization.data(withJSONObject: object))
            bundle = PresetBundle(presetsVersion: 2, exams: [exam], categories: [:], popular: [])
        } else {
            bundle = try SpecPresets.bundle()
        }
        let model = InkFlowViewModel(examId: "ibps_po", docType: .signature, tools: tools) { bundle }
        await model.onAppear()
        await model.imageSelected([1])
        return model
    }

    @Test("each chip is derived from the output bytes, including progressive rejection")
    func outputChecks() async throws {
        let tools = FakeInkTools()
        let model = try await model(tools)
        await model.cropDone()
        guard case let .review(initial) = model.state else { Issue.record("no initial review"); return }
        var progressive = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
        progressive[21] = 0xC2
        let cases: [([UInt8], ReviewChecks)] = [
            (TestJpeg.make(width: 140, height: 60, size: 16 * 1024),
             ReviewChecks(size: true, dimensions: true, jpeg: true)),
            (TestJpeg.make(width: 140, height: 60, size: 30 * 1024),
             ReviewChecks(size: false, dimensions: true, jpeg: true)),
            (TestJpeg.make(width: 140, height: 140, size: 16 * 1024),
             ReviewChecks(size: true, dimensions: false, jpeg: true)),
            (progressive, ReviewChecks(size: true, dimensions: true, jpeg: false)),
        ]
        for (bytes, expected) in cases {
            tools.produce(bytes)
            model.setCrispBlack(false)
            guard case let .review(working) = model.state else { Issue.record("no working review"); return }
            #expect(working.result == .working(targetKb: 16) && working.before == initial.before)
            await model.renderTask?.value
            guard case let .review(review) = model.state, case let .ready(ready) = review.result else {
                Issue.record("no ready review"); return
            }
            let evaluation = MatchEngine.evaluate(review.slot.spec, FileFacts(Inspector.inspect(bytes), docKind: .signature))
            #expect(ready.bytes == bytes && ready.checks == expected && ready.checks == .of(evaluation))
            #expect(ready.width == Inspector.inspect(bytes).width && ready.height == Inspector.inspect(bytes).height)
            #expect(review.before == initial.before)
        }
    }

    @Test("Before is the oriented free crop and survives options, debounce and render failure")
    func beforeEffects() async throws {
        let tools = FakeInkTools()
        let model = try await model(tools)
        await model.rotate()
        model.resize(.topLeft, dx: 100, dy: 200)
        guard case let .crop(crop) = model.state else { Issue.record("no crop"); return }
        let rect = crop.rect
        let original = crop.image.crop(x: rect.x, y: rect.y, width: rect.w, height: rect.h)
        await model.cropDone()
        guard case let .review(initial) = model.state else { Issue.record("no review"); return }
        #expect(initial.before == original && tools.fitInput == original)
        model.setCrispBlack(false)
        guard case let .review(working) = model.state else { Issue.record("no working review"); return }
        #expect(working.before == original && working.result == .working(targetKb: 16))
        await model.renderTask?.value
        model.setInkFactor(0.3)
        guard case let .review(debounced) = model.state else { Issue.record("no debounced review"); return }
        #expect(debounced.before == original && model.renderPending)
        #expect(debounced.result == .working(targetKb: debounced.slot.targetKb))
        await model.renderTask?.value
        guard case let .review(after) = model.state else { Issue.record("no new review"); return }
        #expect(after.before == original)
        tools.fail(.tooDetailed)
        model.setCrispBlack(true)
        await model.renderTask?.value
        guard case let .review(failed) = model.state else { Issue.record("no failed review"); return }
        #expect(failed.before == original && failed.result == .failed(.tooDetailed))
    }

    @Test("an unknown slot never shows green technical checks")
    func unknown() async throws {
        let model = try await model(FakeInkTools(), unknown: true)
        await model.cropDone()
        guard case let .review(review) = model.state, case let .ready(ready) = review.result else {
            Issue.record("no ready review"); return
        }
        model.confirmHandwriting()
        #expect(ready.checks == .unchecked && !ready.meetsRules && !model.canSave)
        #expect(review.before != nil)
    }
}
