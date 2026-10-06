import Foundation
import Imaging
@testable import PhotoFlow
import ScanData
import ScanModel
import Testing
import TestSupport

@MainActor
final class ExporterSpy: DocumentExporting {
    var operation = "success"
    var held = false
    private(set) var requests: [ExportRequest] = []
    private var continuation: CheckedContinuation<ExportState, Never>?
    private var started: CheckedContinuation<Void, Never>?

    func save(_ request: ExportRequest) async -> ExportState {
        requests.append(request)
        started?.resume()
        started = nil
        if held { return await withCheckedContinuation { continuation = $0 } }
        return await ExportOperation.run(
            request, transport: operation == "cancel" ? nil : ScriptedExportTransport(operation)
        ).state
    }

    func waitForStart() async {
        if !requests.isEmpty { return }
        await withCheckedContinuation { started = $0 }
    }

    func finish(_ state: ExportState) {
        continuation?.resume(returning: state)
        continuation = nil
    }
}

@MainActor
@Suite("Photo flow export")
struct PhotoFlowExportTests {
    let tools = FakePhotoTools()
    let exporter = ExporterSpy()
    let defaults: UserDefaults
    let preferences: UserPreferences

    init() {
        let suite = "scanfit.export.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite) ?? .standard
        preferences = UserPreferences(defaults: defaults)
    }

    private func model(_ examId: String = "ibps_po") async throws -> PhotoFlowViewModel {
        let bundle = try SpecPresets.bundle()
        let model = PhotoFlowViewModel(
            examId: examId, docType: .photo, tools: tools,
            faces: FakeFaceDetector(faces: [Face(x: 400, y: 500, w: 200, h: 240)]),
            preferences: preferences, exporter: exporter
        ) { bundle }
        await model.onAppear()
        await model.imageSelected([1])
        await model.cropDone()
        return model
    }

    @Test("all shared outcomes record Saved only after verified publication and retain review for retry")
    func sharedOutcomes() async throws {
        for test in try ExportCases.load() {
            let fixture = PhotoFlowExportTests()
            fixture.exporter.operation = test.operation
            let model = try await fixture.model()
            #expect(model.exportState == .idle)
            await model.save()
            #expect(model.exportState.rawValue == test.expect.state, "\(test.id)")
            #expect(fixture.preferences.isSaved("ibps_po", .photo) == test.expect.record, "\(test.id)")
            #expect(UserPreferences(defaults: fixture.defaults).isSaved("ibps_po", .photo) == test.expect.record)
            guard case .review = model.state else { Issue.record("review lost: \(test.id)"); continue }
            #expect(model.canSave, "retry stays available: \(test.id)")
        }
    }

    @Test("saving rejects duplicates, option changes, new sources and back navigation")
    func busy() async throws {
        exporter.held = true
        let model = try await model()
        let before = model.state
        let saving = Task { await model.save() }
        await exporter.waitForStart()
        #expect(model.exportState == .saving && !model.canSave)
        await model.save()
        model.setName("changed")
        model.setDate("01/01/2027")
        model.setNameDate(true)
        model.setWhiteBackground(true)
        #expect(model.back(), "back is consumed, so the view cannot exit")
        await model.imageSelected(nil)
        #expect(model.state == before && exporter.requests.count == 1)
        #expect(!preferences.isSaved("ibps_po", .photo))
        exporter.finish(.saveFailed)
        await saving.value
        #expect(model.exportState == .saveFailed && model.canSave)
    }

    @Test("a failed save can retry, and later cancellation or failure cannot clear a previous Saved status")
    func retryAndHistory() async throws {
        let model = try await model()
        exporter.operation = "read_failed"
        await model.save()
        #expect(model.exportState == .saveFailed && !preferences.isSaved("ibps_po", .photo))
        exporter.operation = "success"
        await model.save()
        #expect(model.exportState == .saved && preferences.isSaved("ibps_po", .photo))
        exporter.operation = "cancel"
        await model.save()
        #expect(model.exportState == .idle && preferences.isSaved("ibps_po", .photo))
        exporter.operation = "corrupt"
        await model.save()
        #expect(model.exportState == .verifyFailed && preferences.isSaved("ibps_po", .photo))
        model.setName("edit after failure")
        await model.renderTask?.value
        #expect(model.exportState == .idle && preferences.isSaved("ibps_po", .photo))
    }

    @Test("saving during the name/date debounce waits for the latest render, not visible stale bytes")
    func staleRender() async throws {
        let model = try await model()
        model.setNameDate(true)
        await model.renderTask?.value
        guard case let .review(initial) = model.state, case let .ready(old) = initial.result else {
            Issue.record("no initial ready result"); return
        }
        tools.setFit { FakePhotoTools.success($0, FakePhotoTools.jpeg(width: 200, height: 230, size: 35 * 1024)) }
        model.setName("Asha Rao")
        model.setDate("04/10/2026")
        guard case let .review(review) = model.state else {
            Issue.record("no pending review"); return
        }
        #expect(review.result == .working(targetKb: review.slot.targetKb))
        #expect(old.bytes.count == 34 * 1024 && model.renderPending)
        let saving = Task { await model.save() }
        await exporter.waitForStart()
        await saving.value
        let request = try #require(exporter.requests.last)
        #expect(request.bytes.count == 35 * 1024 && request.bytes != old.bytes)
        #expect(request.filename == "photo_IBPS-PO_200x230_35kb.jpg")
        #expect(tools.strips.last?.0 == "Asha Rao" && tools.strips.last?.1 == "04/10/2026")
        #expect(model.exportState == .saved && !model.renderPending)
    }

    @Test("a pending render that fails or no longer matches never opens the destination picker")
    func staleFailure() async throws {
        let model = try await model()
        tools.setFit { _ in .failure(.tooDetailed) }
        model.setName("pending")
        await model.save()
        #expect(model.exportState == .idle && exporter.requests.isEmpty && !model.canSave)
        tools.setFit { FakePhotoTools.success($0, FakePhotoTools.jpeg(width: 200, height: 230, size: 60 * 1024)) }
        model.setDate("pending")
        await model.save()
        #expect(model.exportState == .idle && exporter.requests.isEmpty && !model.canSave)
    }

    @Test("prescribed names and non-exact accepted dimensions are preserved")
    func prescribedNameAndAccepted() async throws {
        tools.setFit { FakePhotoTools.success($0, FakePhotoTools.jpeg(width: 400, height: 460, size: 34 * 1024)) }
        let accepted = try await model()
        await accepted.save()
        #expect(accepted.exportState == .saved)
        #expect(exporter.requests.last?.filename == "photo_IBPS-PO_400x460_34kb.jpg")
        let nta = try await model("jee_main")
        await nta.save()
        #expect(nta.exportState == .saved && exporter.requests.last?.filename == "Photograph.jpg")
    }

    @Test("retaining Before never changes the ready bytes or Saved review")
    func comparisonPreservesExport() async throws {
        let model = try await model()
        let reviewed = model.state
        guard case let .review(review) = reviewed, case let .ready(ready) = review.result else {
            Issue.record("no ready review"); return
        }
        #expect(review.before != nil)
        await model.save()
        #expect(exporter.requests.last?.bytes == ready.bytes)
        #expect(model.exportState == .saved && model.state == reviewed)
    }
}
