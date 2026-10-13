import Foundation
import Imaging
@testable import PhotoFlow
import ScanData
import ScanModel
import ScanVision
import Testing
import TestSupport

/// The photo flow for a Custom size (ALGORITHMS 11): the same face-aware pipeline, no draft, no Saved status.
@MainActor
@Suite("PhotoFlow Custom size")
struct PhotoCustomFlowTests {
    private let tools = FakePhotoTools()
    private let drafts = FakeDraftStore()
    private let photo: [UInt8] = [1, 2, 3]

    private func model(_ text: String, prefs: UserPreferences? = nil) async throws -> PhotoFlowViewModel {
        let size = try #require(CustomSize.parse(text))
        let bundle = try SpecPresets.bundle()
        let m = PhotoFlowViewModel(
            target: .custom(size), tools: tools, faces: FakeFaceDetector(faces: [Face(x: 400, y: 500, w: 200, h: 240)]),
            segmenter: FakePersonSegmenter(), preferences: prefs, exporter: ScriptedExporter(), drafts: drafts
        ) { bundle }
        await m.onAppear()
        return m
    }

    @Test("a custom photo is cropped to its own aspect; a signature size is not a photo flow")
    func slot() async throws {
        guard case let .pickSource(slot, _) = try await model("photo;200x230;20-50;200").state else {
            Issue.record("not picking"); return
        }
        #expect(slot.custom && slot.aspect == 200.0 / 230.0)
        #expect(try await model("signature;140x60;10-20;200").state == .notFound)
    }

    @Test("saving keeps no draft and records no Saved status")
    func save() async throws {
        let name = "scanfit.photocustom.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        let prefs = UserPreferences(defaults: defaults)
        let m = try await model("photo;200x230;20-50;200", prefs: prefs)
        await m.imageSelected(photo)
        await m.cropDone()
        await m.renderTask?.value
        guard case let .review(review) = m.state, case let .ready(ready) = review.result else {
            Issue.record("not ready"); return
        }
        #expect(ready.meetsRules)
        #expect(await drafts.retained.isEmpty)
        await m.save()
        #expect(m.exportState == .saved)
        #expect(prefs.savedDocuments.isEmpty)
    }
}

/// Saves through the real export operation over a scripted, always-successful transport.
@MainActor
final class ScriptedExporter: DocumentExporting {
    func save(_ request: ExportRequest) async -> ExportState {
        await ExportOperation.run(request, transport: ScriptedExportTransport("success")).state
    }
}
