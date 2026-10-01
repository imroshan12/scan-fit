import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Production JPEG encoder: ImageIO, baseline, sRGB, 8-bit RGB. The result goes through `JpegPatcher`, which strips whatever
/// metadata ImageIO adds (an sRGB ICC profile is removed, anything else is refused).
public struct ImageIOJpegEncoder: JpegEncoder {
    public init() {}

    public func encode(_ raster: Raster, quality: Int) -> [UInt8] {
        guard let image = Self.cgImage(from: raster),
              let data = CFDataCreateMutable(nil, 0),
              let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return [] }
        let options: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: Double(quality) / 100.0]
        CGImageDestinationAddImage(destination, image, options as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return [] }
        return [UInt8](data as Data)
    }

    static func cgImage(from raster: Raster) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(raster.rgb) as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: raster.width, height: raster.height, bitsPerComponent: 8, bitsPerPixel: 24,
                       bytesPerRow: raster.width * 3, space: space, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }
}

/// Production decoder (ALGORITHMS 1.1): downsamples on decode (never a full-resolution bitmap), applies the EXIF orientation,
/// converts to 8-bit sRGB and flattens alpha onto white.
public enum ImageIODecoder {
    /// The cap for the decoded long side given the largest target dimension (ALGORITHMS 1.1 step 2).
    public static func longSideCap(largestTargetDimension: Int) -> Int { max(2 * largestTargetDimension, 1600) }

    public static func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster? {
        guard let source = CGImageSourceCreateWithData(Data(bytes) as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let storedW = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let storedH = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue, storedW > 0, storedH > 0 else { return nil }
        let orientation = (props[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        let long = max(storedW, storedH)
        let target = min(maxLongSide, long)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: target,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              let raster = raster(from: image) else { return nil }
        // Exact output size per ALGORITHMS 9.1 (ImageIO may be off by a pixel when rounding).
        let swapped = (5...8).contains(orientation)
        let orientedW = swapped ? storedH : storedW
        let orientedH = swapped ? storedW : storedH
        let scale = Double(target) / Double(long)
        let wantW = target == long ? orientedW : (orientedW >= orientedH ? target : max(1, roundHalfUp(Double(orientedW) * scale)))
        let wantH = target == long ? orientedH : (orientedH > orientedW ? target : max(1, roundHalfUp(Double(orientedH) * scale)))
        return (raster.width == wantW && raster.height == wantH) ? raster : Resampler.resize(raster, width: wantW, height: wantH)
    }

    static func raster(from image: CGImage) -> Raster? {
        let w = image.width
        let h = image.height
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1) // flatten alpha onto white
        context.fill(CGRect(x: 0, y: 0, width: w, height: h))
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let base = context.data else { return nil }
        let rgba = base.bindMemory(to: UInt8.self, capacity: w * h * 4)
        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        for p in 0..<(w * h) {
            rgb[p * 3] = rgba[p * 4]
            rgb[p * 3 + 1] = rgba[p * 4 + 1]
            rgb[p * 3 + 2] = rgba[p * 4 + 2]
        }
        return Raster(width: w, height: h, rgb: rgb)
    }
}
