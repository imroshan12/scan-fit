import Foundation
import Presets
import ScanModel
import TestSupport
import Testing

/// The Phase 0 exit gate for iOS: the embedded presets pass the format checks and load, and a damaged file is refused.
@Suite("Embedded presets")
struct EmbeddedPresetsTests {
    /// spec/dist is produced by spec/tools/build_all.sh (CI runs it before the tests).
    private func distFiles() throws -> PresetFiles {
        PresetFiles(bundle: try SpecFiles.data("dist/presets.json"))
    }

    @Test("the real bundle in spec/dist loads")
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
        let uniqueIds = Set(bundle.exams.map(\.id))
        #expect(uniqueIds.count == bundle.exams.count, "exam ids are unique")
    }

    @Test("a damaged copy of the real bundle is rejected and nothing is installed")
    func damagedBundleIsRejected() async throws {
        let files = try distFiles()
        let damaged = PresetFiles(bundle: files.bundle.prefix(files.bundle.count / 3))
        let repo = PresetsRepository()
        #expect(await repo.loadEmbedded(damaged) == .failed(.parse))
        #expect(await repo.bundle == nil)
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
