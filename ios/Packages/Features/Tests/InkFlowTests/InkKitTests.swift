import Foundation
import Imaging
@testable import InkFlow
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

/// Save to My Kit on the ink review, "Use for exam…" and export history (ALGORITHMS 13).
@MainActor
@Suite("Ink flow and My Kit")
struct InkKitTests {
    private let tools = FakeInkTools()
    private let exporter = RecordingExporter()
    private let kit = FakeKitStore()

    private func model(_ doc: DocType = .signature, original: String? = nil) async throws -> InkFlowViewModel {
        let bundle = try SpecPresets.bundle()
        let name = "scanfit.inkkit.\(UUID().uuidString)"
        let prefs = UserPreferences(defaults: UserDefaults(suiteName: name) ?? .standard)
        prefs.confirmHandwriting()
        let m = InkFlowViewModel(
            target: .exam(examId: "ibps_po", docType: doc), tools: tools, preferences: prefs, exporter: exporter,
            kit: kit, originalID: original
        ) { bundle }
        await m.onAppear()
        return m
    }

    private func reviewed(_ m: InkFlowViewModel) async {
        await m.imageSelected([1, 2, 3])
        await m.cropDone()
        await m.renderTask?.value
    }

    @Test("keeps the working picture as the slot's kind")
    func saves() async throws {
        let m = try await model(.leftThumb)
        await reviewed(m)
        await m.saveOriginal()
        #expect(m.kitSave == .saved)
        #expect(await kit.originalsList.first?.kind == .thumb)
    }

    @Test("the preview is the working picture and saves nothing")
    func preview() async throws {
        let m = try await model(.leftThumb)
        await reviewed(m)
        let picture = try #require(await m.originalPreview())
        #expect(max(picture.width, picture.height) <= 720)
        #expect(await kit.originalsList.isEmpty)
    }

    @Test("an original opens at the crop and counts as kept")
    func fromOriginal() async throws {
        guard case let .saved(id) = await kit.saveOriginal(
            kind: .signature, jpeg: TestJpeg.make(width: 800, height: 400, size: 4096)
        ) else { Issue.record("not saved"); return }
        let m = try await model(original: id)
        guard case .crop = m.state else { Issue.record("not cropping: \(m.state)"); return }
        await m.cropDone()
        await m.renderTask?.value
        #expect(m.kitSave == .saved)
    }

    @Test("a verified save is recorded in export history; a failed one is not")
    func history() async throws {
        let m = try await model()
        await reviewed(m)
        exporter.operation = "corrupt"
        await m.save()
        #expect(await kit.exports.isEmpty)
        exporter.operation = "success"
        await m.save()
        #expect(m.exportState == .saved)
        let record = try #require(await kit.exports.first)
        #expect(record.examId == "ibps_po" && record.docType == .signature && record.kind == .signature)
        #expect(record.fileName == exporter.requests.last?.filename)
    }
}
