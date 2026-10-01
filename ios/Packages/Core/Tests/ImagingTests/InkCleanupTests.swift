import Foundation
import Imaging
import TestSupport
import Testing

@Suite("InkCleanup")
struct InkCleanupTests {
    /// Paper with a diagonal shadow gradient and a thick dark pen line across the middle.
    private func shadowedLine(_ w: Int = 400, _ h: Int = 200, ink: Int = 0x1C225A) -> Raster {
        Raster.make(w, h) { x, y in
            let shade = 235 - (x * 70 / w)
            return (y >= 90 && y <= 99 && x >= 40 && x <= 360) ? ink : (shade << 16) | (shade << 8) | shade
        }
    }

    private func blackAndWhiteOnly(_ r: Raster) -> Bool {
        (0..<r.height).allSatisfy { y in (0..<r.width).allSatisfy { x in r.r(x, y) == r.g(x, y) && r.g(x, y) == r.b(x, y) && (r.r(x, y) == 0 || r.r(x, y) == 255) } }
    }

    private func medianLuma(_ r: Raster) -> Int { Int(r.luma().sorted()[r.width * r.height / 2]) }

    @Test("a signature on shadowed paper becomes black on white")
    func shadowedSignature() {
        let out = InkCleanup.clean(shadowedLine(), variant: .signature)
        #expect(out.quality == .ok)
        #expect(blackAndWhiteOnly(out.raster))
        #expect(medianLuma(out.raster) == 255)
        let ink = (0..<out.raster.height).reduce(0) { acc, y in acc + (0..<out.raster.width).filter { out.raster.r($0, y) == 0 }.count }
        #expect(ink > 2500)
    }

    @Test("the trim keeps 8% of the long side as margin")
    func trimMargin() {
        let out = InkCleanup.clean(shadowedLine(), variant: .signature).raster
        #expect(out.width == 321 + 2 * 26 && out.height == 10 + 2 * 26)
    }

    @Test("document cleanup keeps the ink colour darkened; crisp black and the ink factor are options")
    func colourOptions() {
        let doc = InkCleanup.clean(shadowedLine(), variant: .document).raster
        let x = doc.width / 2
        let y = (0..<doc.height).first { doc.r(x, $0) != 255 || doc.g(x, $0) != 255 } ?? 0
        #expect(doc.r(x, y) == 17 && doc.g(x, y) == 20 && doc.b(x, y) == 54)
        #expect(blackAndWhiteOnly(InkCleanup.clean(shadowedLine(), variant: .document, options: InkOptions(crispBlack: true)).raster))
        let darker = InkCleanup.clean(shadowedLine(), variant: .signature, options: InkOptions(crispBlack: false, inkFactor: 0.3)).raster
        let dy = (0..<darker.height).first { darker.r(darker.width / 2, $0) != 255 || darker.g(darker.width / 2, $0) != 255 } ?? 0
        #expect(darker.r(darker.width / 2, dy) == 8)
    }

    @Test("uniform paper is too faint; dense strokes are too dark; specks do not stretch the trim")
    func gates() {
        let paper = Raster.make(300, 200) { _, _ in 0xE8E4DA }
        let faint = InkCleanup.clean(paper, variant: .signature)
        #expect(faint.quality == .tooFaint && faint.coverage == 0 && faint.raster.width == 300 && faint.raster.height == 200)
        #expect(medianLuma(faint.raster) == 255)
        let dense = Raster.make(300, 200) { x, y in (50...250).contains(x) && (40...160).contains(y) && (x / 3) % 3 != 2 ? 0x000000 : 0xF0F0F0 }
        #expect(InkCleanup.clean(dense, variant: .signature).quality == .tooDark)
        let speckled = Raster.make(400, 200) { x, y in
            let line = (90...99).contains(y) && (40...360).contains(x)
            let speck = (x == 20 && y == 20) || (x == 380 && y == 30)
            return line || speck ? 0x000000 : 0xF0F0F0
        }
        #expect(InkCleanup.clean(speckled, variant: .signature).raster.width == 321 + 2 * 26)
    }

    @Test("thumb cleanup is a grey square centred on the ink and keeps grey levels")
    func thumb() {
        let ridges = Raster.make(300, 300) { x, y in
            let dx = x - 120, dy = y - 150
            let d = Int((Double(dx * dx + dy * dy)).squareRoot())
            return d < 70 && d % 8 < 4 ? 0x28328C : 0xEAE6DC
        }
        let out = InkCleanup.clean(ridges, variant: .thumb)
        #expect(out.raster.width == out.raster.height && (150...175).contains(out.raster.width))
        #expect((0..<out.raster.height).allSatisfy { y in (0..<out.raster.width).allSatisfy { x in out.raster.r(x, y) == out.raster.g(x, y) && out.raster.g(x, y) == out.raster.b(x, y) } })
        #expect(Set((0..<out.raster.width).map { out.raster.r($0, out.raster.height / 2) }).count > 2)
        #expect(out.quality == .ok)
        let blank = InkCleanup.clean(Raster.make(100, 100) { _, _ in 0xFFFFFF }, variant: .thumb)
        #expect(blank.quality == .tooFaint && blank.raster.width == 100 && blank.raster.height == 100)
    }

    @Test("the real paper-shadow and thumb fixtures clean up")
    func fixtures() throws {
        let sig = try #require(ImageIODecoder.decode(try CasesFile.image("signature_paper_shadow.jpg"), maxLongSide: 1600))
        #expect(sig.width == 1600 && sig.height == 933)
        let out = InkCleanup.clean(sig, variant: .signature)
        #expect(out.quality == .ok && medianLuma(out.raster) == 255)
        #expect(out.raster.width < sig.width && out.raster.height < sig.height && blackAndWhiteOnly(out.raster))
        let thumbSrc = try #require(ImageIODecoder.decode(try CasesFile.image("thumb_ink.jpg"), maxLongSide: 1600))
        let thumb = InkCleanup.clean(thumbSrc, variant: .thumb)
        #expect(thumb.raster.width == thumb.raster.height && (200...1600).contains(thumb.raster.width))
        #expect(Set(thumb.raster.luma()).count > 8)
    }
}
