import Foundation

/// Applies an EXIF orientation (1-8) so the pixels are upright; the output never carries an orientation tag
/// (ALGORITHMS 1.1). `dest(x, y)` is read from `src` per the standard EXIF transforms.
public enum Orientation {
    public static func apply(_ src: Raster, _ orientation: Int) -> Raster {
        guard (2...8).contains(orientation) else { return src }
        let sw = src.width
        let sh = src.height
        let swapped = orientation >= 5
        let dw = swapped ? sh : sw
        let dh = swapped ? sw : sh
        var out = [UInt8](repeating: 0, count: dw * dh * 3)
        for y in 0..<dh {
            for x in 0..<dw {
                let sx: Int
                let sy: Int
                switch orientation {
                case 2: (sx, sy) = (sw - 1 - x, y) // mirror horizontally
                case 3: (sx, sy) = (sw - 1 - x, sh - 1 - y) // rotate 180
                case 4: (sx, sy) = (x, sh - 1 - y) // mirror vertically
                case 5: (sx, sy) = (y, x) // transpose
                case 6: (sx, sy) = (y, sh - 1 - x) // rotate 90 clockwise
                case 7: (sx, sy) = (sw - 1 - y, sh - 1 - x) // transverse
                default: (sx, sy) = (sw - 1 - y, x) // 8: rotate 90 counter-clockwise
                }
                let s = (sy * sw + sx) * 3
                let d = (y * dw + x) * 3
                out[d] = src.rgb[s]
                out[d + 1] = src.rgb[s + 1]
                out[d + 2] = src.rgb[s + 2]
            }
        }
        return Raster(width: dw, height: dh, rgb: out)
    }
}
