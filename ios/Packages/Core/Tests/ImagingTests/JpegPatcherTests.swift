import CoreGraphics
import Foundation
import ImageIO
import Imaging
import Inspect
import TestSupport
import Testing

@Suite("JpegPatcher")
struct JpegPatcherTests {
    private func ascii(_ s: String) -> [UInt8] { Array(s.utf8) }

    private func decodes(_ bytes: [UInt8]) -> Bool {
        guard let source = CGImageSourceCreateWithData(Data(bytes) as CFData, nil) else { return false }
        return CGImageSourceCreateImageAtIndex(source, 0, nil) != nil
    }

    private func markers(_ bytes: [UInt8]) -> [Int] { JpegWalk(bytes).segments.map(\.marker) }

    private func ok(_ result: Result<[UInt8], PatchError>, sourceLocation: SourceLocation = #_sourceLocation) throws -> [UInt8] {
        switch result {
        case let .success(bytes): return bytes
        case let .failure(error):
            Issue.record("expected success, got \(error)", sourceLocation: sourceLocation)
            throw error
        }
    }

    private func app(_ marker: Int, _ body: [UInt8]) -> [UInt8] {
        let length = body.count + 2
        return [0xFF, UInt8(marker), UInt8(length >> 8), UInt8(length & 0xFF)] + body
    }

    private func com(_ n: Int) -> [UInt8] { app(0xFE, [UInt8](repeating: 0x20, count: n - 4)) }

    /// SOI, [extra], SOF, SOS + a few scan bytes, EOI: enough structure for the patcher.
    private func jpeg(extra: [[UInt8]] = [], comps: Int = 3, sof: Int = 0xC0) -> [UInt8] {
        var out: [UInt8] = [0xFF, 0xD8] + extra.flatMap { $0 }
        out += [0xFF, UInt8(sof), 0, UInt8(8 + comps * 3), 8, 0, 10, 0, 20, UInt8(comps)]
        for index in 0..<comps { out += [UInt8(index + 1), 0x11, 0] }
        return out + [0xFF, 0xDA, 0, 4, 1, 2, 9, 9, 9, 0xFF, 0xD9]
    }

