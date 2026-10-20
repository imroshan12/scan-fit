import CoreGraphics
import Foundation
import ImageIO
import Imaging
import PDF

/// Decodes Kit files for thumbnails and previews, downsampled by ImageIO (CLAUDE.md rule 4); PDFs through
/// CoreGraphics. A protocol so the view models are testable without image decoding.
public protocol KitImages: Sendable {
    /// An upright image at most `maxPixelSize` on its long side, or nil when the bytes are not an image.
    func image(_ bytes: [UInt8], maxPixelSize: Int) -> CGImage?
    /// How many pages a PDF has; nil when it does not open.
    func pdfPageCount(_ bytes: [UInt8]) -> Int?
    /// Page `index` of a PDF, `width` pixels wide (aspect kept); nil when it cannot be drawn.
    func pdfPage(_ bytes: [UInt8], index: Int, width: Int) -> CGImage?
}

public extension KitImages {
    func pdfPageCount(_: [UInt8]) -> Int? { nil }
    func pdfPage(_: [UInt8], index _: Int, width _: Int) -> CGImage? { nil }

    /// A PDF's first page with its long side at most `maxPixelSize` (a history thumbnail).
    func pdfThumbnail(_ bytes: [UInt8], maxPixelSize: Int) -> CGImage? {
        pdfPage(bytes, index: 0, width: Int(Double(maxPixelSize) * 0.71))
    }
}

public struct LiveKitImages: KitImages {
    private let opener = CGPdfOpener()

    public init() {}

    public func image(_ bytes: [UInt8], maxPixelSize: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(Data(bytes) as CFData, nil) else { return nil }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ] as CFDictionary
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options)
    }

    public func pdfPageCount(_ bytes: [UInt8]) -> Int? { opener.open(bytes)?.pageCount }

    public func pdfPage(_ bytes: [UInt8], index: Int, width: Int) -> CGImage? {
        guard let pages = opener.open(bytes), index >= 0, index < pages.pageCount, width > 0 else { return nil }
        let size = pages.pageSize(index)
        let height = max(1, Int(Double(width) * size.height / size.width))
        return pages.render(index, width: width, height: height)?.cgImage
    }
}
