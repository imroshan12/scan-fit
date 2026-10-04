import Foundation
import Imaging
import Inspect
import Match
import Observation
import ScanData
import ScanModel

/// The ink flow (ALGORITHMS §3, §9.5): pick → free crop → cleanup → pad + fit → review → save. Image work runs off the
/// main actor; a newer action supersedes an older one (`generation`). Same rules as Android's `InkFlowViewModel`.
@MainActor
@Observable
public final class InkFlowViewModel {
    public private(set) var state: InkState = .loading
    public let examId: String
    public let docType: DocType
    public private(set) var exportState: ExportState = .idle
    public private(set) var renderPending = false
    public let exporter: any DocumentExporting

    @ObservationIgnored private let preferences: UserPreferences?
    @ObservationIgnored private let tools: any InkTools
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var lastCrop: InkCropState?
    @ObservationIgnored private var cropped: Raster?
    /// The running review render; tests await it.
    @ObservationIgnored private(set) var renderTask: Task<Void, Never>?

    private nonisolated static let maxInputBytes = 64 * 1024 * 1024
    private static let sliderDebounce = Duration.milliseconds(300)

    public init(
        examId: String,
        docType: DocType,
        tools: any InkTools = LiveInkTools(),
        preferences: UserPreferences? = nil,
        exporter: any DocumentExporting = FilesExporter(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.examId = examId
        self.docType = docType
        self.tools = tools
        self.preferences = preferences
        self.exporter = exporter
        self.load = load
    }

    /// Loads once; later calls (re-appearing views) do nothing.
    public func onAppear() async {
        guard state == .loading else { return }
        let id = examId, type = docType
        guard let exam = await load()?.exams.first(where: { $0.id == id }),
              let spec = exam.documents.first(where: { $0.type == type })
        else {
            state = .notFound
            return
        }
        let slot = InkSlot(examId: exam.id, examName: exam.name, unverified: exam.isUnverified, spec: spec)
        state = slot.kind == .photo || slot.kind == .pdfDocument ? .notFound : .pickSource(slot, openFailed: false)
    }

    /// The picked or captured file's bytes; nil when it could not be read.
    public func imageSelected(_ bytes: [UInt8]?) async {
        guard let slot = state.slot, exportState != .saving else { return }
        generation += 1
        let gen = generation
        state = .opening(slot)
        let cap = Self.decodeCap(slot.spec)
        let tools = self.tools
        let raster: Raster? = await Self.background {
            guard let bytes, bytes.count <= Self.maxInputBytes else { return nil }
            return tools.decode(bytes, maxLongSide: cap)
        }.flatMap { $0 }
        guard gen == generation else { return }
        if let raster {
            state = .crop(InkCropState(slot: slot, image: raster, rect: Self.wholeImage(raster)))
        } else {
            state = .pickSource(slot, openFailed: true)
        }
    }

    public func move(dx: Double, dy: Double) {
        updateCrop { CropAdjust.move($0.rect, dx: dx, dy: dy, imgW: $0.image.width, imgH: $0.image.height) }
    }

    public func resize(_ corner: CropCorner, dx: Double, dy: Double) {
        updateCrop {
            CropAdjust.resize($0.rect, corner: corner, dx: dx, dy: dy, imgW: $0.image.width, imgH: $0.image.height)
        }
    }

    /// Back to the whole image (§9.5: the free crop starts as the whole image).
    public func resetCrop() { updateCrop { Self.wholeImage($0.image) } }

    public func rotate() async {
        guard case let .crop(crop) = state else { return }
        generation += 1
        let gen = generation
        let image = crop.image
        guard let rotated = await Self.background({ CropAdjust.rotateClockwise(image) }), gen == generation else {
            return
        }
        state = .crop(InkCropState(slot: crop.slot, image: rotated, rect: Self.wholeImage(rotated)))
    }

    public func cropDone() async {
        guard case let .crop(crop) = state else { return }
        generation += 1
        let gen = generation
        let r = crop.rect, image = crop.image
        guard let cut = await Self.background({ image.crop(x: r.x, y: r.y, width: r.w, height: r.h) }),
              gen == generation else { return }
        lastCrop = crop
        cropped = cut
        let options = InkReviewOptions(crispBlack: crop.slot.variant == .signature)
        startRender(crop.slot, options, confirmed: preferences?.handwritingConfirmed ?? false, debounce: false)
        await renderTask?.value
    }

    public func setCrispBlack(_ on: Bool) { updateOptions(debounce: false) { $0.crispBlack = on } }

    /// "Darker ink" (§9.5): 0.3–0.9 in steps of 0.1; a slider drag is debounced.
    public func setInkFactor(_ factor: Double) {
        updateOptions(debounce: true) { $0.inkFactor = (min(max(factor, 0.3), 0.9) * 10).rounded() / 10 }
    }

    /// The one-time handwriting tick (§9.5): remembered on the device, never asked again.
    public func confirmHandwriting() {
        guard case var .review(review) = state, !review.handwritingConfirmed else { return }
        review.handwritingConfirmed = true
        state = .review(review)
        preferences?.confirmHandwriting()
    }

    public var canSave: Bool {
        guard exportState != .saving, case let .review(review) = state else { return false }
        guard !review.slot.needsHandwritingConfirmation || review.handwritingConfirmed else { return false }
        if renderPending { return true }
        guard case let .ready(ready) = review.result else { return false }
        return ready.meetsRules
    }

    /// Saves the review (ALGORITHMS 1.6) once the running render has finished; Saved is recorded only after the
    /// exporter verified and published the file.
    public func save() async {
        guard canSave else { return }
        exportState = .saving
        await renderTask?.value
        guard case let .review(review) = state, case let .ready(ready) = review.result, ready.meetsRules else {
            exportState = .idle
            return
        }
        let filename = ExportNaming.fileName(
            examName: review.slot.examName, slot: review.slot.spec,
            width: ready.width, height: ready.height, bytes: ready.bytes.count
        )
        exportState = await exporter.save(ExportRequest(
            bytes: ready.bytes, filename: filename, spec: review.slot.spec, kind: review.slot.kind
        ))
        if exportState == .saved { preferences?.recordSaved(examId, docType) }
    }

    /// Back inside the flow: Review -> Crop -> PickSource. false = leave the flow.
    public func back() -> Bool {
        guard let slot = state.slot else { return false }
        if exportState == .saving { return true }
        switch state {
        case .review:
            renderTask?.cancel()
            generation += 1
            renderPending = false
            exportState = .idle
            state = lastCrop.map { .crop($0) } ?? .pickSource(slot, openFailed: false)
            return true
        case .crop, .opening:
            generation += 1
            state = .pickSource(slot, openFailed: false)
            return true
        default:
            return false
        }
    }

    // MARK: - Private

    private func updateCrop(_ change: (InkCropState) -> CropRect) {
        guard case var .crop(crop) = state else { return }
        crop.rect = change(crop)
        state = .crop(crop)
    }

    private func updateOptions(debounce: Bool, _ change: (inout InkReviewOptions) -> Void) {
        guard exportState != .saving, case let .review(review) = state, review.slot.hasInkOptions else { return }
        var options = review.options
        change(&options)
        exportState = exportState == .saved ? .idle : exportState
        startRender(review.slot, options, confirmed: review.handwritingConfirmed, debounce: debounce)
    }

    /// Cleanup → pad to aspect → fit → re-inspect (§3, §9.5). A newer change cancels the running one. While the slider
    /// moves, the last result stays on screen until the debounce has passed.
    private func startRender(_ slot: InkSlot, _ options: InkReviewOptions, confirmed: Bool, debounce: Bool) {
        renderTask?.cancel()
        renderPending = true
        let working = InkReviewResult.working(targetKb: slot.targetKb)
        let shown: InkReviewResult
        if debounce, case let .review(current) = state { shown = current.result } else { shown = working }
        state = .review(InkReviewState(slot: slot, options: options, result: shown, handwritingConfirmed: confirmed))
        renderTask = Task { [weak self] in
            if debounce {
                try? await Task.sleep(for: Self.sliderDebounce)
                guard !Task.isCancelled else { return }
                self?.setResult(slot, options, working)
            }
            await self?.render(slot, options)
        }
    }

    private func setResult(_ slot: InkSlot, _ options: InkReviewOptions, _ result: InkReviewResult) {
        let confirmed = if case let .review(current) = state { current.handwritingConfirmed } else { false }
        state = .review(InkReviewState(slot: slot, options: options, result: result, handwritingConfirmed: confirmed))
    }

    private func render(_ slot: InkSlot, _ options: InkReviewOptions) async {
        guard let source = cropped else { return }
        let tools = self.tools, spec = slot.spec, pipeline = slot.pipeline, kind = slot.kind
        let ink = InkOptions(crispBlack: options.crispBlack, inkFactor: options.inkFactor)
        let result: InkReviewResult = await Self.background {
            switch tools.fit(source, spec: spec, pipeline: pipeline, ink: ink) {
            case let .success(r): return .ready(Self.ready(r.fit.bytes, spec, kind, r.ink?.quality ?? .ok))
            case let .failure(error): return .failed(error)
            }
        } ?? .failed(.encodingFailed)
        guard !Task.isCancelled else { return }
        renderPending = false
        setResult(slot, options, result)
    }

    /// Runs image work off the main actor. A thrown error becomes nil.
    private nonisolated static func background<T: Sendable>(
        _ work: @escaping @Sendable () async throws -> T
    ) async -> T? {
        try? await Task.detached(priority: .userInitiated, operation: work).value
    }

    private nonisolated static func wholeImage(_ image: Raster) -> CropRect {
        CropRect(x: 0, y: 0, w: image.width, h: image.height)
    }

    /// ALGORITHMS 1.1: decode no larger than 2x the largest target dimension (at least 1600 px).
    private nonisolated static func decodeCap(_ spec: DocSpec) -> Int {
        let d = spec.dimensions
        let largest = [d.width, d.height, d.maxW, d.maxH].compactMap { $0 }.max() ?? 0
        return ImageIODecoder.longSideCap(largestTargetDimension: largest)
    }

    /// Re-inspects the fitted bytes and evaluates them as the slot's kind, as the export will be (rule 3).
    private nonisolated static func ready(
        _ bytes: [UInt8], _ spec: DocSpec, _ kind: DocKind, _ quality: InkQuality
    ) -> InkReady {
        let inspected = Inspector.inspect(bytes)
        let verdict = MatchEngine.evaluate(spec, FileFacts(inspected, docKind: kind)).verdict
        return InkReady(
            bytes: bytes,
            kb: (bytes.count + 512) / 1024,
            width: inspected.width ?? 0,
            height: inspected.height ?? 0,
            meetsRules: verdict == .exact || verdict == .accepted,
            quality: quality
        )
    }
}
