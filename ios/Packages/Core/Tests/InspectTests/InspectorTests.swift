import Foundation
import Inspect
import Testing

@Suite("Inspector")
struct InspectorTests {
    private func ascii(_ s: String) -> [UInt8] { Array(s.utf8) }

    /// SOI + optional APP1(EXIF) + SOF + EOI, hand-built so every field is under test control.
    private func jpeg(components: Int = 3, width: Int = 20, height: Int = 10, sof: Int = 0xC0, exif: [UInt8]? = nil) -> [UInt8] {
        var out: [UInt8] = [0xFF, 0xD8]
        if let exif {
            let length = exif.count + 2
            out += [0xFF, 0xE1, UInt8(length >> 8), UInt8(length & 0xFF)] + exif
        }
        let length = 8 + components * 3
        out += [0xFF, UInt8(sof), UInt8(length >> 8), UInt8(length & 0xFF), 8,
                UInt8(height >> 8), UInt8(height & 0xFF), UInt8(width >> 8), UInt8(width & 0xFF), UInt8(components)]
        for index in 0..<components { out += [UInt8(index + 1), 0x11, 0] }
        return out + [0xFF, 0xD9]
    }

    private func tiff(little: Bool, entries: [(tag: Int, value: Int)]) -> [UInt8] {
        var out = ascii("Exif\0\0") + ascii(little ? "II" : "MM")
        func w16(_ v: Int) { out += little ? [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] : [UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)] }
        func w32(_ v: Int) {
            if little { w16(v & 0xFFFF); w16(v >> 16) } else { w16(v >> 16); w16(v & 0xFFFF) }
        }
        w16(42); w32(8); w16(entries.count)
        for entry in entries { w16(entry.tag); w16(3); w32(1); w16(entry.value); w16(0) }
        w32(0)
        return out
    }

    @Test("format comes from magic bytes, not the extension")
    func formats() {
        #expect(Inspector.detectFormat([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A]) == .png)
        #expect(Inspector.detectFormat(ascii("%PDF-1.7\n")) == .pdf)
        #expect(Inspector.detectFormat([0xFF, 0xD8, 0xFF, 0xE0]) == .jpeg)
        #expect(Inspector.detectFormat([1, 2, 3]) == .unknown)
        #expect(Inspector.detectFormat([]) == .unknown)
        var heic = [UInt8](repeating: 0, count: 16)
        heic.replaceSubrange(4..<12, with: ascii("ftypheic"))
        #expect(Inspector.detectFormat(heic) == .heic)
        var webp = [UInt8](repeating: 0, count: 16)
        webp.replaceSubrange(0..<4, with: ascii("RIFF"))
        webp.replaceSubrange(8..<12, with: ascii("WEBP"))
        #expect(Inspector.detectFormat(webp) == .webp)
    }

    @Test("JPEG components map to colour; CMYK is an issue")
    func colour() {
        #expect(Inspector.inspect(jpeg(components: 3)).color == .rgb)
        #expect(Inspector.inspect(jpeg(components: 1)).color == .gray)
        let cmyk = Inspector.inspect(jpeg(components: 4), fileName: "x.jpg")
        #expect(cmyk.color == .cmyk)
        #expect(cmyk.issues == [.cmykColor])
    }

    @Test("progressive SOFs are detected, baseline ones are not")
    func progressive() {
        for sof in [0xC2, 0xC6, 0xCA, 0xCE] { #expect(Inspector.inspect(jpeg(sof: sof)).progressive, "SOF\(sof - 0xC0)") }
        for sof in [0xC0, 0xC1] { #expect(!Inspector.inspect(jpeg(sof: sof)).progressive, "SOF\(sof - 0xC0)") }
        #expect(Inspector.inspect(jpeg(sof: 0xC2)).sof == "SOF2")
    }

    @Test("DHT, JPG and DAC markers are not frame headers")
    func nonFrameMarkers() {
        for marker in [0xC4, 0xC8, 0xCC] { #expect(Inspector.inspect(jpeg(sof: marker)).width == nil, "marker \(marker)") }
    }

    @Test("EXIF orientation and GPS are read in both byte orders")
    func exif() {
        for little in [true, false] {
            let f = Inspector.inspect(jpeg(exif: tiff(little: little, entries: [(0x0112, 6), (0x8825, 0)])), fileName: "p.jpg")
            #expect(f.exifOrientation == 6, "little=\(little)")
            #expect(f.hasGps && f.hasExif)
            #expect(f.issues == [.hasGpsExif, .rotatedByExif])
        }
        #expect(Inspector.inspect(jpeg(exif: tiff(little: true, entries: [(0x0112, 9)]))).exifOrientation == nil)
        #expect(Inspector.inspect(jpeg(exif: tiff(little: true, entries: [(0x0112, 1)]))).issues.isEmpty)
    }

    @Test("hostile or truncated input never traps")
    func hostile() {
        let inputs: [[UInt8]] = [
            [], [0xFF], [0xFF, 0xD8], [0xFF, 0xD8, 0xFF], [0xFF, 0xD8, 0xFF, 0xE1, 0xFF, 0xFF],
            [0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x02], [0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x03, 0x08],
            ascii("%PDF-"), [0x89, 0x50, 0x4E, 0x47],
            jpeg(exif: ascii("Exif\0\0II") + [42, 0, 0xFF, 0xFF, 0xFF, 0x7F, 0xFF, 0xFF]),
        ]
        for input in inputs { _ = Inspector.inspect(input, fileName: "broken.jpg") }
        #expect(Inspector.inspect([0xFF, 0xD8, 0xFF, 0xC0, 0x7F, 0xFF, 8, 0, 10, 0, 20, 3]).width == nil)
    }

    @Test("PNG reads IHDR")
    func png() {
        var png = [UInt8](repeating: 0, count: 33)
        png.replaceSubrange(0..<8, with: [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        png.replaceSubrange(16..<20, with: [0, 0, 1, 0xA4])
        png.replaceSubrange(20..<24, with: [0, 0, 0, 0xB4])
        png[24] = 8; png[25] = 2
        let f = Inspector.inspect(png, fileName: "s.png")
        #expect(f.width == 420 && f.height == 180 && f.color == .rgb)
        png[25] = 0
        #expect(Inspector.inspect(png).color == .gray)
        #expect(Inspector.inspect([0x89, 0x50, 0x4E, 0x47]).format == .png)
    }

    @Test("PDF pages, encryption and image-only")
    func pdf() {
        let text = "%PDF-1.4\n1 0 obj<</Type/Page>>endobj\n2 0 obj<</Type/Pages>>endobj\n3 0 obj<</Type /Page>>endobj\ntrailer<</Encrypt 9 0 R>>"
        let f = Inspector.inspect(ascii(text), fileName: "a.pdf")
        #expect(f.pdfPages == 2)
        #expect(f.pdfEncrypted && f.pdfImageOnly)
        #expect(f.issues == [.pdfEncrypted])
        #expect(!Inspector.inspect(ascii("%PDF-1.4 /Font <<>>")).pdfImageOnly)
        #expect(!Inspector.inspect(ascii("%PDF-1.4 /Type/Page /Encryption")).pdfEncrypted)
    }

    @Test("extension mismatch only when the extension names a different known format")
    func extensionMismatch() {
        var png = [UInt8](repeating: 0, count: 33)
        png.replaceSubrange(0..<4, with: [0x89, 0x50, 0x4E, 0x47])
        #expect(Inspector.inspect(png, fileName: "photo.JPG").issues == [.extensionMismatch])
        #expect(Inspector.inspect(png, fileName: "photo.png").issues.isEmpty)
        #expect(Inspector.inspect(png, fileName: "photo.dat").issues.isEmpty)
        #expect(Inspector.inspect(png, fileName: "photo").issues.isEmpty)
        var heic = [UInt8](repeating: 0, count: 16)
        heic.replaceSubrange(4..<12, with: ascii("ftypheic"))
        #expect(Inspector.inspect(heic, fileName: "a.heif").issues == [.heicNotAccepted])
        #expect(Inspector.inspect(heic, fileName: "a.jpg").issues == [.extensionMismatch, .heicNotAccepted])
    }

    @Test("KB is 1024 bytes")
    func kb() {
        #expect(Inspector.inspect([UInt8](repeating: 0, count: 2048)).kb == 2.0)
    }
}
