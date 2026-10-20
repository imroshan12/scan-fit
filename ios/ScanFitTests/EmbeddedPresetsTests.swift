import Foundation
import Presets
import Testing
@testable import ScanFit

/// Phase 0 exit gate, on the real app bundle: the presets that the build phase embedded pass the format checks and load.
@Suite("Embedded presets in the app bundle")
struct EmbeddedPresetsTests {
    @Test("the app bundle contains the snapshot")
    func fileIsPresent() {
        #expect(PresetFiles.embedded(in: .main) != nil, "run spec/tools/build_all.sh, then rebuild")
    }

    @Test("the embedded snapshot loads")
    func loads() async {
        let repository = PresetsRepository()
        let outcome = await repository.loadEmbedded(PresetFiles.embedded(in: .main))
        guard case let .ready(summary) = outcome else {
            Issue.record("expected .ready, got \(outcome)")
            return
        }
        #expect(summary.examCount >= 55)
    }

    @Test("a damaged copy of the embedded snapshot is rejected")
    func damagedIsRejected() async throws {
        let files = try #require(PresetFiles.embedded(in: .main))
        let damaged = PresetFiles(bundle: files.bundle.prefix(files.bundle.count / 2))
        let outcome = await PresetsRepository().loadEmbedded(damaged)
        #expect(outcome == .failed(.parse))
    }

    @Test("the container loads once and shares the outcome, and exposes the loaded bundle")
    @MainActor
    func containerSharesOutcome() async {
        let container = AppContainer.live()
        let first = await container.presetsOutcome.value
        let second = await container.presetsOutcome.value
        #expect(first == second)
        if case .ready = first {} else { Issue.record("expected .ready, got \(first)") }
        let bundle = await container.trustedBundle()
        #expect(bundle?.exams.count == 55)
        #expect(bundle?.popular?.isEmpty == false, "the bundle carries the popular list")
    }
}
