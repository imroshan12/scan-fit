import Foundation
import Observation
import ScanModel

/// iOS saves through the Files export picker; the app presents `document` and reports the picker's result here.
@MainActor
@Observable
public final class FilesExporter: DocumentExporting {
    private typealias BatchContinuation = CheckedContinuation<SaveAllOutcome, Never>

    public struct Document: Identifiable {
        public let id = UUID()
        /// Every staged file: one for a save, several for Save all (one picker, one folder).
        public let urls: [URL]
        public var url: URL { urls[0] }
    }

    public private(set) var document: Document?
    @ObservationIgnored private var continuation: CheckedContinuation<ExportState, Never>?
    @ObservationIgnored private var request: ExportRequest?
    @ObservationIgnored private var stagedURL: URL?
    @ObservationIgnored private var saving = false
    /// The running Save all: its requests and who waits for the outcome.
    @ObservationIgnored private var batch: (requests: [ExportRequest], resume: BatchContinuation)?

    public init() {}

    public func save(_ request: ExportRequest) async -> ExportState {
        guard !saving else { return .saveFailed }
        saving = true
        defer { saving = false }
        let staged = await Task.detached(priority: .userInitiated) { () -> URL? in
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let url = directory.appendingPathComponent(request.filename)
                try Data(request.bytes).write(to: url, options: .atomic)
                return url
            } catch {
                try? FileManager.default.removeItem(at: directory)
                return nil as URL?
            }
        }.value
        guard let staged else { return .saveFailed }
        self.request = request
        stagedURL = staged
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            document = Document(urls: [staged])
        }
    }

    public func presentationDismissed() {
        document = nil
    }

    /// Save all (ALGORITHMS 1.6.2): one Files picker for every file; each returned URL is matched to its request by
    /// byte equality (the picker may rename on "Keep both") and verified on its own.
    public func saveAll(_ requests: [ExportRequest]) async -> SaveAllOutcome {
        guard !saving, !requests.isEmpty else {
            return SaveAllOutcome(states: requests.map { _ in .saveFailed }, folder: nil)
        }
        saving = true
        defer { saving = false }
        let staged = await Task.detached(priority: .userInitiated) { () -> [URL]? in
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                return try requests.map { request in
                    let url = directory.appendingPathComponent(request.filename)
                    try Data(request.bytes).write(to: url, options: .atomic)
                    return url
                }
            } catch {
                try? FileManager.default.removeItem(at: directory)
                return nil as [URL]?
            }
        }.value
        guard let staged else { return SaveAllOutcome(states: requests.map { _ in .saveFailed }, folder: nil) }
        stagedURL = staged.first
        return await withCheckedContinuation { continuation in
            batch = (requests, continuation)
            document = Document(urls: staged)
        }
    }

    public func cancelled() {
        if let batch {
            self.batch = nil
            let staged = stagedURL
            stagedURL = nil
            document = nil
            Task {
                await Self.cleanStaging(staged)
                let cancelled = SaveAllOutcome(states: batch.requests.map { _ in .idle }, folder: nil)
                batch.resume.resume(returning: cancelled)
            }
            return
        }
        guard let continuation else { return }
        self.continuation = nil
        let staged = stagedURL
        stagedURL = nil
        document = nil
        request = nil
        Task {
            await Self.cleanStaging(staged)
            continuation.resume(returning: .idle)
        }
    }

    public func completed(_ urls: [URL]) {
        if let batch {
            self.batch = nil
            let staged = stagedURL
            stagedURL = nil
            document = nil
            Task {
                let outcome = await Task.detached(priority: .userInitiated) {
                    await Self.verifyAll(batch.requests, at: urls)
                }.value
                await Self.cleanStaging(staged)
                batch.resume.resume(returning: outcome)
            }
            return
        }
        guard let continuation, let request else { return }
        self.continuation = nil
        self.request = nil
        let staged = stagedURL
        stagedURL = nil
        document = nil
        Task {
            let result: ExportState
            if let url = urls.first, urls.count == 1 {
                result = await Task.detached(priority: .userInitiated) {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let transport = FilesExportTransport(url: url)
                    return await ExportOperation.run(request, transport: transport).state
                }.value
            } else {
                result = .saveFailed
            }
            await Self.cleanStaging(staged)
            continuation.resume(returning: result)
        }
    }

    /// Matches each request to the returned URL holding exactly its bytes, then runs the full verification there. A
    /// URL that matches nothing is never verified as, or removed for, another slot.
    nonisolated static func verifyAll(_ requests: [ExportRequest], at urls: [URL]) async -> SaveAllOutcome {
        var contents: [(url: URL, bytes: [UInt8])] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            if let bytes = try? await FilesExportTransport(url: url).read() { contents.append((url, bytes)) }
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        var used = Set<Int>()
        var states: [ExportState] = []
        for request in requests {
            let match = contents.indices.first { !used.contains($0) && contents[$0].bytes == request.bytes }
            guard let index = match else {
                states.append(.saveFailed)
                continue
            }
            used.insert(index)
            let url = contents[index].url
            let scoped = url.startAccessingSecurityScopedResource()
            states.append(await ExportOperation.run(request, transport: FilesExportTransport(url: url)).state)
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        return SaveAllOutcome(states: states, folder: urls.first.flatMap { folderName(of: $0) })
    }

    /// The picked folder as Files shows it; nil at a provider's root, whose real directory name ("File Provider
    /// Storage") means nothing to the user.
    nonisolated static func folderName(of file: URL) -> String? {
        let folder = file.deletingLastPathComponent()
        let name = (try? folder.resourceValues(forKeys: [.localizedNameKey]))?.localizedName ?? folder.lastPathComponent
        return name.isEmpty || name == "File Provider Storage" ? nil : name
    }

    private nonisolated static func cleanStaging(_ url: URL?) async {
        guard let url else { return }
        await Task.detached {
            try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        }.value
    }
}

public struct FilesExportTransport: DocumentExportTransport {
    public init(url: URL) { self.url = url }

    let url: URL

    public func write(_: [UInt8]) async throws {}

    public func publish() async throws {}

    public func read() async throws -> [UInt8] {
        let coordinator = NSFileCoordinator()
        var coordinationError: NSError?
        var result: Result<[UInt8], Error>?
        coordinator.coordinate(readingItemAt: url, options: [], error: &coordinationError) { readable in
            result = Result { [UInt8](try Data(contentsOf: readable)) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw CocoaError(.fileReadUnknown) }
        return try result.get()
    }

    public func remove() async -> Bool {
        let coordinator = NSFileCoordinator()
        var coordinationError: NSError?
        var removed = false
        coordinator.coordinate(writingItemAt: url, options: .forDeleting, error: &coordinationError) { writable in
            do {
                try FileManager.default.removeItem(at: writable)
                removed = true
            } catch {
                removed = false
            }
        }
        return removed && coordinationError == nil
    }
}
