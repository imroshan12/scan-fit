import CoreGraphics
import Foundation

/// Turns a segmentation model's person confidence (0...1 per pixel, row-major, `maskW×maskH`) into the whitening mask
/// for a `width×height` raster (ALGORITHMS 9.6): resampled bilinearly when the sizes differ, then a hard threshold at
/// 0.5 into 0 (background) or 255 (person). The feathering in `BackgroundWhitening` is the only softening.
public enum PersonMask {
    public static func fromConfidence(
        _ confidence: [Float], maskW: Int, maskH: Int, width: Int, height: Int
    ) -> [UInt8] {
        precondition(maskW > 0 && maskH > 0 && confidence.count == maskW * maskH, "mask size mismatch")
        precondition(width > 0 && height > 0, "empty raster")
        var out = [UInt8](repeating: 0, count: width * height)
        let sx = Double(maskW) / Double(width)
        let sy = Double(maskH) / Double(height)
        for y in 0..<height {
            let fy = min(max((Double(y) + 0.5) * sy - 0.5, 0), Double(maskH - 1))
            let y0 = Int(fy), y1 = min(Int(fy) + 1, maskH - 1)
            let ty = Float(fy - Double(y0))
            for x in 0..<width {
                let fx = min(max((Double(x) + 0.5) * sx - 0.5, 0), Double(maskW - 1))
                let x0 = Int(fx), x1 = min(Int(fx) + 1, maskW - 1)
                let tx = Float(fx - Double(x0))
                let top = confidence[y0 * maskW + x0] * (1 - tx) + confidence[y0 * maskW + x1] * tx
                let bottom = confidence[y1 * maskW + x0] * (1 - tx) + confidence[y1 * maskW + x1] * tx
                if top * (1 - ty) + bottom * ty >= 0.5 { out[y * width + x] = 255 }
            }
        }
        return out
    }
}

public extension Raster {
    /// The pixels as an sRGB `CGImage` (for Vision and on-screen previews).
    var cgImage: CGImage? { ImageIOJpegEncoder.cgImage(from: self) }
}
