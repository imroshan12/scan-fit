@testable import Exams
import Foundation
import ScanData
import ScanModel
import Testing
import TestSupport

/// Saves through the real export operation over scripted transports; can fail chosen slots or cancel the picker.
@MainActor
private final class BatchExporter: DocumentExporting {
    var failing: Set<DocType> = []
    var cancels = false
    private(set) var batches: [[ExportRequest]] = []

    func save(_ request: ExportRequest) async -> ExportState {
        await ExportOperation.run(request, transport: ScriptedExportTransport("success")).state
    }

    func saveAll(_ requests: [ExportRequest]) async -> SaveAllOutcome {
        batches.append(requests)
        if cancels { return SaveAllOutcome(states: requests.map { _ in .idle }, folder: nil) }
        var states: [ExportState] = []
        for request in requests {
            let script = failing.contains(request.spec.type) ? "write_failed" : "success"
            states.append(await ExportOperation.run(request, transport: ScriptedExportTransport(script)).state)
        }
        return SaveAllOutcome(states: states, folder: "IBPS-PO")
    }
}

@MainActor
@Suite("Exam Save all")
struct ExamSaveAllTests {
    private let drafts = FakeDraftStore()
    private let exporter = BatchExporter()
    private let kit = FakeKitStore()

    private func model(confirmed: Bool, gate: SaveGate? = nil) async throws -> (ExamViewModel, UserPreferences) {
        let defaults = try #require(UserDefaults(suiteName: "scanfit.exam.saveall.\(UUID().uuidString)"))
        let prefs = UserPreferences(defaults: defaults)
        if confirmed { prefs.confirmHandwriting() }
        await drafts.seed(TestJpeg.make(width: 200, height: 230, size: 34 * 1024), examID: "ibps_po", type: .photo)
        await drafts.seed(TestJpeg.make(width: 140, height: 60, size: 16 * 1024), examID: "ibps_po", type: .signature)
        let bundle = try SpecPresets.bundle()
        let model = ExamViewModel(
            examId: "ibps_po", preferences: prefs, drafts: drafts, exporter: exporter, kit: kit, gate: gate
        ) { bundle }
        await model.onAppear()
        return (model, prefs)
    }

    @Test("every Ready row goes to one picker under its export name and is recorded Saved")
    func savesAll() async throws {
        let (model, prefs) = try await model(confirmed: true)
        #expect(model.saveAllPlan.included == [.photo, .signature])
        await model.saveAll()
        #expect(model.saveAllStatus == .done(saved: 2, total: 2, folder: "IBPS-PO"))
        #expect(exporter.batches.count == 1, "one picker for all files")
        #expect(exporter.batches[0].map(\.filename) == ["photo_IBPS-PO_200x230_34kb.jpg", "signature_IBPS-PO_140x60_16kb.jpg"])
        #expect(prefs.isSaved("ibps_po", .photo) && prefs.isSaved("ibps_po", .signature))
        #expect(model.saveAllPlan.included.isEmpty, "saved rows leave the plan")
    }

    @Test("the whole batch is decided first: too few free saves save nothing (ALGORITHMS 14.1)")
    func spentFreeSaves() async throws {
        let gate = await testSaveGate(used: 19)
        let (model, prefs) = try await model(confirmed: true, gate: gate)
        await model.saveAll()
        #expect(gate.paywall.current == .saveAll)
        #expect(model.saveAllStatus == .idle)
        #expect(exporter.batches.isEmpty)
        #expect(!prefs.isSaved("ibps_po", .photo))
        #expect(gate.state?.used == 19)
    }

    @Test("each saved file spends one free save")
    func spendsPerFile() async throws {
        let gate = await testSaveGate(used: 18)
        let (model, _) = try await model(confirmed: true, gate: gate)
        await model.saveAll()
        #expect(gate.state?.used == 20)
        #expect(gate.paywall.current == nil)
    }

    @Test("a failure does not stop the others; retry writes only the rest")
    func partial() async throws {
        let (model, prefs) = try await model(confirmed: true)
        exporter.failing = [.photo]
        await model.saveAll()
        #expect(model.saveAllStatus == .done(saved: 1, total: 2, folder: "IBPS-PO"))
        #expect(!prefs.isSaved("ibps_po", .photo) && prefs.isSaved("ibps_po", .signature))
        // Export history (ALGORITHMS 13.3) lists only the verified file.
        #expect(await kit.exports.map(\.docType) == [.signature])
        exporter.failing = []
        await model.saveAll()
        #expect(exporter.batches.last?.map(\.spec.type) == [.photo])
        #expect(model.saveAllStatus == .done(saved: 1, total: 1, folder: "IBPS-PO"))
    }

    @Test("cancelling the picker saves and records nothing")
    func cancel() async throws {
        let (model, prefs) = try await model(confirmed: true)
        exporter.cancels = true
        await model.saveAll()
        #expect(model.saveAllStatus == .idle)
        #expect(!prefs.isSaved("ibps_po", .photo) && !prefs.isSaved("ibps_po", .signature))
    }

    @Test("an unconfirmed signature is held; a draft that expired since loading fails only its row")
    func heldAndExpired() async throws {
        let (model, _) = try await model(confirmed: false)
        #expect(model.saveAllPlan.included == [.photo] && model.saveAllPlan.held == [.signature])
        await drafts.seed(nil, examID: "ibps_po", type: .photo)
        await model.saveAll()
        #expect(model.saveAllStatus == .done(saved: 0, total: 1, folder: nil))
        model.saveAllDismissed()
        #expect(model.saveAllStatus == .idle)
    }
}
