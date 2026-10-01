import Foundation
import Imaging
import ScanModel
import TestSupport
import Testing

@Suite("Geometry")
struct GeometryTests {
    @Test("the shared geometry cases all hold")
    func sharedCases() throws {
        let cases = try CasesFile.section("geometry_cases")
        #expect(cases.count >= 8)
        for c in cases {
            let id = try #require(c.string("id"))
            let preset = try #require(c.string("preset"))
            let type = try #require(DocType(rawValue: c.string("doc") ?? ""))
            let spec = try TestSlots.slot(preset, type)
            let src = try #require(c.dict("source"))
            let sw = try #require(src.int("w")), sh = try #require(src.int("h"))
            let e = try #require(c.dict("expect"))
            if let crop = e.dict("crop") {
                let a = try #require(Geometry.targetAspect(spec))
                #expect(Geometry.defaultCrop(srcW: sw, srcH: sh, aspect: a) == CropRect(x: crop.int("x") ?? -1, y: crop.int("y") ?? -1, w: crop.int("w") ?? -1, h: crop.int("h") ?? -1), "\(id): crop")
            }
            if let pad = e.dict("pad") {
                let a = try #require(Geometry.targetAspect(spec))
                #expect(Geometry.padToAspect(srcW: sw, srcH: sh, aspect: a) == PadPlan(w: pad.int("w") ?? -1, h: pad.int("h") ?? -1, x: pad.int("x") ?? -1, y: pad.int("y") ?? -1), "\(id): pad")
            }
            if let start = e.dict("start") {
                #expect(Geometry.startSize(spec, srcW: sw, srcH: sh) == Size(start.int("w") ?? -1, start.int("h") ?? -1), "\(id): start")
            }
        }
    }

    @Test("range start clamps the height into the box; open aspect comes from the box midpoints")
    func rangeStart() throws {
        let s = try TestSlots.inline(dims: #"{"mode":"range","min_w":100,"min_h":100,"max_w":300,"max_h":120,"aspect_w_over_h":{"min":0.5,"max":0.5}}"#)
        #expect(Geometry.startSize(s, srcW: 1000, srcH: 1000) == Size(100, 120))
        let open = try TestSlots.inline(dims: #"{"mode":"range","min_w":100,"min_h":50,"max_w":300,"max_h":150}"#)
        #expect(Geometry.targetAspect(open) == 2.0)
        #expect(Geometry.startSize(open, srcW: 1000, srcH: 1000) == Size(200, 100))
    }

    @Test("none mode keeps the aspect and never upscales")
    func noneMode() throws {
        let sig = try TestSlots.inline(dims: #"{"mode":"none"}"#)
        #expect(Geometry.startSize(sig, srcW: 2000, srcH: 1000) == Size(1000, 500))
        #expect(Geometry.startSize(sig, srcW: 1000, srcH: 2000) == Size(500, 1000))
        #expect(Geometry.startSize(sig, srcW: 300, srcH: 100) == Size(300, 100))
        #expect(Geometry.targetAspect(sig) == nil)
        let doc = try TestSlots.inline(type: "class10_certificate", dims: #"{"mode":"none"}"#)
        #expect(Geometry.startSize(doc, srcW: 4000, srcH: 2000) == Size(1600, 800))
    }

    @Test("scale is measured from the start size and never below one pixel")
    func scaled() {
        #expect(Geometry.scaled(Size(140, 60), 0.85) == Size(119, 51))
        #expect(Geometry.scaled(Size(140, 60), 1.25) == Size(175, 75))
        #expect(Geometry.scaled(Size(2, 2), 0.01) == Size(1, 1))
    }

    @Test("upscale caps follow the mode")
    func caps() throws {
        let start = Size(140, 60)
        let preferred = try TestSlots.inline(dims: #"{"mode":"preferred","width":140,"height":60}"#)
        #expect(Geometry.withinUpscaleCap(preferred, start: start, candidate: Size(280, 120)))
        #expect(!Geometry.withinUpscaleCap(preferred, start: start, candidate: Size(281, 120)))
        let exact = try TestSlots.inline(dims: #"{"mode":"exact","width":140,"height":60}"#)
        #expect(!Geometry.withinUpscaleCap(exact, start: start, candidate: Size(141, 60)))
        let range = try TestSlots.inline(dims: #"{"mode":"range","min_w":10,"min_h":10,"max_w":500,"max_h":200,"aspect_w_over_h":{"min":2,"max":3}}"#)
        #expect(Geometry.withinUpscaleCap(range, start: start, candidate: Size(500, 200)))
        #expect(!Geometry.withinUpscaleCap(range, start: start, candidate: Size(501, 200)))
        let none = try TestSlots.inline(dims: #"{"mode":"none"}"#)
        #expect(Geometry.withinUpscaleCap(none, start: start, candidate: Size(2000, 5)))
        #expect(!Geometry.withinUpscaleCap(none, start: start, candidate: Size(2001, 5)))
    }

    @Test("only photos crop to aspect; everything else pads")
    func profiles() {
        #expect(FitProfile.cropsToAspect(.photo) && FitProfile.cropsToAspect(.postcardPhoto))
        for type in DocType.allCases where type != .photo && type != .postcardPhoto { #expect(!FitProfile.cropsToAspect(type), "\(type)") }
    }
}
