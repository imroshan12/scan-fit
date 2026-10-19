import CoreGraphics
import Foundation
import Match
import Observation
import ScanData
import ScanModel

/// One original's screen: preview, "Use for exam…" and Delete (ALGORITHMS 13.1, 13.2).
@MainActor
@Observable
public final class KitOriginalViewModel {
    public enum State: Equatable {
        case loading
        /// The original is gone or failed its read check (it was removed): `kit.original_missing`.
        case missing
        /// Deleted on the user's request: the screen leaves.
        case deleted
        case ready(KitOriginal, preview: CGImage?)
    }

    /// Twice a 280 pt preview at 2x, never the stored size (CLAUDE.md rule 4).
    nonisolated static let previewPixels = 1120

    public let id: String
    public private(set) var state: State = .loading
    public var query = "" {
        didSet { updateTargets() }
    }

    /// "Use for exam…": the Checker's list for the original's kind (ALGORITHMS 13.2).
    public private(set) var targets: [CheckerTarget] = []
    @ObservationIgnored private let kit: any KitStore
    @ObservationIgnored private let images: any KitImages
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    @ObservationIgnored private var bundle: PresetBundle?

    public init(
        id: String, kit: any KitStore, images: any KitImages = LiveKitImages(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.id = id
        self.kit = kit
        self.images = images
        self.load = load
    }

    #if DEBUG
    convenience init(previewState: State, targets: [CheckerTarget] = []) {
        self.init(id: "", kit: NoKitStore()) { nil }
        state = previewState
        self.targets = targets
    }
    #endif

    public func onAppear() async {
        guard state == .loading else { return }
        guard KitIds.isValid(id), let entry = try? await kit.originals().first(where: { $0.id == id }),
              let bytes = await kit.original(id: id) else {
            state = .missing
            return
        }
        let images = self.images
        let preview = await Task.detached(priority: .userInitiated) {
            images.image(bytes, maxPixelSize: Self.previewPixels)
        }.value
        bundle = await load()
        state = .ready(entry, preview: preview)
        updateTargets()
    }

    public func delete() async {
        guard case .ready = state else { return }
        await kit.deleteOriginal(id: id)
        state = .deleted
    }

    private func updateTargets() {
        guard case let .ready(original, _) = state, let bundle else {
            targets = []
            return
        }
        targets = CheckerTargets.of(bundle, kind: original.kind, query: query)
    }
}
