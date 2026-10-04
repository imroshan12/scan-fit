import Foundation
import Imaging
import Testing
import TestSupport

/// `crop_cases` (ALGORITHMS 9.6): auto-framing and the crop screen's move/zoom, computed by spec/tools/photo_crop.py.
@Suite("Crop conformance")
struct CropConformanceTests {
    private func rect(_ d: [String: Any]) -> CropRect {
        CropRect(x: d.int("x") ?? -1, y: d.int("y") ?? -1, w: d.int("w") ?? -1, h: d.int("h") ?? -1)
    }

    @Test("every shared crop case holds")
    func sharedCases() throws {
        let cases = try CasesFile.section("crop_cases")
        #expect(cases.count >= 10)
        for c in cases {
            let id = try #require(c.string("id"))
            let img = try #require(c.dict("image"))
            let imgW = try #require(img.int("w")), imgH = try #require(img.int("h"))
            let pair = try #require(c["aspect"] as? [NSNumber])
            let aspect = pair[0].doubleValue / pair[1].doubleValue
            let expect = try #require(c.dict("expect"))
            let actual: CropRect
            switch c.string("op") {
            case "frame":
                let f = try #require(c.dict("face"))
                let face = Face(x: f.double("x") ?? 0, y: f.double("y") ?? 0, w: f.double("w") ?? 0, h: f.double("h") ?? 0)
                let framing = AutoFraming.frame(face, imgW: imgW, imgH: imgH, aspect: aspect,
                                                coverage: c.double("coverage") ?? AutoFraming.defaultCoverage)
                #expect(framing.coverageAdjusted == expect.bool("coverage_adjusted"), "\(id): coverage_adjusted")
                actual = framing.crop
            case "move":
                actual = CropAdjust.move(rect(try #require(c.dict("rect"))), dx: c.double("dx") ?? 0,
                                         dy: c.double("dy") ?? 0, imgW: imgW, imgH: imgH)
            case "zoom":
                actual = CropAdjust.zoom(rect(try #require(c.dict("rect"))), factor: c.double("factor") ?? 1,
                                         aspect: aspect, imgW: imgW, imgH: imgH)
            default:
                Issue.record("\(id): unknown op")
                continue
            }
            #expect(actual == rect(try #require(expect.dict("rect"))), "\(id)")
        }
    }

    @Test("zoom keeps the aspect, stays inside and never drops below 64 px")
    func zoomInvariants() {
        let aspect = 200.0 / 230.0
        var r = CropRect(x: 100, y: 100, w: 400, h: 460)
        for f in [1.3, 0.7, 2.5, 0.2, 1.1, 9.0, 0.05] {
            r = CropAdjust.zoom(r, factor: f, aspect: aspect, imgW: 1000, imgH: 1400)
            #expect(r.x >= 0 && r.y >= 0 && r.x + r.w <= 1000 && r.y + r.h <= 1400)
            #expect(abs(Double(r.w) / Double(r.h) - aspect) < 0.02)
            #expect(min(r.w, r.h) >= 64)
        }
        let tiny = CropAdjust.zoom(CropRect(x: 0, y: 0, w: 40, h: 46), factor: 10, aspect: aspect, imgW: 40, imgH: 50)
        #expect(tiny.w <= 40 && tiny.h <= 50)
    }

    @Test("rotate turns the raster clockwise")
    func rotate() {
        // 2x1: red, blue -> 1x2: red on top (the left column becomes the top row), blue below
        let src = Raster.make(2, 1) { x, _ in x == 0 ? 0xFF0000 : 0x0000FF }
        let out = CropAdjust.rotateClockwise(src)
        #expect(out.width == 1 && out.height == 2)
        #expect(out.r(0, 0) == 255 && out.b(0, 1) == 255)
    }
}
