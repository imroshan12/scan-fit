import Foundation

/// 8-bit sRGB, interleaved R,G,B, row-major, no alpha (ALGORITHMS 9.1).
public struct Raster: Sendable, Equatable {
    public static let channels = 3

    public let width: Int
    public let height: Int
    public let rgb: [UInt8]

    /// Traps on an empty or mis-sized buffer: that is a programmer error, never user input.
    public init(width: Int, height: Int, rgb: [UInt8]) {
        precondition(width > 0 && height > 0, "empty raster \(width)x\(height)")
        let expected = width * height * Self.channels
        precondition(rgb.count == expected, "buffer size \(rgb.count) != \(expected)")
        self.width = width
        self.height = height
        self.rgb = rgb
    }

    public var longSide: Int { max(width, height) }

    @inline(__always) public func r(_ x: Int, _ y: Int) -> Int { Int(rgb[(y * width + x) * 3]) }
    @inline(__always) public func g(_ x: Int, _ y: Int) -> Int { Int(rgb[(y * width + x) * 3 + 1]) }
    @inline(__always) public func b(_ x: Int, _ y: Int) -> Int { Int(rgb[(y * width + x) * 3 + 2]) }

    /// Integer luma plane, `L = (299R + 587G + 114B + 500) / 1000` (ALGORITHMS 9.1).
    public func luma() -> [UInt8] {
        var out = [UInt8](repeating: 0, count: width * height)
        var i = 0
        for p in 0..<(width * height) {
            out[p] = UInt8((299 * Int(rgb[i]) + 587 * Int(rgb[i + 1]) + 114 * Int(rgb[i + 2]) + 500) / 1000)
            i += 3
        }
        return out
    }

    /// Copies the window; anything outside this raster is white. The window may start at negative coordinates.
    public func crop(x: Int, y: Int, width w: Int, height h: Int) -> Raster {
        var out = [UInt8](repeating: 255, count: w * h * 3)
        for dy in 0..<h {
            let sy = y + dy
            guard sy >= 0, sy < height else { continue }
            let from = max(0, -x)
            let to = min(w, width - x)
            guard from < to else { continue }
            let src = (sy * width + x + from) * 3
            let dst = (dy * w + from) * 3
            out.replaceSubrange(dst..<(dst + (to - from) * 3), with: rgb[src..<(src + (to - from) * 3)])
        }
        return Raster(width: w, height: h, rgb: out)
    }

    public static func white(_ w: Int, _ h: Int) -> Raster {
        Raster(width: w, height: h, rgb: [UInt8](repeating: 255, count: w * h * 3))
    }

    /// Builds a raster from a per-pixel packed 0xRRGGBB function (handy for tests).
    public static func make(_ w: Int, _ h: Int, _ pixel: (_ x: Int, _ y: Int) -> Int) -> Raster {
        var out = [UInt8](repeating: 0, count: w * h * 3)
        for y in 0..<h {
            for x in 0..<w {
                let c = pixel(x, y)
                let i = (y * w + x) * 3
                out[i] = UInt8((c >> 16) & 0xFF)
                out[i + 1] = UInt8((c >> 8) & 0xFF)
                out[i + 2] = UInt8(c & 0xFF)
            }
        }
        return Raster(width: w, height: h, rgb: out)
    }
}

/// round(x) = floor(x + 0.5) (ALGORITHMS 9.1).
@inline(__always) func roundHalfUp(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }
