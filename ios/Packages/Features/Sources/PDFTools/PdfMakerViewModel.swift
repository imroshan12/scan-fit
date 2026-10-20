import Foundation
import Imaging
import Inspect
import Match
import Observation
import PDF
import ScanData
import ScanModel

/// What the PDF is made for: an exam's PDF slot (records Saved) or a tool's synthetic slot (ALGORITHMS 6.6).
public struct PdfTarget: Sendable, Equatable {
    public let examId: String
    public let examName: String
    public let spec: DocSpec
    /// Low-confidence preset: never "meets the rules" (CLAUDE.md rule 6).
    public let unverified: Bool
    public let exam: Bool

    /// Tools save as exam `pdf` / `PDF` (ALGORITHMS 6.6): a PDF of at most `maxKb`, no minimum, no dimensions.
    public static func tool(_ tool: PdfTool, maxKb: Int) -> PdfTarget {
        let type: DocType = tool == .idCard ? .idCard : .document
        return PdfTarget(examId: "pdf", examName: "PDF", spec: .pdf(type, sizeKb: SizeKB(max: Double(maxKb))),
                         unverified: false, exam: false)
    }
}

/// One added file: a picture (one page) or a PDF (its pages). The bytes stay in the view model.
public struct PdfItem: Sendable, Equatable, Identifiable {
    public let id: Int
    public let document: Bool
    public let pages: Int
    public let kb: Int
    public var thumbnail: Raster?
    public var quarterTurns = 0
    public var crop: CropRect?
    public var cropBase: PixelSize?
}

/// Cropping one picture: the upright, turned picture and the free crop on it (ALGORITHMS 9.5 free crop).
public struct PdfCrop: Sendable, Equatable {
    public let itemId: Int
    public let image: Raster
    public var rect: CropRect
}

/// Why a file was not added.
public enum AddFailure: Sendable, Equatable { case unreadable, encrypted, tooManyPages }

public enum PdfResult: Sendable, Equatable {
    /// The PDF exactly as it will be written, and whether it passes the §6.5 check against the target.
    case ready(
        bytes: [UInt8], kb: Int, pages: Int, dpi: Int?, strategy: PdfStrategy, preview: Raster?, meetsRules: Bool
    )
    case failed(PdfError)
}

/// What is missing before Make PDF, per tool (ALGORITHMS 6.1).
public enum PdfMissing: Sendable, Equatable { case target, files, twoPdfs, bothSides, maxKb }

/// The PDF tools and an exam slot's PDF maker (ALGORITHMS 6, UI_UX §3): one screen, configured by the route. Same rules
/// as Android's `PdfMakerViewModel`.
@MainActor
@Observable
public final class PdfMakerViewModel {
    public static let defaultMaxKb = 200
    public static let quickMaxKb = [100, 200, 300, 500]

    public let tool: PdfTool
    public private(set) var target: PdfTarget?
    public private(set) var notFound = false
    public private(set) var items: [PdfItem] = []
    public private(set) var adding = false
    public private(set) var addFailure: AddFailure?
    public private(set) var options = PdfOptions()
    public private(set) var maxKbText = String(defaultMaxKb)
    public private(set) var crop: PdfCrop?
    /// Non-nil while the PDF is being made.
    public private(set) var making: PdfProgress?
    public private(set) var result: PdfResult?
    public private(set) var exportState: ExportState = .idle
    public internal(set) var share: ShareStatus = .none
    public let exporter: any DocumentExporting

    @ObservationIgnored private let tools: any PdfMakerTools
    @ObservationIgnored private let preferences: UserPreferences?
    @ObservationIgnored private let kit: any KitStore
    /// The free-saves gate (ALGORITHMS 14); `nil` saves and shares without counting (tests, previews).
    @ObservationIgnored private let gate: SaveGate?
    @ObservationIgnored private var bytes: [Int: [UInt8]] = [:]
    @ObservationIgnored private var nextId = 1
    @ObservationIgnored private var job: Task<Void, Never>?

    private nonisolated static let maxInputBytes = 64 * 1024 * 1024
    private nonisolated static let cropCap = 1600
    private nonisolated static let thumbnail = 192.0

