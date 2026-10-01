import CoreGraphics
import Foundation
import ImageIO
import Imaging
import Inspect
import ScanModel
import TestSupport
import Testing

/// Runs every `fit_cases` entry of spec/fixtures/cases.json end to end with the production ImageIO codec (ALGORITHMS 9.9).
@Suite("Fit conformance")
struct FitConformanceTests {
    private func median(_ r: Raster) -> Int { Int(r.luma().sorted()[r.width * r.height / 2]) }

    @Test("every fit case meets its expectations")
    func everyCase() throws {
        let cases = try CasesFile.section("fit_cases")
        #expect(cases.count >= 11)
        let pipeline = FitPipeline(encoder: ImageIOJpegEncoder())
        for c in cases {
            let id = try #require(c.string("id"))
            let bytes = try CasesFile.image(try #require(c.string("input")))
            let original = Inspector.inspect(bytes)
            var exam: Exam?
            if let preset = c.string("preset") { exam = try TestSlots.exam(preset) }
            let spec: DocSpec
            if let inline = c["spec"] {
                spec = try PresetBundle.decoder().decode(DocSpec.self, from: JSONSerialization.data(withJSONObject: inline))
            } else {
                let type = try #require(DocType(rawValue: c.string("doc") ?? ""))
                spec = try #require(exam?.documents.first { $0.type == type })
            }
            let d = spec.dimensions
            let largest = [d.width, d.height, d.maxW, d.maxH].compactMap { $0 }.max() ?? 0
            let decoded = try #require(ImageIODecoder.decode(bytes, maxLongSide: ImageIODecoder.longSideCap(largestTargetDimension: largest)), "\(id): decode")
            var crop: CropRect?
            if let rect = c.dict("crop") {
                crop = CropRect(x: rect.int("x") ?? 0, y: rect.int("y") ?? 0, w: rect.int("w") ?? 1, h: rect.int("h") ?? 1)
                    .scaled(srcW: original.width ?? 1, srcH: original.height ?? 1, to: decoded)
            }
            let kind = Pipeline(rawValue: c.string("pipeline") ?? "plain") ?? .plain
            let minFill: MinFillStrategy = c.dict("options")?.string("min_fill_strategy") == "pad_only" ? .padOnly : .upscaleThenPad
            let outcome = pipeline.run(decoded, spec: spec, pipeline: kind, crop: crop, options: FitOptions(minFill: minFill))
            guard case let .success(piped) = outcome else {
                Issue.record("\(id): fit failed with \(outcome)")
                continue
            }
            let result = piped.fit
            let e = try #require(c.dict("expect"))
            let f = Inspector.inspect(result.bytes)
            let w = f.width ?? -1, h = f.height ?? -1

            if e.string("format") != nil { #expect(f.format == .jpeg && f.sof == "SOF0", "\(id): baseline jpeg") }
            if let color = e.string("color") { #expect(f.color.rawValue == color, "\(id): color") }
            if let min = e.double("kb_min") { #expect(f.kb >= min, "\(id): \(f.kb) KB >= \(min)") }
            if let max = e.double("kb_max") { #expect(f.kb <= max, "\(id): \(f.kb) KB <= \(max)") }
            if let tw = e.int("width"), let th = e.int("height") {
                if e.bool("allow_upscaled_preferred") == true {
                    #expect((tw...(tw * 2)).contains(w) && (th...(th * 2)).contains(h), "\(id): \(w)x\(h) is 1x..2x of \(tw)x\(th)")
                    #expect(abs(w * th - h * tw) <= max(tw, th), "\(id): aspect kept (\(w)x\(h))")
                } else {
                    #expect(w == tw && h == th, "\(id): size \(w)x\(h)")
                }
            }
            if let r = e["w_range"] as? [Double] { #expect(Double(w) >= r[0] && Double(w) <= r[1], "\(id): width \(w)") }
            if let r = e["h_range"] as? [Double] { #expect(Double(h) >= r[0] && Double(h) <= r[1], "\(id): height \(h)") }
            if let r = e["aspect_range"] as? [Double] { #expect(Double(w) / Double(h) >= r[0] && Double(w) / Double(h) <= r[1], "\(id): aspect") }
            if let aspect = e.double("aspect") { #expect(abs(aspect - Double(w) / Double(h)) <= (e.double("aspect_tol") ?? 0.02), "\(id): aspect") }
            if e.string("exif") != nil { #expect(!f.hasExif && !f.hasXmp && !f.hasIcc, "\(id): no EXIF/XMP/ICC") }
            if let dpi = e.int("dpi") { #expect(f.jfif == JfifDensity(units: 1, xDensity: dpi, yDensity: dpi), "\(id): dpi") }
            if let name = e.string("export_filename"), let exam {
                #expect(ExportNaming.fileName(exam: exam, slot: spec, width: w, height: h, bytes: result.bytes.count) == name, "\(id): file name")
            }
            let back = try #require(ImageIODecoder.decode(result.bytes, maxLongSide: 4000), "\(id): the written bytes must decode")
            if let min = e.double("background_mean_min") { #expect(Double(median(back)) >= min, "\(id): median luma") }
            if let pct = e.double("ink_pixels_min_pct") {
                let ink = Double(back.luma().filter { $0 < 128 }.count) * 100 / Double(back.width * back.height)
                #expect(ink >= pct, "\(id): ink \(ink)% >= \(pct)%")
            }
        }
    }
}
