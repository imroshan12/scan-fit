import Imaging
import Testing

@Suite("AutoFraming")
struct AutoFramingTests {
    @Test("a centred face gets the documented crop")
    func centred() {
        // face 200x240 at (400,500); coverage 0.675 -> crop height round(1.25*240/0.675) = 444, width round(444*200/230) = 386
        let f = AutoFraming.frame(Face(x: 400, y: 500, w: 200, h: 240), imgW: 1000, imgH: 1400, aspect: 200.0 / 230.0)
        #expect(f.crop == CropRect(x: 307, y: 426, w: 386, h: 444) && !f.coverageAdjusted)
    }

    @Test("the face takes the target share; the crown sits 10% from the top; the crop is centred on the face")
    func proportions() {
        let face = Face(x: 300, y: 400, w: 300, h: 360)
        let crop = AutoFraming.frame(face, imgW: 2000, imgH: 2000, aspect: 0.8).crop
        #expect(abs(1.25 * face.h / Double(crop.h) - 0.675) < 0.01)
        #expect(abs((face.y - 0.125 * face.h - Double(crop.y)) / Double(crop.h) - 0.10) < 0.01)
        #expect(abs(face.centerX - (Double(crop.x) + Double(crop.w) / 2)) <= 1)
        let face2 = Face(x: 400, y: 500, w: 200, h: 240)
        let tight = AutoFraming.frame(face2, imgW: 2000, imgH: 2000, aspect: 0.8, coverage: 0.75).crop
        let loose = AutoFraming.frame(face2, imgW: 2000, imgH: 2000, aspect: 0.8, coverage: 0.60).crop
        #expect(tight.h < loose.h)
    }

    @Test("a crop that would leave the image is shifted back; one that cannot fit shrinks and is flagged")
    func clamping() {
        let shifted = AutoFraming.frame(Face(x: 10, y: 20, w: 200, h: 240), imgW: 1000, imgH: 1400, aspect: 0.8)
        #expect(shifted.crop.x == 0 && shifted.crop.y == 0 && !shifted.coverageAdjusted)
        let big = AutoFraming.frame(Face(x: 50, y: 100, w: 900, h: 1000), imgW: 1000, imgH: 1200, aspect: 0.8)
        #expect(big.coverageAdjusted && big.crop.w <= 1000 && big.crop.h <= 1200)
        #expect(abs(Double(big.crop.w) / Double(big.crop.h) - 0.8) < 0.01)
        let wide = AutoFraming.frame(Face(x: 0, y: 0, w: 400, h: 100), imgW: 300, imgH: 1000, aspect: 2.0)
        #expect(wide.crop.w <= 300 && wide.coverageAdjusted)
    }
}

@Suite("BackgroundWhitening")
struct BackgroundWhiteningTests {
    @Test("background goes white, the person is untouched, the boundary is feathered")
    func basic() {
        let img = Raster.make(40, 10) { _, _ in 0x646464 }
        let mask = (0..<400).map { $0 % 40 < 20 ? UInt8(255) : UInt8(0) }
        let out = BackgroundWhitening.composite(img, mask: mask)
        #expect(out.r(5, 5) == 100 && out.r(35, 5) == 255)
        #expect((101...254).contains(out.r(20, 5)))
    }

    @Test("all-background whitens everything; all-person changes nothing")
    func extremes() {
        let img = noisyRaster(16, 16)
        #expect(BackgroundWhitening.composite(img, mask: [UInt8](repeating: 0, count: 256)).rgb.allSatisfy { $0 == 255 })
        #expect(BackgroundWhitening.composite(img, mask: [UInt8](repeating: 255, count: 256)) == img)
    }
}

@Suite("NameDateStrip")
struct NameDateStripTests {
    private struct Fixed: TextMeasurer { func width(_ text: String, sizePx: Float, bold: Bool) -> Float { Float(text.count) * sizePx * 0.6 } }
    private struct Huge: TextMeasurer { func width(_ text: String, sizePx: Float, bold: Bool) -> Float { 9999 } }

