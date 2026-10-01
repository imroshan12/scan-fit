import Foundation

/// Measures text for the strip layout; the platform text engine implements it (CoreText on Apple platforms).
public protocol TextMeasurer: Sendable {
    func width(_ text: String, sizePx: Float, bold: Bool) -> Float
}

/// One line of strip text, positioned by `centerX` and its `baselineY` in output pixels (y grows downwards).
public struct StripLine: Sendable, Equatable {
    public let text: String
    public let sizePx: Float
    public let bold: Bool
    public let centerX: Float
    public let baselineY: Float
}

public struct StripLayout: Sendable, Equatable {
    public let stripTop: Int
    public let stripHeight: Int
    public let photoArea: Size
    public let lines: [StripLine]
}

/// The white name/date strip added inside the target dimensions (ALGORITHMS 2.4 / 9.6). Layout math is pure.
public enum NameDateStrip {
    private static let stripFraction = 0.18
    private static let nameSize: Float = 0.38, dateSize: Float = 0.32, gap: Float = 0.08
    private static let shrinkFloor: Float = 0.60
    private static let margin = 0.04
    private static let ascent: Float = 0.8
    private static let ellipsis = "…"

    public static func layout(
        name: String,
        date: String,
        width w: Int,
        height h: Int,
        measurer: any TextMeasurer
    ) -> StripLayout {
        let stripH = roundHalfUp(stripFraction * Double(h))
        let stripTop = h - stripH
        let available = Float(Double(w) * (1 - 2 * margin))
        let nameBase = nameSize * Float(stripH)
        let dateSizePx = dateSize * Float(stripH)
        let (nameText, nameSizePx) = fitName(name.uppercased(), nameBase, available, measurer)
        let block = nameSizePx + gap * Float(stripH) + dateSizePx
        let top = Float(stripTop) + (Float(stripH) - block) / 2
        let cx = Float(w) / 2
        let lines = [
            StripLine(text: nameText, sizePx: nameSizePx, bold: true, centerX: cx,
                      baselineY: top + ascent * nameSizePx),
            StripLine(text: date, sizePx: dateSizePx, bold: false, centerX: cx,
                      baselineY: top + nameSizePx + gap * Float(stripH) + ascent * dateSizePx),
        ]
        return StripLayout(stripTop: stripTop, stripHeight: stripH, photoArea: Size(w, h - stripH), lines: lines)
    }

    /// Shrinks to 60 % of the base size, then truncates with an ellipsis (never both silently).
    private static func fitName(
        _ text: String, _ baseSize: Float, _ available: Float, _ m: any TextMeasurer
    ) -> (String, Float) {
        if m.width(text, sizePx: baseSize, bold: true) <= available { return (text, baseSize) }
        let small = baseSize * shrinkFloor
        if m.width(text, sizePx: small, bold: true) <= available {
            var size = baseSize
            while size > small && m.width(text, sizePx: size, bold: true) > available { size -= baseSize * 0.02 }
            return (text, max(size, small))
        }
        var cut = text
        while !cut.isEmpty && m.width(cut + ellipsis, sizePx: small, bold: true) > available { cut.removeLast() }
        return (cut.trimmingCharacters(in: .whitespaces) + ellipsis, small)
    }

    /// Photo scaled uniformly into the area above the strip (letterboxed horizontally);
    /// the strip below stays white for the text.
    public static func placePhoto(_ photo: Raster, layout: StripLayout, width w: Int, height h: Int) -> Raster {
        let area = layout.photoArea
        let scale = min(Double(area.w) / Double(photo.width), Double(area.h) / Double(photo.height))
        let pw = max(1, roundHalfUp(Double(photo.width) * scale))
        let ph = max(1, roundHalfUp(Double(photo.height) * scale))
        let scaled = Resampler.resize(photo, width: pw, height: ph)
        var out = [UInt8](repeating: 255, count: w * h * 3)
        let x0 = (w - pw) / 2, y0 = (area.h - ph) / 2
        for row in 0..<ph {
            let src = row * pw * 3
            let dst = ((y0 + row) * w + x0) * 3
            out.replaceSubrange(dst..<(dst + pw * 3), with: scaled.rgb[src..<(src + pw * 3)])
        }
        return Raster(width: w, height: h, rgb: out)
    }
}