    /// `examId` + `docType` for an exam slot (resolved through `load`), else `tool`.
    public init(
        route: PdfRoute, tools: any PdfMakerTools = LivePdfMakerTools(), preferences: UserPreferences?,
        exporter: any DocumentExporting = FilesExporter(), kit: any KitStore = NoKitStore(), gate: SaveGate? = nil,
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.gate = gate
        tool = route.examId == nil ? PdfTool(rawValue: route.tool) ?? .images : .exam
        self.tools = tools
        self.preferences = preferences
        self.exporter = exporter
        self.kit = kit
        if let examId = route.examId, let docType = route.docType {
            Task { await resolve(examId: examId, docType: docType, load: load) }
        } else {
            target = .tool(tool, maxKb: Self.defaultMaxKb)
        }
    }

    private func resolve(examId: String, docType: DocType, load: @Sendable () async -> PresetBundle?) async {
        let bundle = await load()
        guard let exam = bundle?.exams.first(where: { $0.id == examId && $0.status == .active }),
              let spec = exam.documents.first(where: { $0.type == docType }), spec.formats.contains(.pdf) else {
            notFound = true
            return
        }
        target = PdfTarget(examId: exam.id, examName: exam.name, spec: spec, unverified: exam.isUnverified, exam: true)
    }

    /// Tools ask for Max KB, 1–5120 (ALGORITHMS 6.1); an exam slot has its own window.
    public var maxKb: Int? { Int(maxKbText).flatMap { (1...5120).contains($0) ? $0 : nil } }

    public var pages: Int { tool == .idCard ? min(1, items.count) : items.reduce(0) { $0 + $1.pages } }

    public var hasPictures: Bool { items.contains { !$0.document } }

    public var busy: Bool { adding || making != nil || exportState == .saving }

    public var missing: PdfMissing? {
        if tool != .exam, maxKb == nil { return .maxKb }
        if target == nil { return .target }
        if tool == .idCard, items.count < 2 { return .bothSides }
        if tool == .merge, items.count < 2 { return .twoPdfs }
        return items.isEmpty ? .files : nil
    }

    public var canMake: Bool { missing == nil && !busy && result == nil && crop == nil }

    public var canSave: Bool {
        guard case let .ready(_, _, _, _, _, _, meets) = result else { return false }
        return meets && exportState != .saving
    }

    // MARK: Files

    /// Files picked from Photos, Files or the camera (nil bytes = could not be read); `side` is the ID card side.
    public func add(_ files: [[UInt8]?], side: Int? = nil) async {
        guard !files.isEmpty, !busy else { return }
        adding = true
        addFailure = nil
        var failure: AddFailure?
        let tools = self.tools
        for file in files {
            let info = await Task.detached(priority: .userInitiated) { () -> PdfFileInfo? in
                guard let file, file.count <= Self.maxInputBytes else { return nil }
                return tools.describe(file)
            }.value
            guard let file, let info else { failure = .unreadable; continue }
            if info.encrypted { failure = .encrypted; continue }
            if !append(file, info, side: side) { failure = .tooManyPages; break }
        }
        adding = false
        addFailure = failure
    }

    private func append(_ data: [UInt8], _ info: PdfFileInfo, side: Int?) -> Bool {
        let item = PdfItem(id: nextId, document: info.document, pages: info.pages, kb: (data.count + 512) / 1024,
                           thumbnail: info.thumbnail)
        nextId += 1
        var next = items
        switch tool {
        case .compress: next = [item]
        // ID card: replace the side when it is there, otherwise the next side (front, then back).
        case .idCard where side.map { $0 < next.count } ?? false: next[side ?? 0] = item
        case .idCard where next.count >= 2: next[1] = item
        default: next.append(item)
        }
        if tool != .idCard, next.reduce(0, { $0 + $1.pages }) > PdfFit.maxPages { return false }
        bytes[item.id] = data
        edit { $0.items = next }
        bytes = bytes.filter { key, _ in next.contains { $0.id == key } }
        return true
    }

    public func remove(_ id: Int) { edit { model in model.items.removeAll { $0.id == id } } }

