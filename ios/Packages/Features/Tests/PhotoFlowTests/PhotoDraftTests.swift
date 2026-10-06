import Foundation
import Imaging
import Match
@testable import PhotoFlow
import ScanData
import ScanModel
import ScanVision
import Testing
import TestSupport

@MainActor
@Suite("Photo retained drafts")
struct PhotoDraftTests {
    let drafts = FakeDraftStore()
    let tools = FakePhotoTools()
    let faces = FakeFaceDetector(faces: [Face(x: 400, y: 500, w: 200, h: 240)])
    let exporter = ExporterSpy()

    private func model(drafts: (any DraftStore)? = nil, prefs: UserPreferences? = nil) throws -> PhotoFlowViewModel {
        let bundle = try SpecPresets.bundle()
        return PhotoFlowViewModel(
            examId: "ibps_po", docType: .photo, tools: tools, faces: faces,
            preferences: prefs, exporter: exporter, drafts: drafts ?? self.drafts
        ) { bundle }
    }

    private func render(_ model: PhotoFlowViewModel) async {
        await model.onAppear()
        await model.imageSelected([1])
        await model.cropDone()
    }

    private func review(_ model: PhotoFlowViewModel) throws -> ReviewState {
        guard case let .review(review) = model.state else { throw Failure() }
        return review
    }

    struct Failure: Error {}

