import Foundation
@testable import InkFlow
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

@MainActor
@Suite("Ink retained drafts")
struct InkDraftTests {
    let drafts = FakeDraftStore()
    let tools = FakeInkTools()
    let exporter = RecordingExporter()

    private func model(prefs: UserPreferences? = nil) throws -> InkFlowViewModel {
        let bundle = try SpecPresets.bundle()
        return InkFlowViewModel(
            examId: "ibps_po", docType: .signature, tools: tools, preferences: prefs,
            exporter: exporter, drafts: drafts
        ) { bundle }
    }

    private func render(_ model: InkFlowViewModel) async {
        await model.onAppear()
        await model.imageSelected([1])
        await model.cropDone()
    }

    private func review(_ model: InkFlowViewModel) throws -> InkReviewState {
        guard case let .review(review) = model.state else { throw Failure() }
        return review
    }

    struct Failure: Error {}

    @Test("a retained signature cannot reopen a retired exam")
    func retiredExam() async throws {
        let bundle = try SpecPresets.bundle()
        var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(bundle)) as? [String: Any])
        var exams = try #require(object["exams"] as? [[String: Any]])
        let index = try #require(exams.firstIndex { $0["id"] as? String == "ibps_po" })
        exams[index]["status"] = "retired"
        object["exams"] = exams
        let retired = try JSONDecoder().decode(PresetBundle.self, from: JSONSerialization.data(withJSONObject: object))
        await drafts.seed(TestJpeg.make(width: 140, height: 60, size: 16 * 1024), examID: "ibps_po", type: .signature)
        let model = InkFlowViewModel(examId: "ibps_po", docType: .signature, drafts: drafts) { retired }
        await model.onAppear()
        #expect(model.state == .notFound && !model.canSave)
        #expect(await drafts.loads == 0)
    }

    @Test("native store relaunch restores exact bytes; foreground revalidates missing drafts")
    func filesystemAndForeground() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let bundle = try SpecPresets.bundle()
        let original = InkFlowViewModel(
            examId: "ibps_po", docType: .signature, tools: tools, exporter: exporter,
            drafts: FileDraftStore(root: root)
        ) { bundle }
        await render(original)
        let fits = tools.fits.count
        let live = try review(original)
        let restored = InkFlowViewModel(
            examId: "ibps_po", docType: .signature, tools: tools, exporter: exporter,
            drafts: FileDraftStore(root: root)
        ) { bundle }
        await restored.onAppear()
        #expect(try review(restored).restored && review(restored).result == live.result)
        #expect(tools.fits.count == fits)
        restored.confirmHandwriting()
        await restored.save()
        guard case let .ready(ready) = live.result else { throw Failure() }
        #expect(exporter.requests.last?.bytes == ready.bytes)
        try FileManager.default.removeItem(at: root)
        await restored.onForeground()
        #expect(restored.state == .pickSource(live.slot, openFailed: false))
    }

    @Test("retention precedes Ready; restored signature skips cleanup and still requires handwriting")
    func restoreAndSave() async throws {
        let suite = "scanfit.ink.drafts.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = UserPreferences(defaults: defaults)
        let original = try model(prefs: prefs)
        await drafts.retainGate.arm()
        let rendering = Task { await render(original) }
        await drafts.retainGate.waitForStart()
        #expect(original.renderPending && !original.canSave)
        await drafts.retainGate.release()
        await rendering.value
        let live = try review(original)
        guard case let .ready(ready) = live.result else { throw Failure() }
        #expect(!prefs.isSaved("ibps_po", .signature))
        let fits = tools.fits.count
        prefs.recordSaved("ibps_po", .signature)
        let restored = try model(prefs: prefs)
        await restored.onAppear()
        let loaded = try review(restored)
        #expect(loaded.restored && loaded.before == nil && loaded.result == live.result)
        #expect(tools.fits.count == fits && restored.exportState == .idle)
        #expect(!restored.canSave && !loaded.handwritingConfirmed)
        await restored.save()
        #expect(exporter.requests.isEmpty)
        restored.setCrispBlack(true)
        restored.setInkFactor(0.3)
        #expect(restored.state == .review(loaded))
        restored.confirmHandwriting()
        #expect(restored.canSave)
        await restored.save()
        #expect(restored.exportState == .saved && exporter.requests.last?.bytes == ready.bytes)
        let relaunched = try model(prefs: UserPreferences(defaults: defaults))
        await relaunched.onAppear()
        #expect(relaunched.exportState == .idle && relaunched.canSave)
    }

    @Test("restored Back and Replace pick a source, keep draft; normal Back crops")
    func navigation() async throws {
        let original = try model()
        await render(original)
        let slot = try review(original).slot
        #expect(original.back())
        guard case .crop = original.state else { throw Failure() }
        let restored = try model()
        await restored.onAppear()
        #expect(restored.back() && restored.state == .pickSource(slot, openFailed: false))
        let replacing = try model()
        await replacing.onAppear()
        replacing.replaceDraft()
        #expect(replacing.state == .pickSource(slot, openFailed: false))
        #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .signature) != nil)
    }

    @Test("retention failure warns while new review remains exportable and old draft survives")
    func failureSaveable() async throws {
        let model = try model()
        await render(model)
        let slot = try review(model).slot
        let old = await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .signature)
        await drafts.setFailure(true)
        tools.produce(TestJpeg.make(width: 140, height: 60, size: 17 * 1024))
        model.setCrispBlack(false)
        await model.renderTask?.value
        model.confirmHandwriting()
        #expect(try review(model).retainFailed && model.canSave)
        await model.save()
        #expect(model.exportState == .saved && exporter.requests.last?.bytes.count == 17 * 1024)
        #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .signature)?.bytes == old?.bytes)
    }

    @Test("full verifier rejects wrong DPI despite a matching ink verdict")
    func fullVerifier() async throws {
        var bytes = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
        bytes[15] = 72
        tools.produce(bytes)
        let model = try model()
        await render(model)
        #expect(try review(model).retainFailed)
        #expect(await drafts.retained.isEmpty)
    }

    @Test("superseded and cancelled retention cannot overwrite the current signature")
    func staleRetention() async throws {
        let model = try model()
        await render(model)
        let slot = try review(model).slot
        await drafts.retainGate.arm()
        tools.produce(TestJpeg.make(width: 140, height: 60, size: 17 * 1024))
        model.setCrispBlack(false)
        let stale = model.renderTask
        await drafts.retainGate.waitForStart()
        tools.produce(TestJpeg.make(width: 140, height: 60, size: 18 * 1024))
        model.setCrispBlack(true)
        await model.renderTask?.value
        await drafts.retainGate.release()
        await stale?.value
        #expect(await drafts.retained.map(\.count) == [16 * 1024, 18 * 1024])
        #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .signature)?.bytes.count == 18 * 1024)
        await drafts.retainGate.arm()
        model.setCrispBlack(false)
        let cancelled = model.renderTask
        await drafts.retainGate.waitForStart()
        #expect(model.back())
        await drafts.retainGate.release()
        await cancelled?.value
        #expect(await drafts.retained.count == 2)
        guard case .crop = model.state else { throw Failure() }
    }

    @Test("late foreground load cannot override Replace; failed export keeps the retained bytes")
    func lateLoadAndFailure() async throws {
        let original = try model()
        await render(original)
        let restored = try model()
        await restored.onAppear()
        let slot = try review(restored).slot
        await drafts.loadGate.arm()
        let refreshing = Task { await restored.onForeground() }
        await drafts.loadGate.waitForStart()
        #expect(restored.draftValidationPending && !restored.canSave)
        restored.replaceDraft()
        await drafts.loadGate.release()
        await refreshing.value
        #expect(restored.state == .pickSource(slot, openFailed: false))
        #expect(!restored.draftValidationPending)
        let saving = try model()
        await saving.onAppear()
        saving.confirmHandwriting()
        exporter.operation = "corrupt"
        await saving.save()
        #expect(saving.exportState == .verifyFailed && saving.canSave)
        #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .signature) != nil)
    }
}