    public func move(_ id: Int, by offset: Int) {
        edit { model in
            guard let from = model.items.firstIndex(where: { $0.id == id }), model.items.indices.contains(from + offset)
            else { return }
            model.items.swapAt(from, from + offset)
        }
    }

    /// A quarter turn clockwise; the crop starts again (it was drawn on the unturned picture).
    public func turn(_ id: Int) {
        edit { model in
            guard let i = model.items.firstIndex(where: { $0.id == id }), !model.items[i].document else { return }
            model.items[i].quarterTurns = (model.items[i].quarterTurns + 1) % 4
            model.items[i].crop = nil
            model.items[i].cropBase = nil
            model.items[i].thumbnail = model.items[i].thumbnail.map(CropAdjust.rotateClockwise)
        }
    }

    public func dismissAddFailure() { addFailure = nil }

    // MARK: Crop

    public func startCrop(_ id: Int) async {
        guard let item = items.first(where: { $0.id == id && !$0.document }), let data = bytes[id], !busy else {
            return
        }
        let tools = self.tools, turns = item.quarterTurns
        let image = await Task.detached(priority: .userInitiated) { () -> Raster? in
            guard var raster = tools.decode(data, maxLongSide: Self.cropCap) else { return nil }
            for _ in 0..<turns { raster = CropAdjust.rotateClockwise(raster) }
            return raster
        }.value
        guard let image else { return }
        let whole = CropRect(x: 0, y: 0, w: image.width, h: image.height)
        let rect = item.cropBase == PixelSize(width: image.width, height: image.height) ? item.crop ?? whole : whole
        crop = PdfCrop(itemId: id, image: image, rect: rect)
    }

    public func moveCrop(dx: Double, dy: Double) {
        guard let current = crop else { return }
        crop?.rect = CropAdjust.move(
            current.rect, dx: dx, dy: dy, imgW: current.image.width, imgH: current.image.height
        )
    }

    public func resizeCrop(_ corner: CropCorner, dx: Double, dy: Double) {
        guard let current = crop else { return }
        crop?.rect = CropAdjust.resize(current.rect, corner: corner, dx: dx, dy: dy,
                                       imgW: current.image.width, imgH: current.image.height)
    }

    public func resetCrop() {
        guard let current = crop else { return }
        crop?.rect = CropRect(x: 0, y: 0, w: current.image.width, h: current.image.height)
    }

    public func cancelCrop() { crop = nil }

    public func finishCrop() async {
        guard let current = crop else { return }
        let thumbnail = await Task.detached(priority: .userInitiated) { () -> Raster in
            let r = current.rect
            let cut = current.image.crop(x: r.x, y: r.y, width: r.w, height: r.h)
            let scale = Self.thumbnail / Double(max(cut.width, cut.height))
            guard scale < 1 else { return cut }
            return Resampler.resize(cut, width: max(1, Int(Double(cut.width) * scale)),
                                    height: max(1, Int(Double(cut.height) * scale)))
        }.value
        let whole = current.rect == CropRect(x: 0, y: 0, w: current.image.width, h: current.image.height)
        crop = nil
        edit { model in
            guard let i = model.items.firstIndex(where: { $0.id == current.itemId }) else { return }
            model.items[i].crop = whole ? nil : current.rect
            model.items[i].cropBase = whole ? nil : PixelSize(width: current.image.width, height: current.image.height)
            model.items[i].thumbnail = thumbnail
        }
    }

    // MARK: Options

    public func setGrayscale(_ on: Bool) { edit { $0.options.grayscale = on } }

    public func setCleanup(_ on: Bool) { edit { $0.options.cleanup = on } }

    public func setMaxKb(_ text: String) {
        edit { model in
            model.maxKbText = String(text.filter(\.isNumber).prefix(4))
            if model.tool != .exam { model.target = model.maxKb.map { .tool(model.tool, maxKb: $0) } }
        }
    }

    // MARK: Make, save, share

