import Foundation
import Observation

@MainActor
@Observable
public final class FilesPhotoExporter: PhotoExporting {
    struct Document: Identifiable {
        let id = UUID()
        let url: URL
    }

    private(set) var document: Document?
    @ObservationIgnored private var continuation: CheckedContinuation<PhotoExportState, Never>?
    @ObservationIgnored private var request: PhotoExportRequest?
    @ObservationIgnored private var saving = false

    public init() {}

    public func save(_ request: PhotoExportRequest) async -> PhotoExportState {
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
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            document = Document(url: staged)
        }
    }

    func cancelled() {
        guard let continuation else { return }
        self.continuation = nil
        let staged = document?.url
        document = nil
        request = nil
        Task {
            await Self.cleanStaging(staged)
            continuation.resume(returning: .idle)
        }
    }

    func completed(_ urls: [URL]) {
        guard let continuation, let request else { return }
        self.continuation = nil
        self.request = nil
        let staged = document?.url
        document = nil
        Task {
            let result: PhotoExportState
            if let url = urls.first, urls.count == 1 {
                result = await Task.detached(priority: .userInitiated) {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let transport = FilesExportTransport(url: url)
                    return await PhotoExportOperation.run(request, transport: transport).state
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

struct FilesExportTransport: PhotoExportTransport {
    let url: URL

    func write(_: [UInt8]) async throws {}

    func publish() async throws {}

    func read() async throws -> [UInt8] {
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

    func remove() async -> Bool {
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
