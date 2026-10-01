import Foundation
import Presets
import ScanModel
import TestSupport
import Testing

/// The Phase 0 exit gate for iOS: the embedded presets load and verify, and anything else is refused.
@Suite("Embedded presets")
struct EmbeddedPresetsTests {
    private func devKeyText() throws -> String {
        try SpecFiles.text("signing/dev_public_key.b64")
    }

    /// spec/dist is produced by spec/tools/build_all.sh (CI runs it before the tests).
    private func distFiles() throws -> PresetFiles {
        let bundle = try SpecFiles.data("dist/presets.json")
        let sig = try SpecFiles.text("dist/presets.json.sig")
        return PresetFiles(bundle: bundle, signatureText: sig, publicKeyBase64: try devKeyText())
    }

    @Test("the real bundle in spec/dist verifies against the dev key and decodes")
    func realBundleLoads() async throws {
        let files = try distFiles()
        let repo = PresetsRepository()
        let outcome = await repo.loadEmbedded(files)
        guard case let .ready(summary) = outcome else {
            Issue.record("expected .ready, got \(outcome). Run spec/tools/build_all.sh first.")
            return
        }
        #expect(summary.examCount >= 55)
        #expect(summary.version >= 1)
        let bundle = try #require(await repo.bundle)
        #expect(bundle.exams.contains { $0.id == "ibps_po" })
        #expect(Set(bundle.exams.map(\.id)).count == bundle.exams.count, "exam ids are unique")
    }

    @Test("a bundle signed by another key is rejected and nothing is installed")
    func forgedBundleIsRejected() async throws {
        let files = try distFiles()
        let other = try SpecFiles.text("fixtures/signing/other_public_key.b64")
        let forged = PresetFiles(bundle: files.bundle, signatureText: files.signatureText, publicKeyBase64: other)
        let repo = PresetsRepository()
        #expect(await repo.loadEmbedded(forged) == .failed(.badSignature))
        #expect(await repo.bundle == nil)
    }

    @Test("a one-byte change to the real bundle is rejected")
    func tamperedBundleIsRejected() async throws {
        let files = try distFiles()
        var bytes = files.bundle
        bytes[bytes.count / 3] ^= 0x20
        let tampered = PresetFiles(bundle: bytes, signatureText: files.signatureText, publicKeyBase64: files.publicKeyBase64)
        let repo = PresetsRepository()
        #expect(await repo.loadEmbedded(tampered) == .failed(.badSignature))
    }

    @Test("a build with no embedded snapshot reports it instead of crashing")
    func missingSnapshot() async {
        let repo = PresetsRepository()
        #expect(await repo.loadEmbedded(nil) == .missingEmbedded)
    }

    @Test("installing the same bundle twice is a stale-version refusal, not a replace")
    func monotonicVersions() async throws {
        let repo = PresetsRepository()
        let files = try distFiles()
        _ = await repo.loadEmbedded(files)
        #expect(await repo.loadEmbedded(files) == .failed(.staleVersion))
        #expect(await repo.bundle != nil, "the earlier good bundle stays installed")
    }
}
