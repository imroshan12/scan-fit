import Foundation
import Observation
import ScanModel

/// iOS saves through the Files export picker; the app presents `document` and reports the picker's result here.
@MainActor
@Observable
public final class FilesExporter: DocumentExporting {
    public struct Document: Identifiable {
        public let id = UUID()
        public let url: URL
    }

    public private(set) var document: Document?
    @ObservationIgnored private var continuation: CheckedContinuation<ExportState, Never>?
    @ObservationIgnored private var request: ExportRequest?
    @ObservationIgnored private var stagedURL: URL?
    @ObservationIgnored private var saving = false

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
            document = Document(url: staged)
        }
    }

    public func presentationDismissed() {
        document = nil
    }

    public func cancelled() {
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
