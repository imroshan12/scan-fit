import Kit
import ScanModel
import TestSupport
import Testing

@MainActor
private final class WeakKitModel {
    weak var value: KitViewModel?

    init(_ model: KitViewModel) { value = model }
}

@MainActor
@Suite("Kit load lifecycle")
struct KitLifecycleTests {
    @Test("superseded bundle loads cannot replace a newer failure")
    func obsoleteBundle() async throws {
        let exam = try KitFixtures.exam("ibps_po")
        let source = KitPresetSource(KitFixtures.bundle([exam]))
        let model = KitViewModel { await source.load() }
        await source.gate.arm()
        let stale = Task { await model.refresh() }
        await source.gate.waitForStart()
        #expect(model.state == .loading)
        await source.set(nil)
        await model.refresh()
        #expect(model.state == .failed)
        await source.gate.release()
        await stale.value
        #expect(model.state == .failed)
    }

    @Test("cancelled and superseded draft reads cannot publish obsolete rows")
    func obsoleteDraft() async throws {
        let exam = try KitFixtures.exam("ibps_po")
        let source = KitPresetSource(KitFixtures.bundle([exam]))
        let store = FakeDraftStore()
        await store.seed(TestJpeg.make(width: 200, height: 230, size: 34 * 1024), examID: exam.id, type: .photo)
        let model = KitViewModel(drafts: store) { await source.load() }
        await store.loadGate.arm()
        let cancelled = Task { await model.refresh() }
        await store.loadGate.waitForStart()
        cancelled.cancel()
        await store.loadGate.release()
        await cancelled.value
        #expect(model.state == .loading)
        await model.refresh()
        #expect(try KitFixtures.groups(model).count == 1)
        await store.loadGate.arm()
        let stale = Task { await model.refresh() }
        await store.loadGate.waitForStart()
        await source.set(KitFixtures.bundle([]))
        await model.refresh()
        #expect(model.state == .empty)
        await store.loadGate.release()
        await stale.value
        #expect(model.state == .empty)
    }

    @Test("store revisions add and remove rows, reload current presets, and release observers on cancellation")
    func revisions() async throws {
        let exam = try KitFixtures.exam("ibps_po")
        let source = KitPresetSource(KitFixtures.bundle([exam]))
        let store = FakeDraftStore()
        var model: KitViewModel? = KitViewModel(drafts: store) { await source.load() }
        let weakModel = WeakKitModel(try #require(model))
        do {
            let observed = try #require(model)
            let observation = Task { await observed.observeDrafts() }
            for _ in 0..<10_000 {
                if observed.state == .empty { break }
                await Task.yield()
            }
            #expect(observed.state == .empty)
            await store.seed(TestJpeg.make(width: 200, height: 230, size: 34 * 1024), examID: exam.id, type: .photo)
            for _ in 0..<10_000 {
                if case .ready = observed.state { break }
                await Task.yield()
            }
            #expect(try KitFixtures.groups(observed).first?.rows.first?.roundedKB == 34)
            await store.seed([1, 2], examID: exam.id, type: .photo)
            for _ in 0..<10_000 {
                if observed.state == .empty { break }
                await Task.yield()
            }
            #expect(observed.state == .empty)
            await source.set(nil)
            await store.seed(nil, examID: exam.id, type: .photo)
            for _ in 0..<10_000 {
                if observed.state == .failed { break }
                await Task.yield()
            }
            #expect(observed.state == .failed)
            observation.cancel()
            await observation.value
        }
        model = nil
        #expect(weakModel.value == nil)
        for _ in 0..<10_000 {
            if await store.subscribers == 0 { break }
            await Task.yield()
        }
        #expect(await store.subscribers == 0)
    }
}
