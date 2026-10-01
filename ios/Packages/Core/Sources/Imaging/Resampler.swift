import Foundation

/// High-quality separable resize: exact area averaging when shrinking an axis, bilinear when enlarging it. Mirrors the
/// Kotlin implementation so both platforms resample alike and the engines need no platform graphics API.
public enum Resampler {
    private struct Weights {
        var first: [Int]
        var weights: [[Float]]
    }

    public static func resize(_ src: Raster, width w: Int, height h: Int) -> Raster {
        if w == src.width && h == src.height { return src }
        let horizontal = w == src.width ? nil : axisWeights(src.width, w)
        let vertical = h == src.height ? nil : axisWeights(src.height, h)
        let mid = horizontal.map { passHorizontal(src, $0, w) } ?? src.rgb.map { Float($0) }
        if let vertical { return passVertical(mid, w, vertical, h) }
        return Raster(width: w, height: src.height, rgb: mid.map(clamp))
    }

    private static func axisWeights(_ srcN: Int, _ dstN: Int) -> Weights {
        var first = [Int](repeating: 0, count: dstN)
        var weights = [[Float]](repeating: [], count: dstN)
        let scale = Double(srcN) / Double(dstN)
        for d in 0..<dstN {
            if scale > 1.0 { // shrinking: the destination pixel covers [d*scale, (d+1)*scale) of the source
                let lo = Double(d) * scale
                let hi = Double(d + 1) * scale
                let i0 = Int(lo)
                let i1 = min(srcN - 1, Int(hi.rounded(.up)) - 1)
                var w = [Float](repeating: 0, count: i1 - i0 + 1)
                var sum: Float = 0
                for i in i0...i1 {
                    let cover = Float(min(hi, Double(i + 1)) - max(lo, Double(i)))
                    w[i - i0] = cover
                    sum += cover
                }
                for k in w.indices { w[k] /= sum }
                first[d] = i0
                weights[d] = w
            } else { // enlarging: bilinear around the centre of the destination pixel
                let pos = (Double(d) + 0.5) * scale - 0.5
                let i0 = Int(pos.rounded(.down))
                let frac = Float(pos - Double(i0))
                let a = min(max(i0, 0), srcN - 1)
                let b = min(max(i0 + 1, 0), srcN - 1)
                first[d] = a
                weights[d] = a == b ? [1] : [1 - frac, frac]
            }
        }
        return Weights(first: first, weights: weights)
    }

    private static func passHorizontal(_ src: Raster, _ wts: Weights, _ dstW: Int) -> [Float] {
        var out = [Float](repeating: 0, count: dstW * src.height * 3)
        src.rgb.withUnsafeBufferPointer { s in
            for y in 0..<src.height {
                for x in 0..<dstW {
                    let w = wts.weights[x]
                    let from = wts.first[x]
                    for c in 0..<3 {
                        var acc: Float = 0
                        for k in w.indices { acc += w[k] * Float(s[((y * src.width) + from + k) * 3 + c]) }
                        out[(y * dstW + x) * 3 + c] = acc
                    }
                }
            }
        }
        return out
    }

    private static func passVertical(_ mid: [Float], _ w: Int, _ wts: Weights, _ dstH: Int) -> Raster {
        var out = [UInt8](repeating: 0, count: w * dstH * 3)
        mid.withUnsafeBufferPointer { m in
            for y in 0..<dstH {
                let wy = wts.weights[y]
                let from = wts.first[y]
                for x in 0..<w {
                    for c in 0..<3 {
                        var acc: Float = 0
                        for k in wy.indices { acc += wy[k] * m[((from + k) * w + x) * 3 + c] }
                        out[(y * w + x) * 3 + c] = clamp(acc)
                    }
                }
            }
        }
        return Raster(width: w, height: dstH, rgb: out)
    }

    private static func clamp(_ v: Float) -> UInt8 { UInt8(min(max(Int(v + 0.5), 0), 255)) }
}
