import Foundation
import Presets
import Testing
@testable import ScanFit

/// Phase 0 exit gate, on the real app bundle: the presets that the build phase embedded verify and load.
@Suite("Embedded presets in the app bundle")
struct EmbeddedPresetsTests {
    @Test("the app bundle contains the snapshot, signature and key")
    func filesArePresent() {
        #expect(PresetFiles.embedded(in: .main) != nil, "run spec/tools/build_all.sh, then rebuild")
    }

    @Test("the embedded snapshot verifies and loads")
    func loads() async {
        let repository = PresetsRepository()
        let outcome = await repository.loadEmbedded(PresetFiles.embedded(in: .main))
        guard case let .ready(summary) = outcome else {
            Issue.record("expected .ready, got \(outcome)")
            return
        }
        #expect(summary.examCount >= 55)
    }

    @Test("a tampered copy of the embedded snapshot is rejected")
    func tamperedIsRejected() async throws {
        let files = try #require(PresetFiles.embedded(in: .main))
        var bytes = files.bundle
        bytes[bytes.count / 2] ^= 0x01
        let tampered = PresetFiles(bundle: bytes, signatureText: files.signatureText, publicKeyBase64: files.publicKeyBase64)
        let outcome = await PresetsRepository().loadEmbedded(tampered)
        #expect(outcome == .failed(.badSignature))
    }

    @Test("the container verifies once and shares the outcome, and exposes the trusted bundle")
    @MainActor
    func containerSharesOutcome() async {
        let container = AppContainer.live()
        let first = await container.presetsOutcome.value
        let second = await container.presetsOutcome.value
        #expect(first == second)
        if case .ready = first {} else { Issue.record("expected .ready, got \(first)") }
        let bundle = await container.trustedBundle()
        #expect(bundle?.exams.count == 55)
        #expect(bundle?.popular?.isEmpty == false, "the signed bundle carries the popular list")
    }
}
