@testable import Exams
import Foundation
import ScanData
import ScanModel
import Testing
import TestSupport

private actor DraftPresetSource {
    private var bundle: PresetBundle?
    let gate = DraftGate()

    init(_ bundle: PresetBundle?) { self.bundle = bundle }

    func set(_ bundle: PresetBundle?) { self.bundle = bundle }

    func load() async -> PresetBundle? {
        let snapshot = bundle
        await gate.wait()
        return snapshot
    }
}

@MainActor
private final class WeakExamModel {
    weak var value: ExamViewModel?

    init(_ model: ExamViewModel?) { value = model }
}

@MainActor
@Suite("Exam retained Ready rows")
struct ExamDraftTests {
    private let drafts = FakeDraftStore()
    private let photo = TestJpeg.make(width: 200, height: 230, size: 34 * 1024 + 600)

    private func preferences() throws -> UserPreferences {
        let defaults = try #require(UserDefaults(suiteName: "scanfit.exam.drafts.\(UUID().uuidString)"))
        return UserPreferences(defaults: defaults)
    }

    @Test("entry and reentry verify bytes; historical Saved takes precedence with or without draft")
    func precedenceAndReentry() async throws {
        let bundle = try SpecPresets.bundle(), prefs = try preferences()
        let model = ExamViewModel(examId: "ibps_po", preferences: prefs, drafts: drafts) { bundle }
        await model.onAppear()
        #expect(model.status(.photo) == .notStarted && model.readyDocuments.isEmpty)
        await drafts.seed(photo, examID: "ibps_po", type: .photo)
        await model.onAppear()
        #expect(model.readyDocuments == [.photo: 35] && model.status(.photo) == .ready(35))
        prefs.recordSaved("ibps_po", .photo)
        #expect(model.status(.photo) == .saved)
        await drafts.seed(nil, examID: "ibps_po", type: .photo)
        await model.onAppear()
        #expect(model.readyDocuments.isEmpty && model.status(.photo) == .saved)
        #expect(model.status(.signature) == .notStarted)
        await drafts.seed([0xFF, 0xD8], examID: "ibps_po", type: .photo)
        await model.onAppear()
        #expect(model.readyDocuments.isEmpty && model.status(.photo) == .saved)
    }

    @Test("every shared draft status obeys Saved Ready NotStarted precedence")
    func sharedStatuses() async throws {
        let cases = try CasesFile.section("draft_cases"), bundle = try SpecPresets.bundle()
        #expect(cases.count == 11)
        for test in cases {
            let store = FakeDraftStore(), prefs = try preferences()
            if test.bool("saved") == true { prefs.recordSaved("ibps_po", .photo) }
            let expected = try #require(test.dict("expect"))
            if expected.bool("available") == true { await store.seed(photo, examID: "ibps_po", type: .photo) }
            let model = ExamViewModel(examId: "ibps_po", preferences: prefs, drafts: store) { bundle }
            await model.onAppear()
            let status: String
            switch model.status(.photo) {
            case .saved: status = "saved"
            case .ready: status = "ready"
            case .notStarted: status = "not_started"
            }
            #expect(status == expected.string("status"))
        }
    }

    @Test("current trusted preset changes and unavailability remove cached Ready")
    func presetChanges() async throws {
        let bundle = try SpecPresets.bundle()
        let source = DraftPresetSource(bundle)
        let model = ExamViewModel(examId: "ibps_po", preferences: try preferences(), drafts: drafts) {
            await source.load()
        }
        await drafts.seed(photo, examID: "ibps_po", type: .photo)
        await model.onAppear()
        #expect(model.status(.photo) == .ready(35))
        var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(bundle)) as? [String: Any])
        var exams = try #require(object["exams"] as? [[String: Any]])
        let index = try #require(exams.firstIndex { $0["id"] as? String == "ibps_po" })
        var documents = try #require(exams[index]["documents"] as? [[String: Any]])
        let photoIndex = try #require(documents.firstIndex { $0["type"] as? String == "photo" })
        documents[photoIndex]["dpi"] = 300
        exams[index]["documents"] = documents
        object["exams"] = exams
        let changed = try JSONDecoder().decode(PresetBundle.self, from: JSONSerialization.data(withJSONObject: object))
        await source.set(changed)
        await model.onAppear()
        #expect(model.readyDocuments.isEmpty && model.status(.photo) == .notStarted)
        await source.set(nil)
        await model.onAppear()
        #expect(model.state == .notFound && model.readyDocuments.isEmpty)
    }

    @Test("retired exams never publish Ready rows")
    func retiredExam() async throws {
        let bundle = try SpecPresets.bundle()
        var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(bundle)) as? [String: Any])
        var exams = try #require(object["exams"] as? [[String: Any]])
        let index = try #require(exams.firstIndex { $0["id"] as? String == "ibps_po" })
        exams[index]["status"] = "retired"
        object["exams"] = exams
        let retired = try JSONDecoder().decode(PresetBundle.self, from: JSONSerialization.data(withJSONObject: object))
        await drafts.seed(photo, examID: "ibps_po", type: .photo)
        let model = ExamViewModel(examId: "ibps_po", preferences: try preferences(), drafts: drafts) { retired }
        await model.onAppear()
        #expect(model.state == .notFound && model.readyDocuments.isEmpty)
        #expect(await drafts.loads == 0)
    }

    @Test("late preset responses cannot publish stale rows")
    func lateResponse() async throws {
        let source = DraftPresetSource(try SpecPresets.bundle())
        let model = ExamViewModel(examId: "ibps_po", preferences: try preferences(), drafts: drafts) {
            await source.load()
        }
        await drafts.seed(photo, examID: "ibps_po", type: .photo)
        await source.gate.arm()
        let stale = Task { await model.onAppear() }
        await source.gate.waitForStart()
        await source.set(nil)
        await model.onAppear()
        await source.gate.release()
        await stale.value
        #expect(model.state == .notFound && model.readyDocuments.isEmpty)
    }

    @Test("store revisions refresh existing rows and observation cancels without a retained model")
    func revisions() async throws {
        let bundle = try SpecPresets.bundle()
        var model: ExamViewModel? = ExamViewModel(examId: "ibps_po", preferences: try preferences(), drafts: drafts) {
            bundle
        }
        let weakModel = WeakExamModel(model)
        do {
            let observed = try #require(model)
            let observation = Task { await observed.observeDrafts() }
            for _ in 0..<10_000 {
                if await drafts.loads > 0 { break }
                await Task.yield()
            }
            await drafts.seed(photo, examID: "ibps_po", type: .photo)
            for _ in 0..<10_000 {
                if observed.status(.photo) == .ready(35) { break }
                await Task.yield()
            }
            #expect(observed.status(.photo) == .ready(35))
            await drafts.seed([1, 2], examID: "ibps_po", type: .photo)
            for _ in 0..<10_000 {
                if observed.status(.photo) == .notStarted { break }
                await Task.yield()
            }
            #expect(observed.status(.photo) == .notStarted)
            observation.cancel()
            await observation.value
        }
        model = nil
        #expect(weakModel.value == nil)
        for _ in 0..<10_000 {
            if await drafts.subscribers == 0 { break }
            await Task.yield()
        }
        #expect(await drafts.subscribers == 0)
    }
}
