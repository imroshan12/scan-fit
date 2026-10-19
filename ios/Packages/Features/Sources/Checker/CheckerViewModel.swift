import Foundation
import Imaging
import Inspect
import Match
import Observation
import PDF
import ScanData
import ScanModel

/// The picked file as the Checker shows it: what it is and its own problems (ALGORITHMS 5, 12).
public struct CheckedFile: Sendable, Equatable {
    public let name: String
    public let inspected: InspectedFile
    public var kb: Int { (inspected.bytes + 512) / 1024 }
}

/// The file compared with one exam slot.
public struct TargetResult: Sendable, Equatable {
    public let target: CheckerTarget
    public let evaluation: SlotEvaluation
    public let checks: ReviewChecks
    public let status: CheckerStatus
}

/// "Make it fit" (ALGORITHMS 12): nothing yet, working, a fitted copy, or why it could not be made.
public enum FixState: Sendable, Equatable {
    case none, working
    case ready(FixedCopy)
    /// nil = the file could not be decoded.
    case failed(FitError?)
    /// No PDF could be made for a PDF slot.
    case pdfFailed(PdfError)
}

/// A PDF made by "Make it fit": its pages, whether the app wrote it, and its first page for display.
public struct PdfFix: Sendable, Equatable {
    public let pages: Int
    public let written: Bool
    public let preview: Raster?
}

public struct FixedCopy: Sendable, Equatable {
    /// The fitted JPEG, exactly as it would be written.
    public let bytes: [UInt8]
    public let kb: Int
    public let width: Int
    public let height: Int
    public let meetsRules: Bool
    public let checks: ReviewChecks
    public let note: MatchNote
    /// Set when the copy is a PDF made for a PDF slot (ALGORITHMS 6.6).
    public var pdf: PdfFix?
}

/// The pre-submit Checker (ALGORITHMS 12, UI_UX §3): checks a file and fixes a copy on request. Same rules as
/// Android's `CheckerViewModel`.
@MainActor
@Observable
public final class CheckerViewModel {
    public private(set) var opening = false
    public private(set) var openFailed = false
    public private(set) var file: CheckedFile?
    public private(set) var kind: DocKind?
    /// Exams that accept the file as it is, for the chosen kind.
    public private(set) var note = MatchNote.empty
    public private(set) var query = ""
    public private(set) var targets: [CheckerTarget] = []
    public private(set) var result: TargetResult?
    public private(set) var fix: FixState = .none
    public private(set) var exportState: ExportState = .idle
    public internal(set) var share: ShareStatus = .none
    public let exporter: any DocumentExporting
    public let preferences: UserPreferences?

    @ObservationIgnored private let tools: any CheckerTools
    /// Export history (ALGORITHMS 13.3): one entry per verified fixed copy.
    @ObservationIgnored private let kit: any KitStore
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    /// The picked file's bytes, kept out of the observed state; only "Make it fit" decodes them.
    @ObservationIgnored private var bytes: [UInt8]?
    @ObservationIgnored private var generation = 0

    private nonisolated static let maxInputBytes = 64 * 1024 * 1024

