import Foundation
import Imaging
import Inspect
import Match
import Observation
import ScanData
import ScanModel
import ScanVision

/// The photo flow (ALGORITHMS §2, §9.6): pick → face check → aspect-locked crop → whitening and name/date strip → fit
/// → review. Image work runs off the main actor; a newer action supersedes an older one (`generation`).
@MainActor
@Observable
public final class PhotoFlowViewModel {
    public private(set) var state: PhotoState = .loading
    public let examId: String
    public let docType: DocType
    public private(set) var exportState: ExportState = .idle
    public private(set) var renderPending = false
    public private(set) var draftValidationPending = false
    public let exporter: any DocumentExporting
    @ObservationIgnored private let preferences: UserPreferences?
    @ObservationIgnored private let drafts: any DraftStore

    @ObservationIgnored private let tools: any PhotoTools
    @ObservationIgnored private let faces: any FaceDetector
    @ObservationIgnored private let segmenter: any PersonSegmenter
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    /// Every exam and the popularity order, for the review's match note.
    @ObservationIgnored private var exams: [Exam] = []
    @ObservationIgnored private var popular: [String] = []
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var lastCrop: CropState?
    @ObservationIgnored private var cropped: Raster?
    @ObservationIgnored private var mask: [UInt8]?
    @ObservationIgnored private var maskTried = false
    /// The running review render; tests await it.
    @ObservationIgnored private(set) var renderTask: Task<Void, Never>?

    private nonisolated static let maxInputBytes = 64 * 1024 * 1024
    private static let typingDebounce = Duration.milliseconds(400)

