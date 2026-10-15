@testable import Checker
import Foundation
import Imaging
import Inspect
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

/// Test double for `CheckerTools`: a fixed decode and a configurable fit; remembers what it fitted.
final class FakeCheckerTools: CheckerTools, @unchecked Sendable {
    private let lock = NSLock()
    private var storedDecoded: Raster? = Raster.make(600, 300) { _, _ in 0xF0F0F0 }
    private var storedFailure: FitError?
    private var storedFitted: [DocSpec] = []

    var decoded: Raster? {
        get { lock.withLock { storedDecoded } }
        set { lock.withLock { storedDecoded = newValue } }
    }

    var failure: FitError? {
        get { lock.withLock { storedFailure } }
        set { lock.withLock { storedFailure = newValue } }
    }

    var fitted: [DocSpec] { lock.withLock { storedFitted } }

    func decode(_: [UInt8], maxLongSide _: Int) -> Raster? { decoded }

    func fit(_ image: Raster, spec: DocSpec) -> Result<PipelineResult, FitError> {
        lock.withLock {
            storedFitted.append(spec)
            if let storedFailure { return .failure(storedFailure) }
            let bytes = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
            let fit = FitResult(bytes: bytes, width: 0, height: 0, quality: 90, strategy: .qualitySearch, encodes: 3)
            return .success(PipelineResult(fit: fit, prepared: image, ink: nil))
        }
    }
}

/// Saves through the real export operation over a scripted transport, and remembers the requests.
@MainActor
final class RecordingExporter: DocumentExporting {
    private(set) var requests: [ExportRequest] = []

    func save(_ request: ExportRequest) async -> ExportState {
        requests.append(request)
        return await ExportOperation.run(request, transport: ScriptedExportTransport("success")).state
    }
}

@MainActor
@Suite("CheckerViewModel")
struct CheckerViewModelTests {
    private let tools = FakeCheckerTools()
    private let exporter = RecordingExporter()
    private let good = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
    private let big = TestJpeg.make(width: 140, height: 60, size: 30 * 1024)

    private func preferences(confirmed: Bool = true) -> UserPreferences {
        let name = "scanfit.checker.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        let prefs = UserPreferences(defaults: defaults)
        if confirmed { prefs.confirmHandwriting() }
        return prefs
    }

    private func model(_ prefs: UserPreferences? = nil) throws -> CheckerViewModel {
        let bundle = try SpecPresets.bundle()
        return CheckerViewModel(tools: tools, preferences: prefs ?? preferences(), exporter: exporter) { bundle }
    }

    private func ibps(_ m: CheckerViewModel) throws -> CheckerTarget {
        try #require(m.targets.first { $0.exam.id == "ibps_po" })
    }

    @Test("a picked file shows its own problems, with its name")
    func picked() async throws {
        let m = try model()
        await m.fileSelected(big, name: "my-sign.png")
        let file = try #require(m.file)
        #expect(file.kb == 30)
        #expect(file.inspected.issues.contains(.extensionMismatch), "a JPEG named .png")
        #expect(m.kind == nil, "no kind until the user says")
        await m.fileSelected(nil, name: "")
        #expect(m.openFailed && m.file == nil)
    }

    @Test("a kind lists exams and the match note for the file as it is")
    func kind() async throws {
        let m = try model()
        await m.fileSelected(good, name: "sign.jpg")
        await m.choose(kind: .signature)
        #expect(m.note.accepted > 0)
        #expect(m.targets.allSatisfy { DocKind.of($0.slot.type) == .signature })
        await m.search("sbi po")
        #expect(m.targets.first?.exam.id == "sbi_po")
    }

    @Test("a matching file is OK and offers no fix")
    func ok() async throws {
        let m = try model()
        await m.fileSelected(good, name: "sign.jpg")
        await m.choose(kind: .signature)
        m.choose(target: try ibps(m))
        #expect(m.result?.status == .ok)
        await m.makeItFit()
        #expect(tools.fitted.isEmpty, "nothing to fix")
    }

    @Test("a too-big file is fitted, saved for that slot, and the original is untouched")
    func fix() async throws {
        let prefs = preferences()
        let m = try model(prefs)
        let original = big
        await m.fileSelected(original, name: "sign.jpg")
        await m.choose(kind: .signature)
        m.choose(target: try ibps(m))
        let result = try #require(m.result)
        #expect(result.status == .fix && result.evaluation.issues.contains(.tooLargeKb))
        await m.makeItFit()
        guard case let .ready(copy) = m.fix else { Issue.record("not fitted"); return }
        #expect(copy.meetsRules && m.canSave)
        #expect(tools.fitted.map(\.type) == [.signature])
        await m.save()
        #expect(m.exportState == .saved)
        #expect(prefs.isSaved("ibps_po", .signature))
        #expect(exporter.requests.last?.filename == "signature_IBPS-PO_140x60_16kb.jpg")
        #expect(original == big, "the original is not changed")
    }

    @Test("a signature waits for the handwriting tick before save or share")
    func handwriting() async throws {
        let prefs = preferences(confirmed: false)
        let m = try model(prefs)
        await m.fileSelected(big, name: "sign.jpg")
        await m.choose(kind: .signature)
        m.choose(target: try ibps(m))
        await m.makeItFit()
        #expect(!m.canSave)
        await m.save()
        await m.shareCopy()
        #expect(exporter.requests.isEmpty && m.share == .none)
        m.confirmHandwriting()
        #expect(m.canSave)
        await m.shareCopy()
        #expect(m.share.file?.url.lastPathComponent == "signature_IBPS-PO_140x60_16kb.jpg")
        #expect(!prefs.isSaved("ibps_po", .signature), "sharing is not saving")
    }

    @Test("a PDF cannot be fixed; a failed fit and an undecodable image are shown")
    func failures() async throws {
        let m = try model()
        await m.fileSelected(Array("%PDF-1.4\n%%EOF".utf8), name: "doc.pdf")
        await m.choose(kind: .signature)
        m.choose(target: try ibps(m))
        #expect(m.result?.status == .noFix)

        await m.fileSelected(big, name: "sign.jpg")
        await m.choose(kind: .signature)
        m.choose(target: try ibps(m))
        tools.failure = .tooDetailed
        await m.makeItFit()
        #expect(m.fix == .failed(.tooDetailed))
        tools.decoded = nil
        m.changeExam()
        m.choose(target: try ibps(m))
        await m.makeItFit()
        #expect(m.fix == .failed(nil), "an undecodable image")
    }
}
