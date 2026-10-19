import Foundation

/// One page to write: its size and the placed JPEGs, drawn in order as /Im1, /Im2.
public struct WriterPage: Sendable {
    public let size: PdfSize
    public let images: [WriterImage]

    public init(size: PdfSize, images: [WriterImage]) {
        self.size = size
        self.images = images
    }
}

public struct WriterImage: Sendable {
    public let jpeg: [UInt8]
    public let width: Int
    public let height: Int
    public let rect: PdfRect

    public init(jpeg: [UInt8], width: Int, height: Int, rect: PdfRect) {
        self.jpeg = jpeg
        self.width = width
        self.height = height
        self.rect = rect
    }
}

/// A page's geometry without image data, for measuring sizes (`PdfWriter.size`).
public struct PageGeometry: Sendable {
    public let size: PdfSize
    public let images: [PlacedPixels]
}

public struct PlacedPixels: Sendable {
    public let width: Int
    public let height: Int
    public let rect: PdfRect
}

/// The image PDF the app writes (ALGORITHMS 6.4 / 9.10): PDF 1.4, no metadata, no fonts, one DCT image XObject per
/// placed JPEG. Byte-identical to the Kotlin writer and the Python reference (`pdf_writer_cases`). Pure.
public enum PdfWriter {
    private static let header: [UInt8] = Array("%PDF-1.4\n%".utf8) + [0xE2, 0xE3, 0xCF, 0xD3] + Array("\n".utf8)

    /// `num(v)` of ALGORITHMS 9.10: at most two decimals, no trailing zeros.
    public static func num(_ v: Double) -> String {
        let c = Int64((v * 100 + 0.5).rounded(.down))
        let whole = c / 100, frac = c % 100
        if frac == 0 { return "\(whole)" }
        if c % 10 == 0 { return "\(whole).\(frac / 10)" }
        return "\(whole)." + (frac < 10 ? "0\(frac)" : "\(frac)")
    }

    public static func write(_ pages: [WriterPage], pad: Int? = nil) -> [UInt8] {
        var sink = Sink(keep: true)
        sink.out.reserveCapacity(pages.reduce(4096) { $0 + $1.images.reduce(0) { $0 + $1.jpeg.count } })
        emit(pages, pad: pad, sink: &sink) { p, i in pages[p].images[i].jpeg.count }
        return sink.out
    }

    /// The size `write` would produce for these pages with images of `lengths` bytes, without the image data.
    public static func size(_ pages: [PageGeometry], lengths: [[Int]], pad: Int? = nil) -> Int {
        let placeholders = pages.map { page in
            WriterPage(size: page.size, images: page.images.map {
                WriterImage(jpeg: [], width: $0.width, height: $0.height, rect: $0.rect)
            })
        }
        var sink = Sink(keep: false)
        emit(placeholders, pad: pad, sink: &sink) { p, i in lengths[p][i] }
        return sink.count
    }

    /// ALGORITHMS 9.10 Pad: the smallest padding object making the PDF at least `target` bytes (none if already).
    public static func writePadded(_ pages: [WriterPage], target: Int) -> [UInt8] {
        let plain = write(pages)
        if plain.count >= target { return plain }
        let geometry = pages.map { page in
            PageGeometry(size: page.size, images: page.images.map {
                PlacedPixels(width: $0.width, height: $0.height, rect: $0.rect)
            })
        }
        let lengths = pages.map { $0.images.map(\.jpeg.count) }
        func sized(_ p: Int) -> Int { size(geometry, lengths: lengths, pad: p) }
        var p = max(0, target - sized(0))
        while sized(p) < target { p += 1 }
        while p > 0, sized(p - 1) >= target { p -= 1 }
        return write(pages, pad: p)
    }

    private struct Sink {
        let keep: Bool
        var out: [UInt8] = []
        var count = 0

        mutating func text(_ s: String) {
            count += s.utf8.count
            if keep { out += Array(s.utf8) }
        }

        mutating func data(_ bytes: [UInt8], length: Int) {
            count += length
            if keep { out += bytes }
        }
    }

    private static func emit(
        _ pages: [WriterPage], pad: Int?, sink: inout Sink, lengthOf: (_ page: Int, _ image: Int) -> Int
    ) {
        sink.data(header, length: header.count)
        var offsets: [Int] = []
        func obj(_ number: Int, _ body: (inout Sink) -> Void) {
            offsets.append(sink.count)
            sink.text("\(number) 0 obj\n")
            body(&sink)
            sink.text("\nendobj\n")
        }
        var next = 3
        var numbers: [Int] = []
        for page in pages {
            numbers.append(next)
            next += 2 + page.images.count
        }
        obj(1) { $0.text("<< /Type /Catalog /Pages 2 0 R >>") }
        let kids = numbers.map { "\($0) 0 R" }.joined(separator: " ")
        obj(2) { $0.text("<< /Type /Pages /Kids [\(kids)] /Count \(pages.count) >>") }
        for (p, page) in pages.enumerated() {
            let pageNo = numbers[p], contentNo = pageNo + 1
            let xobjects = page.images.indices.map { "/Im\($0 + 1) \(contentNo + 1 + $0) 0 R" }.joined(separator: " ")
            obj(pageNo) {
                $0.text(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 \(num(page.size.width)) \(num(page.size.height))] "
                        + "/Resources << /XObject << \(xobjects) >> >> /Contents \(contentNo) 0 R >>"
                )
            }
            var content = ""
            for (j, image) in page.images.enumerated() {
                let r = image.rect
                content += "q \(num(r.width)) 0 0 \(num(r.height)) \(num(r.x)) "
                content += "\(num(page.size.height - r.y - r.height)) cm /Im\(j + 1) Do Q\n"
            }
            obj(contentNo) { $0.text("<< /Length \(content.utf8.count) >>\nstream\n\(content)\nendstream") }
            for (j, image) in page.images.enumerated() {
                let length = lengthOf(p, j)
                obj(contentNo + 1 + j) {
                    $0.text(
                        "<< /Type /XObject /Subtype /Image /Width \(image.width) /Height \(image.height) "
                            + "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode "
                            + "/Length \(length) >>\nstream\n"
                    )
                    $0.data(image.jpeg, length: length)
                    $0.text("\nendstream")
                }
            }
        }
        if let pad {
            obj(next) { $0.text("<< /Length \(pad) >>\nstream\n" + String(repeating: " ", count: pad) + "\nendstream") }
        }
        let xref = sink.count
        let count = offsets.count + 1
        sink.text("xref\n0 \(count)\n0000000000 65535 f \n")
        for offset in offsets {
            let digits = String(offset)
            sink.text(String(repeating: "0", count: max(0, 10 - digits.count)) + digits + " 00000 n \n")
        }
        sink.text("trailer\n<< /Size \(count) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n")
    }
}
