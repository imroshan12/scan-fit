import Darwin
import Foundation
import Inspect
import Match
import ScanModel

/// My Kit on disk (ALGORITHMS 13.1): `index.json` (metadata only) and the bytes in `originals/<id>.jpg` and
/// `exports/<id>.jpg`. Bytes are staged, re-read and verified, then renamed into place before the index names them;
/// the actor serialises every change. Loading drops bad entries and removes files no entry names. `root` is in
/// Application Support and excluded from backup.
public actor FileKitStore: KitStore {
    struct Index: Codable, Equatable {
        var version = 1
        var originals: [KitOriginal] = []
        var exports: [ExportRecord] = []
    }

    enum KitError: Error { case unsafe, invalid, storage }

    private static let indexName = "index.json"
    private static let originalsName = "originals"
    private static let exportsName = "exports"
    private static let maxIndex = 4 * 1024 * 1024

    private let root: URL
    private let clock: @Sendable () -> Int64
    private let newID: @Sendable () -> String
    private let beforeIndex: @Sendable () throws -> Void
    private var revision: UInt64 = 0
    private var observers: [UUID: AsyncStream<UInt64>.Continuation] = [:]

    public init(
        root: URL,
        clock: @escaping @Sendable () -> Int64 = { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) },
        newID: @escaping @Sendable () -> String = KitIds.newID,
        beforeIndex: @escaping @Sendable () throws -> Void = {}
    ) {
        self.root = root.standardizedFileURL
        self.clock = clock
        self.newID = newID
        self.beforeIndex = beforeIndex
    }

    private var originalsDir: URL { root.appendingPathComponent(Self.originalsName) }
    private var exportsDir: URL { root.appendingPathComponent(Self.exportsName) }
    private var indexURL: URL { root.appendingPathComponent(Self.indexName) }

    public func originals() throws -> [KitOriginal] {
        try load().originals.sorted { $0.createdAt > $1.createdAt }
    }

    public func saveOriginal(kind: DocKind, jpeg: [UInt8]) -> SaveOriginalResult {
        do {
            let file = Inspector.inspect(jpeg)
            let width = file.width ?? 0, height = file.height ?? 0
            guard kind != .pdfDocument, kitOriginalReadable(jpeg, width: width, height: height) else {
                return .failed
            }
            var index = try load()
            if index.originals.count >= KitLimits.maxOriginals { return .full }
            let id = newID()
            guard KitIds.isValid(id) else { return .failed }
            try writeBlob(in: originalsDir, id: id, bytes: jpeg) {
                kitOriginalReadable($0, width: width, height: height)
            }
            try beforeIndex()
            // A failed index write leaves the blob unnamed: the next load removes it.
            index.originals.append(KitOriginal(
                id: id, kind: kind, createdAt: clock(), width: width, height: height, size: jpeg.count
            ))
            try write(index)
            changed()
            return .saved(id: id)
        } catch {
            return .failed
        }
    }

    public func original(id: String) -> [UInt8]? {
        guard KitIds.isValid(id), var index = try? load(),
              let entry = index.originals.first(where: { $0.id == id }) else { return nil }
        if let bytes = try? readBlob(in: originalsDir, id: id, size: entry.size),
           kitOriginalReadable(bytes, width: entry.width, height: entry.height) {
            return bytes
        }
        index.originals.removeAll { $0.id == id }
        try? write(index)
        _ = unlink(blob(in: originalsDir, id: id).path)
        changed()
        return nil
    }

    public func deleteOriginal(id: String) {
        guard KitIds.isValid(id), var index = try? load() else { return }
        index.originals.removeAll { $0.id == id }
        try? write(index)
        _ = unlink(blob(in: originalsDir, id: id).path)
        changed()
    }

    public func history() throws -> [HistoryEntry] {
        let now = clock()
        return try ExportHistory.prune(load().exports, now: now).map {
            let present = FileManager.default.fileExists(atPath: copy($0.record).path)
            return HistoryEntry(record: $0.record, shareable: $0.shareable && present)
        }
    }

    @discardableResult
    public func recordExport(_ request: ExportRequest, examId: String, examName: String) -> Bool {
        do {
            let bytes = request.bytes
            let file = Inspector.inspect(bytes)
            let pages = request.pdf?.pages
            let width = pages == nil ? file.width ?? 0 : 0, height = pages == nil ? file.height ?? 0 : 0
            let readable = pages.map { $0 >= 1 && kitPdfReadable(bytes, size: bytes.count) }
                ?? kitJpegReadable(bytes, width: width, height: height)
            guard bytes.count <= KitLimits.maxCopyBytes, readable, !examId.isEmpty else { return false }
            var index = try load()
            let id = newID()
            guard KitIds.isValid(id) else { return false }
            let record = ExportRecord(
                id: id, createdAt: clock(), examId: examId, examName: examName, docType: request.spec.type,
                kind: request.kind, fileName: request.filename, size: bytes.count, width: width, height: height,
                pages: pages
            )
            try writeBlob(to: copy(record), in: exportsDir, bytes: bytes) { $0 == bytes }
            try beforeIndex()
            index.exports.append(record)
            try write(index)
            // Pruning on the next load drops entries past the newest 100 and their copies.
            _ = try load()
            changed()
            return true
        } catch {
            return false
        }
    }

    public func exportCopy(id: String) -> [UInt8]? {
        guard KitIds.isValid(id), let index = try? load(),
              let entry = index.exports.first(where: { $0.id == id }),
              ExportHistory.shareable(createdAt: entry.createdAt, now: clock()) else { return nil }
        if let bytes = try? readFile(copy(entry), limit: entry.size), bytes.count == entry.size,
           entry.isPdf ? kitPdfReadable(bytes, size: entry.size)
           : kitJpegReadable(bytes, width: entry.width, height: entry.height) {
            return bytes
        }
        _ = unlink(copy(entry).path)
        changed()
        return nil
    }

    public func clearHistory() {
        guard var index = try? load() else { return }
        index.exports = []
        try? write(index)
        _ = try? load()
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

    // MARK: - Private

    /// The index with every bad entry dropped, history pruned (§13.3) and every file no entry names removed. Writes the
    /// cleaned index back when anything was dropped. Throws when the root is unsafe or cannot be created.
    private func load() throws -> Index {
        try safeRoot()
        // A missing, corrupt or unsupported index counts as empty and is replaced; its files become orphans.
        let parsed = try? parse(readFile(indexURL, limit: Self.maxIndex))
        let now = clock()
        var seen = Set<String>()
        let originals = (parsed?.originals ?? []).filter { sane($0) && seen.insert($0.id).inserted }
        let exports = (parsed?.exports ?? []).filter { sane($0) && seen.insert($0.id).inserted }
        let kept = Set(ExportHistory.prune(exports, now: now).map(\.record.id))
        let clean = Index(originals: originals, exports: exports.filter { kept.contains($0.id) })
        if clean != parsed { try write(clean) }
        sweep(originalsDir, keep: Set(originals.map { $0.id + ".jpg" }))
        let copies = clean.exports.filter { ExportHistory.shareable(createdAt: $0.createdAt, now: now) }.map(\.copyName)
        sweep(exportsDir, keep: Set(copies))
        sweepRoot()
        return clean
    }

    private func parse(_ data: [UInt8]) throws -> Index {
        let index = try JSONDecoder().decode(Index.self, from: Data(data))
        guard index.version == 1 else { throw KitError.invalid }
        return index
    }

    private func sane(_ entry: KitOriginal) -> Bool {
        KitIds.isValid(entry.id) && entry.kind != .pdfDocument && entry.createdAt >= 0 && entry.width > 0
            && entry.height > 0 && (1...KitLimits.maxOriginalBytes).contains(entry.size)
    }

    private func sane(_ entry: ExportRecord) -> Bool {
        // A PDF has pages and no dimensions; a JPEG has dimensions.
        let shape = entry.pages.map { $0 >= 1 && entry.width == 0 && entry.height == 0 }
            ?? (entry.width > 0 && entry.height > 0)
        return KitIds.isValid(entry.id) && entry.createdAt >= 0 && shape
            && (1...KitLimits.maxCopyBytes).contains(entry.size) && !entry.examId.isEmpty && !entry.fileName.isEmpty
    }

    /// Removes every file in `dir` whose name is not in `keep` (temporary files included).
    private func sweep(_ dir: URL, keep: Set<String>) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for name in names where !keep.contains(name) {
            _ = unlink(dir.appendingPathComponent(name).path)
        }
    }

    /// An export's private copy (`exports/<id>.jpg` or `.pdf`).
    private func copy(_ record: ExportRecord) -> URL {
        precondition(KitIds.isValid(record.id))
        return exportsDir.appendingPathComponent(record.copyName)
    }

    private func sweepRoot() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
        for name in names where ![Self.indexName, Self.originalsName, Self.exportsName].contains(name) {
            _ = unlink(root.appendingPathComponent(name).path)
        }
    }

    private func safeRoot() throws {
        for dir in [root, originalsDir, exportsDir] {
            var information = stat()
            if lstat(dir.path, &information) != 0 {
                guard errno == ENOENT else { throw KitError.unsafe }
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            }
            guard dir.resolvingSymlinksInPath().path == dir.path, lstat(dir.path, &information) == 0,
                  information.st_mode & S_IFMT == S_IFDIR else { throw KitError.unsafe }
        }
        var excluded = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
    }

    private func blob(in dir: URL, id: String) -> URL {
        precondition(KitIds.isValid(id))
        return dir.appendingPathComponent(id + ".jpg")
    }

    private func writeBlob(in dir: URL, id: String, bytes: [UInt8], verifies: ([UInt8]) -> Bool) throws {
        try writeBlob(to: blob(in: dir, id: id), in: dir, bytes: bytes, verifies: verifies)
    }

    private func writeBlob(to destination: URL, in dir: URL, bytes: [UInt8], verifies: ([UInt8]) -> Bool) throws {
        let staged = dir.appendingPathComponent(".kit-" + UUID().uuidString + ".tmp")
        defer { _ = unlink(staged.path) }
        try Data(bytes).write(to: staged, options: .withoutOverwriting)
        guard verifies(try readFile(staged, limit: bytes.count)) else { throw KitError.invalid }
        var information = stat()
        guard lstat(destination.path, &information) != 0 else { throw KitError.storage }
        guard rename(staged.path, destination.path) == 0 else { throw KitError.storage }
    }

    private func readBlob(in dir: URL, id: String, size: Int) throws -> [UInt8] {
        let bytes = try readFile(blob(in: dir, id: id), limit: size)
        guard bytes.count == size else { throw KitError.invalid }
        return bytes
    }

    private func write(_ index: Index) throws {
        let data = try JSONEncoder().encode(index)
        guard data.count <= Self.maxIndex else { throw KitError.invalid }
        var information = stat()
        if lstat(indexURL.path, &information) == 0, information.st_mode & S_IFMT != S_IFREG { throw KitError.unsafe }
        let staged = root.appendingPathComponent(".index-" + UUID().uuidString + ".tmp")
        defer { _ = unlink(staged.path) }
        try data.write(to: staged, options: .withoutOverwriting)
        guard rename(staged.path, indexURL.path) == 0 else { throw KitError.storage }
    }

    /// The whole regular file, refusing symlinks and anything longer than `limit`.
    private func readFile(_ url: URL, limit: Int) throws -> [UInt8] {
        let descriptor = open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard descriptor >= 0 else { throw KitError.storage }
        let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        defer { try? handle.close() }
        var information = stat()
        guard fstat(descriptor, &information) == 0, information.st_mode & S_IFMT == S_IFREG,
              information.st_size > 0, information.st_size <= limit else { throw KitError.invalid }
        guard let data = try handle.read(upToCount: limit + 1), data.count <= limit else { throw KitError.invalid }
        return [UInt8](data)
    }

    private func changed() {
        revision &+= 1
        for observer in observers.values { observer.yield(revision) }
    }

    private func removeObserver(_ identifier: UUID) {
        observers.removeValue(forKey: identifier)
    }
}
