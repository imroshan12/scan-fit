import Foundation

/// A size in PDF points (1/72 in).
public struct PdfSize: Sendable, Equatable {
    public let width: Double
    public let height: Double

    public init(width: Double, height: Double) {
        self.width = width
        self.height = height
    }
}

/// A rectangle in points, origin top-left, y down (ALGORITHMS 9.10).
public struct PdfRect: Sendable, Equatable {
    public let x: Double, y: Double, width: Double, height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

/// One image placed on a page: where it goes and how many points one source pixel covers.
public struct Placement: Sendable, Equatable {
    public let rect: PdfRect
    public let scale: Double
}

/// Pixel dimensions of an image to render or resample.
public struct PixelSize: Sendable, Equatable, Hashable {
    public let width: Int
    public let height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }
}

/// Page geometry of the PDF engine (ALGORITHMS 9.10, `pdf_layout_cases`). Every formula keeps the operation order
/// of the spec so Swift, Kotlin and the Python reference compute the same doubles.
public enum PdfLayout {
    public static let a4Width = 595.0
    public static let a4Height = 842.0
    public static let dpiLadder = [200, 150, 120, 100]
    private static let pointsPerInch = 72.0
    private static let margin = 0.05 * a4Width
    private static let mm = pointsPerInch / 25.4
    private static let cardWidth = 85.6 * mm
    private static let cardHeight = 54.0 * mm
    private static let cardGap = 15.0 * mm

    /// An image on its own A4 page: portrait unless the image is wider than tall, 5 % margins, centred.
    public static func imagePage(_ w: Int, _ h: Int) -> (PdfSize, Placement) {
        let page = w <= h ? PdfSize(width: a4Width, height: a4Height) : PdfSize(width: a4Height, height: a4Width)
        let bw = page.width - 2 * margin
        let bh = page.height - 2 * margin
        let s = min(bw / Double(w), bh / Double(h))
        let rw = Double(w) * s
        let rh = Double(h) * s
        let rect = PdfRect(x: (page.width - rw) / 2, y: (page.height - rh) / 2, width: rw, height: rh)
        return (page, Placement(rect: rect, scale: s))
    }

    /// Front above back, each contained in an 85.6 × 54 mm box, 15 mm apart, the pair centred on A4 portrait.
    public static func idCardPage(front: PixelSize, back: PixelSize) -> (PdfSize, [Placement]) {
        let x = (a4Width - cardWidth) / 2
        let frontY = (a4Height - (2 * cardHeight + cardGap)) / 2
        let backY = frontY + cardHeight + cardGap
        let placements = [(front, frontY), (back, backY)].map { side, y in
            let s = min(cardWidth / Double(side.width), cardHeight / Double(side.height))
            let rw = Double(side.width) * s
            let rh = Double(side.height) * s
            return Placement(
                rect: PdfRect(x: x + (cardWidth - rw) / 2, y: y + (cardHeight - rh) / 2, width: rw, height: rh),
                scale: s
            )
        }
        return (PdfSize(width: a4Width, height: a4Height), placements)
    }

    /// Pixels of a `w × h` image placed at `scale` points per pixel, rendered at `dpi`: never upscaled, long side.
    public static func pixels(_ w: Int, _ h: Int, scale: Double, dpi: Int) -> PixelSize {
        let k = scale * Double(dpi) / pointsPerInch
        if k >= 1 { return PixelSize(width: w, height: h) }
        if w >= h {
            let pw = max(1, roundHalfUp(Double(w) * k))
            return PixelSize(width: pw, height: max(1, roundHalfUp(Double(h * pw) / Double(w))))
        }
        let ph = max(1, roundHalfUp(Double(h) * k))
        return PixelSize(width: max(1, roundHalfUp(Double(w * ph) / Double(h))), height: ph)
    }

    /// Pixels of a whole PDF page at `dpi`; a page larger than A4 gets A4's pixel budget.
    public static func pdfPagePixels(_ page: PdfSize, dpi: Int) -> PixelSize {
        let k = (Double(dpi) / pointsPerInch) * min(1, a4Height / max(page.width, page.height))
        return PixelSize(width: max(1, roundHalfUp(page.width * k)), height: max(1, roundHalfUp(page.height * k)))
    }
}

/// round(x) = floor(x + 0.5) (ALGORITHMS 9.1).
@inline(__always) func roundHalfUp(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }
