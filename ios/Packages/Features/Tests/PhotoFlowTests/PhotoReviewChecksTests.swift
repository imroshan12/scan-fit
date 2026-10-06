import Foundation
import Imaging
import Inspect
import Match
@testable import PhotoFlow
import ScanModel
import ScanVision
import Testing
import TestSupport

@MainActor
@Suite("Photo review checks")
struct PhotoReviewChecksTests {
    private func model(_ tools: FakePhotoTools, unknown: Bool = false) async throws -> PhotoFlowViewModel {
        let bundle: PresetBundle
        if unknown {
            let data = try SpecFiles.data("presets/exams/banking/ibps_po.json")
            var object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
            var documents = try #require(object["documents"] as? [[String: Any]])
            let index = try #require(documents.firstIndex { $0["type"] as? String == "photo" })
            documents[index]["size_kb"] = [String: Double]()
            documents[index]["dimensions"] = ["mode": "none"]
            object["documents"] = documents
            let exam = try PresetBundle.decodeExam(JSONSerialization.data(withJSONObject: object))
            bundle = PresetBundle(presetsVersion: 2, exams: [exam], categories: [:], popular: [])
        } else {
            bundle = try SpecPresets.bundle()
        }
        let model = PhotoFlowViewModel(
            examId: "ibps_po", docType: .photo, tools: tools,
            faces: FakeFaceDetector(faces: [Face(x: 400, y: 500, w: 200, h: 240)])
        ) { bundle }
        await model.onAppear()
        await model.imageSelected([1])
        await model.cropDone()
        return model
    }

    @Test("each chip is derived from re-inspected output, not the fit report or overall verdict")
    func outputChecks() async throws {
        let tools = FakePhotoTools()
        let model = try await model(tools)
        guard case let .review(initial) = model.state else { Issue.record("no initial review"); return }
        var progressive = TestJpeg.make(width: 200, height: 230, size: 34 * 1024)
        progressive[21] = 0xC2
        let cases: [([UInt8], ReviewChecks)] = [
            (TestJpeg.make(width: 200, height: 230, size: 34 * 1024),
             ReviewChecks(size: true, dimensions: true, jpeg: true)),
            (TestJpeg.make(width: 200, height: 230, size: 60 * 1024),
             ReviewChecks(size: false, dimensions: true, jpeg: true)),
            (TestJpeg.make(width: 200, height: 200, size: 34 * 1024),
             ReviewChecks(size: true, dimensions: false, jpeg: true)),
            (progressive, ReviewChecks(size: true, dimensions: true, jpeg: false)),
        ]
        for (bytes, expected) in cases {
            tools.setFit { FakePhotoTools.success($0, bytes) }
            model.setNameDate(false)
            guard case let .review(working) = model.state else { Issue.record("no working review"); return }
            #expect(working.result == .working(targetKb: 38) && working.before == initial.before)
            await model.renderTask?.value
            guard case let .review(review) = model.state, case let .ready(ready) = review.result else {
                Issue.record("no ready review"); return
            }
            let evaluation = MatchEngine.evaluate(review.slot.spec, FileFacts(Inspector.inspect(bytes), docKind: .photo))
            #expect(ready.bytes == bytes && ready.checks == expected && ready.checks == .of(evaluation))
            #expect(ready.width == Inspector.inspect(bytes).width && ready.height == Inspector.inspect(bytes).height)
            #expect(review.before == initial.before)
        }
        tools.setFit { _ in .failure(.encodingFailed) }
        model.setNameDate(false)
        await model.renderTask?.value
        guard case let .review(failed) = model.state else { Issue.record("no failed review"); return }
        #expect(failed.result == .failed(.encodingFailed) && failed.before == initial.before)
    }

    @Test("a debounced edit hides the old preview and checks immediately")
    func pendingEdit() async throws {
        let model = try await model(FakePhotoTools())
        guard case let .review(initial) = model.state else { Issue.record("no initial review"); return }
        model.setName("Changed")
        guard case let .review(pending) = model.state else { Issue.record("no pending review"); return }
        #expect(model.renderPending && pending.result == .working(targetKb: pending.slot.targetKb))
        #expect(pending.before == initial.before)
        await model.renderTask?.value
        guard case let .review(finished) = model.state, case .ready = finished.result else {
            Issue.record("no finished review"); return
        }
        #expect(!model.renderPending)
    }

    @Test("an unknown slot never shows green technical checks")
    func unknown() async throws {
        let model = try await model(FakePhotoTools(), unknown: true)
        guard case let .review(review) = model.state, case let .ready(ready) = review.result else {
            Issue.record("no ready review"); return
        }
        #expect(ready.checks == .unchecked && !ready.meetsRules && !model.canSave)
        #expect(review.before != nil)
    }
}
