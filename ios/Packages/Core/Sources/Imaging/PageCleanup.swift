import Foundation

/// Photographed pages for the PDF engine (ALGORITHMS 6.2 / 9.10): the paper turns white and ink, photos and seals
/// stay dark, by the thumb's paper-only tone (9.5 steps 1–4). Grey output, or each colour channel scaled by the
/// same gain.
public enum PageCleanup {
    public static func clean(_ src: Raster, grayscale: Bool) -> Raster {
        let (l, tone) = InkCleanup.paperTone(src)
        var out = [UInt8](repeating: 0, count: src.rgb.count)
        for p in 0..<l.count {
            let i = p * 3
            if grayscale {
                let v = UInt8(tone[p])
                out[i] = v; out[i + 1] = v; out[i + 2] = v
            } else {
                let luma = l[p], gain = tone[p]
                for c in 0..<3 {
                    let channel = Int(src.rgb[i + c])
                    out[i + c] = UInt8(min(255, (channel * gain + luma / 2) / max(luma, 1)))
                }
            }
        }
        return Raster(width: src.width, height: src.height, rgb: out)
    }

    /// Every pixel becomes its luma, R = G = B = L (ALGORITHMS 6.2 grayscale without cleanup).
    public static func grayscale(_ src: Raster) -> Raster {
        let l = src.luma()
        var out = [UInt8](repeating: 0, count: src.rgb.count)
        for p in 0..<l.count {
            out[p * 3] = l[p]; out[p * 3 + 1] = l[p]; out[p * 3 + 2] = l[p]
        }
        return Raster(width: src.width, height: src.height, rgb: out)
    }
}
