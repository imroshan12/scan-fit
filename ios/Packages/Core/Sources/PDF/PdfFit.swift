import Foundation
import ScanModel

/// How the PDF reached its window (analytics enum only).
public enum PdfStrategy: String, Sendable, Equatable {
    case quality, lowerDpi = "lower_dpi", pad, unchanged
}

/// Why no PDF was made (ALGORITHMS 9.10 job checks).
public enum PdfError: String, Error, Sendable, Equatable {
    case noPages = "no_pages", encrypted, unreadable, tooManyPages = "too_many_pages"
    case unknownLimit = "unknown_limit", cannotReach = "cannot_reach"
    /// Encoding or writing produced something the checks refused (a bug, surfaced instead of exported).
    case failed
}

/// §9.4 window in KB: the goal band and the effective target `T'`.
public struct PdfWindow: Sendable, Equatable {
    public let min: Double, max: Double, goalLo: Double, goalHi: Double, target: Double

    public var hiBytes: Double { goalHi * 1024 }
    public var loBytes: Double { goalLo * 1024 }

    /// `nil` when the slot has no maximum (`unknownLimit`).
    public init?(_ sizeKb: SizeKB) {
        guard let max = sizeKb.max else { return nil }
        let min = sizeKb.min ?? 0
        let margin = Swift.max(1.0, 0.05 * (max - min))
        var lo = min + margin, hi = max - margin
        if lo > hi { lo = min; hi = max }
        self.min = min
        self.max = max
        goalLo = lo
        goalHi = hi
        target = Swift.min(Swift.max(sizeKb.target ?? (min + max) / 2, lo), hi)
    }
}

/// The chosen DPI and quality, and the pad target in bytes when the PDF must be made bigger.
public struct PdfChoice: Sendable, Equatable {
    public let dpi: Int
    public let quality: Int
    public let padTo: Int?
    public let strategy: PdfStrategy
}

/// The fit selection of ALGORITHMS 9.10 (`pdf_fit_cases`). Pure.
public enum PdfFit {
    public static let qualityGrid = [85, 75, 65, 55, 45, 40]
    /// The last rung (100 DPI) only: ImageIO compresses less than libjpeg at equal quality (ALGORITHMS 9.10).
    private static let lowTail = [35, 30, 25, 20]

    /// The qualities tried at `dpi`, highest first.
    public static func grid(_ dpi: Int) -> [Int] {
        dpi == PdfLayout.dpiLadder.last ? qualityGrid + lowTail : qualityGrid
    }
    public static let maxPages = 20

    /// `sizes` maps a DPI to the whole PDF's bytes at each grid quality; a DPI with no entry was not measured.
    public static func choose(_ window: PdfWindow, sizes: [Int: [Int]]) -> PdfChoice? {
        for dpi in PdfLayout.dpiLadder {
            guard let atDpi = sizes[dpi] else { continue }
            if let choice = choose(window, dpi: dpi, sizes: atDpi) { return choice }
        }
        return nil
    }

    /// The choice at one DPI, or `nil` when even its lowest quality is over `goal.hi`.
    public static func choose(_ window: PdfWindow, dpi: Int, sizes: [Int]) -> PdfChoice? {
        guard let last = sizes.last, Double(last) <= window.hiBytes,
              let index = sizes.firstIndex(where: { Double($0) <= window.hiBytes }) else { return nil }
        // A Tools limit (no minimum) never pads: only a slot's minimum is a reason to make a PDF bigger.
        let padded = window.min > 0 && Double(sizes[index]) < window.loBytes
        let strategy: PdfStrategy = padded ? .pad : (dpi == 200 ? .quality : .lowerDpi)
        return PdfChoice(
            dpi: dpi, quality: grid(dpi)[index], padTo: padded ? roundHalfUp(window.target * 1024) : nil,
            strategy: strategy
        )
    }
}
