import Presets
import Testing
@testable import Home

@MainActor
@Suite("HomeViewModel")
struct HomeViewModelTests {
    @Test("starts loading, then shows the verified presets summary")
    func loadsPresets() async {
        let summary = PresetsSummary(examCount: 55, version: 3)
        let model = HomeViewModel { .ready(summary) }
        #expect(model.state == .loading)
        await model.onAppear()
        #expect(model.state == .ready(summary))
    }

    @Test("a bad signature is a plain failed state, never a crash or a half-trusted bundle")
    func rejectsBadSignature() async {
        let model = HomeViewModel { .failed(.badSignature) }
        await model.onAppear()
        #expect(model.state == .failed)
    }

    @Test("a missing embedded snapshot is a failed state")
    func missingSnapshot() async {
        let model = HomeViewModel { .missingEmbedded }
        await model.onAppear()
        #expect(model.state == .failed)
    }

    @Test("re-appearing does not reload")
    func loadsOnce() async {
        let counter = Counter()
        let model = HomeViewModel {
            await counter.increment()
            return .ready(PresetsSummary(examCount: 1, version: 1))
        }
        await model.onAppear()
        await model.onAppear()
        #expect(await counter.value == 1)
    }
}

private actor Counter {
    private(set) var value = 0
    func increment() { value += 1 }
}
