import Foundation
import Imaging
@testable import InkFlow
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

/// Test double for `InkTools`: a fixed decoded raster and a configurable cleanup + fit. Records what it was asked.
final class FakeInkTools: InkTools, @unchecked Sendable {
    private let lock = NSLock()
    private var _decoded: Raster? = Raster.make(1200, 800) { x, y in
        (300...900).contains(x) && (300...500).contains(y) ? 0x202040 : 0xF0EEE8
    }
    private var _fits: [(Pipeline, InkOptions)] = []
    private var _fitInput: Raster?
    private var _quality: InkQuality = .ok
    private var _bytes = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
    private var failure: FitError?

    var decoded: Raster? {
        get { lock.withLock { _decoded } }
        set { lock.withLock { _decoded = newValue } }
    }

    var fits: [(Pipeline, InkOptions)] { lock.withLock { _fits } }
    var fitInput: Raster? { lock.withLock { _fitInput } }

    func produce(_ bytes: [UInt8], quality: InkQuality = .ok) {
        lock.withLock {
            _bytes = bytes
            _quality = quality
            failure = nil
        }
    }

    func fail(_ error: FitError) { lock.withLock { failure = error } }

    func decode(_: [UInt8], maxLongSide _: Int) -> Raster? { decoded }

    func fit(_ cropped: Raster, spec _: DocSpec, pipeline: Pipeline, ink: InkOptions) -> Result<PipelineResult, FitError> {
        lock.withLock {
            _fitInput = cropped
            _fits.append((pipeline, ink))
            if let failure { return .failure(failure) }
            let ink = InkResult(raster: cropped, quality: _quality, coverage: 0.05)
            let fit = FitResult(bytes: _bytes, width: 0, height: 0, quality: 90, strategy: .qualitySearch, encodes: 3)
            return .success(PipelineResult(fit: fit, prepared: cropped, ink: ink))
        }
    }
}

/// Saves through the real export operation over a scripted transport, and remembers the requests.
@MainActor
final class RecordingExporter: DocumentExporting {
    var operation = "success"
    private(set) var requests: [ExportRequest] = []

    func save(_ request: ExportRequest) async -> ExportState {
        requests.append(request)
        return await ExportOperation.run(request, transport: ScriptedExportTransport(operation)).state
    }
}

@MainActor
@Suite("InkFlowViewModel")
struct InkFlowViewModelTests {
    private let tools = FakeInkTools()
    private let exporter = RecordingExporter()
    private let photo: [UInt8] = [1, 2, 3]

    private func preferences(confirmed: Bool = false) -> UserPreferences {
        let name = "scanfit.inktests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        let prefs = UserPreferences(defaults: defaults)
        if confirmed { prefs.confirmHandwriting() }
        return prefs
    }

    private func model(_ exam: String = "ibps_po", _ doc: DocType = .signature,
                       prefs: UserPreferences? = nil) async throws -> InkFlowViewModel {
        let bundle = try SpecPresets.bundle()
        let m = InkFlowViewModel(examId: exam, docType: doc, tools: tools, preferences: prefs ?? preferences(),
                                 exporter: exporter) { bundle }
        await m.onAppear()
        return m
    }

    private func reviewed(_ m: InkFlowViewModel) async throws -> InkReviewState {
        await m.imageSelected(photo)
        await m.cropDone()
        await m.renderTask?.value
        guard case let .review(review) = m.state else { throw Unexpected(state: m.state) }
        return review
    }

    struct Unexpected: Error { let state: InkState }

    @Test("starts at the picker for the exam's ink slot")
    func start() async throws {
        guard case let .pickSource(slot, openFailed) = try await model().state else { Issue.record("not picking"); return }
        #expect(slot.kind == .signature && slot.targetKb == 16 && !openFailed)
    }

    @Test("a photo slot or an unknown exam is not an ink flow")
    func notFound() async throws {
        #expect(try await model("ibps_po", .photo).state == .notFound)
        #expect(try await model("no_such_exam").state == .notFound)
        #expect(try await model("ibps_po", .tripleSignature).state == .notFound)
    }

    @Test("each slot type gets its cleanup variant and match kind")
    func variants() async throws {
        let cases: [(String, DocType, Pipeline, DocKind)] = [
            ("ibps_po", .signature, .signatureCleanup, .signature),
            ("ibps_po", .leftThumb, .thumbCleanup, .thumb),
            ("ibps_po", .handwrittenDeclaration, .documentCleanup, .declaration),
            ("neet_ug", .leftHandFingersThumb, .thumbCleanup, .fingers),
            ("upsc_cse", .tripleSignature, .signatureCleanup, .signature),
        ]
        for (exam, doc, pipeline, kind) in cases {
            let slot = try #require(try await model(exam, doc).state.slot)
            #expect(slot.pipeline == pipeline && slot.kind == kind, "\(exam)/\(doc)")
        }
    }

    @Test("a picked image starts as a whole-image crop; an unreadable file goes back with a message")
    func picking() async throws {
        let m = try await model()
        await m.imageSelected(photo)
        guard case let .crop(crop) = m.state else { Issue.record("no crop"); return }
        #expect(crop.rect == CropRect(x: 0, y: 0, w: 1200, h: 800))
        await m.imageSelected(nil)
        #expect(m.state == .pickSource(try #require(m.state.slot), openFailed: true))
    }