    @Test("a retained photo cannot reopen a retired exam")
    func retiredExam() async throws {
        let bundle = try SpecPresets.bundle()
        var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(bundle)) as? [String: Any])
        var exams = try #require(object["exams"] as? [[String: Any]])
        let index = try #require(exams.firstIndex { $0["id"] as? String == "ibps_po" })
        exams[index]["status"] = "retired"
        object["exams"] = exams
        let retired = try JSONDecoder().decode(PresetBundle.self, from: JSONSerialization.data(withJSONObject: object))
        await drafts.seed(TestJpeg.make(width: 200, height: 230, size: 34 * 1024), examID: "ibps_po", type: .photo)
        let model = PhotoFlowViewModel(examId: "ibps_po", docType: .photo, drafts: drafts) { retired }
        await model.onAppear()
        #expect(model.state == .notFound && !model.canSave)
        #expect(await drafts.loads == 0)
    }

    @Test("historical Saved does not disable restored Save; foreground rechecks tampered and missing bytes")
    func historyAndForeground() async throws {
        let suite = "scanfit.photo.drafts.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = UserPreferences(defaults: defaults)
        let original = try model(prefs: prefs)
        await render(original)
        #expect(!prefs.isSaved("ibps_po", .photo))
        prefs.recordSaved("ibps_po", .photo)
        let restored = try model(prefs: prefs)
        await restored.onAppear()
        #expect(restored.exportState == .idle && restored.canSave)
        let slot = try review(restored).slot
        await drafts.seed([1, 2], examID: "ibps_po", type: .photo)
        await restored.onAppear()
        #expect(restored.state == .pickSource(slot, nil))
        await drafts.seed(TestJpeg.make(width: 200, height: 230, size: 34 * 1024), examID: "ibps_po", type: .photo)
        let reentered = try model(prefs: prefs)
        await reentered.onAppear()
        await drafts.seed(nil, examID: "ibps_po", type: .photo)
        await reentered.onForeground()
        #expect(reentered.state == .pickSource(slot, nil) && prefs.isSaved("ibps_po", .photo))
    }

    @Test("retention precedes Ready, relaunch restores checks and skips all image processing")
    func retainAndRestore() async throws {
        let original = try model()
        await drafts.retainGate.arm()
        let rendering = Task { await render(original) }
        await drafts.retainGate.waitForStart()
        #expect(original.renderPending)
        #expect(await drafts.retained.isEmpty)
        await drafts.retainGate.release()
        await rendering.value
        let live = try review(original)
        guard case let .ready(ready) = live.result else { throw Failure() }
        #expect(!live.restored && !live.retainFailed && live.before != nil)
        #expect(await drafts.retained == [ready.bytes])
        let calls = faces.calls, input = tools.fitInput
        let restored = try model()
        await restored.onAppear()
        let loaded = try review(restored)
        #expect(loaded.restored && loaded.before == nil && !loaded.retainFailed)
        #expect(loaded.result == live.result)
        #expect(restored.exportState == .idle && restored.canSave)
        #expect(faces.calls == calls && tools.fitInput == input && tools.strips.isEmpty)
        restored.setWhiteBackground(true)
        restored.setNameDate(true)
        restored.setName("ignored")
        #expect(restored.state == .review(loaded))
        await restored.save()
        #expect(exporter.requests.last?.bytes == ready.bytes && restored.exportState == .saved)
    }

    @Test("restored Files export keeps exact retained bytes even when sheet dismissal comes first")
    func realFilesExport() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FileDraftStore(root: root.appendingPathComponent("drafts"))
        let original = try model(drafts: store)
        await render(original)
        let ready = try review(original).result
        let bundle = try SpecPresets.bundle()
        let files = FilesExporter()
        let restored = PhotoFlowViewModel(
            examId: "ibps_po", docType: .photo, tools: tools, faces: faces, exporter: files, drafts: store
        ) { bundle }
        await restored.onAppear()
        let saving = Task { await restored.save() }
        for _ in 0..<10_000 {
            if files.document != nil { break }
            await Task.yield()
        }
        let document = try #require(files.document)
        let destination = root.appendingPathComponent("export.jpg")
        try FileManager.default.copyItem(at: document.url, to: destination)
        files.presentationDismissed()
        files.completed([destination])
        await saving.value
        guard case let .ready(result) = ready else { throw Failure() }
        #expect(try Data(contentsOf: destination) == Data(result.bytes))
        #expect(restored.exportState == .saved)
        let slot = try review(restored).slot
        #expect(await store.load(examID: "ibps_po", spec: slot.spec, kind: .photo)?.bytes == result.bytes)
    }

    @Test("Replace and restored Back pick a source without deleting the old draft; normal Back crops")
    func replaceAndBack() async throws {
        let original = try model()
        await render(original)
        #expect(original.back())
        guard case .crop = original.state else { throw Failure() }
        let restored = try model()
        await restored.onAppear()
        let slot = try review(restored).slot
        #expect(restored.back())
        #expect(restored.state == .pickSource(slot, nil))
        let replacing = try model()
        await replacing.onAppear()
        replacing.replaceDraft()
        #expect(replacing.state == .pickSource(slot, nil))
        #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .photo) != nil)
    }

    @Test("retention failure warns but permits saving and preserves the previous draft")
    func failureSaveable() async throws {
        let original = try model()
        await render(original)
        let slot = try review(original).slot
        let previous = await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .photo)
        await drafts.setFailure(true)
        tools.setFit { FakePhotoTools.success($0, TestJpeg.make(width: 200, height: 230, size: 35 * 1024)) }
        original.setWhiteBackground(false)
        await original.renderTask?.value
        #expect(try review(original).retainFailed && original.canSave)
        await original.save()
        #expect(original.exportState == .saved && exporter.requests.last?.bytes.count == 35 * 1024)
        #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .photo)?.bytes == previous?.bytes)
    }

    @Test("full export verifier rejects privacy metadata even when match and check chips pass")
    func fullVerifier() async throws {
        let model = try model()
        let bytes = TestJpeg.make(width: 200, height: 230, size: 34 * 1024)
        tools.setFit {
            FakePhotoTools.success($0, Array(bytes.prefix(2)) + [0xFF, 0xE1, 0, 8, 69, 120, 105, 102, 0, 0]
                                   + bytes.dropFirst(2))
        }
        await render(model)
        #expect(try review(model).retainFailed)
        #expect(await drafts.retained.isEmpty)
    }

    @Test("cancelled and superseded retain jobs cannot persist or publish obsolete bytes")
    func staleRetention() async throws {
        let model = try model()
        await render(model)
        let slot = try review(model).slot
        await drafts.retainGate.arm()
        tools.setFit { FakePhotoTools.success($0, TestJpeg.make(width: 200, height: 230, size: 35 * 1024)) }
        model.setWhiteBackground(false)
        let stale = model.renderTask
        await drafts.retainGate.waitForStart()
        tools.setFit { FakePhotoTools.success($0, TestJpeg.make(width: 200, height: 230, size: 36 * 1024)) }
        model.setNameDate(false)
        await model.renderTask?.value
        await drafts.retainGate.release()
        await stale?.value
        #expect(await drafts.retained.map(\.count) == [34 * 1024, 36 * 1024])
        #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .photo)?.bytes.count == 36 * 1024)
        await drafts.retainGate.arm()
        model.setNameDate(false)
        let cancelled = model.renderTask
        await drafts.retainGate.waitForStart()
        #expect(model.back())
        await drafts.retainGate.release()
        await cancelled?.value
        guard case .crop = model.state else { throw Failure() }
        #expect(await drafts.retained.count == 2)
    }

    @Test("a late restored load cannot override Replace; cancellation and save failure keep the draft")
    func lateLoad() async throws {
        let original = try model()
        await render(original)
        let restored = try model()
        await restored.onAppear()
        let slot = try review(restored).slot
        await drafts.loadGate.arm()
        let refresh = Task { await restored.onForeground() }
        await drafts.loadGate.waitForStart()
        #expect(restored.draftValidationPending && !restored.canSave)
        restored.replaceDraft()
        await drafts.loadGate.release()
        await refresh.value
        #expect(restored.state == .pickSource(slot, nil))
        #expect(!restored.draftValidationPending)
        let saving = try model()
        await saving.onAppear()
        for operation in ["cancel", "read_failed"] {
            exporter.operation = operation
            await saving.save()
            #expect(try review(saving).restored && saving.canSave)
            #expect(await drafts.load(examID: "ibps_po", spec: slot.spec, kind: .photo) != nil)
        }
    }
}