    public init(
        tools: any CheckerTools = LiveCheckerTools(), preferences: UserPreferences?,
        exporter: any DocumentExporting = FilesExporter(), kit: any KitStore = NoKitStore(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.tools = tools
        self.kit = kit
        self.preferences = preferences
        self.exporter = exporter
        self.load = load
    }

    /// An exam signature needs the one-time running-handwriting tick before it is saved or shared (ALGORITHMS 9.5).
    public var needsHandwriting: Bool { kind == .signature && !(preferences?.handwritingConfirmed ?? false) }

    public var canSave: Bool {
        guard case let .ready(copy) = fix else { return false }
        return copy.meetsRules && !needsHandwriting && exportState != .saving
    }

    /// The picked file's bytes and name (nil = it could not be read). The name only feeds the extension check.
    public func fileSelected(_ picked: [UInt8]?, name: String) async {
        guard exportState != .saving else { return }
        generation += 1
        let gen = generation
        reset()
        opening = true
        let checked = await Task.detached(priority: .userInitiated) { () -> CheckedFile? in
            guard let picked, picked.count <= Self.maxInputBytes else { return nil }
            return CheckedFile(name: name, inspected: Inspector.inspect(picked, fileName: name))
        }.value
        guard gen == generation else { return }
        opening = false
        bytes = checked == nil ? nil : picked
        file = checked
        openFailed = checked == nil
    }

    /// What the file is (ALGORITHMS 12): the app never guesses. Clears any exam already chosen.
    public func choose(kind: DocKind) async {
        guard let file, exportState != .saving else { return }
        generation += 1
        let gen = generation
        guard let bundle = await load(), gen == generation else { return }
        self.kind = kind
        let facts = FileFacts(file.inspected, docKind: kind)
        note = MatchNote.of(facts, exams: bundle.exams, popularity: bundle.popular ?? [])
        query = ""
        targets = CheckerTargets.of(bundle, kind: kind)
        result = nil
        fix = .none
        exportState = .idle
    }

    public func search(_ text: String) async {
        guard let kind else { return }
        query = text
        guard let bundle = await load(), query == text else { return }
        targets = CheckerTargets.of(bundle, kind: kind, query: text)
    }

    /// Compares the file with one exam slot (ALGORITHMS 9.7, 12).
    public func choose(target: CheckerTarget) {
        guard let file, let kind else { return }
        let evaluation = MatchEngine.evaluate(target.slot, FileFacts(file.inspected, docKind: kind))
        let status = CheckerStatus.of(
            file.inspected.format, slot: target.slot, verdict: evaluation.verdict, unverified: target.exam.isUnverified,
            encrypted: file.inspected.pdfEncrypted
        )
        result = TargetResult(target: target, evaluation: evaluation, checks: .of(evaluation), status: status)
        fix = .none
        exportState = .idle
    }

    /// Back to the exam list for the same file and kind.
    public func changeExam() {
        guard exportState != .saving else { return }
        generation += 1
        result = nil
        fix = .none
        exportState = .idle
    }

    /// Decodes the file and runs the plain §1 pipeline for the chosen slot; the original is never changed.
    public func makeItFit() async {
        guard let result, result.status == .fix, let kind, let source = bytes, fix != .working else { return }
        generation += 1
        let gen = generation
        fix = .working
        exportState = .idle
        let slot = result.target.slot, tools = self.tools
        let bundle = await load()
        let takesJpeg = slot.formats.contains(.jpg) || slot.formats.contains(.jpeg)
        if !takesJpeg || file?.inspected.format == .pdf {
            // A PDF slot (or a PDF for a slot that also takes one): the PDF engine (ALGORITHMS 6.6).
            let outcome = await Task.detached(priority: .userInitiated) {
                await Self.pdfFix(source, slot, kind, bundle, tools)
            }.value
            guard gen == generation else { return }
            fix = outcome
            return
        }
        let outcome: FixState = await Task.detached(priority: .userInitiated) {
            guard let image = tools.decode(source, maxLongSide: Self.decodeCap(slot)) else {
                return FixState.failed(nil)
            }
            switch tools.fit(image, spec: slot) {
            case let .success(fitted): return .ready(Self.copy(fitted.fit.bytes, slot, kind, bundle))
            case let .failure(error): return .failed(error)
            }
        }.value
        guard gen == generation else { return }
        fix = outcome
    }

    public func confirmHandwriting() {
        preferences?.confirmHandwriting()
    }

    /// Saves the fitted copy for the chosen exam slot (ALGORITHMS 1.6); Saved is recorded only after verification.
    public func save() async {
        guard canSave, let request = request() else { return }
        exportState = .saving
        exportState = await exporter.save(request)
        if exportState == .saved, let target = result?.target {
            preferences?.recordSaved(target.exam.id, target.slot.type)
            await kit.recordExport(request, examId: target.exam.id, examName: target.exam.name)
        }
    }

    /// Shares exactly the fitted copy under its export name (ALGORITHMS 1.6.2); same gate as Save.
    public func shareCopy() async {
        guard canSave, let request = request() else { return }
        let staged = fix
        let url = await ShareStager.stage(request.bytes, filename: request.filename)
        guard fix == staged else { return }
        share = url.map { .ready(SharedFile(url: $0)) } ?? .failed
    }

    public func shareHandled() {
        if case .ready = share { share = .none }
    }

    // MARK: - Private

    private func reset() {
        file = nil
        bytes = nil
        kind = nil
        note = .empty
        query = ""
        targets = []
        result = nil
        fix = .none
        exportState = .idle
        share = .none
        openFailed = false
    }

    private func request() -> ExportRequest? {
        guard case let .ready(copy) = fix, let target = result?.target, let kind else { return nil }
        if let pdf = copy.pdf {
            let filename = ExportNaming.pdfFileName(
                examName: target.exam.name, slot: target.slot, bytes: copy.bytes.count
            )
            return ExportRequest(bytes: copy.bytes, filename: filename, spec: target.slot, kind: kind,
                                 pdf: PdfExport(pages: pdf.pages, written: pdf.written))
        }
        let filename = ExportNaming.fileName(
            examName: target.exam.name, slot: target.slot, width: copy.width, height: copy.height,
            bytes: copy.bytes.count
        )
        return ExportRequest(bytes: copy.bytes, filename: filename, spec: target.slot, kind: kind)
    }

    /// "Make it fit" for a PDF slot, checked as the export will check it (ALGORITHMS 6.5).
    private nonisolated static func pdfFix(
        _ source: [UInt8], _ slot: DocSpec, _ kind: DocKind, _ bundle: PresetBundle?, _ tools: any CheckerTools
    ) async -> FixState {
        guard let outcome = try? await tools.makePdf(source, spec: slot) else { return .none }
        switch outcome {
        case let .failed(error): return .pdfFailed(error)
        case let .made(made):
            let written = made.strategy != .unchanged
            let facts = FileFacts(Inspector.inspect(made.bytes), docKind: kind)
            let evaluation = MatchEngine.evaluate(slot, facts)
            let export = PdfExport(pages: made.pages, written: written)
            return .ready(FixedCopy(
                bytes: made.bytes, kb: (made.bytes.count + 512) / 1024, width: 0, height: 0,
                meetsRules: ExportOperation.verifiesPdf(made.bytes, expected: made.bytes, spec: slot, pdf: export),
                checks: .of(evaluation),
                note: bundle.map { MatchNote.of(facts, exams: $0.exams, popularity: $0.popular ?? []) } ?? .empty,
                pdf: PdfFix(pages: made.pages, written: written, preview: made.preview)
            ))
        }
    }

    /// ALGORITHMS 1.1: decode no larger than 2x the largest target dimension (at least 1600 px).
    private nonisolated static func decodeCap(_ spec: DocSpec) -> Int {
        let d = spec.dimensions
        let largest = [d.width, d.height, d.maxW, d.maxH].compactMap { $0 }.max() ?? 0
        return ImageIODecoder.longSideCap(largestTargetDimension: largest)
    }

    /// Re-inspects the fitted bytes and evaluates them as the slot's kind, as the export will be (rule 3).
    private nonisolated static func copy(
        _ bytes: [UInt8], _ slot: DocSpec, _ kind: DocKind, _ bundle: PresetBundle?
    ) -> FixedCopy {
        let inspected = Inspector.inspect(bytes)
        let facts = FileFacts(inspected, docKind: kind)
        let evaluation = MatchEngine.evaluate(slot, facts)
        let accepted = evaluation.verdict == .exact || evaluation.verdict == .accepted
        return FixedCopy(
            bytes: bytes, kb: (bytes.count + 512) / 1024, width: inspected.width ?? 0, height: inspected.height ?? 0,
            meetsRules: accepted && ExportOperation.verifies(bytes, expected: bytes, spec: slot, kind: kind),
            checks: .of(evaluation),
            note: bundle.map { MatchNote.of(facts, exams: $0.exams, popularity: $0.popular ?? []) } ?? .empty
        )
    }
}

#if DEBUG
public extension CheckerViewModel {
    /// A model in a given state, for previews only.
    static func preview(
        file: CheckedFile?, openFailed: Bool = false, kind: DocKind? = nil, note: MatchNote = .empty,
        targets: [CheckerTarget] = [], result: TargetResult? = nil, fix: FixState = .none
    ) -> CheckerViewModel {
        let model = CheckerViewModel(preferences: nil) { nil }
        model.file = file
        model.openFailed = openFailed
        model.kind = kind
        model.note = note
        model.targets = targets
        model.result = result
        model.fix = fix
        return model
    }
}
#endif
