import Foundation
@testable import Imaging
import Inspect
import Match
import ScanModel
import TestSupport
import Testing

/// Runs every `thumb_preservation_cases` entry of spec/fixtures/cases.json (ALGORITHMS 9.5 thumb): a parametric print
/// drawn on paper must keep its heavily inked areas dark, stay whole inside the square and leave the paper white.
@Suite("Thumb preservation conformance")
struct ThumbPreservationConformanceTests {
    /// The print described by a case (the generator is part of the contract, see `_doc` of the section).
    static func source(_ c: [String: Any]) throws -> Raster {
        let w = try #require(c.int("width")), h = try #require(c.int("height"))
        let paper = try #require(c["paper"] as? [Double])
        let shape = try #require(c["print"] as? [Double])
        let ridge = try #require(c["ridge"] as? [Double])
        let rim = c["rim"] as? [Double]
        let core = c["core"] as? [Double]
        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        for y in 0..<h {
            for x in 0..<w {
                var v = roundHalfUp(paper[0] + (paper[1] - paper[0]) * Double(x) / Double(w - 1))
                let ux = (Double(x) - shape[0]) / shape[2], uy = (Double(y) - shape[1]) / shape[3]
                let r = (ux * ux + uy * uy).squareRoot()
                if r <= 1 {
                    let d = ((Double(x) - shape[0]) * (Double(x) - shape[0])
                        + (Double(y) - shape[1]) * shape[2] / shape[3] * (Double(y) - shape[1]) * shape[2] / shape[3])
                        .squareRoot()
                    if Int((2 * d / ridge[0]).rounded(.down)) % 2 == 0 {
                        v = Int(rim.map { r > $0[0] ? $0[1] : ridge[1] } ?? ridge[1])
                    }
                }
                if let core {
                    let cx = (Double(x) - core[0]) / core[2], cy = (Double(y) - core[1]) / core[3]
                    if cx * cx + cy * cy <= 1 { v = Int(core[4]) }
                }
                let i = (y * w + x) * 3
                rgb[i] = UInt8(v); rgb[i + 1] = UInt8(v); rgb[i + 2] = UInt8(v)
            }
        }
        return Raster(width: w, height: h, rgb: rgb)
    }

    static func count(_ r: Raster, where test: (Int) -> Bool) -> Int {
        var n = 0
        for y in 0..<r.height { for x in 0..<r.width where test(r.r(x, y)) { n += 1 } }
        return n
    }

    /// Bounding box of the pixels whose value (red channel; the rasters are grey) passes `ink`.
    static func box(_ r: Raster, ink: (Int, Int) -> Bool) -> (x: Int, y: Int, w: Int, h: Int)? {
        var minX = Int.max, minY = Int.max, maxX = -1, maxY = -1
        for y in 0..<r.height {
            for x in 0..<r.width where ink(x, r.r(x, y)) {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        return maxX < 0 ? nil : (minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    @Test("every thumb case keeps the print dark, whole and on white paper")
    func everyCase() throws {
        let cases = try CasesFile.section("thumb_preservation_cases")
        #expect(cases.count >= 3)
        for c in cases {
            let id = try #require(c.string("id"))
            let expect = try #require(c.dict("expect"))
            let src = try Self.source(c)
            let result = InkCleanup.clean(src, variant: .thumb)
            let out = result.raster
            #expect(out.width == out.height, "\(id): square")
            // Cleanup keeps the source scale: the ink box locates the print in the output.
            let delta = try #require(expect.int("ink_delta")), tolerance = try #require(expect.int("box_tolerance"))
            let paper = try #require(c["paper"] as? [Double])
            let srcBox = try #require(Self.box(src) { x, v in
                v < roundHalfUp(paper[0] + (paper[1] - paper[0]) * Double(x) / Double(src.width - 1)) - delta
            })
            let visible = try #require(expect.int("visible_below"))
            let outBox = try #require(Self.box(out) { _, v in v < visible })
            #expect(abs(outBox.w - srcBox.w) <= tolerance && abs(outBox.h - srcBox.h) <= tolerance,
                    "\(id): the whole print is kept: source box \(srcBox), output box \(outBox)")
            if let maxMean = expect.int("core_max_mean"), let core = c["core"] as? [Double] {
                let dx = outBox.x - srcBox.x, dy = outBox.y - srcBox.y
                var sum = 0, n = 0
                for y in 0..<out.height {
                    for x in 0..<out.width {
                        let ux = (Double(x - dx) - core[0]) / (0.7 * core[2])
                        let uy = (Double(y - dy) - core[1]) / (0.7 * core[3])
                        if ux * ux + uy * uy <= 1 { sum += out.r(x, y); n += 1 }
                    }
                }
                #expect(n > 0 && sum / n <= maxMean, "\(id): the heavy-ink core stays dark (mean \(n > 0 ? sum / n : -1))")
            }
            if let below = expect.int("ink_below"), let min = expect.int("ink_min") {
                let ink = Self.count(out) { $0 < below }
                #expect(ink >= min, "\(id): \(ink) ink pixels darker than \(below), want ≥ \(min)")
            }
            let border = try #require(expect.int("border")), clear = try #require(expect.int("border_clear_above"))
            var touching = 0
            for y in 0..<out.height {
                for x in 0..<out.width where x < border || y < border || x >= out.width - border || y >= out.height - border {
                    if out.r(x, y) < clear { touching += 1 }
                }
            }
            #expect(touching == 0, "\(id): \(touching) ink pixels on the output border (the print was clipped)")
            let white = Double(Self.count(out) { $0 >= 250 }) / Double(out.width * out.height)
            let minWhite = try #require(expect.double("white_min_fraction"))
            #expect(white >= minWhite, "\(id): white fraction \(white)")
            #expect(result.quality == .ok, "\(id): quality \(result.quality)")
        }
    }

    @Test("a heavy-core thumb survives the IBPS fit and its final bytes pass the slot")
    func actualFit() throws {
        let c = try #require(try CasesFile.section("thumb_preservation_cases").first { $0.string("id") == "heavy_core_under_shadow" })
        let bundle = try SpecPresets.bundle()
        let slot = try #require(bundle.exams.first { $0.id == "ibps_po" }?.documents.first { $0.type == .leftThumb })
        let outcome = FitPipeline(encoder: ImageIOJpegEncoder()).run(try Self.source(c), spec: slot, pipeline: .thumbCleanup)
        guard case let .success(result) = outcome else { Issue.record("fit failed: \(outcome)"); return }
        let file = Inspector.inspect(result.fit.bytes)
        let verdict = MatchEngine.evaluate(slot, FileFacts(file, docKind: .thumb)).verdict
        #expect(verdict == .exact || verdict == .accepted, "final slot verdict: \(verdict)")
        let decoded = try #require(ImageIODecoder.decode(result.fit.bytes, maxLongSide: 4000))
        let dark = Self.count(decoded) { $0 < 100 }
        let scale = Double(decoded.width * decoded.height) / Double(result.prepared.width * result.prepared.height)
        let preparedDark = Self.count(result.prepared) { $0 < 100 }
        #expect(Double(dark) >= 0.6 * Double(preparedDark) * scale, "dark core survives encoding: \(dark)")
    }
}
