import Foundation
import Observation
import ScanData
import ScanModel

/// My Kit's History view (ALGORITHMS 13.3): every verified save, newest first, shareable for 30 days.
@MainActor
@Observable
public final class KitHistoryViewModel {
    public enum State: Equatable, Sendable {
        case loading, empty, failed
        case ready([HistoryEntry])
    }

    public private(set) var state: State = .loading
    /// The share sheet's file, or the staging failure (ALGORITHMS 1.6.2).
    public private(set) var share: ShareStatus = .none
    @ObservationIgnored private let kit: any KitStore
    @ObservationIgnored private let stage: @Sendable ([UInt8], String) async -> URL?
    @ObservationIgnored private var generation = 0

    public init(
        kit: any KitStore,
        stage: @escaping @Sendable ([UInt8], String) async -> URL? = { await ShareStager.stage($0, filename: $1) }
    ) {
        self.kit = kit
        self.stage = stage
    }

    #if DEBUG
    convenience init(previewState: State, share: ShareStatus = .none) {
        self.init(kit: NoKitStore())
        state = previewState
        self.share = share
    }
    #endif

    public func refresh() async {
        generation += 1
        let current = generation
        let next: State
        do {
            let entries = try await kit.history()
            next = entries.isEmpty ? .empty : .ready(entries)
        } catch {
            next = .failed
        }
        guard !Task.isCancelled, current == generation else { return }
        state = next
    }

    public func observe() async {
        let stream = await kit.revisions()
        for await _ in stream {
            guard !Task.isCancelled else { return }
            await refresh()
        }
    }

    /// Shares the entry's private copy under its recorded name, exactly as it was saved; never records Saved.
    public func share(_ id: String) async {
        guard case let .ready(entries) = state,
              let entry = entries.first(where: { $0.id == id && $0.shareable }) else { return }
        var url: URL?
        if let bytes = await kit.exportCopy(id: id) { url = await stage(bytes, entry.record.fileName) }
        share = url.map { .ready(SharedFile(url: $0)) } ?? .failed
        // A copy that failed its check was deleted: the row now says it is no longer kept.
        if url == nil { await refresh() }
    }

    /// The share sheet closed.
    public func shareHandled() {
        if case .ready = share { share = .none }
    }

    public func clear() async {
        await kit.clearHistory()
        await refresh()
    }
}