    @Test("the shared patch cases all hold")
    func sharedCases() throws {
        let cases = try CasesFile.section("patch_cases")
        #expect(cases.count >= 10)
        for c in cases {
            let id = try #require(c.string("id"))
            let input = try CasesFile.image(try #require(c.string("input")))
            let result = JpegPatcher.patch(input, dpi: c.int("dpi") ?? 200, allowGrayscale: c.bool("allow_grayscale") ?? false)
            if let expected = c.string("expect_error") {
                guard case let .failure(error) = result else {
                    Issue.record("\(id): expected error \(expected)")
                    continue
                }
                #expect(error.rawValue == expected, "\(id)")
                continue
            }
            var out = try ok(result)
            if let target = c.int("pad_to_bytes") { out = JpegPatcher.pad(out, to: target) }
            let expect = try #require(c.dict("expect"))
            let f = Inspector.inspect(out)
            if expect.bool("no_app1") == true { #expect(!f.hasExif && !f.hasXmp && !markers(out).contains(0xE1), "\(id): APP1 gone") }
            if expect.bool("no_icc") == true { #expect(!f.hasIcc, "\(id): ICC gone") }
            if let jfif = expect.dict("jfif") {
                #expect(f.jfif == JfifDensity(units: jfif.int("units") ?? -1, xDensity: jfif.int("x") ?? -1, yDensity: jfif.int("y") ?? -1), "\(id): jfif")
            }
            if let sof = expect.string("sof") { #expect(f.sof == sof, "\(id): sof") }
            if let components = expect.int("components") { #expect(f.components == components, "\(id): components") }
            if let size = expect.int("size_bytes") { #expect(out.count == size, "\(id): size") }
            if expect.bool("decodes") == true { #expect(decodes(out), "\(id): still decodes") }
        }
    }

    @Test("JFIF is inserted when missing and rewritten when present")
    func jfif() throws {
        let bare = try ok(JpegPatcher.patch(jpeg(), dpi: 300))
        #expect(Inspector.inspect(bare).jfif == JfifDensity(units: 1, xDensity: 300, yDensity: 300))
        let old = app(0xE0, ascii("JFIF\0") + [1, 2, 0, 0, 72, 0, 72, 0, 0])
        let rewritten = try ok(JpegPatcher.patch(jpeg(extra: [old]), dpi: 200))
        #expect(markers(rewritten).filter { $0 == 0xE0 }.count == 1)
        #expect(Inspector.inspect(rewritten).jfif == JfifDensity(units: 1, xDensity: 200, yDensity: 200))
    }

    @Test("EXIF, XMP, IPTC, other APPn, comments and non-JFIF APP0 go; Adobe stays")
    func stripping() throws {
        let extras = [
            app(0xE0, ascii("JFXX\0") + [UInt8](repeating: 0, count: 6)),
            app(0xE1, ascii("Exif\0\0") + [UInt8](repeating: 0, count: 8)),
            app(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0")),
            app(0xE3, [0, 0, 0, 0]), app(0xED, ascii("Photoshop 3.0\0")), app(0xEF, [0, 0]),
            app(0xEE, ascii("Adobe") + [UInt8](repeating: 0, count: 7)), com(10),
        ]
        #expect(markers(try ok(JpegPatcher.patch(jpeg(extra: extras)))) == [0xE0, 0xEE, 0xC0, 0xDA])
    }

    @Test("sRGB ICC is removed (ASCII or UTF-16), anything else is refused")
    func icc() throws {
        func icc(_ text: [UInt8]) -> [UInt8] { app(0xE2, ascii("ICC_PROFILE\0") + [1, 1] + text) }
        #expect(!markers(try ok(JpegPatcher.patch(jpeg(extra: [icc(ascii("....desc sRGB IEC61966-2.1"))])))).contains(0xE2))
        let utf16 = ascii("sRGB").flatMap { [UInt8(0), $0] }
        #expect(!markers(try ok(JpegPatcher.patch(jpeg(extra: [icc(ascii("desc") + utf16)])))).contains(0xE2))
        #expect(JpegPatcher.patch(jpeg(extra: [icc(ascii("Display P3"))])) == .failure(.nonSrgbProfile))
        #expect(JpegPatcher.patch(jpeg(extra: [icc(ascii("Adobe RGB (1998)"))])) == .failure(.nonSrgbProfile))
        func chunk(_ seq: UInt8, _ text: String) -> [UInt8] { app(0xE2, ascii("ICC_PROFILE\0") + [seq, 2] + ascii(text)) }
        #expect(!markers(try ok(JpegPatcher.patch(jpeg(extra: [chunk(1, "xxxxsR"), chunk(2, "GBxxxx")])))).contains(0xE2))
    }

    @Test("structural failures are typed")
    func failures() {
        #expect(JpegPatcher.patch([1, 2, 3]) == .failure(.notJpeg))
        #expect(JpegPatcher.patch([]) == .failure(.notJpeg))
        #expect(JpegPatcher.patch(Array(jpeg().prefix(15))) == .failure(.truncated))
        #expect(JpegPatcher.patch(jpeg(sof: 0xC2)) == .failure(.notBaseline))
        #expect(JpegPatcher.patch(jpeg(sof: 0xC1)) == .failure(.notBaseline))
        #expect(JpegPatcher.patch(jpeg(comps: 4)) == .failure(.cmyk))
        #expect(JpegPatcher.patch(jpeg(comps: 1)) == .failure(.grayscaleNotAllowed))
        #expect(JpegPatcher.patch(jpeg(comps: 2)) == .failure(.notBaseline))
        #expect((try? ok(JpegPatcher.patch(jpeg(comps: 1), allowGrayscale: true))) != nil)
    }

    @Test("scan data is copied untouched")
    func scanData() throws {
        let src = jpeg(extra: [app(0xE1, ascii("Exif\0\0") + [UInt8](repeating: 0, count: 20))])
        let out = try ok(JpegPatcher.patch(src))
        let scan: [UInt8] = [0xFF, 0xDA, 0, 4, 1, 2, 9, 9, 9, 0xFF, 0xD9]
        #expect(Array(out.suffix(scan.count)) == scan)
    }

    @Test("pad reaches the exact target for every gap of 4 or more")
    func padExact() throws {
        let base = try ok(JpegPatcher.patch(jpeg()))
        for gap in [4, 5, 6, 7, 8, 100, 65_533, 65_537, 65_538, 65_539, 65_540, 65_541, 65_542, 131_074, 200_000] {
            let padded = JpegPatcher.pad(base, to: base.count + gap)
            #expect(padded.count == base.count + gap, "gap \(gap)")
            #expect(Inspector.inspect(padded).sof == "SOF0")
        }
    }

    @Test("a gap below four overshoots by at most three; no-ops return the input")
    func padSmallGaps() throws {
        let base = try ok(JpegPatcher.patch(jpeg()))
        for gap in 1...3 { #expect(JpegPatcher.pad(base, to: base.count + gap).count == base.count + 4, "gap \(gap)") }
        #expect(JpegPatcher.pad(base, to: base.count) == base)
        #expect(JpegPatcher.pad(base, to: 10) == base)
    }

    @Test("padding sits right after JFIF as spaces, and patching again strips it")
    func padPlacement() throws {
        let base = try ok(JpegPatcher.patch(jpeg()))
        let padded = JpegPatcher.pad(base, to: base.count + 300)
        #expect(markers(padded) == [0xE0, 0xFE, 0xC0, 0xDA])
        let seg = JpegWalk(padded).segments[1]
        #expect(padded[seg.bodyStart..<seg.end].allSatisfy { $0 == 0x20 })
        #expect(try ok(JpegPatcher.patch(padded)) == base)
        let bare = jpeg()
        #expect(markers(JpegPatcher.pad(bare, to: bare.count + 20)) == [0xFE, 0xC0, 0xDA])
    }
}
