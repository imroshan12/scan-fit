import Foundation
import Inspect
import ScanModel

/// "Which exams accept this file?" (ALGORITHMS section 4 / 9.7). Pure and deterministic: file facts + presets in,
/// verdicts out. The Checker, the review screen's match note and the fit engine's property tests all use it.
public enum MatchEngine {
    private static let aspectTolerance = 0.03
    private static let preferredPixelTolerance = 2
    private static let nearMissWindowFraction = 0.5
    private static let convertible: Set<DetectedFormat> = [.png, .webp, .heic]
    private static let grayscaleKinds: Set<DocKind> = [.signature, .thumb, .fingers, .declaration]

    /// Slot types a file of this kind may be matched against (ALGORITHMS 4). PDFs match any slot that accepts PDF.
    public static func slotTypes(for kind: DocKind) -> Set<DocType> {
        switch kind {
        case .photo: [.photo, .postcardPhoto]
        case .signature: [.signature, .tripleSignature]
        case .thumb: [.leftThumb, .thumbImpression]
        case .declaration: [.handwrittenDeclaration]
        case .fingers: [.leftHandFingersThumb, .rightHandFingersThumb]
        case .pdfDocument: []
        }
    }

    private static func slotMatches(_ kind: DocKind, _ slot: DocSpec) -> Bool {
        kind == .pdfDocument ? slot.formats.contains(.pdf) : slotTypes(for: kind).contains(slot.type)
    }

    public static func match(_ file: FileFacts, exams: [Exam], options: MatchOptions = MatchOptions()) -> MatchResult {
        var entries: [MatchEntry] = []
        for exam in exams where exam.status == .active {
            for slot in exam.documents where slotMatches(file.docKind, slot) {
                let e = evaluate(slot, file, options: options)
                if e.verdict == .no || e.verdict == .unknown { continue }
                let unverified = exam.confidence == .low
                let verdict: Verdict = (unverified && e.verdict == .exact) ? .accepted : e.verdict
                entries.append(MatchEntry(
                    examId: exam.id, examName: exam.name, body: exam.body, docType: slot.type,
                    verdict: verdict, unverified: unverified, fix: e.fix, failed: e.failed, issues: e.issues
                ))
            }
        }
        var rank: [String: Int] = [:]
        for (index, id) in options.popularity.enumerated() where rank[id] == nil { rank[id] = index }
        let sorted = entries.sorted { a, b in
            if a.verdict != b.verdict { return a.verdict < b.verdict }
            let ra = rank[a.examId] ?? Int.max
            let rb = rank[b.examId] ?? Int.max
            if ra != rb { return ra < rb }
            return a.examName.unicodeScalars.lexicographicallyPrecedes(b.examName.unicodeScalars)
        }
        return MatchResult(entries: sorted)
    }

    /// The verdict for one file against one slot (confidence is applied by `match`, not here).
    public static func evaluate(
        _ slot: DocSpec,
        _ file: FileFacts,
        options: MatchOptions = MatchOptions()
    ) -> SlotEvaluation {
        let size = slot.sizeKb
        if size.min == nil, size.max == nil, slot.dimensions.mode == .none {
            return SlotEvaluation(verdict: .unknown)
        }
        var failed: [Constraint] = []
        if !formatOk(slot, file) { failed.append(.format) }
        if !sizeOk(slot, file) { failed.append(.sizeKb) }
        if !encodingOk(file, options) { failed.append(.encoding) }
        if !dimsOk(slot, file) { failed.append(.dims) }
        let issues = issues(for: slot, file, failed)
        if failed.isEmpty {
            let verdict: Verdict = (size.max != nil && preferredDimsClose(slot, file)) ? .exact : .accepted
            return SlotEvaluation(verdict: verdict, issues: issues)
        }
        let fix = failed.count == 1 ? fix(for: failed[0], slot, file) : nil
        return SlotEvaluation(verdict: fix != nil ? .nearMiss : .no, failed: failed, fix: fix, issues: issues)
    }

    private static func formatOk(_ slot: DocSpec, _ f: FileFacts) -> Bool {
        switch f.format {
        case .jpeg: slot.formats.contains(.jpg) || slot.formats.contains(.jpeg)
        case .png: slot.formats.contains(.png)
        case .pdf: slot.formats.contains(.pdf)
        default: false
        }
    }