    /// Makes the PDF off the main actor with progress (ALGORITHMS 6.3); the result is checked like the export.
    public func make() {
        guard canMake, let target else { return }
        let sources: [PdfSource] = items.compactMap { item in
            guard let data = bytes[item.id] else { return nil }
            return item.document ? .document(data)
                : .image(bytes: data, quarterTurns: item.quarterTurns, crop: item.crop, cropBase: item.cropBase)
        }
        let job = PdfJob(tool: tool, sources: sources, sizeKb: target.spec.sizeKb, options: options)
        making = .preparing(done: 0, total: max(1, sources.count))
        addFailure = nil
        let tools = self.tools
        let report: @Sendable (PdfProgress) -> Void = { [self] progress in
            Task { @MainActor in if self.making != nil { self.making = progress } }
        }
        self.job = Task {
            let outcome = try? await tools.make(job, progress: report)
            guard !Task.isCancelled, let outcome else { return }
            making = nil
            result = Self.result(outcome, target: target)
        }
    }

    private nonisolated static func result(_ outcome: PdfOutcome, target: PdfTarget) -> PdfResult {
        switch outcome {
        case let .failed(error): return .failed(error)
        case let .made(pdf):
            let written = pdf.strategy != .unchanged
            let meets = ExportOperation.verifiesPdf(
                pdf.bytes, expected: pdf.bytes, spec: target.spec, pdf: PdfExport(pages: pdf.pages, written: written)
            )
            return .ready(bytes: pdf.bytes, kb: (pdf.bytes.count + 512) / 1024, pages: pdf.pages, dpi: pdf.dpi,
                          strategy: pdf.strategy, preview: pdf.preview, meetsRules: meets)
        }
    }

    /// Back while making: stop, keep the files.
    public func cancelMaking() {
        guard making != nil else { return }
        job?.cancel()
        making = nil
    }

    /// Saves the PDF (ALGORITHMS 6.5); Saved (exam slots) and history are recorded only after verification.
    public func save() async {
        guard canSave, let request = request(), let target,
              gate?.allows([request.bytes], trigger: .save) ?? true else { return }
        exportState = .saving
        exportState = await exporter.save(request)
        if exportState == .saved {
            await gate?.record(request.bytes)
            if target.exam { preferences?.recordSaved(target.examId, target.spec.type) }
            await kit.recordExport(request, examId: target.examId, examName: target.examName)
        }
    }

    /// Shares exactly the made PDF under its export name (ALGORITHMS 1.6.2); same gate as Save.
    public func sharePdf() async {
        guard canSave, let request = request(), gate?.allows([request.bytes], trigger: .share) ?? true else { return }
        let staged = result
        let url = await ShareStager.stage(request.bytes, filename: request.filename)
        if url != nil { await gate?.record(request.bytes) }
        guard result == staged else { return }
        share = url.map { .ready(SharedFile(url: $0)) } ?? .failed
    }

    public func shareHandled() {
        if case .ready = share { share = .none }
    }

    /// The export of the made PDF; nil without a ready result.
    func request() -> ExportRequest? {
        guard case let .ready(data, _, pages, _, strategy, _, _) = result, let target else { return nil }
        let filename = ExportNaming.pdfFileName(examName: target.examName, slot: target.spec, bytes: data.count)
        return ExportRequest(bytes: data, filename: filename, spec: target.spec, kind: .pdfDocument,
                             pdf: PdfExport(pages: pages, written: strategy != .unchanged))
    }

    /// Any change to the files or options drops a made PDF; nothing changes while working or saving.
    private func edit(_ change: (PdfMakerViewModel) -> Void) {
        guard making == nil, exportState != .saving else { return }
        change(self)
        result = nil
        exportState = .idle
        share = .none
    }
}

#if DEBUG
public extension PdfMakerViewModel {
    /// A model in a given state, for previews only.
    static func preview(
        route: PdfRoute, target: PdfTarget?, items: [PdfItem] = [], addFailure: AddFailure? = nil,
        making: PdfProgress? = nil, result: PdfResult? = nil, exportState: ExportState = .idle, crop: PdfCrop? = nil
    ) -> PdfMakerViewModel {
        let model = PdfMakerViewModel(route: route, preferences: nil) { nil }
        model.target = target
        model.items = items
        model.addFailure = addFailure
        model.making = making
        model.result = result
        model.exportState = exportState
        model.crop = crop
        return model
    }
}
#endif
