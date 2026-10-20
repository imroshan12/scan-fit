import CoreGraphics
import Foundation
import Observation
import ScanData
import ScanModel

/// A saved file from My Kit's History, previewed before it is shared again (ALGORITHMS 13.3 previews). Same rules as
/// Android's `KitHistoryPreviewViewModel`.
@MainActor
@Observable
public final class KitHistoryPreviewViewModel {
    public enum State: Equatable, Sendable {
        case loading
        /// The copy is gone, too old or failed its check.
        case failed
        /// The saved image at screen size (nil for a PDF, whose `pages` draw one by one).
        case ready(record: ExportRecord, image: CGImage?, pages: Int)

        public static func == (lhs: State, rhs: State) -> Bool {
            switch (lhs, rhs) {
            case (.loading, .loading), (.failed, .failed): true
            case let (.ready(a, i, p), .ready(b, j, q)): a == b && p == q && (i == nil) == (j == nil)
            default: false
            }
        }
    }

    public static let maxPages = 20
    public private(set) var state: State = .loading
    public internal(set) var share: ShareStatus = .none
    @ObservationIgnored private let id: String
    @ObservationIgnored private let kit: any KitStore
    @ObservationIgnored private let images: any KitImages
    @ObservationIgnored private let stage: @Sendable ([UInt8], String) async -> URL?
    @ObservationIgnored private var bytes: [UInt8]?

    public init(
        id: String, kit: any KitStore, images: any KitImages = LiveKitImages(),
        stage: @escaping @Sendable ([UInt8], String) async -> URL? = { await ShareStager.stage($0, filename: $1) }
    ) {
        self.id = id
        self.kit = kit
        self.images = images
        self.stage = stage
    }

    #if DEBUG
    convenience init(previewState: State) {
        self.init(id: "", kit: NoKitStore())
        state = previewState
    }
    #endif

    public func load() async {
        guard KitIds.isValid(id), let entries = try? await kit.history(),
              let entry = entries.first(where: { $0.id == id && $0.shareable }),
              let copy = await kit.exportCopy(id: id) else {
            state = .failed
            return
        }
        bytes = copy
        let images = self.images
        if entry.record.isPdf {
            let count = await Task.detached(priority: .userInitiated) { images.pdfPageCount(copy) }.value
            state = count.map { .ready(record: entry.record, image: nil, pages: min($0, Self.maxPages)) } ?? .failed
        } else {
            let image = await Task.detached(priority: .userInitiated) { images.image(copy, maxPixelSize: 1600) }.value
            state = image.map { .ready(record: entry.record, image: $0, pages: 1) } ?? .failed
        }
    }

    /// Page `index` of a PDF at `width` pixels; nil when it cannot be drawn.
    public func page(_ index: Int, width: Int) async -> CGImage? {
        guard let bytes, case let .ready(_, nil, pages) = state, index >= 0, index < pages else { return nil }
        let images = self.images, width = min(width, 1440)
        return await Task.detached(priority: .userInitiated) { images.pdfPage(bytes, index: index, width: width) }.value
    }

    /// Shares the copy under its recorded name, exactly as it was saved (ALGORITHMS 1.6.2); never records Saved.
    public func shareCopy() async {
        guard let bytes, case let .ready(record, _, _) = state else { return }
        let url = await stage(bytes, record.fileName)
        share = url.map { .ready(SharedFile(url: $0)) } ?? .failed
    }

    public func shareHandled() {
        if case .ready = share { share = .none }
    }
}
