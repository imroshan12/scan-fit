import Foundation

public enum InkVariant: Sendable { case signature, document, thumb }

/// Outcome of the coverage gate (ALGORITHMS 9.5): the UI warns "too faint" / "too dark or not on white paper".
public enum InkQuality: Sendable, Equatable { case ok, tooFaint, tooDark }

public struct InkOptions: Sendable, Equatable {
    public static let defaultInkFactor = 0.6
    /// Nil = the variant's default (signature: on, document: off).
    public var crispBlack: Bool?
    /// Ink colour = original pixel x this factor when not crisp black (the "Darker ink" slider; lower = darker).
    public var inkFactor: Double

    public init(crispBlack: Bool? = nil, inkFactor: Double = InkOptions.defaultInkFactor) {
        self.crispBlack = crispBlack
        self.inkFactor = inkFactor
    }
}

public struct InkResult: Sendable {
    public let raster: Raster
    public let quality: InkQuality
    public let coverage: Double

    public init(raster: Raster, quality: InkQuality, coverage: Double) {
        self.raster = raster
        self.quality = quality
        self.coverage = coverage
    }
}

/// Turns a phone photo of ink on paper into black-on-white (ALGORITHMS section 3 / 9.5). The input should
/// already be rectified (document scanner or 4-corner crop) and no larger than 1600 px on its long side.
public enum InkCleanup {
    private static let sauvolaK = 0.34, sauvolaR = 128.0
    private static let trimPadding = 0.08, thumbSide = 1.16
    private static let minCoverage = 0.005, maxCoverage = 0.35
    private static let gamma = 0.8

    public static func clean(_ src: Raster, variant: InkVariant, options: InkOptions = InkOptions()) -> InkResult {
        let w = src.width, h = src.height
        let l = src.luma().map { Int($0) }
        let bg = ImageOps.gaussianApprox(l, w, h, sigma: Double(min(w, h)) / 30.0)
        let n = (0..<(w * h)).map { min(255, (l[$0] * 255 + bg[$0] / 2) / max(bg[$0], 1)) }
        return variant == .thumb ? thumb(n, w, h) : binarised(src, n, w, h, variant, options)
    }

    private static func binarised(
        _ src: Raster, _ n: [Int], _ w: Int, _ h: Int, _ variant: InkVariant, _ options: InkOptions
    ) -> InkResult {
        var window = max(15, min(w, h) / 20)
        if window % 2 == 0 { window += 1 }
        var mask = ImageOps.sauvola(n, w, h, window: window, k: sauvolaK, range: sauvolaR)
        if variant == .document {
            mask = ImageOps.open3x3(mask, w, h)
            mask = ImageOps.removeSpecks(mask, w, h, minSize: max(4, Int(0.0002 * Double(w * h))))
        }

        let crisp = options.crispBlack ?? (variant == .signature)
        var out = [UInt8](repeating: 255, count: w * h * 3)
        var minX = w, minY = h, maxX = -1, maxY = -1
        for y in 0..<h {
            for x in 0..<w where mask[y * w + x] == 1 {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                let i = (y * w + x) * 3
                if crisp {
                    out[i] = 0; out[i + 1] = 0; out[i + 2] = 0
                } else {
                    out[i] = darken(src.r(x, y), options.inkFactor)
                    out[i + 1] = darken(src.g(x, y), options.inkFactor)
                    out[i + 2] = darken(src.b(x, y), options.inkFactor)
                }
            }
        }
        let rendered = Raster(width: w, height: h, rgb: out)
        guard maxX >= 0 else { return InkResult(raster: rendered, quality: .tooFaint, coverage: 0) }
        let bw = maxX - minX + 1, bh = maxY - minY + 1
        let pad = roundHalfUp(trimPadding * Double(max(bw, bh)))
        let trimmed = rendered.crop(x: minX - pad, y: minY - pad, width: bw + 2 * pad, height: bh + 2 * pad)
        let ink = mask.reduce(0, +)
        let coverage = Double(ink) / (Double(trimmed.width) * Double(trimmed.height))
        return InkResult(raster: trimmed, quality: gate(coverage), coverage: coverage)
    }

    private static func darken(_ v: Int, _ factor: Double) -> UInt8 {
        UInt8(min(max(roundHalfUp(Double(v) * factor), 0), 255))
    }

    private static func thumb(_ n: [Int], _ w: Int, _ h: Int) -> InkResult {
        var hist = [Int](repeating: 0, count: 256)
        for v in n { hist[v] += 1 }
        let p2 = percentile(hist, w * h, 0.02)
        let p98 = percentile(hist, w * h, 0.98)
        let lut: [Int] = (0..<256).map { v in
            let stretched = p98 > p2 ? min(max((v - p2) * 255 / (p98 - p2), 0), 255) : v
            return min(max(roundHalfUp(255.0 * pow(Double(stretched) / 255.0, gamma)), 0), 255)
        }
        var grey = [UInt8](repeating: 0, count: w * h * 3)
        var count = 0, sx = 0, sy = 0
        var minX = w, minY = h, maxX = -1, maxY = -1
        for y in 0..<h {
            for x in 0..<w {
                let v = lut[n[y * w + x]]
                let i = (y * w + x) * 3
                grey[i] = UInt8(v); grey[i + 1] = UInt8(v); grey[i + 2] = UInt8(v)
                if v < 128 {
                    count += 1; sx += x; sy += y
                    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                }
            }
        }
        let full = Raster(width: w, height: h, rgb: grey)
        guard count > 0 else { return InkResult(raster: full, quality: .tooFaint, coverage: 0) }
        let side = max(1, roundHalfUp(thumbSide * Double(max(maxX - minX + 1, maxY - minY + 1))))
        let cx = roundHalfUp(Double(sx) / Double(count))
        let cy = roundHalfUp(Double(sy) / Double(count))
        let square = full.crop(x: cx - side / 2, y: cy - side / 2, width: side, height: side)
        var inside = 0
        for y in 0..<side {
            for x in 0..<side where square.r(x, y) < 128 {
                let fx = cx - side / 2 + x, fy = cy - side / 2 + y
                if fx >= 0 && fx < w && fy >= 0 && fy < h { inside += 1 }
            }
        }
        let coverage = Double(inside) / (Double(side) * Double(side))
        return InkResult(raster: square, quality: gate(coverage), coverage: coverage)
    }

    private static func percentile(_ hist: [Int], _ total: Int, _ p: Double) -> Int {
        let goal = Int(p * Double(total))
        var acc = 0
        for v in hist.indices {
            acc += hist[v]
            if acc > goal { return v }
        }
        return 255
    }

    private static func gate(_ coverage: Double) -> InkQuality {
        coverage < minCoverage ? .tooFaint : (coverage > maxCoverage ? .tooDark : .ok)
    }
}
