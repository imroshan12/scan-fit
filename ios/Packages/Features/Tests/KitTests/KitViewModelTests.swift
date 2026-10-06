import Foundation
import Kit
import Match
import ScanData
import ScanModel
import TestSupport
import Testing

@MainActor
@Suite("Kit view model")
struct KitViewModelTests {
    @Test("loading, verified empty and trusted bundle failure are distinct")
    func states() async throws {
        let bundle = try SpecPresets.bundle()
        let empty = KitViewModel { bundle }
        #expect(empty.state == .loading)
        await empty.refresh()
        #expect(empty.state == .empty)
        let failed = KitViewModel { nil }
        #expect(failed.state == .loading)
        await failed.refresh()
        #expect(failed.state == .failed)
    }

    @Test("prepared photo and ink rows keep bundle and document order despite Home preferences")
    func preparedRows() async throws {
        let ese = try KitFixtures.exam("upsc_ese")
        let ibps = try KitFixtures.exam("ibps_po")
        let bundle = KitFixtures.bundle([ese, ibps])
        let defaults = try #require(UserDefaults(suiteName: "scanfit.kit.\(UUID().uuidString)"))
        let preferences = UserPreferences(defaults: defaults)
        preferences.setShowUnverified(false)
        preferences.recordSaved(ibps.id, .photo)
        let before = defaults.dictionaryRepresentation() as NSDictionary
        let store = FakeDraftStore()
        await store.seed(TestJpeg.make(width: 240, height: 180, size: 68 * 1024 + 600),
                         examID: ese.id, type: .tripleSignature)
        await store.seed(TestJpeg.make(width: 200, height: 230, size: 34 * 1024), examID: ibps.id, type: .photo)
        await store.seed(TestJpeg.make(width: 140, height: 60, size: 16 * 1024), examID: ibps.id, type: .signature)
        await store.seed(TestJpeg.make(width: 240, height: 180, size: 128 * 1024), examID: ese.id, type: .photo)
        await store.seed([1, 2, 3], examID: "untrusted", type: .photo)
        let model = KitViewModel(drafts: store) { bundle }
        await model.refresh()
        let groups = try KitFixtures.groups(model)
        #expect(groups.map(\.id) == [ese.id, ibps.id])
        let eseGroup = try #require(groups.first)
        #expect(eseGroup.name == ese.name && eseGroup.confidence == .low)
        #expect(eseGroup.rows.map(\.route) == [
            FlowRoute(examId: ese.id, docType: .photo), FlowRoute(examId: ese.id, docType: .tripleSignature),
        ])
        #expect(eseGroup.rows.map(\.roundedKB) == [128, 69])
        let ibpsGroup = try #require(groups.last)
        #expect(ibpsGroup.rows.map(\.route.docType) == [.photo, .signature])
        #expect(ibpsGroup.rows.map(\.roundedKB) == [34, 16])
        let expectedLoads = bundle.exams.flatMap(\.documents).filter { DocKind.of($0.type) != .pdfDocument }.count
        #expect(await store.loads == expectedLoads)
        #expect(await store.retained.isEmpty)
        #expect(before == defaults.dictionaryRepresentation() as NSDictionary)
        #expect(preferences.isSaved(ibps.id, .photo) && !preferences.isSaved(ese.id, .tripleSignature))
        await model.refresh()
        #expect(try KitFixtures.groups(model) == groups)
    }

    @Test("failed trusted presets retry successfully and never reuse the old bundle")
    func retryAndCurrentPresets() async throws {
        let exam = try KitFixtures.exam("ibps_po")
        let source = KitPresetSource(nil)
        let store = FakeDraftStore()
        await store.seed(TestJpeg.make(width: 200, height: 230, size: 34 * 1024), examID: exam.id, type: .photo)
        let model = KitViewModel(drafts: store) { await source.load() }
        await model.refresh()
        #expect(model.state == .failed)
        #expect(await store.loads == 0)
        await source.set(KitFixtures.bundle([exam]))
        await model.refresh()
        #expect(try KitFixtures.groups(model).count == 1)
        await source.set(KitFixtures.bundle([try KitFixtures.changed(exam, dpi: 300)]))
        await model.refresh()
        #expect(model.state == .empty)
        await source.set(KitFixtures.bundle([try KitFixtures.changed(exam, status: "retired")]))
        let reads = await store.loads
        await model.refresh()
        #expect(model.state == .empty)
        #expect(await store.loads == reads)
        await source.set(KitFixtures.bundle([]))
        await model.refresh()
        #expect(model.state == .empty)
        await source.set(nil)
        await model.refresh()
        #expect(model.state == .failed)
        #expect(await source.loads == 6)
    }
}
