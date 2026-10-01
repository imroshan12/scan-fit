import CoreGraphics
import CoreText
import Foundation

/// Apple text engine for the strip: measures and draws with CoreText (system sans, SF Pro).
public struct CoreTextStripRenderer: TextMeasurer {
    public init() {}

    private func font(_ size: Float, _ bold: Bool) -> CTFont {
        CTFontCreateUIFontForLanguage(bold ? .emphasizedSystem : .system, CGFloat(size), nil)
            ?? CTFontCreateWithName("Helvetica" as CFString, CGFloat(size), nil)
    }

    private func line(_ text: String, _ size: Float, _ bold: Bool) -> CTLine {
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font(size, bold),
            NSAttributedString.Key(kCTForegroundColorFromContextAttributeName as String): true,
        ]
        return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
    }

    public func width(_ text: String, sizePx: Float, bold: Bool) -> Float {
        Float(CTLineGetTypographicBounds(line(text, sizePx, bold), nil, nil, nil))
    }

    /// Draws the layout's lines onto `base` in black and returns the result.
    public func draw(_ base: Raster, layout: StripLayout) -> Raster {
        let w = base.width, h = base.height
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let data = ctx.data else { return base }
        let px = data.bindMemory(to: UInt8.self, capacity: w * h * 4)
        for p in 0..<(w * h) {
            px[p * 4] = base.rgb[p * 3]
            px[p * 4 + 1] = base.rgb[p * 3 + 1]
            px[p * 4 + 2] = base.rgb[p * 3 + 2]
            px[p * 4 + 3] = 255
        }
        ctx.setFillColor(red: 0, green: 0, blue: 0, alpha: 1)
        ctx.setShouldAntialias(true)
        for entry in layout.lines {
            let ctLine = line(entry.text, entry.sizePx, entry.bold)
            let textWidth = CTLineGetTypographicBounds(ctLine, nil, nil, nil)
            // CoreGraphics' origin is bottom-left; the layout's y grows downwards.
            ctx.textPosition = CGPoint(
                x: CGFloat(entry.centerX) - CGFloat(textWidth) / 2,
                y: CGFloat(h) - CGFloat(entry.baselineY)
            )
            CTLineDraw(ctLine, ctx)
        }
        var out = [UInt8](repeating: 0, count: w * h * 3)
        for p in 0..<(w * h) { out[p * 3] = px[p * 4]; out[p * 3 + 1] = px[p * 4 + 1]; out[p * 3 + 2] = px[p * 4 + 2] }
        return Raster(width: w, height: h, rgb: out)
    }

    /// Photo + strip in one step: the output has the same size as `photo`.
    public func withStrip(_ photo: Raster, name: String, date: String) -> Raster {
        let layout = NameDateStrip.layout(
            name: name, date: date, width: photo.width, height: photo.height, measurer: self
        )
        let placed = NameDateStrip.placePhoto(photo, layout: layout, width: photo.width, height: photo.height)
        return draw(placed, layout: layout)
    }
}
