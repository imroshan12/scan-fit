import CoreGraphics
import Foundation
import ImageIO
import Imaging
import Inspect
import ScanModel
import Testing

@Suite("FitEngine")
struct FitEngineTests {
    private let engine = FitEngine(encoder: ImageIOJpegEncoder())

    private func preferred() throws -> DocSpec {
        try TestSlots.inline(type: "photo", min: "20", max: "50", target: "38", dims: #"{"mode":"preferred","width":200,"height":230}"#)
    }

    private func ok(_ r: Result<FitResult, FitError>) throws -> FitResult {
        try r.get()
    }

    private func decodes(_ bytes: [UInt8]) -> Bool {
        guard let s = CGImageSourceCreateWithData(Data(bytes) as CFData, nil) else { return false }
        return CGImageSourceCreateImageAtIndex(s, 0, nil) != nil
    }

    /// A mostly-white page with thin strokes: tiny as a JPEG, like a clean signature.
    private func sparse(_ w: Int, _ h: Int) -> Raster { Raster.make(w, h) { x, y in (x / 3 + y / 5) % 17 == 0 ? 0x000000 : 0xFFFFFF } }

    private func inWindow(_ r: FitResult, _ min: Double, _ max: Double) {
        #expect((min...max).contains(r.kb), "\(r.kb) KB not in [\(min), \(max)]")
    }

    @Test("a detailed photo lands inside the window")
    func detailedPhoto() throws {
        let r = try ok(engine.fit(noisyRaster(200, 230, noise: 120), spec: preferred()))
        inWindow(r, 21, 49)
        #expect(r.width == 200 && r.height == 230)
        #expect((1...8).contains(r.encodes))
        #expect(decodes(r.bytes))
    }

    @Test("the result is baseline RGB with JFIF DPI and no EXIF, XMP or ICC")
    func structure() throws {
        let f = Inspector.inspect(try ok(engine.fit(noisyRaster(200, 230), spec: preferred())).bytes)
        #expect(f.sof == "SOF0" && f.components == 3)
        #expect(f.jfif == JfifDensity(units: 1, xDensity: 200, yDensity: 200))
        #expect(!f.hasExif && !f.hasXmp && !f.hasIcc)
    }

    @Test("the slot DPI is written when present")
    func slotDpi() throws {
        let s = try TestSlots.inline(type: "left_thumb", min: "20", max: "50", target: "38", dims: #"{"mode":"preferred","width":240,"height":240}"#, extra: #","dpi":300"#)
        #expect(Inspector.inspect(try ok(engine.fit(noisyRaster(240, 240, noise: 100), spec: s)).bytes).jfif?.xDensity == 300)
    }

    @Test("an impossible window is too detailed for fixed sizes")
    func tooDetailed() throws {
        let tight = try TestSlots.inline(type: "photo", min: "5", max: "7", target: "6", dims: #"{"mode":"preferred","width":200,"height":230}"#)
        #expect(engine.fit(noisyRaster(200, 230, noise: 255), spec: tight) == .failure(.tooDetailed))
        let exact = try TestSlots.inline(type: "photo", min: "5", max: "7", target: "6", dims: #"{"mode":"exact","width":200,"height":230}"#)
        #expect(engine.fit(noisyRaster(200, 230, noise: 255), spec: exact) == .failure(.tooDetailed))
    }

    @Test("none mode shrinks until it fits and reports downscale; gives up at the floor")
    func noneMode() throws {
        let none = try TestSlots.inline(type: "photo", min: "10", max: "40", target: "25", dims: #"{"mode":"none"}"#)
        let r = try ok(engine.fit(noisyRaster(1200, 1600, noise: 100), spec: none))
        #expect(r.strategy == .downscale)
        inWindow(r, 11, 39)
        #expect(r.height < 1600 && max(r.width, r.height) >= 600)
        #expect(abs(Double(r.width) / Double(r.height) - 0.75) < 0.01)
        let hopeless = try TestSlots.inline(type: "photo", min: "5", max: "8", target: "6", dims: #"{"mode":"none"}"#)
        #expect(engine.fit(noisyRaster(1200, 1600, noise: 255), spec: hopeless) == .failure(.tooDetailed))
        let inkSmall = try TestSlots.inline(type: "signature", min: "5", max: "8", target: "6", dims: #"{"mode":"none"}"#)
        #expect(engine.fit(noisyRaster(1000, 800, noise: 255), spec: inkSmall) == .failure(.tooDetailed))
    }

    @Test("range mode stays inside the box")
    func rangeMode() throws {
        let gate = try TestSlots.slot("gate", .photo)
        let r = try ok(engine.fit(noisyRaster(400, 516, noise: 200), spec: gate))
        #expect((200...530).contains(r.width) && (260...690).contains(r.height))
        inWindow(r, 5, 600)
        let tight = try TestSlots.inline(type: "photo", min: "5", max: "6", target: "5.5", dims: #"{"mode":"range","min_w":200,"min_h":260,"max_w":530,"max_h":690,"aspect_w_over_h":{"min":0.66,"max":0.89}}"#)
        #expect(engine.fit(noisyRaster(400, 516, noise: 255), spec: tight) == .failure(.tooDetailed))
    }

    @Test("an almost blank picture upscales then pads exactly to the target")
    func upscaleThenPad() throws {
        let sig = try TestSlots.inline(dims: #"{"mode":"preferred","width":140,"height":60}"#)
        let blank = Raster.make(420, 180) { x, y in y == 90 && (100...300).contains(x) ? 0x000000 : 0xFFFFFF }
        let r = try ok(engine.fit(blank, spec: sig))
        #expect(r.strategy == .upscaleThenPad)
        inWindow(r, 11, 19)
        #expect(r.width <= 280 && r.height <= 120 && r.width > 140)
        #expect(abs(Double(r.width) / Double(r.height) - 140.0 / 60.0) < 0.03)
        #expect(abs(r.kb - 16.0) < 0.01)
        #expect(decodes(r.bytes))
    }

    @Test("exact mode can only pad and never changes pixels; pad_only keeps the preferred size")
    func padPaths() throws {
        let exact = try TestSlots.inline(min: "12", max: "20", target: "16", dims: #"{"mode":"exact","width":100,"height":40}"#)
        let a = try ok(engine.fit(sparse(250, 100), spec: exact))
        #expect(a.strategy == .pad && a.width == 100 && a.height == 40 && abs(a.kb - 16) < 0.01)
        let sig = try TestSlots.inline(dims: #"{"mode":"preferred","width":140,"height":60}"#)
        let b = try ok(engine.fit(sparse(420, 180), spec: sig, options: FitOptions(minFill: .padOnly)))
        #expect(b.strategy == .pad && b.width == 140 && b.height == 60)
    }

    @Test("a window narrower than 2 KB uses the whole window as its goal")
    func narrowWindow() throws {
        let narrow = try TestSlots.inline(min: "10", max: "11", target: "10.5", dims: #"{"mode":"preferred","width":140,"height":60}"#)
        inWindow(try ok(engine.fit(sparse(420, 180), spec: narrow)), 10, 11)
    }

    @Test("no target means the window midpoint; an open minimum means zero")
    func targets() throws {
        let noTarget = try TestSlots.spec(#"{"type":"photo","required":true,"formats":["jpg"],"size_kb":{"min":20,"max":40,"target":null},"dimensions":{"mode":"preferred","width":200,"height":230}}"#)
        inWindow(try ok(engine.fit(noisyRaster(200, 230, noise: 150), spec: noTarget)), 21, 39)
        let openMin = try TestSlots.spec(#"{"type":"photo","required":true,"formats":["jpg"],"size_kb":{"min":null,"max":50,"target":35},"dimensions":{"mode":"none"}}"#)
        #expect(try ok(engine.fit(noisyRaster(600, 800, noise: 40), spec: openMin)).kb <= 50)
    }

    @Test("unknown limits and non-JPEG slots are typed errors")
    func typedErrors() throws {
        let noMax = try TestSlots.spec(#"{"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":null,"max":null,"target":null},"dimensions":{"mode":"none"}}"#)
        #expect(engine.fit(noisyRaster(10, 10), spec: noMax) == .failure(.unknownLimit))
        let pdf = try TestSlots.spec(#"{"type":"class10_certificate","required":true,"formats":["pdf"],"size_kb":{"min":null,"max":400,"target":null},"dimensions":{"mode":"none"}}"#)
        #expect(engine.fit(noisyRaster(10, 10), spec: pdf) == .failure(.unsupportedFormat))
    }

    @Test("an encoder that produces garbage is surfaced, never exported")
    func garbageEncoder() throws {
        struct Garbage: JpegEncoder { func encode(_ raster: Raster, quality: Int) -> [UInt8] { [1, 2, 3] } }
        #expect(FitEngine(encoder: Garbage()).fit(noisyRaster(10, 10), spec: try preferred()) == .failure(.encodingFailed))
    }

    @Test("fit is deterministic")
    func deterministic() throws {
        let a = try ok(engine.fit(noisyRaster(200, 230, noise: 90), spec: preferred()))
        let b = try ok(engine.fit(noisyRaster(200, 230, noise: 90), spec: preferred()))
        #expect(a == b)
    }

    @Test("export naming follows ALGORITHMS 9.8")
    func naming() throws {
        let ibps = try TestSlots.exam("ibps_po")
        let sig = try #require(ibps.documents.first { $0.type == .signature })
        #expect(ExportNaming.examShort("IBPS PO / MT") == "IBPS-PO")
        #expect(ExportNaming.examShort("SSC CGL (Tier 1)") == "SSC-CGL")
        #expect(ExportNaming.examShort("  A&B  C ") == "A-B-C")
        #expect(ExportNaming.fileName(exam: ibps, slot: sig, width: 140, height: 60, bytes: 16 * 1024 + 100) == "signature_IBPS-PO_140x60_16kb.jpg")
        let jee = try TestSlots.exam("jee_main")
        let photo = try #require(jee.documents.first { $0.type == .photo })
        #expect(ExportNaming.fileName(exam: jee, slot: photo, width: 1, height: 1, bytes: 1) == "Photograph.jpg")
    }
}
