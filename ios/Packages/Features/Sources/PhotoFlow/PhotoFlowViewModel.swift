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
    public let exporter: any DocumentExporting
    @ObservationIgnored private let preferences: UserPreferences?

    @ObservationIgnored private let tools: any PhotoTools
    @ObservationIgnored private let faces: any FaceDetector
    @ObservationIgnored private let segmenter: any PersonSegmenter
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
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
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.examId = examId
        self.docType = docType
        self.tools = tools
        self.faces = faces
        self.segmenter = segmenter
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
        let slot = PhotoSlot(examId: exam.id, examName: exam.name, unverified: exam.isUnverified, spec: spec)
        state = .pickSource(slot, nil)
    }

    /// The picked or captured file's bytes; nil when it could not be read.
    public func imageSelected(_ bytes: [UInt8]?) async {
        guard exportState != .saving, let slot = state.slot else { return }
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
        switch state {
        case .review:
            renderTask?.cancel()
            generation += 1
            renderPending = false
            exportState = .idle
            state = lastCrop.map { .crop($0) } ?? .pickSource(slot, nil)
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
        guard exportState != .saving, case let .review(review) = state else { return }
        var options = review.options
        change(&options)
        startRender(review.slot, options, debounce: debounce)
    }

    /// Whitening -> strip -> fit -> re-inspect (ALGORITHMS 9.6 order). A newer change cancels the running one. While
    /// typing, the last result stays on screen until the debounce has passed.
    private func startRender(_ slot: PhotoSlot, _ options: PhotoOptions, debounce: Bool) {
        renderTask?.cancel()
        renderPending = true
        if exportState != .saving { exportState = .idle }
        let working = ReviewResult.working(targetKb: slot.targetKb)
        if case let .review(current) = state, debounce {
            state = .review(ReviewState(slot: slot, options: options, result: current.result))
        } else {
            state = .review(ReviewState(slot: slot, options: options, result: working))
        }
        renderTask = Task { [weak self] in
            if debounce {
                try? await Task.sleep(for: Self.typingDebounce)
                guard !Task.isCancelled else { return }
                self?.state = .review(ReviewState(slot: slot, options: options, result: working))
            }
            await self?.render(slot, options)
        }
    }

    private func render(_ slot: PhotoSlot, _ options: PhotoOptions) async {
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
        let name = applied.name.trimmingCharacters(in: .whitespaces)
        let date = applied.date.trimmingCharacters(in: .whitespaces).isEmpty
            ? Self.ddmmyyyy(tools.today())
            : applied.date.trimmingCharacters(in: .whitespaces)
        let prepared = image
        let result: ReviewResult = await Self.background {
            let input = strip ? tools.drawStrip(prepared, name: name, date: date) : prepared
            switch tools.fit(input, spec: spec) {
            case let .success(r): return .ready(Self.ready(r.fit.bytes, spec))
            case let .failure(error): return .failed(error)
            }
        } ?? .failed(.encodingFailed)
        guard !Task.isCancelled else { return }
        renderPending = false
        state = .review(ReviewState(slot: slot, options: applied, result: result))
    }

    public var canSave: Bool {
        guard exportState != .saving, case let .review(review) = state else { return false }
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

    /// Runs image work off the main actor. A thrown error becomes nil.
    private nonisolated static func background<T: Sendable>(
        _ work: @escaping @Sendable () async throws -> T
    ) async -> T? {
        try? await Task.detached(priority: .userInitiated, operation: work).value
    }

    /// ALGORITHMS 1.1: decode no larger than 2x the largest target dimension (at least 1600 px).
    private nonisolated static func decodeCap(_ spec: DocSpec) -> Int {
        let d = spec.dimensions
        let largest = [d.width, d.height, d.maxW, d.maxH].compactMap { $0 }.max() ?? 0
        return ImageIODecoder.longSideCap(largestTargetDimension: largest)
    }

    /// Auto-framing for one face (ALGORITHMS 9.6); else the default crop, and a re-crop ask for several faces.
    private nonisolated static func framed(_ slot: PhotoSlot, _ image: Raster, _ found: [Face]) -> CropState {
        let aspect = slot.aspect ?? Double(image.width) / Double(image.height)
        switch FaceCheck.of(found) {
        case let .single(face):
            let framing = AutoFraming.frame(face, imgW: image.width, imgH: image.height, aspect: aspect)
            return CropState(slot: slot, image: image, rect: framing.crop, tight: framing.coverageAdjusted)
        case .noFace, .several:
            let rect = Geometry.defaultCrop(srcW: image.width, srcH: image.height, aspect: aspect)
            let problem: CropProblem? = found.count > 1 ? .severalFaces : nil
            return CropState(slot: slot, image: image, rect: rect, tight: false, problem: problem)
        }
    }

    /// Re-inspects the fitted bytes and evaluates them against the slot, as the export will be (rule 3).
    private nonisolated static func ready(_ bytes: [UInt8], _ spec: DocSpec) -> ReviewReady {
        let inspected = Inspector.inspect(bytes)
        let verdict = MatchEngine.evaluate(spec, FileFacts(inspected, docKind: .photo)).verdict
        return ReviewReady(
            bytes: bytes,
            kb: (bytes.count + 512) / 1024,
            width: inspected.width ?? 0,
            height: inspected.height ?? 0,
            meetsRules: verdict == .exact || verdict == .accepted
        )
    }

    /// DD/MM/YYYY in the Gregorian calendar with ASCII digits, whatever the app language (ALGORITHMS 2.4).
    nonisolated static func ddmmyyyy(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let c = calendar.dateComponents([.day, .month, .year], from: date)
        func pad(_ n: Int, _ width: Int) -> String {
            let digits = String(n)
            return String(repeating: "0", count: max(0, width - digits.count)) + digits
        }
        return "\(pad(c.day ?? 1, 2))/\(pad(c.month ?? 1, 2))/\(pad(c.year ?? 2000, 4))"
    }
}
