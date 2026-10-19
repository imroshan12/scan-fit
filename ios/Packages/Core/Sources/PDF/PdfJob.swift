import Foundation
import Imaging
import ScanModel

/// What the user is making (ALGORITHMS 6.1). Only `idCard` changes the layout; the others differ in what they accept.
public enum PdfTool: String, Sendable, Equatable, Hashable, CaseIterable {
    case images, compress, merge, idCard = "id_card", exam
}

/// One input file.
public enum PdfSource: Sendable {
    /// A picture: decoded upright (§1.1), turned `quarterTurns` × 90° clockwise, then cropped to `crop`, given in the
    /// pixels of a `cropBase`-sized view of the turned picture (the editor's raster), scaled to the engine's decode.
    case image(bytes: [UInt8], quarterTurns: Int = 0, crop: CropRect? = nil, cropBase: PixelSize? = nil)
    /// A PDF: every page, in order.
    case document([UInt8])

    var isImage: Bool { if case .image = self { true } else { false } }
}

public struct PdfOptions: Sendable, Equatable {
    /// Default on (decision 2026-10-15): about half the size; seals lose their colour.
    public var grayscale: Bool
    /// Default on; applies to pictures only.
    public var cleanup: Bool

    public init(grayscale: Bool = true, cleanup: Bool = true) {
        self.grayscale = grayscale
        self.cleanup = cleanup
    }
}

public struct PdfJob: Sendable {
    public let tool: PdfTool
    public let sources: [PdfSource]
    public let sizeKb: SizeKB
    public let options: PdfOptions

    public init(tool: PdfTool, sources: [PdfSource], sizeKb: SizeKB, options: PdfOptions = PdfOptions()) {
        self.tool = tool
        self.sources = sources
        self.sizeKb = sizeKb
        self.options = options
    }
}

/// A finished PDF: the exact bytes to save, plus what the result screen shows.
public struct PdfMade: Sendable {
    public let bytes: [UInt8]
    public let pages: Int
    /// `nil` for an unchanged file.
    public let dpi: Int?
    public let quality: Int?
    public let strategy: PdfStrategy
    /// The first page, at most `PdfEngine.previewLongSide` px on its long side.
    public let preview: Raster?

    public init(bytes: [UInt8], pages: Int, dpi: Int?, quality: Int?, strategy: PdfStrategy, preview: Raster?) {
        self.bytes = bytes
        self.pages = pages
        self.dpi = dpi
        self.quality = quality
        self.strategy = strategy
        self.preview = preview
    }
}

/// Progress for the making screen.
public enum PdfProgress: Sendable, Equatable {
    case preparing(done: Int, total: Int)
    case fitting(page: Int, total: Int)
}

/// A PDF opened for rendering: CoreGraphics in the app and in tests.
public protocol PdfPages: Sendable {
    var pageCount: Int { get }
    /// The displayed page's size in points.
    func pageSize(_ index: Int) -> PdfSize
    /// The page scaled to exactly `width` × `height` pixels on white.
    func render(_ index: Int, width: Int, height: Int) -> Raster?
}

/// Opens PDF bytes for rendering; `nil` when the platform cannot read them.
public protocol PdfOpener: Sendable {
    func open(_ bytes: [UInt8]) -> PdfPages?
}

/// Decodes a picture upright with the long side capped (ALGORITHMS 1.1); `nil` when it does not decode.
public protocol PictureDecoder: Sendable {
    func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster?
}
