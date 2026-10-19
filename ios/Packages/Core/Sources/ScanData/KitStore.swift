import Foundation
import Inspect
import Match
import ScanModel

/// A picture the user kept in My Kit to use for other exams (ALGORITHMS 13.1). Metadata only, never the bytes.
public struct KitOriginal: Sendable, Equatable, Codable, Identifiable {
    public let id: String
    public let kind: DocKind
    public let createdAt: Int64
    public let width: Int
    public let height: Int
    public let size: Int

    public init(id: String, kind: DocKind, createdAt: Int64, width: Int, height: Int, size: Int) {
        self.id = id
        self.kind = kind
        self.createdAt = createdAt
        self.width = width
        self.height = height
        self.size = size
    }

    enum CodingKeys: String, CodingKey {
        case id, kind, width, height, size
        case createdAt = "created_at_ms"
    }
}

/// One verified export (ALGORITHMS 13.3). Custom size entries carry `exam_id` `custom`.
public struct ExportRecord: Sendable, Equatable, Codable, Identifiable {
    public let id: String
    public let createdAt: Int64
    public let examId: String
    public let examName: String
    public let docType: DocType
    public let kind: DocKind
    public let fileName: String
    public let size: Int
    public let width: Int
    public let height: Int

    public init(
        id: String, createdAt: Int64, examId: String, examName: String, docType: DocType, kind: DocKind,
        fileName: String, size: Int, width: Int, height: Int
    ) {
        self.id = id
        self.createdAt = createdAt
        self.examId = examId
        self.examName = examName
        self.docType = docType
        self.kind = kind
        self.fileName = fileName
        self.size = size
        self.width = width
        self.height = height
    }

    enum CodingKeys: String, CodingKey {
        case id, kind, size, width, height
        case createdAt = "created_at_ms"
        case examId = "exam_id"
        case examName = "exam_name"
        case docType = "doc_type"
        case fileName = "file_name"
    }
}

/// A history row: `shareable` while its private copy is inside the 30-day window.
public struct HistoryEntry: Sendable, Equatable, Identifiable {
    public let record: ExportRecord
    public let shareable: Bool
    public var id: String { record.id }

    public init(record: ExportRecord, shareable: Bool) {
        self.record = record
        self.shareable = shareable
    }
}

public enum SaveOriginalResult: Sendable, Equatable {
    case saved(id: String)
    /// My Kit already holds `KitLimits.maxOriginals`: nothing was written.
    case full
    case failed
}

public extension KitSaveStatus {
    init(_ result: SaveOriginalResult) {
        switch result {
        case .saved: self = .saved
        case .full: self = .full
        case .failed: self = .failed
        }
    }
}

public enum KitLimits {
    public static let maxOriginals = 30
    public static let maxOriginalBytes = 16 * 1024 * 1024
    public static let maxCopyBytes = 64 * 1024 * 1024
    /// ALGORITHMS 13.1 (the encoder's `KitOriginalImage.maxLongSide`).
    public static let maxLongSide = 2400
}

/// My Kit's originals and export history (ALGORITHMS 13), app-private and excluded from backup. Listing throws when
/// the storage cannot be read, so the screen can offer a retry instead of claiming an empty kit.
public protocol KitStore: Sendable {
    /// Every original, newest first (bytes are re-checked by `original(id:)`).
    func originals() async throws -> [KitOriginal]
    func saveOriginal(kind: DocKind, jpeg: [UInt8]) async -> SaveOriginalResult
    /// The original's bytes after the §13.1 read check, or nil (an invalid one is removed).
    func original(id: String) async -> [UInt8]?
    func deleteOriginal(id: String) async
    /// Newest first, at most `ExportHistory.maxEntries`.
    func history() async throws -> [HistoryEntry]
    /// Best effort: false when the entry or its copy could not be written. Never affects the export.
    @discardableResult
    func recordExport(_ request: ExportRequest, examId: String, examName: String) async -> Bool
    /// The copy's bytes after the §13.3 checks, or nil when it is gone, too old or does not match its entry.
    func exportCopy(id: String) async -> [UInt8]?
    func clearHistory() async
    func revisions() async -> AsyncStream<UInt64>
}

/// Ids are app-made lowercase UUID v4 text, checked before any path is built from them (`kit_id_cases`).
public enum KitIds {
    public static func isValid(_ id: String) -> Bool {
        let parts = id.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.map(\.count) == [8, 4, 4, 4, 12] else { return false }
        let hex = Set("0123456789abcdef")
        guard parts.allSatisfy({ $0.allSatisfy(hex.contains) }) else { return false }
        return parts[2].first == "4" && "89ab".contains(parts[3].first ?? "x")
    }

    public static func newID() -> String { UUID().uuidString.lowercased() }
}

/// Export history retention (ALGORITHMS 13.3, `kit_history_cases`).
public enum ExportHistory {
    public static let maxEntries = 100
    public static let shareWindow: Int64 = 2_592_000_000

    /// `entries` in recorded order → newest first (later-recorded first on equal times), the first `maxEntries`.
    public static func prune(_ entries: [ExportRecord], now: Int64, maxEntries: Int = maxEntries) -> [HistoryEntry] {
        Array(entries.enumerated().sorted {
            $0.element.createdAt != $1.element.createdAt
                ? $0.element.createdAt > $1.element.createdAt
                : $0.offset > $1.offset
        }.prefix(maxEntries)).map {
            HistoryEntry(record: $0.element, shareable: shareable(createdAt: $0.element.createdAt, now: now))
        }
    }

    public static func shareable(createdAt: Int64, now: Int64) -> Bool {
        let age = now - createdAt
        return age >= 0 && age < shareWindow
    }
}

/// The §13.1 read check: a baseline RGB JPEG without private metadata, of the recorded size.
func kitJpegReadable(_ bytes: [UInt8], width: Int, height: Int) -> Bool {
    let file = Inspector.inspect(bytes)
    return file.format == .jpeg && file.sof == "SOF0" && !file.progressive && file.color == .rgb
        && file.components == 3 && !file.hasExif && !file.hasGps && !file.hasXmp
        && file.width == width && file.height == height
}

/// Originals also stay within the stored-size limit.
func kitOriginalReadable(_ bytes: [UInt8], width: Int, height: Int) -> Bool {
    bytes.count <= KitLimits.maxOriginalBytes && max(width, height) <= KitLimits.maxLongSide
        && kitJpegReadable(bytes, width: width, height: height)
}

/// No Kit (previews and tests that do not exercise it): nothing listed, nothing kept.
public struct NoKitStore: KitStore {
    public init() {}
    public func originals() async throws -> [KitOriginal] { [] }
    public func saveOriginal(kind: DocKind, jpeg: [UInt8]) async -> SaveOriginalResult { .failed }
    public func original(id: String) async -> [UInt8]? { nil }
    public func deleteOriginal(id: String) async {}
    public func history() async throws -> [HistoryEntry] { [] }
    public func recordExport(_ request: ExportRequest, examId: String, examName: String) async -> Bool { false }
    public func exportCopy(id: String) async -> [UInt8]? { nil }
    public func clearHistory() async {}
    public func revisions() async -> AsyncStream<UInt64> { AsyncStream { $0.finish() } }
}
