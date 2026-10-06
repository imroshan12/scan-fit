import Foundation
import Inspect
import ScanModel

/// What the file is for. It comes from the flow that produced the file, or from the user's pick in the
/// Checker (ALGORITHMS 4).
public enum DocKind: String, Sendable, Equatable, CaseIterable {
    case photo, signature, thumb, declaration, fingers
    case pdfDocument = "pdf_document"

    /// The kind a slot of `type` is matched and checked as (ALGORITHMS 4 / 9.5); PDF-only slots are documents.
    public static func of(_ type: DocType) -> DocKind {
        allCases.first { MatchEngine.slotTypes(for: $0).contains(type) } ?? .pdfDocument
    }
}

public enum Verdict: Int, Sendable, Equatable, Comparable {
    case exact, accepted, nearMiss, no, unknown

    public static func < (lhs: Verdict, rhs: Verdict) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// One-tap fixes offered for a near miss. The raw value is the name used in the shared cases.
public enum FixAction: String, Sendable, Equatable {
    case compressToTarget = "compress_to_target"
    case enlargeToTarget = "enlarge_to_target"
    case convertToJpeg = "convert_to_jpeg"
    case reencodeBaseline = "reencode_baseline"
}

/// The four hard constraints of section 9.7. The raw value matches `param` in the shared cases.
public enum Constraint: String, Sendable, Equatable {
    case format
    case sizeKb = "size_kb"
    case encoding
    case dims
}

/// The facts about a file that matching needs. `width`/`height` are nil for PDFs. `dpi` is only used for an
/// advisory issue.
public struct FileFacts: Sendable, Equatable {
    public var format: DetectedFormat
    public var kb: Double
    public var width: Int?
    public var height: Int?
    public var color: ColorKind
    public var progressive: Bool
    public var docKind: DocKind
    public var dpi: Int?

    public init(format: DetectedFormat, kb: Double, width: Int?, height: Int?, color: ColorKind,
                progressive: Bool, docKind: DocKind, dpi: Int? = nil) {
        self.format = format
        self.kb = kb
        self.width = width
        self.height = height
        self.color = color
        self.progressive = progressive
        self.docKind = docKind
        self.dpi = dpi
    }

    public init(_ file: InspectedFile, docKind: DocKind) {
        let dpi = file.jfif.flatMap { $0.units == 1 ? $0.xDensity : nil }
        self.init(format: file.format, kb: file.kb, width: file.width, height: file.height, color: file.color,
                  progressive: file.progressive, docKind: docKind, dpi: dpi)
    }
}

public struct MatchOptions: Sendable, Equatable {
    /// Remote Config `allow_grayscale_docs`.
    public var allowGrayscale: Bool
    /// Exam ids in popularity order (Remote Config `popular_exam_order`).
    public var popularity: [String]

    public init(allowGrayscale: Bool = false, popularity: [String] = []) {
        self.allowGrayscale = allowGrayscale
        self.popularity = popularity
    }
}

/// The verdict for one file against one slot of one exam.
public struct SlotEvaluation: Sendable, Equatable {
    public var verdict: Verdict
    public var failed: [Constraint] = []
    public var fix: FixAction?
    /// Slot-specific issue codes for the Checker (ALGORITHMS 5): TOO_SMALL_KB ... GRAYSCALE_NOT_ALLOWED.
    public var issues: [Issue] = []

    public init(verdict: Verdict, failed: [Constraint] = [], fix: FixAction? = nil, issues: [Issue] = []) {
        self.verdict = verdict
        self.failed = failed
        self.fix = fix
        self.issues = issues
    }
}

public struct MatchEntry: Sendable, Equatable {
    public let examId: String
    public let examName: String
    public let body: String
    public let docType: DocType
    public let verdict: Verdict
    /// True for low-confidence exams: shown as "likely OK", never counted in the headline numbers.
    public let unverified: Bool
    public let fix: FixAction?
    public let failed: [Constraint]
    public let issues: [Issue]
    /// The slot's size window, for the match note's "needs ≤ 20 KB" (ALGORITHMS 9.7).
    public let sizeKb: SizeKB

    public init(
        examId: String, examName: String, body: String, docType: DocType, verdict: Verdict, unverified: Bool,
        fix: FixAction?, failed: [Constraint], issues: [Issue], sizeKb: SizeKB = SizeKB()
    ) {
        self.examId = examId
        self.examName = examName
        self.body = body
        self.docType = docType
        self.verdict = verdict
        self.unverified = unverified
        self.fix = fix
        self.failed = failed
        self.issues = issues
        self.sizeKb = sizeKb
    }
}

public struct MatchResult: Sendable, Equatable {
    /// Only exact, accepted and near-miss entries, sorted (ALGORITHMS 9.7).
    public let entries: [MatchEntry]

    public init(entries: [MatchEntry]) {
        self.entries = entries
    }

    /// Exams with at least one verified exact/accepted slot: the headline "Accepted by N exams".
    public var acceptedExamIds: [String] {
        var seen = Set<String>()
        return entries.filter { !$0.unverified && ($0.verdict == .exact || $0.verdict == .accepted) }
            .map(\.examId).filter { seen.insert($0).inserted }
    }

    public var acceptedExamCount: Int { acceptedExamIds.count }
    public var quickFixCount: Int { entries.filter { !$0.unverified && $0.verdict == .nearMiss }.count }
}
