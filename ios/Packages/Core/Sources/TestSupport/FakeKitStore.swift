import Foundation
import Inspect
import Match
import ScanData
import ScanModel

/// In-memory My Kit (ALGORITHMS 13) for view-model tests; `FileKitStore` has its own tests on disk.
public actor FakeKitStore: KitStore {
    public private(set) var originalsList: [KitOriginal] = []
    public private(set) var exports: [ExportRecord] = []
    private var bytes: [String: [UInt8]] = [:]
    private var copies: [String: [UInt8]] = [:]
    public var now: Int64 = 1_800_000_000_000
    private var failReads = false
    private var failWrites = false
    private var next = 0
    private var revision: UInt64 = 0
    private var observers: [UUID: AsyncStream<UInt64>.Continuation] = [:]

    public init() {}

    public func setFailures(reads: Bool = false, writes: Bool = false) {
        failReads = reads
        failWrites = writes
    }

    public func advance(by milliseconds: Int64) { now += milliseconds }

    public func removeAllOriginals() {
        originalsList = []
        bytes = [:]
    }

    /// Valid v4-shaped ids that sort in creation order.
    public func newID() -> String {
        defer { next += 1 }
        return String(format: "00000000-0000-4000-8000-%012d", next)
    }

    public func originals() throws -> [KitOriginal] {
        if failReads { throw CocoaError(.fileReadUnknown) }
        return originalsList.sorted { $0.createdAt > $1.createdAt }
    }

    public func saveOriginal(kind: DocKind, jpeg: [UInt8]) -> SaveOriginalResult {
        if failWrites { return .failed }
        if originalsList.count >= KitLimits.maxOriginals { return .full }
        let file = Inspector.inspect(jpeg)
        let id = newID()
        originalsList.append(KitOriginal(
            id: id, kind: kind, createdAt: now, width: file.width ?? 0, height: file.height ?? 0, size: jpeg.count
        ))
        bytes[id] = jpeg
        changed()
        return .saved(id: id)
    }

    public func original(id: String) -> [UInt8]? { bytes[id] }

    public func deleteOriginal(id: String) {
        originalsList.removeAll { $0.id == id }
        bytes[id] = nil
        changed()
    }

    public func history() throws -> [HistoryEntry] {
        if failReads { throw CocoaError(.fileReadUnknown) }
        return ExportHistory.prune(exports, now: now).map {
            HistoryEntry(record: $0.record, shareable: $0.shareable && copies[$0.record.id] != nil)
        }
    }

    @discardableResult
    public func recordExport(_ request: ExportRequest, examId: String, examName: String) -> Bool {
        if failWrites { return false }
        let file = Inspector.inspect(request.bytes)
        let id = newID()
        exports.append(ExportRecord(
            id: id, createdAt: now, examId: examId, examName: examName, docType: request.spec.type,
            kind: request.kind, fileName: request.filename, size: request.bytes.count,
            width: request.pdf == nil ? file.width ?? 0 : 0, height: request.pdf == nil ? file.height ?? 0 : 0,
            pages: request.pdf?.pages
        ))
        copies[id] = request.bytes
        changed()
        return true
    }

    public func exportCopy(id: String) -> [UInt8]? {
        guard let entry = exports.first(where: { $0.id == id }),
              ExportHistory.shareable(createdAt: entry.createdAt, now: now) else { return nil }
        return copies[id]
    }

    public func clearHistory() {
        exports = []
        copies = [:]
        changed()
    }

    public func revisions() -> AsyncStream<UInt64> {
        let identifier = UUID()
        let pair = AsyncStream<UInt64>.makeStream(bufferingPolicy: .bufferingNewest(1))
        observers[identifier] = pair.continuation
        pair.continuation.yield(revision)
        pair.continuation.onTermination = { [weak self] _ in
            Task { await self?.removeObserver(identifier) }
        }
        return pair.stream
    }

    private func changed() {
        revision &+= 1
        for observer in observers.values { observer.yield(revision) }
    }

    private func removeObserver(_ identifier: UUID) {
        observers.removeValue(forKey: identifier)
    }
}
