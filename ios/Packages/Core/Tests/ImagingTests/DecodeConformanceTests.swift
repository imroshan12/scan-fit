import Foundation
import Imaging
import TestSupport
import Testing

/// Runs every `decode_cases` entry of spec/fixtures/cases.json through the production decoder (ALGORITHMS 1.1).
@Suite("Decode conformance")
struct DecodeConformanceTests {
    @Test("every decode case holds, including all 8 EXIF orientations")
    func sharedCases() throws {
        let cases = try CasesFile.section("decode_cases")
        #expect(cases.count >= 10)
        for c in cases {
            let id = try #require(c.string("id"))
            let bytes = try CasesFile.image(try #require(c.string("input")))
            let cap = ImageIODecoder.longSideCap(largestTargetDimension: c.int("target_max_dim") ?? 0)
            let raster = try #require(ImageIODecoder.decode(bytes, maxLongSide: cap), "\(id): decodes")
            let e = try #require(c.dict("expect"))
            #expect(raster.width == e.int("width") && raster.height == e.int("height"), "\(id): size \(raster.width)x\(raster.height)")
            guard let tolerance = e.int("tolerance") else { continue }
            let samples: [(String, Int, Int)] = [("TL", raster.width / 4, raster.height / 4), ("TR", raster.width * 3 / 4, raster.height / 4),
                                                 ("BL", raster.width / 4, raster.height * 3 / 4), ("BR", raster.width * 3 / 4, raster.height * 3 / 4)]
            for (name, x, y) in samples {
                let want = (e[name] as? [Int]) ?? []
                let got = [raster.r(x, y), raster.g(x, y), raster.b(x, y)]
                #expect(zip(got, want).allSatisfy { abs($0 - $1) <= tolerance }, "\(id) \(name): got \(got), want \(want)")
            }
        }
    }

    @Test("the long-side cap is max(2 x largest target, 1600)")
    func cap() {
        #expect(ImageIODecoder.longSideCap(largestTargetDimension: 230) == 1600)
        #expect(ImageIODecoder.longSideCap(largestTargetDimension: 1000) == 2000)
    }

    @Test("garbage does not decode")
    func garbage() {
        #expect(ImageIODecoder.decode([1, 2, 3, 4], maxLongSide: 100) == nil)
    }
}
