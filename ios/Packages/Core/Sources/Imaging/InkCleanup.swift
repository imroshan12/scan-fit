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
    /// Thumb tone (ALGORITHMS 9.5): candidates below this normalised level, white point, mid-grey exponent.
    private static let thumbCandidate = 230, thumbWhite = 235, thumbExponent = 1.25
    private static let thumbTrimInk = 240, gateInk = 128

    public static func clean(_ src: Raster, variant: InkVariant, options: InkOptions = InkOptions()) -> InkResult {
        let w = src.width, h = src.height
        let l = src.luma().map { Int($0) }
        let bg = ImageOps.gaussianApprox(l, w, h, sigma: Double(min(w, h)) / 30.0)
        let n = normalise(l, bg)
        return variant == .thumb ? thumb(l, n, w, h) : binarised(src, n, w, h, variant, options)
    }

    private static func normalise(_ l: [Int], _ bg: [Int]) -> [Int] {
        (0..<l.count).map { min(255, (l[$0] * 255 + bg[$0] / 2) / max(bg[$0], 1)) }
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

    /// The thumb's tone (steps 1–4) on `src`'s luma: paper white, ink kept dark. ALGORITHMS 9.10 page cleanup reuses it
    /// for photographed pages. Returns the luma and the toned grey value of every pixel.
    static func paperTone(_ src: Raster) -> (luma: [Int], tone: [Int]) {
        let w = src.width, h = src.height
        let l = src.luma().map { Int($0) }
        let n1 = normalise(l, ImageOps.gaussianApprox(l, w, h, sigma: Double(min(w, h)) / 30.0))
        return (l, thumbTone(l, n1, w, h))
    }

    /// Steps 1–4 of the thumb: candidates, paper-only background, normalisation and the tone curve.
    private static func thumbTone(_ l: [Int], _ n1: [Int], _ w: Int, _ h: Int) -> [Int] {
        let total = w * h
        var lumaHist = [Int](repeating: 0, count: 256)
        for v in l { lumaHist[v] += 1 }
        let otsuLevel = otsu(lumaHist, total)
        var candidates = (0..<total).map { n1[$0] < thumbCandidate || l[$0] <= otsuLevel ? 1 : 0 }
        candidates = ImageOps.dilate3x3(ImageOps.dilate3x3(candidates, w, h), w, h)

        var paperHist = [Int](repeating: 0, count: 256), paperCount = 0
        var weights = [Int](repeating: 0, count: total), paperLuma = [Int](repeating: 0, count: total)
        for i in 0..<total where candidates[i] == 0 {
            paperHist[l[i]] += 1; paperCount += 1
            weights[i] = 255; paperLuma[i] = l[i]
        }
        let paper = paperCount > 0 ? percentile(paperHist, paperCount, 0.5) : 255
        let sigma = Double(min(w, h)) / 8.0
        let b = ImageOps.gaussianApprox(weights, w, h, sigma: sigma)
        let c = ImageOps.gaussianApprox(paperLuma, w, h, sigma: sigma)
        let bg = (0..<total).map { (c[$0] * 255 + 5 * paper) / (b[$0] + 5) }
        let n = normalise(l, bg)

        var inkHist = [Int](repeating: 0, count: 256), inkCount = 0
        for i in 0..<total where candidates[i] == 1 { inkHist[n[i]] += 1; inkCount += 1 }
        let black = inkCount > 0 ? percentile(inkHist, inkCount, 0.02) : 0
        let lut: [Int] = (0..<256).map { v in
            let s = black >= thumbWhite
                ? Double(v) / 255
                : Double(min(max(v - black, 0), thumbWhite - black)) / Double(thumbWhite - black)
            return min(max(roundHalfUp(255 * pow(s, thumbExponent)), 0), 255)
        }
        return n.map { lut[$0] }
    }

    /// Thumb (ALGORITHMS 9.5): normalise by a paper-only background, stretch linearly, darken mid-greys slightly,
    /// then a square around the robust ink box. `n1` is the local-mean normalisation, used only to find candidates.
    private static func thumb(_ l: [Int], _ n1: [Int], _ w: Int, _ h: Int) -> InkResult {
        let total = w * h
        let tone = thumbTone(l, n1, w, h)
        var grey = [UInt8](repeating: 0, count: total * 3)
        var columns = [Int](repeating: 0, count: w), rows = [Int](repeating: 0, count: h), count = 0
        for y in 0..<h {
            for x in 0..<w {
                let v = tone[y * w + x]
                let i = (y * w + x) * 3
                grey[i] = UInt8(v); grey[i + 1] = UInt8(v); grey[i + 2] = UInt8(v)
                if v < thumbTrimInk { columns[x] += 1; rows[y] += 1; count += 1 }
            }
        }
        let full = Raster(width: w, height: h, rgb: grey)
        guard count > 0 else { return InkResult(raster: full, quality: .tooFaint, coverage: 0) }
        let low = Int(0.005 * Double(count - 1)), high = Int(0.995 * Double(count - 1))
        let x0 = position(columns, low), x1 = position(columns, high)
        let y0 = position(rows, low), y1 = position(rows, high)
        let side = max(1, roundHalfUp(thumbSide * Double(max(x1 - x0 + 1, y1 - y0 + 1))))
        let ox = (x0 + x1 + 1) / 2 - side / 2, oy = (y0 + y1 + 1) / 2 - side / 2
        let square = full.crop(x: ox, y: oy, width: side, height: side)
        var inside = 0
        for y in 0..<side {
            for x in 0..<side where square.r(x, y) < gateInk {
                let fx = ox + x, fy = oy + y
                if fx >= 0 && fx < w && fy >= 0 && fy < h { inside += 1 }
            }
        }
        let coverage = Double(inside) / (Double(side) * Double(side))
        return InkResult(raster: square, quality: gate(coverage), coverage: coverage)
    }

    /// The coordinate at sorted position `index` of the pixels counted per coordinate in `counts`.
    private static func position(_ counts: [Int], _ index: Int) -> Int {
        var acc = 0
        for (coordinate, n) in counts.enumerated() {
            acc += n
            if acc > index { return coordinate }
        }
        return counts.count - 1
    }

    /// Otsu level: the `v` maximising the between-class variance of the split `<= v` / `> v` (first maximum).
    static func otsu(_ hist: [Int], _ total: Int) -> Int {
        var sum = 0.0
        for v in 0..<256 { sum += Double(v * hist[v]) }
        var sumLow = 0.0, low = 0.0, best = -1.0, level = 255
        for v in 0..<255 {
            low += Double(hist[v])
            sumLow += Double(v * hist[v])
            let high = Double(total) - low
            guard low > 0, high > 0 else { continue }
            let mean0 = sumLow / low, mean1 = (sum - sumLow) / high
            let between = low * high * (mean0 - mean1) * (mean0 - mean1)
            if between > best { best = between; level = v }
        }
        return level
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
