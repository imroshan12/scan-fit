import Foundation

/// Plane operations for ink cleanup (ALGORITHMS 9.5). Planes are row-major `[Int]` of 0...255 (or 0/1 for masks).
enum ImageOps {
    /// Widths of the three box blurs that approximate a Gaussian of `sigma` ("boxes for Gauss", ALGORITHMS 9.5).
    static func boxWidths(_ sigma: Double) -> [Int] {
        let ideal = (12.0 * sigma * sigma / 3.0 + 1).squareRoot()
        var wl = Int(ideal.rounded(.down))
        if wl % 2 == 0 { wl -= 1 }
        wl = max(wl, 1)
        let wu = wl + 2
        let mIdeal = (12.0 * sigma * sigma - 3.0 * Double(wl * wl) - 12.0 * Double(wl) - 9.0) / (-4.0 * Double(wl) - 4.0)
        let m = min(max(roundHalfUp(mIdeal), 0), 3)
        return (0..<3).map { $0 < m ? wl : wu }
    }

    static func gaussianApprox(_ plane: [Int], _ w: Int, _ h: Int, sigma: Double) -> [Int] {
        var cur = plane
        for width in boxWidths(sigma) where width > 1 {
            cur = boxBlurVertical(boxBlurHorizontal(cur, w, h, width), w, h, width)
        }
        return cur
    }

    private static func boxBlurHorizontal(_ src: [Int], _ w: Int, _ h: Int, _ width: Int) -> [Int] {
        let r = (width - 1) / 2
        var out = [Int](repeating: 0, count: src.count)
        for y in 0..<h {
            let row = y * w
            var sum = 0
            for i in -r...r { sum += src[row + min(max(i, 0), w - 1)] }
            for x in 0..<w {
                out[row + x] = (sum + width / 2) / width
                sum += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
        return out
    }

    private static func boxBlurVertical(_ src: [Int], _ w: Int, _ h: Int, _ width: Int) -> [Int] {
        let r = (width - 1) / 2
        var out = [Int](repeating: 0, count: src.count)
        for x in 0..<w {
            var sum = 0
            for i in -r...r { sum += src[min(max(i, 0), h - 1) * w + x] }
            for y in 0..<h {
                out[y * w + x] = (sum + width / 2) / width
                sum += src[min(y + r + 1, h - 1) * w + x] - src[max(y - r, 0) * w + x]
            }
        }
        return out
    }

    /// Sauvola threshold (ALGORITHMS 9.5): 1 where the pixel is ink. Window clipped at the borders.
    static func sauvola(_ n: [Int], _ w: Int, _ h: Int, window: Int, k: Double, range: Double) -> [Int] {
        let stride = w + 1
        var sum = [Int](repeating: 0, count: stride * (h + 1))
        var sq = [Int](repeating: 0, count: stride * (h + 1))
        for y in 0..<h {
            var rowSum = 0
            var rowSq = 0
            for x in 0..<w {
                let v = n[y * w + x]
                rowSum += v
                rowSq += v * v
                sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + rowSum
                sq[(y + 1) * stride + x + 1] = sq[y * stride + x + 1] + rowSq
            }
        }
        func rect(_ t: [Int], _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Int {
            t[(y1 + 1) * stride + x1 + 1] - t[y0 * stride + x1 + 1] - t[(y1 + 1) * stride + x0] + t[y0 * stride + x0]
        }
        let r = window / 2
        var out = [Int](repeating: 0, count: w * h)
        for y in 0..<h {
            let y0 = max(0, y - r), y1 = min(h - 1, y + r)
            for x in 0..<w {
                let x0 = max(0, x - r), x1 = min(w - 1, x + r)
                let count = Double((x1 - x0 + 1) * (y1 - y0 + 1))
                let s = Double(rect(sum, x0, y0, x1, y1))
                let s2 = Double(rect(sq, x0, y0, x1, y1))
                let mean = s / count
                let sd = max(s2 / count - mean * mean, 0).squareRoot()
                let threshold = mean * (1 + k * (sd / range - 1))
                if Double(n[y * w + x]) < threshold { out[y * w + x] = 1 }
            }
        }
        return out
    }

    /// 3x3 binary opening; erosion treats outside as ink, dilation as background (ALGORITHMS 9.5).
    static func open3x3(_ mask: [Int], _ w: Int, _ h: Int) -> [Int] { dilate(erode(mask, w, h), w, h) }

    private static func erode(_ m: [Int], _ w: Int, _ h: Int) -> [Int] {
        var out = [Int](repeating: 0, count: m.count)
        for y in 0..<h {
            for x in 0..<w {
                var all = 1
                scan: for dy in -1...1 {
                    for dx in -1...1 {
                        let nx = x + dx, ny = y + dy
                        if nx < 0 || ny < 0 || nx >= w || ny >= h { continue }
                        if m[ny * w + nx] == 0 {
                            all = 0
                            break scan
                        }
                    }
                }
                out[y * w + x] = all
            }
        }
        return out
    }

    private static func dilate(_ m: [Int], _ w: Int, _ h: Int) -> [Int] {
        var out = [Int](repeating: 0, count: m.count)
        for y in 0..<h {
            for x in 0..<w {
                var any = 0
                scan: for dy in -1...1 {
                    for dx in -1...1 {
                        let nx = x + dx, ny = y + dy
                        if nx < 0 || ny < 0 || nx >= w || ny >= h { continue }
                        if m[ny * w + nx] == 1 {
                            any = 1
                            break scan
                        }
                    }
                }
                out[y * w + x] = any
            }
        }
        return out
    }

    /// Removes 8-connected components smaller than `minSize` pixels.
    static func removeSpecks(_ mask: [Int], _ w: Int, _ h: Int, minSize: Int) -> [Int] {
        var out = mask
        var seen = [Bool](repeating: false, count: mask.count)
        var stack = [Int](repeating: 0, count: mask.count)
        for start in mask.indices where mask[start] == 1 && !seen[start] {
            var top = 0
            var size = 0
            stack[top] = start
            top += 1
            seen[start] = true
            var members: [Int] = []
            while top > 0 {
                top -= 1
                let p = stack[top]
                size += 1
                if size <= minSize { members.append(p) }
                let px = p % w, py = p / w
                for dy in -1...1 {
                    for dx in -1...1 {
                        let nx = px + dx, ny = py + dy
                        if nx < 0 || ny < 0 || nx >= w || ny >= h { continue }
                        let q = ny * w + nx
                        if mask[q] == 1 && !seen[q] {
                            seen[q] = true
                            stack[top] = q
                            top += 1
                        }
                    }
                }
            }
            if size < minSize { for member in members { out[member] = 0 } }
        }
        return out
    }
}
