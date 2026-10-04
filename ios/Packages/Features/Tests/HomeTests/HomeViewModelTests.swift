import Foundation
@testable import Home
import Presets
import ScanData
import ScanModel
import Testing
import TestSupport

@MainActor
@Suite("HomeViewModel")
struct HomeViewModelTests {
    private let summary = PresetsSummary(examCount: 55, version: 2)

    private func preferences(pinned: [String] = [], showUnverified: Bool = false) -> UserPreferences {
        let name = "scanfit.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        let prefs = UserPreferences(defaults: defaults)
        for id in pinned.reversed() { prefs.setPinned(id, true) }
        prefs.setShowUnverified(showUnverified)
        return prefs
    }

    private func readyModel(_ prefs: UserPreferences? = nil) async throws -> HomeViewModel {
        let bundle = try SpecPresets.bundle()
        let summary = summary
        let model = HomeViewModel(preferences: prefs ?? preferences()) {
            .init(outcome: .ready(summary), bundle: bundle)
        }
        await model.onAppear()
        return model
    }

    @Test("starts loading, then shows the verified presets summary")
    func loadsPresets() async throws {
        let bundle = try SpecPresets.bundle()
        let model = HomeViewModel(preferences: preferences()) { [summary] in
            .init(outcome: .ready(summary), bundle: bundle)
        }
        #expect(model.presets == .loading)
        await model.onAppear()
        #expect(model.presets == .ready(summary))
    }

    @Test("a bad signature is a plain failed state, never a crash or a half-trusted bundle")
    func rejectsBadSignature() async {
        let model = HomeViewModel(preferences: preferences()) { .init(outcome: .failed(.badSignature), bundle: nil) }
        await model.onAppear()
        #expect(model.presets == .failed)
        model.query = "ssc"
        #expect(model.results.isEmpty && model.popular.isEmpty)
    }

    @Test("re-appearing does not reload")
    func loadsOnce() async {
        let counter = Counter()
        let model = HomeViewModel(preferences: preferences()) {
            await counter.increment()
            return .init(outcome: .ready(PresetsSummary(examCount: 1, version: 1)), bundle: nil)
        }
        await model.onAppear()
        await model.onAppear()
        #expect(await counter.value == 1)
    }

    @Test("an empty query shows sections: pinned in pin order, popular in bundle order")
    func sections() async throws {
        let model = try await readyModel(preferences(pinned: ["ssc_cgl", "ibps_po"]))
        #expect(model.showsSections)
        #expect(model.pinned.map(\.id) == ["ssc_cgl", "ibps_po"])
        #expect(Array(model.popular.prefix(3)).map(\.id) == ["ibps_po", "sbi_po", "ssc_cgl"])
    }

    @Test("typing searches with the shared engine, Hindi included")
    func search() async throws {
        let model = try await readyModel()
        model.query = "ssc cgl"
        #expect(!model.showsSections)
        #expect(model.results.first?.id == "ssc_cgl")
        model.query = "बैंक"
        #expect(!model.results.isEmpty && model.results.allSatisfy { $0.category == .banking })
    }

    @Test("a category chip browses that category, and tapping it again clears it")
    func categories() async throws {
        let model = try await readyModel()
        model.select(.ssc)
        #expect(!model.results.isEmpty && model.results.allSatisfy { $0.category == .ssc })
        model.select(.ssc)
        #expect(model.showsSections)
    }

    @Test("unverified exams appear only when the setting is on, but a pinned one always shows")
    func unverified() async throws {
        let prefs = preferences(pinned: ["rrb_alp"])
        let model = try await readyModel(prefs)
        model.query = "rrb alp"
        #expect(!model.results.contains { $0.id == "rrb_alp" })
        #expect(model.pinned.map(\.id) == ["rrb_alp"])
        prefs.setShowUnverified(true)
        #expect(model.results.contains { $0.id == "rrb_alp" })
    }
}

private actor Counter {
    private(set) var value = 0
    func increment() { value += 1 }
}
