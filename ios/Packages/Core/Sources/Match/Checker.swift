import Foundation
import Inspect
import ScanModel

/// What the Checker says about a file for one exam slot (ALGORITHMS 12). Same rule as Android's `CheckerStatus`.
public enum CheckerStatus: String, Sendable, Equatable {
    case ok, likelyOk = "likely_ok", fix, noFix = "no_fix", unknown

    private static let decodable: Set<DetectedFormat> = [.jpeg, .png, .webp, .heic]

    public static func of(
        _ format: DetectedFormat, slot: DocSpec, verdict: Verdict, unverified: Bool
    ) -> CheckerStatus {
        if verdict == .unknown { return .unknown }
        if verdict == .exact || verdict == .accepted { return unverified ? .likelyOk : .ok }
        let takesJpeg = slot.formats.contains(.jpg) || slot.formats.contains(.jpeg)
        return decodable.contains(format) && takesJpeg ? .fix : .noFix
    }
}

/// One (exam, slot) the Checker can compare a file with.
public struct CheckerTarget: Sendable, Equatable, Hashable {
    public let exam: Exam
    public let slot: DocSpec

    public init(exam: Exam, slot: DocSpec) {
        self.exam = exam
        self.slot = slot
    }

    public static func == (lhs: CheckerTarget, rhs: CheckerTarget) -> Bool {
        lhs.exam.id == rhs.exam.id && lhs.slot.type == rhs.slot.type
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(exam.id)
        hasher.combine(slot.type)
    }
}

public enum CheckerTargets {
    /// Active exams with a slot of `kind` (an exam with two matching slots appears twice), in popularity order then
    /// name, or in search order for a non-blank `query` (ALGORITHMS 10). Unverified exams are included, with their
    /// badge.
    public static func of(_ bundle: PresetBundle, kind: DocKind, query: String = "") -> [CheckerTarget] {
        let exams: [Exam]
        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            var rank: [String: Int] = [:]
            for (index, id) in (bundle.popular ?? []).enumerated() where rank[id] == nil { rank[id] = index }
            exams = bundle.exams.filter { $0.status == .active }.sorted { a, b in
                let ra = rank[a.id] ?? Int.max, rb = rank[b.id] ?? Int.max
                return ra != rb ? ra < rb : a.name < b.name
            }
        } else {
            exams = ExamSearch(bundle: bundle).search(query, showUnverified: true)
        }
        return exams.flatMap { exam in
            exam.documents
                .filter { DocKind.of($0.type) == kind && (kind != .pdfDocument || $0.formats.contains(.pdf)) }
                .map { CheckerTarget(exam: exam, slot: $0) }
        }
    }
}
