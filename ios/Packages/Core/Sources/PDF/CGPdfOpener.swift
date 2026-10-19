import CoreGraphics
import Foundation
import Imaging

/// CoreGraphics PDF rendering (works on iOS and in macOS tests). A password-protected PDF does not open.
public struct CGPdfOpener: PdfOpener {
    public init() {}

    public func open(_ bytes: [UInt8]) -> PdfPages? {
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let document = CGPDFDocument(provider) else { return nil }
        if document.isEncrypted, !document.isUnlocked, !document.unlockWithPassword("") { return nil }
        guard document.numberOfPages > 0 else { return nil }
        return CGPdfPages(document)
    }
}

/// CGPDFDocument is immutable once opened; the engine renders one page at a time.
private final class CGPdfPages: PdfPages, @unchecked Sendable {
    private let document: CGPDFDocument

    init(_ document: CGPDFDocument) { self.document = document }

    var pageCount: Int { document.numberOfPages }

    private struct Page {
        let page: CGPDFPage
        let box: CGRect
        let angle: Int
    }

    private func page(_ index: Int) -> Page? {
        guard let page = document.page(at: index + 1) else { return nil }
        let crop = page.getBoxRect(.cropBox)
        let box = crop.isEmpty ? page.getBoxRect(.mediaBox) : crop
        return Page(page: page, box: box, angle: ((Int(page.rotationAngle) % 360) + 360) % 360)
    }

    func pageSize(_ index: Int) -> PdfSize {
        guard let found = page(index) else { return PdfSize(width: 1, height: 1) }
        let box = found.box, angle = found.angle
        let turned = angle == 90 || angle == 270
        return PdfSize(width: Double(turned ? box.height : box.width), height: Double(turned ? box.width : box.height))
    }

    func render(_ index: Int, width: Int, height: Int) -> Raster? {
        guard let found = page(index),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: width * 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let pdfPage = found.page, box = found.box, angle = found.angle
        context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let size = pageSize(index)
        context.scaleBy(x: CGFloat(width) / CGFloat(size.width), y: CGFloat(height) / CGFloat(size.height))
        // The displayed page: /Rotate turns it clockwise.
        switch angle {
        case 90:
            context.translateBy(x: 0, y: box.width)
            context.rotate(by: -.pi / 2)
        case 180:
            context.translateBy(x: box.width, y: box.height)
            context.rotate(by: .pi)
        case 270:
            context.translateBy(x: box.height, y: 0)
            context.rotate(by: .pi / 2)
        default:
            break
        }
        context.translateBy(x: -box.minX, y: -box.minY)
        context.clip(to: box)
        context.drawPDFPage(pdfPage)
        guard let data = context.data else { return nil }
        let rgba = data.bindMemory(to: UInt8.self, capacity: width * height * 4)
        var rgb = [UInt8](repeating: 0, count: width * height * 3)
        for p in 0..<(width * height) {
            rgb[p * 3] = rgba[p * 4]
            rgb[p * 3 + 1] = rgba[p * 4 + 1]
            rgb[p * 3 + 2] = rgba[p * 4 + 2]
        }
        return Raster(width: width, height: height, rgb: rgb)
    }
}

/// The production picture decoder (ALGORITHMS 1.1).
public struct ImageIOPictureDecoder: PictureDecoder {
    public init() {}

    public func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster? {
        ImageIODecoder.decode(bytes, maxLongSide: maxLongSide)
    }
}