    public init(
        examId: String,
        docType: DocType,
        tools: any PhotoTools = LivePhotoTools(),
        faces: any FaceDetector = VisionFaceDetector(),
        segmenter: any PersonSegmenter = VisionPersonSegmenter(),
        preferences: UserPreferences? = nil,
        exporter: any DocumentExporting = FilesExporter(),
        drafts: any DraftStore = NoDraftStore(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.examId = examId
        self.docType = docType
        self.tools = tools
        self.faces = faces
        self.segmenter = segmenter
        self.preferences = preferences
        self.exporter = exporter
        self.drafts = drafts
        self.load = load
    }

    /// Resolves the trusted slot and revalidates restored reviews on entry.
    public func onAppear() async {
        guard exportState != .saving else { return }
        if case let .review(review) = state, review.restored { await restoreDraft(); return }
        guard state == .loading else { return }
        await restoreDraft()
    }

    /// The picked or captured file's bytes; nil when it could not be read.
    public func imageSelected(_ bytes: [UInt8]?) async {
        guard exportState != .saving, let slot = state.slot else { return }
        draftValidationPending = false
        renderTask?.cancel()
        generation += 1
        let gen = generation
        state = .findingFace(slot)
        let cap = Self.decodeCap(slot.spec)
        let tools = self.tools
        let raster: Raster? = await Self.background {
            guard let bytes, bytes.count <= Self.maxInputBytes else { return nil }
            return tools.decode(bytes, maxLongSide: cap)
        }.flatMap { $0 }
        guard gen == generation else { return }
        guard let raster else {
            state = .pickSource(slot, .openFailed)
            return
        }
        let found = await detect(raster)
        guard gen == generation else { return }
        switch found {
        case nil: state = .pickSource(slot, .failed)
        case let .some(list) where list.isEmpty: state = .pickSource(slot, .noFace)
        case let .some(list): state = .crop(Self.framed(slot, raster, list))
        }
    }

    public func move(dx: Double, dy: Double) {
        updateCrop { CropAdjust.move($0.rect, dx: dx, dy: dy, imgW: $0.image.width, imgH: $0.image.height) }
    }

    public func zoom(_ factor: Double) {
        updateCrop {
            let aspect = $0.slot.aspect ?? Double($0.rect.w) / Double($0.rect.h)
            return CropAdjust.zoom($0.rect, factor: factor, aspect: aspect, imgW: $0.image.width, imgH: $0.image.height)
        }
    }

    public func rotate() async {
        guard case let .crop(crop) = state, !crop.checking else { return }
        generation += 1
        let gen = generation
        let image = crop.image
        guard let rotated = await Self.background({ CropAdjust.rotateClockwise(image) }) else { return }
        let found = await detect(rotated) ?? []
        guard gen == generation else { return }
        state = .crop(Self.framed(crop.slot, rotated, found))
    }

    /// Back to the automatic framing for the current image.
    public func resetCrop() async {
        guard case let .crop(crop) = state, !crop.checking else { return }
        generation += 1
        let gen = generation
        let found = await detect(crop.image) ?? []
        guard gen == generation else { return }
        state = .crop(Self.framed(crop.slot, crop.image, found))
    }

    /// The crop must hold exactly one face (ALGORITHMS 9.6 face count) before the photo is fitted.
    public func cropDone() async {
        guard case var .crop(crop) = state, !crop.checking else { return }
        generation += 1
        let gen = generation
        crop.checking = true
        state = .crop(crop)
        let r = crop.rect, image = crop.image
        guard let cut = await Self.background({ image.crop(x: r.x, y: r.y, width: r.w, height: r.h) }) else { return }
        let found = await detect(cut)
        guard gen == generation else { return }
        crop.checking = false
        switch found.map(FaceCheck.of) {
        case nil:
            crop.problem = .failed
        case .noFace:
            crop.problem = .noFace
        case .several:
            crop.problem = .severalFaces
        case .single:
            crop.problem = nil
            lastCrop = crop
            cropped = cut
            mask = nil
            maskTried = false
            // The strip is off until the user turns it on (ALGORITHMS 2.4); a preset that asks for it shows a hint.
            var options = PhotoOptions()
            options.date = Self.ddmmyyyy(tools.today())
            startRender(crop.slot, options, debounce: false)
            await renderTask?.value
            return
        }
        state = .crop(crop)
    }

    public func setWhiteBackground(_ on: Bool) { updateOptions(debounce: false) { $0.whiteBackground = on } }

    public func setNameDate(_ on: Bool) { updateOptions(debounce: false) { $0.nameDate = on } }

    public func setName(_ name: String) { updateOptions(debounce: true) { $0.name = String(name.prefix(60)) } }

    public func setDate(_ date: String) { updateOptions(debounce: true) { $0.date = String(date.prefix(10)) } }

    /// Back inside the flow: Review -> Crop -> PickSource. false = leave the flow.
    public func back() -> Bool {
        guard exportState != .saving else { return true }
        guard let slot = state.slot else { return false }
        draftValidationPending = false
        switch state {
        case let .review(review):
            renderTask?.cancel()
            generation += 1
            renderPending = false
            exportState = .idle
            state = review.restored ? .pickSource(slot, nil) : lastCrop.map { .crop($0) } ?? .pickSource(slot, nil)
            return true
        case .crop, .findingFace:
            generation += 1
            state = .pickSource(slot, nil)
            return true
        default:
            return false
        }
    }

    // MARK: - Private

    private func updateCrop(_ change: (CropState) -> CropRect) {
        guard case var .crop(crop) = state, !crop.checking else { return }
        crop.rect = change(crop)
        crop.problem = nil
        state = .crop(crop)
    }

    private func updateOptions(debounce: Bool, _ change: (inout PhotoOptions) -> Void) {
        guard exportState != .saving, case let .review(review) = state, !review.restored else { return }
        var options = review.options
        change(&options)
        startRender(review.slot, options, debounce: debounce)
    }

    /// Whitening -> strip -> fit -> re-inspect (ALGORITHMS 9.6 order). A newer change cancels the running one.
    private func startRender(_ slot: PhotoSlot, _ options: PhotoOptions, debounce: Bool) {
        renderTask?.cancel()
        generation += 1
        let gen = generation
        renderPending = true
        if exportState != .saving { exportState = .idle }
        let working = ReviewResult.working(targetKb: slot.targetKb)
        state = .review(ReviewState(slot: slot, options: options, result: working, before: cropped))
        renderTask = Task { [weak self] in
            if debounce {
                try? await Task.sleep(for: Self.typingDebounce)
                guard !Task.isCancelled else { return }
                self?.state = .review(ReviewState(slot: slot, options: options, result: working, before: self?.cropped))
            }
            await self?.render(slot, options, generation: gen)
        }
    }

    private func render(_ slot: PhotoSlot, _ options: PhotoOptions, generation gen: Int) async {
        guard let source = cropped else { return }
        var applied = options
        var image = source
        if options.whiteBackground {
            if let mask = await personMask(source) {
                image = await Self.background { BackgroundWhitening.composite(source, mask: mask) } ?? source
            } else {
                applied.whiteBackground = false
                applied.whiteBackgroundAvailable = false
            }
        }
        let tools = self.tools, spec = slot.spec, strip = applied.nameDate
        let exams = self.exams, popular = self.popular
        let name = applied.name.trimmingCharacters(in: .whitespaces)
        let date = applied.date.trimmingCharacters(in: .whitespaces).isEmpty
            ? Self.ddmmyyyy(tools.today())
            : applied.date.trimmingCharacters(in: .whitespaces)
        let prepared = image
        let result: ReviewResult = await Self.background {
            let input = strip ? tools.drawStrip(prepared, name: name, date: date) : prepared
            switch tools.fit(input, spec: spec) {
            case let .success(r): return .ready(Self.ready(r.fit.bytes, spec, exams, popular))
            case let .failure(error): return .failed(error)
            }
        } ?? .failed(.encodingFailed)
        guard !Task.isCancelled, gen == generation else { return }
        var retainFailed = false
        if case let .ready(ready) = result {
            do {
                try await drafts.retain(ready.bytes, examID: examId, spec: spec, kind: .photo)
            } catch {
                retainFailed = true
            }
        }
        guard !Task.isCancelled, gen == generation else { return }
        renderPending = false
        state = .review(ReviewState(
            slot: slot, options: applied, result: result, before: source, retainFailed: retainFailed
        ))
    }

    public var canSave: Bool {
        guard !draftValidationPending, exportState != .saving, case let .review(review) = state else { return false }
        if renderPending { return true }
        guard case let .ready(ready) = review.result else { return false }
        return ready.meetsRules
    }

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
            bytes: ready.bytes, filename: filename, spec: review.slot.spec, kind: .photo
        ))
        if exportState == .saved { preferences?.recordSaved(examId, docType) }
    }

    private func personMask(_ source: Raster) async -> [UInt8]? {
        if !maskTried {
            maskTried = true
            let segmenter = self.segmenter
            mask = await Self.background { try await segmenter.mask(for: source) }.flatMap { $0 }
        }
        return mask
    }

    /// nil when the detector failed (any error): the flow then offers a retry instead of crashing.
    private func detect(_ raster: Raster) async -> [Face]? {
        let faces = self.faces
        return await Self.background { try await faces.detect(in: raster) }
    }

}

