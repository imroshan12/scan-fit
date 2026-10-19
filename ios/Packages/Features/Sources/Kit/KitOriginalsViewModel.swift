import CoreGraphics
import Foundation
import Match
import Observation
import ScanData
import ScanModel

public struct KitOriginalGroup: Identifiable, Equatable, Sendable {
    public let kind: DocKind
    public let originals: [KitOriginal]
    public var id: DocKind { kind }

    public init(kind: DocKind, originals: [KitOriginal]) {
        self.kind = kind
        self.originals = originals
    }
}

/// My Kit's Originals view (ALGORITHMS 13.1): grouped by kind, newest first; thumbnails load per row.
@MainActor
@Observable
public final class KitOriginalsViewModel {
    public enum State: Equatable, Sendable {
        case loading, empty, failed
        case ready([KitOriginalGroup])
    }

    /// Twice the 56 pt row thumbnail at 3x (ALGORITHMS 13.1: never the stored size).
    public nonisolated static let thumbnailPixels = 168
    /// The order of ALGORITHMS 13.1: Photo, Signature, Thumb, Fingers, Declaration.
    nonisolated static let kindOrder: [DocKind] = [.photo, .signature, .thumb, .fingers, .declaration]

    public private(set) var state: State = .loading
    @ObservationIgnored private let kit: any KitStore
    @ObservationIgnored private let images: any KitImages
    @ObservationIgnored private var generation = 0

    public init(kit: any KitStore, images: any KitImages = LiveKitImages()) {
        self.kit = kit
        self.images = images
    }

    #if DEBUG
    convenience init(previewState: State) {
        self.init(kit: NoKitStore())
        state = previewState
    }
    #endif

    public func refresh() async {
        generation += 1
        let current = generation
        let next: State
        do {
            let groups = Self.group(try await kit.originals())
            next = groups.isEmpty ? .empty : .ready(groups)
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

    /// A small upright copy for a row, decoded off the main actor; nil when the original cannot be read.
    public func thumbnail(_ id: String) async -> CGImage? {
        guard let bytes = await kit.original(id: id) else { return nil }
        let images = self.images
        return await Task.detached(priority: .userInitiated) {
            images.image(bytes, maxPixelSize: Self.thumbnailPixels)
        }.value
    }

    nonisolated static func group(_ originals: [KitOriginal]) -> [KitOriginalGroup] {
        kindOrder.compactMap { kind in
            let matching = originals.filter { $0.kind == kind }.sorted { $0.createdAt > $1.createdAt }
            return matching.isEmpty ? nil : KitOriginalGroup(kind: kind, originals: matching)
        }
    }
}
