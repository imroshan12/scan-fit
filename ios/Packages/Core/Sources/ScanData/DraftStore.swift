import CryptoKit
import Darwin
import Foundation
import Match
import ScanModel

public struct RetainedDraft: Sendable {
    public let bytes: [UInt8]
    public let createdAt: Date

    public init(bytes: [UInt8], createdAt: Date) {
        self.bytes = bytes
        self.createdAt = createdAt
    }
}

public protocol DraftStore: Sendable {
    func load(examID: String, spec: DocSpec, kind: DocKind) async -> RetainedDraft?
    func retain(_ bytes: [UInt8], examID: String, spec: DocSpec, kind: DocKind) async throws
    func revisions() async -> AsyncStream<UInt64>
}

public enum DraftError: Error {
    case invalid, storage
}

public actor FileDraftStore: DraftStore {
    public static let lifetime: TimeInterval = 2_592_000
    public static let maximumJPEG = 64 * 1_024 * 1_024
    public static let maximumRecord = 96 * 1_024 * 1_024

    struct Record: Codable {
        let version: Int
        let createdAt: Date
        let jpeg: Data
    }

    private let root: URL
    private let clock: @Sendable () -> Date
    private let beforeCommit: @Sendable () throws -> Void
    private var revision: UInt64 = 0
    private var observers: [UUID: AsyncStream<UInt64>.Continuation] = [:]

    public init(
        root: URL,
        clock: @escaping @Sendable () -> Date = { Date() },
        beforeCommit: @escaping @Sendable () throws -> Void = {}
    ) {
        self.root = root.standardizedFileURL
        self.clock = clock
        self.beforeCommit = beforeCommit
    }

    public func load(examID: String, spec: DocSpec, kind: DocKind) -> RetainedDraft? {
        let slot = slotURL(examID: examID, type: spec.type)
        do {
            guard try safeRoot(create: false) else { return nil }
            let record = try read(slot)
            guard valid(record, spec: spec, kind: kind) else { throw DraftError.invalid }
            return RetainedDraft(bytes: Array(record.jpeg), createdAt: record.createdAt)
        } catch {
            if (try? safeRoot(create: false)) == true, unlink(slot.path) == 0 { changed() }
            return nil
        }
    }

    public func retain(_ bytes: [UInt8], examID: String, spec: DocSpec, kind: DocKind) throws {
        try Task.checkCancellation()
        guard bytes.count <= Self.maximumJPEG,
              ExportOperation.verifies(bytes, expected: bytes, spec: spec, kind: kind) else {
            throw DraftError.invalid
        }
        guard try safeRoot(create: true) else { throw DraftError.storage }
        let slot = slotURL(examID: examID, type: spec.type)
        var information = stat()
        if lstat(slot.path, &information) == 0, information.st_mode & S_IFMT != S_IFREG {
            throw DraftError.storage
        }
        let record = Record(version: 1, createdAt: clock(), jpeg: Data(bytes))
        let encoded = try JSONEncoder().encode(record)
        guard encoded.count <= Self.maximumRecord else { throw DraftError.invalid }
        let temporary = root.appendingPathComponent(UUID().uuidString + ".tmp")
        defer { _ = unlink(temporary.path) }
        try encoded.write(to: temporary, options: .withoutOverwriting)
        var excluded = temporary
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        let written = try read(temporary)
        guard valid(written, spec: spec, kind: kind), written.jpeg == record.jpeg,
              written.createdAt == record.createdAt else { throw DraftError.invalid }
        try beforeCommit()
        try Task.checkCancellation()
        guard rename(temporary.path, slot.path) == 0 else { throw DraftError.storage }
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

    func slotURL(examID: String, type: DocType) -> URL {
        let key = try? JSONEncoder().encode([examID, type.rawValue])
        let digest = SHA256.hash(data: key ?? Data())
        let name = digest.map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(name + ".json")
    }

    private func valid(_ record: Record, spec: DocSpec, kind: DocKind) -> Bool {
        let age = clock().timeIntervalSince(record.createdAt)
        guard record.version == 1, age >= 0, age < Self.lifetime,
              record.jpeg.count <= Self.maximumJPEG else { return false }
        let bytes = Array(record.jpeg)
        return ExportOperation.verifies(bytes, expected: bytes, spec: spec, kind: kind)
    }

    private func safeRoot(create: Bool) throws -> Bool {
        guard root.resolvingSymlinksInPath().path == root.path else { throw DraftError.storage }
        var information = stat()
        if lstat(root.path, &information) != 0 {
            guard errno == ENOENT, create else { return false }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        }
        guard root.resolvingSymlinksInPath().path == root.path,
              lstat(root.path, &information) == 0, information.st_mode & S_IFMT == S_IFDIR else {
            throw DraftError.storage
        }
        var directory = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        return true
    }

    private func read(_ url: URL) throws -> Record {
        let descriptor = open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard descriptor >= 0 else { throw DraftError.storage }
        let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        defer { try? handle.close() }
        var information = stat()
        guard fstat(descriptor, &information) == 0, information.st_mode & S_IFMT == S_IFREG,
              information.st_size > 0, information.st_size <= Self.maximumRecord else {
            throw DraftError.invalid
        }
        guard let data = try handle.read(upToCount: Self.maximumRecord + 1),
              data.count <= Self.maximumRecord else { throw DraftError.invalid }
        return try JSONDecoder().decode(Record.self, from: data)
    }

    private func changed() {
        revision &+= 1
        for observer in observers.values { observer.yield(revision) }
    }

    private func removeObserver(_ identifier: UUID) {
        observers.removeValue(forKey: identifier)
    }
}

public struct NoDraftStore: DraftStore {
    public init() {}

    public func load(examID: String, spec: DocSpec, kind: DocKind) async -> RetainedDraft? { nil }

    public func retain(_ bytes: [UInt8], examID: String, spec: DocSpec, kind: DocKind) async throws {
        try Task.checkCancellation()
        throw DraftError.storage
    }

    public func revisions() async -> AsyncStream<UInt64> {
        AsyncStream { $0.finish() }
    }
}
