import Foundation
import Match
import ScanData
import ScanModel

public actor DraftGate {
    private var armed = false
    private var blocked: CheckedContinuation<Void, Never>?
    private var started: CheckedContinuation<Void, Never>?

    public init() {}

    public func arm() { armed = true }

    public func wait() async {
        guard armed else { return }
        armed = false
        await withCheckedContinuation { continuation in
            blocked = continuation
            started?.resume()
            started = nil
        }
    }

    public func waitForStart() async {
        if blocked != nil { return }
        await withCheckedContinuation { started = $0 }
    }

    public func release() {
        blocked?.resume()
        blocked = nil
    }
}

public actor FakeDraftStore: DraftStore {
    public let retainGate = DraftGate()
    public let loadGate = DraftGate()
    public private(set) var retained: [[UInt8]] = []
    public private(set) var loads = 0
    public private(set) var subscribers = 0
    private var failing = false
    private var records: [String: RetainedDraft] = [:]
    private var revision: UInt64 = 0
    private var observers: [UUID: AsyncStream<UInt64>.Continuation] = [:]

    public init() {}

    public func setFailure(_ failure: Bool) { failing = failure }

    public func seed(_ bytes: [UInt8]?, examID: String, type: DocType) {
        records[key(examID, type)] = bytes.map { RetainedDraft(bytes: $0, createdAt: Date()) }
        changed()
    }

    public func load(examID: String, spec: DocSpec, kind: DocKind) async -> RetainedDraft? {
        loads += 1
        let record = records[key(examID, spec.type)]
        await loadGate.wait()
        guard let record,
              ExportOperation.verifies(record.bytes, expected: record.bytes, spec: spec, kind: kind) else { return nil }
        return record
    }

    public func retain(_ bytes: [UInt8], examID: String, spec: DocSpec, kind: DocKind) async throws {
        await retainGate.wait()
        try Task.checkCancellation()
        guard !failing else { throw DraftError.storage }
        guard ExportOperation.verifies(bytes, expected: bytes, spec: spec, kind: kind) else { throw DraftError.invalid }
        records[key(examID, spec.type)] = RetainedDraft(bytes: bytes, createdAt: Date())
        retained.append(bytes)
        changed()
    }

    public func revisions() -> AsyncStream<UInt64> {
        let identifier = UUID()
        let pair = AsyncStream<UInt64>.makeStream(bufferingPolicy: .bufferingNewest(1))
        observers[identifier] = pair.continuation
        subscribers = observers.count
        pair.continuation.yield(revision)
        pair.continuation.onTermination = { [weak self] _ in
            Task { await self?.removeObserver(identifier) }
        }
        return pair.stream
    }

    private func key(_ examID: String, _ type: DocType) -> String { "\(examID):\(type.rawValue)" }

    private func changed() {
        revision &+= 1
        for observer in observers.values { observer.yield(revision) }
    }

    private func removeObserver(_ identifier: UUID) {
        observers.removeValue(forKey: identifier)
        subscribers = observers.count
    }
}