    @Test("corners resize the frame, reset goes back to the whole image, rotate turns it")
    func adjust() async throws {
        let m = try await model()
        await m.imageSelected(photo)
        m.resize(.topLeft, dx: 300, dy: 200)
        guard case let .crop(resized) = m.state else { return }
        #expect(resized.rect == CropRect(x: 300, y: 200, w: 900, h: 600))
        m.move(dx: 5000, dy: 0)
        guard case let .crop(moved) = m.state else { return }
        #expect(moved.rect.x == 300)
        m.resetCrop()
        guard case let .crop(reset) = m.state else { return }
        #expect(reset.rect == CropRect(x: 0, y: 0, w: 1200, h: 800))
        await m.rotate()
        guard case let .crop(rotated) = m.state else { return }
        #expect(rotated.rect == CropRect(x: 0, y: 0, w: 800, h: 1200))
    }

    @Test("review cleans exactly the crop and checks the bytes as the slot's kind")
    func review() async throws {
        let m = try await model()
        await m.imageSelected(photo)
        m.resize(.bottomRight, dx: -200, dy: -100)
        await m.cropDone()
        await m.renderTask?.value
        guard case let .review(review) = m.state, case let .ready(ready) = review.result else {
            Issue.record("not ready"); return
        }
        #expect(tools.fitInput?.width == 1000 && tools.fitInput?.height == 700)
        #expect(ready.kb == 16 && ready.meetsRules)
        #expect(tools.fits.last?.0 == .signatureCleanup && tools.fits.last?.1.crispBlack == true)
    }

    @Test("a file outside the window does not meet the rules; a fit error is shown")
    func failures() async throws {
        tools.produce(TestJpeg.make(width: 140, height: 60, size: 30 * 1024))
        guard case let .ready(ready) = try await reviewed(model()).result else { Issue.record("not ready"); return }
        #expect(!ready.meetsRules)
        tools.fail(.tooDetailed)
        #expect(try await reviewed(model()).result == .failed(.tooDetailed))
    }

    @Test("the quality gate warns but does not block saving")
    func quality() async throws {
        tools.produce(TestJpeg.make(width: 140, height: 60, size: 16 * 1024), quality: .tooFaint)
        let m = try await model(prefs: preferences(confirmed: true))
        guard case let .ready(ready) = try await reviewed(m).result else { Issue.record("not ready"); return }
        #expect(ready.quality == .tooFaint && m.canSave)
    }

    @Test("crisp black is off for a declaration; a thumb has no ink options")
    func options() async throws {
        tools.produce(TestJpeg.make(width: 800, height: 400, size: 80 * 1024))
        let declaration = try await model("ibps_po", .handwrittenDeclaration)
        _ = try await reviewed(declaration)
        #expect(tools.fits.last?.1.crispBlack == false)
        declaration.setCrispBlack(true)
        await declaration.renderTask?.value
        #expect(tools.fits.last?.1.crispBlack == true)

        tools.produce(TestJpeg.make(width: 240, height: 240, size: 38 * 1024))
        let thumb = try await model("ibps_po", .leftThumb)
        let review = try await reviewed(thumb)
        let before = tools.fits.count
        thumb.setCrispBlack(true)
        thumb.setInkFactor(0.3)
        await thumb.renderTask?.value
        #expect(tools.fits.count == before, "a thumb keeps its ridges: no re-render")
        #expect(!review.slot.needsHandwritingConfirmation && thumb.canSave)
    }

    @Test("darker ink snaps to tenths inside the range and is debounced")
    func darkerInk() async throws {
        tools.produce(TestJpeg.make(width: 800, height: 400, size: 80 * 1024))
        let m = try await model("ibps_po", .handwrittenDeclaration)
        _ = try await reviewed(m)
        let before = tools.fits.count
        m.setInkFactor(0.27)
        guard case let .review(snapped) = m.state else { return }
        #expect(abs(snapped.options.inkFactor - 0.3) < 1e-9)
        try await Task.sleep(for: .milliseconds(100)) // well inside the 300 ms debounce
        #expect(tools.fits.count == before, "not fitted on every slider step")
        await m.renderTask?.value
        #expect(abs((tools.fits.last?.1.inkFactor ?? 0) - 0.3) < 1e-9)
        m.setInkFactor(0.66)
        await m.renderTask?.value
        #expect(abs((tools.fits.last?.1.inkFactor ?? 0) - 0.7) < 1e-9)
    }

    @Test("a signature needs the handwriting tick once, then saves as a signature")
    func handwriting() async throws {
        let prefs = preferences()
        let m = try await model(prefs: prefs)
        _ = try await reviewed(m)
        #expect(!m.canSave, "the first signature save waits for the tick")
        await m.save()
        #expect(exporter.requests.isEmpty)
        m.confirmHandwriting()
        #expect(prefs.handwritingConfirmed && m.canSave)
        await m.save()
        #expect(m.exportState == .saved)
        #expect(prefs.isSaved("ibps_po", .signature))
        #expect(exporter.requests.last?.kind == .signature)
        #expect(exporter.requests.last?.filename == "signature_IBPS-PO_140x60_16kb.jpg")
    }

    @Test("a remembered tick is not asked again")
    func remembered() async throws {
        let m = try await model(prefs: preferences(confirmed: true))
        let review = try await reviewed(m)
        #expect(review.handwritingConfirmed && m.canSave)
    }

    @Test("a failed verification keeps the review for a retry")
    func retry() async throws {
        let m = try await model(prefs: preferences(confirmed: true))
        _ = try await reviewed(m)
        exporter.operation = "corrupt"
        await m.save()
        #expect(m.exportState == .verifyFailed)
        exporter.operation = "success"
        await m.save()
        #expect(m.exportState == .saved)
    }

    @Test("back steps through the flow, then leaves")
    func back() async throws {
        let m = try await model()
        #expect(!m.back(), "the picker is the first step")
        _ = try await reviewed(m)
        #expect(m.back())
        guard case .crop = m.state else { Issue.record("not back on the crop"); return }
        #expect(m.back())
        guard case .pickSource = m.state else { Issue.record("not back on the picker"); return }
        #expect(!m.back())
    }
}
