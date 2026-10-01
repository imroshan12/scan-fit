import Foundation

/// Replaces the background with white using a person mask (ALGORITHMS 2.3 / 9.6).
/// Pixels inside the mask are never altered.
public enum BackgroundWhitening {
    private static let featherPasses = 2

    /// - Parameter mask: alpha per pixel (0 background, 255 person), `src.width * src.height` long.
    public static func composite(_ src: Raster, mask: [UInt8]) -> Raster {
        precondition(mask.count == src.width * src.height, "mask size \(mask.count) != \(src.width * src.height)")
        var alpha = mask.map { Int($0) }
        for _ in 0..<featherPasses { alpha = box3x3(alpha, src.width, src.height) }
        var out = [UInt8](repeating: 0, count: src.rgb.count)
        for p in alpha.indices {
            let a = alpha[p]
            for c in 0..<3 {
                let v = Int(src.rgb[p * 3 + c])
                out[p * 3 + c] = a == 255 ? UInt8(v) : UInt8((v * a + 255 * (255 - a) + 127) / 255)
            }
        }
        return Raster(width: src.width, height: src.height, rgb: out)
    }

    private static func box3x3(_ a: [Int], _ w: Int, _ h: Int) -> [Int] {
        var out = [Int](repeating: 0, count: a.count)
        for y in 0..<h {
            for x in 0..<w {
                var sum = 0
                for dy in -1...1 {
                    for dx in -1...1 { sum += a[min(max(y + dy, 0), h - 1) * w + min(max(x + dx, 0), w - 1)] }
                }
                out[y * w + x] = (sum + 4) / 9
            }
        }
        return out
    }
}