    private static func sizeOk(_ slot: DocSpec, _ f: FileFacts) -> Bool {
        let min = slot.sizeKb.min ?? 0
        return f.kb >= min && (slot.sizeKb.max.map { f.kb <= $0 } ?? true)
    }

    private static func encodingOk(_ f: FileFacts, _ options: MatchOptions) -> Bool {
        guard f.format == .jpeg else { return true }
        if f.progressive { return false }
        switch f.color {
        case .rgb: return true
        case .gray: return options.allowGrayscale && grayscaleKinds.contains(f.docKind)
        default: return false
        }
    }

    private static func dimsOk(_ slot: DocSpec, _ f: FileFacts) -> Bool {
        guard let w = f.width, let h = f.height, f.format != .pdf else { return true }
        let d = slot.dimensions
        switch d.mode {
        case .exact: return w == d.width && h == d.height
        case .preferred: return aspectClose(w, h, d.width, d.height)
        case .range: return insideBox(slot, w, h) && insideAspect(slot, w, h)
        case .none: return true
        }
    }

    private static func aspectClose(_ w: Int, _ h: Int, _ pw: Int?, _ ph: Int?) -> Bool {
        guard let pw, let ph, h != 0, ph != 0 else { return false }
        let actual: Double = Double(w) / Double(h)
        let wanted: Double = Double(pw) / Double(ph)
        return abs(actual / wanted - 1.0) <= aspectTolerance
    }

    private static func insideBox(_ slot: DocSpec, _ w: Int, _ h: Int) -> Bool {
        let d = slot.dimensions
        return w >= (d.minW ?? 0) && w <= (d.maxW ?? Int.max) && h >= (d.minH ?? 0) && h <= (d.maxH ?? Int.max)
    }

    private static func insideAspect(_ slot: DocSpec, _ w: Int, _ h: Int) -> Bool {
        guard let range = slot.dimensions.aspectWOverH else { return true }
        guard h != 0 else { return false }
        let a = Double(w) / Double(h)
        return a >= (range.min ?? 0) && a <= (range.max ?? Double.greatestFiniteMagnitude)
    }

    private static func preferredDimsClose(_ slot: DocSpec, _ f: FileFacts) -> Bool {
        let d = slot.dimensions
        guard d.mode == .preferred, let w = f.width, let h = f.height else { return true }
        guard let pw = d.width, let ph = d.height else { return false }
        return abs(w - pw) <= preferredPixelTolerance && abs(h - ph) <= preferredPixelTolerance
    }

    private static func fix(for constraint: Constraint, _ slot: DocSpec, _ f: FileFacts) -> FixAction? {
        switch constraint {
        case .sizeKb: return sizeFix(slot, f)
        case .format:
            let acceptsJpeg = slot.formats.contains(.jpg) || slot.formats.contains(.jpeg)
            return convertible.contains(f.format) && acceptsJpeg ? .convertToJpeg : nil
        case .encoding: return .reencodeBaseline
        case .dims: return nil // a dims failure needs a new crop, never a one-tap fix
        }
    }

    private static func sizeFix(_ slot: DocSpec, _ f: FileFacts) -> FixAction? {
        let min = slot.sizeKb.min ?? 0
        guard let max = slot.sizeKb.max else { return nil }
        let limit = (max - min) * nearMissWindowFraction
        if f.kb > max, f.kb - max <= limit { return .compressToTarget }
        if f.kb < min, min - f.kb <= limit { return .enlargeToTarget }
        return nil
    }

    private static func issues(for slot: DocSpec, _ f: FileFacts, _ failed: [Constraint]) -> [Issue] {
        var out: [Issue] = []
        for constraint in failed {
            switch constraint {
            case .sizeKb:
                out.append(f.kb < (slot.sizeKb.min ?? 0) ? .tooSmallKb : .tooLargeKb)
            case .dims:
                let d = slot.dimensions
                let boxFails = d.mode == .range && !insideBox(slot, f.width ?? 0, f.height ?? 0)
                out.append(d.mode == .exact || boxFails ? .wrongDimensions : .wrongAspect)
            case .encoding:
                out.append(f.progressive ? .progressiveJpeg : (f.color == .cmyk ? .cmykColor : .grayscaleNotAllowed))
            case .format:
                break // no dedicated issue code: HEIC and extension problems come from the inspector
            }
        }
        if let slotDpi = slot.dpi, let dpi = f.dpi, dpi != slotDpi { out.append(.lowDpiMetadata) }
        return out
    }
}
