import Foundation
import ScanModel

/// An exam named in the match note's preview.
public struct ExamRef: Sendable, Equatable {
    public let id: String
    public let name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

/// What a near-miss slot needs: `.atMost(20)` reads "needs ≤ 20 KB".
public enum Need: Sendable, Equatable {
    case atMost(Int)
    case atLeast(Int)
    case jpeg
    case baseline
}

public struct QuickFix: Sendable, Equatable {
    public let exam: ExamRef
    public let docType: DocType
    public let need: Need
}

/// One body's rows in the detail sheet, in result order.
public struct MatchGroup: Sendable, Equatable {
    public let body: String
    public let entries: [MatchEntry]
}

/// The match note under a review's verdict (ALGORITHMS 4 "Match note on review", 9.7 "Match note"): the headline
/// count, the first names, the likely-OK count for unverified exams, the quick fixes with what they need, and the
/// sheet's groups.
public struct MatchNote: Sendable, Equatable {
    public enum Headline: Sendable, Equatable { case accepted, likelyOk, none }

    public static let previewSize = 3
    public static let empty = MatchNote.of(MatchResult(entries: []))

    public let accepted: Int
    public let preview: [ExamRef]
    public let more: Int
    public let likelyOk: Int
    public let quickFixes: [QuickFix]
    public let groups: [MatchGroup]

    public var headline: Headline {
        if accepted > 0 { return .accepted }
        return likelyOk > 0 ? .likelyOk : .none
    }

    /// The note for a file of `facts` against `exams`, sorted by `popularity` (the bundle's `popular` list).
    public static func of(_ facts: FileFacts, exams: [Exam], popularity: [String]) -> MatchNote {
        of(MatchEngine.match(facts, exams: exams, options: MatchOptions(popularity: popularity)))
    }

    /// Summarises a sorted `result`; never re-sorts it.
    public static func of(_ result: MatchResult) -> MatchNote {
        let entries = result.entries
        func accepting(_ e: MatchEntry) -> Bool { e.verdict == .exact || e.verdict == .accepted }
        var accepted: [ExamRef] = []
        for e in entries where !e.unverified && accepting(e) && !accepted.contains(where: { $0.id == e.examId }) {
            accepted.append(ExamRef(id: e.examId, name: e.examName))
        }
        var likely: [String] = []
        for e in entries where e.unverified && accepting(e) && !accepted.contains(where: { $0.id == e.examId })
            && !likely.contains(e.examId) {
            likely.append(e.examId)
        }
        let fixes = entries.filter { !$0.unverified && $0.verdict == .nearMiss }.compactMap { e in
            need(e).map { QuickFix(exam: ExamRef(id: e.examId, name: e.examName), docType: e.docType, need: $0) }
        }
        var groups: [MatchGroup] = []
        for e in entries {
            if let i = groups.firstIndex(where: { $0.body == e.body }) {
                groups[i] = MatchGroup(body: e.body, entries: groups[i].entries + [e])
            } else {
                groups.append(MatchGroup(body: e.body, entries: [e]))
            }
        }
        let preview = Array(accepted.prefix(previewSize))
        return MatchNote(accepted: accepted.count, preview: preview, more: accepted.count - preview.count,
                         likelyOk: likely.count, quickFixes: fixes, groups: groups)
    }

    /// What a near-miss entry needs; nil for other verdicts or when the slot has no usable limit.
    public static func need(_ e: MatchEntry) -> Need? {
        switch e.fix {
        case .compressToTarget: e.sizeKb.max.map { .atMost(Int($0.rounded(.down))) }
        case .enlargeToTarget: e.sizeKb.min.map { .atLeast(Int($0.rounded(.up))) }
        case .convertToJpeg: .jpeg
        case .reencodeBaseline: .baseline
        case nil: nil
        }
    }
}