    @Test("the strip is 18% of the height and sizes follow the spec")
    func sizes() {
        let l = NameDateStrip.layout(name: "Asha Rao", date: "01/02/2003", width: 200, height: 230, measurer: Fixed())
        #expect(l.stripHeight == 41 && l.stripTop == 189 && l.photoArea == Size(200, 189))
        #expect(l.lines[0].text == "ASHA RAO" && abs(l.lines[0].sizePx - 0.38 * 41) < 0.01)
        #expect(l.lines[1].text == "01/02/2003" && abs(l.lines[1].sizePx - 0.32 * 41) < 0.01)
        #expect(l.lines.allSatisfy { $0.centerX == 100 })
        #expect(l.lines[0].baselineY < l.lines[1].baselineY)
        #expect(l.lines.allSatisfy { $0.baselineY > Float(l.stripTop) && $0.baselineY < 230 })
    }

    @Test("a long name shrinks first and only then truncates with an ellipsis")
    func shrinkThenTruncate() {
        let base: Float = 0.38 * 41
        let shrinks = NameDateStrip.layout(name: String(repeating: "A", count: 22), date: "01/02/2003", width: 200, height: 230, measurer: Fixed()).lines[0]
        #expect(shrinks.sizePx < base && shrinks.sizePx >= base * 0.6 - 0.01)
        #expect(shrinks.text == String(repeating: "A", count: 22))
        let cut = NameDateStrip.layout(name: String(repeating: "A", count: 60), date: "01/02/2003", width: 200, height: 230, measurer: Fixed()).lines[0]
        #expect(abs(cut.sizePx - base * 0.6) < 0.01 && cut.text.hasSuffix("…") && cut.text.count < 60)
        #expect(Fixed().width(cut.text, sizePx: cut.sizePx, bold: true) <= 200 * 0.92)
        #expect(NameDateStrip.layout(name: "", date: "d", width: 200, height: 230, measurer: Huge()).lines[0].text == "…")
        #expect(NameDateStrip.layout(name: "", date: "d", width: 200, height: 230, measurer: Fixed()).lines[0].text.isEmpty)
    }

    @Test("the photo is letterboxed, not stretched")
    func letterbox() {
        let l = NameDateStrip.layout(name: "A", date: "d", width: 200, height: 230, measurer: Fixed())
        let out = NameDateStrip.placePhoto(Raster.make(200, 230) { _, _ in 0x646464 }, layout: l, width: 200, height: 230)
        #expect(out.width == 200 && out.height == 230)
        #expect(out.r(5, 100) == 255 && out.r(100, 100) == 100 && out.r(100, 200) == 255)
    }

    @Test("CoreText renders black text inside the strip only, centred")
    func coreText() {
        let renderer = CoreTextStripRenderer()
        let out = renderer.withStrip(Raster.make(200, 230) { _, _ in 0x808080 }, name: "Asha Rao", date: "01/02/2003")
        #expect(out.width == 200 && out.height == 230)
        let dark = (189..<230).reduce(0) { acc, y in acc + (0..<200).filter { out.r($0, y) < 100 }.count }
        #expect(dark > 40)
        let outside = (0..<180).reduce(0) { acc, y in acc + (40..<160).filter { out.r($0, y) < 100 }.count }
        #expect(outside == 0)
        let xs = (189..<230).flatMap { y in (0..<200).filter { out.r($0, y) < 100 } }
        let mean = Double(xs.reduce(0, +)) / Double(max(xs.count, 1))
        #expect((80.0...120.0).contains(mean))
        #expect(renderer.width("MMMM", sizePx: 20, bold: false) > renderer.width("MMMM", sizePx: 10, bold: false))
        #expect(renderer.width("MMMM", sizePx: 20, bold: true) >= renderer.width("MMMM", sizePx: 20, bold: false))
    }
}