private extension PhotoFlowViewModel {
    func restoreDraft() async {
        generation += 1
        let gen = generation
        draftValidationPending = true
        defer { if gen == generation { draftValidationPending = false } }
        let bundle = await load()
        guard !Task.isCancelled, gen == generation else { return }
        exams = bundle?.exams ?? []
        popular = bundle?.popular ?? []
        guard let exam = exams.first(where: { $0.id == examId && $0.status == .active }),
              let spec = exam.documents.first(where: { $0.type == docType }), DocKind.of(spec.type) == .photo else {
            state = .notFound
            return
        }
        let slot = PhotoSlot(examId: exam.id, examName: exam.name, unverified: exam.isUnverified, spec: spec)
        let draft = await drafts.load(examID: examId, spec: spec, kind: .photo)
        guard !Task.isCancelled, gen == generation else { return }
        let exams = exams, popular = popular
        if let draft, let ready = await Self.background({ Self.ready(draft.bytes, spec, exams, popular) }) {
            guard !Task.isCancelled, gen == generation else { return }
            lastCrop = nil
            cropped = nil
            renderTask = nil
            renderPending = false
            exportState = .idle
            state = .review(ReviewState(slot: slot, options: PhotoOptions(), result: .ready(ready), restored: true))
        } else {
            state = .pickSource(slot, nil)
        }
    }

    /// Re-inspects the fitted bytes and evaluates them against the slot, as the export will be (rule 3).
    nonisolated static func ready(
        _ bytes: [UInt8], _ spec: DocSpec, _ exams: [Exam], _ popular: [String]
    ) -> ReviewReady {
        let inspected = Inspector.inspect(bytes)
        let facts = FileFacts(inspected, docKind: .photo)
        let evaluation = MatchEngine.evaluate(spec, facts)
        return ReviewReady(
            bytes: bytes,
            kb: (bytes.count + 512) / 1024,
            width: inspected.width ?? 0,
            height: inspected.height ?? 0,
            meetsRules: evaluation.verdict == .exact || evaluation.verdict == .accepted,
            note: MatchNote.of(facts, exams: exams, popularity: popular),
            checks: .of(evaluation)
        )
    }
}

public extension PhotoFlowViewModel {
    #if DEBUG
    static func preview(_ review: ReviewState) -> PhotoFlowViewModel {
        let model = PhotoFlowViewModel(examId: review.slot.examId, docType: review.slot.spec.type) { nil }
        model.state = .review(review)
        return model
    }
    #endif
    func replaceDraft() {
        guard exportState != .saving, case let .review(review) = state, review.restored else { return }
        renderTask?.cancel()
        generation += 1
        renderPending = false
        draftValidationPending = false
        exportState = .idle
        lastCrop = nil
        cropped = nil
        state = .pickSource(review.slot, nil)
    }

    func onForeground() async {
        guard exportState != .saving, case let .review(review) = state, review.restored else { return }
        await restoreDraft()
    }
    func observeDrafts() async {
        let stream = await drafts.revisions()
        for await _ in stream {
            guard !Task.isCancelled else { return }
            await onForeground()
        }
    }
}
