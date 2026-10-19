import CoreGraphics
import Foundation
import ImageIO

/// Decodes Kit originals for thumbnails and previews, downsampled by ImageIO (CLAUDE.md rule 4). A protocol so the view
/// models are testable without image decoding.
public protocol KitImages: Sendable {
    /// An upright image at most `maxPixelSize` on its long side, or nil when the bytes are not an image.
    func image(_ bytes: [UInt8], maxPixelSize: Int) -> CGImage?
}

public struct LiveKitImages: KitImages {
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
}
