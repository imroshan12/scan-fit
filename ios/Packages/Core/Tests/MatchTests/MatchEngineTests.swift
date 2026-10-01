import Foundation
import Inspect
import Match
import ScanModel
import Testing

@Suite("MatchEngine")
struct MatchEngineTests {
    private func slotJSON(type: String = "signature", formats: String = #"["jpg"]"#, min: String = "10", max: String = "20",
                          dims: String = #"{"mode":"preferred","width":140,"height":60}"#, dpi: String? = nil) -> String {
        let dpiPart = dpi.map { #","dpi":\#($0)"# } ?? ""
        return #"{"type":"\#(type)","required":true,"formats":\#(formats),"size_kb":{"min":\#(min),"max":\#(max),"target":null},"dimensions":\#(dims)\#(dpiPart)}"#
    }

    private func slot(type: String = "signature", formats: String = #"["jpg"]"#, min: String = "10", max: String = "20",
                      dims: String = #"{"mode":"preferred","width":140,"height":60}"#, dpi: String? = nil) throws -> DocSpec {
        let json = slotJSON(type: type, formats: formats, min: min, max: max, dims: dims, dpi: dpi)
        return try PresetBundle.decoder().decode(DocSpec.self, from: Data(json.utf8))
    }

    private func exam(_ id: String, _ name: String, slotJSON: String? = nil, confidence: String = "high", status: String = "active") throws -> Exam {
        let doc = slotJSON ?? self.slotJSON()
        let json = #"{"id":"\#(id)","name":"\#(name)","body":"B","category":"banking","live_photo_capture":null,"status":"\#(status)","documents":[\#(doc)],"special_rules":[],"sources":[{"url":"https://x.example","kind":"official"}],"confidence":"\#(confidence)","confidence_note":"","last_verified":"2026-01-01"}"#
        return try PresetBundle.decodeExam(Data(json.utf8))
    }

    private func jpeg(kb: Double = 15, w: Int? = 140, h: Int? = 60, color: ColorKind = .rgb, progressive: Bool = false,
                      kind: DocKind = .signature, dpi: Int? = nil) -> FileFacts {
        FileFacts(format: .jpeg, kb: kb, width: w, height: h, color: color, progressive: progressive, docKind: kind, dpi: dpi)
    }

    private func verdict(_ slot: DocSpec, _ file: FileFacts, _ options: MatchOptions = MatchOptions()) -> SlotEvaluation {
        MatchEngine.evaluate(slot, file, options: options)
    }

    @Test("exact mode needs an exact pixel match")
    func exactMode() throws {
        let s = try slot(dims: #"{"mode":"exact","width":100,"height":40}"#)
        #expect(verdict(s, jpeg(w: 100, h: 40)).verdict == .exact)
        let off = verdict(s, jpeg(w: 101, h: 40))
        #expect(off.verdict == .no)
        #expect(off.issues == [.wrongDimensions])
    }

    @Test("preferred mode: 3% aspect tolerance, 2 px exactness")
    func preferredMode() throws {
        let s = try slot()
        #expect(verdict(s, jpeg(w: 142, h: 60)).verdict == .exact)
        #expect(verdict(s, jpeg(w: 143, h: 60)).verdict == .accepted)
        #expect(verdict(s, jpeg(w: 146, h: 60)).verdict == .no)
        #expect(verdict(s, jpeg(w: 146, h: 60)).issues == [.wrongAspect])
        #expect(verdict(s, jpeg(w: 280, h: 120)).verdict == .accepted)
    }

    @Test("range mode checks the box and the aspect")
    func rangeMode() throws {
        let s = try slot(dims: #"{"mode":"range","min_w":250,"min_h":80,"max_w":580,"max_h":180,"aspect_w_over_h":{"min":2.75,"max":3.75}}"#)
        #expect(verdict(s, jpeg(w: 500, h: 150)).verdict == .exact)
        #expect(verdict(s, jpeg(w: 600, h: 160)).issues == [.wrongDimensions])
        #expect(verdict(s, jpeg(w: 300, h: 150)).issues == [.wrongAspect])
        let noAspect = try slot(dims: #"{"mode":"range","min_w":10,"min_h":10,"max_w":500,"max_h":500}"#)
        #expect(verdict(noAspect, jpeg(w: 400, h: 20)).verdict == .exact)
    }

    @Test("none mode accepts any size; a PDF ignores dimensions")
    func noneAndPdf() throws {
        let none = try slot(dims: #"{"mode":"none"}"#)
        #expect(verdict(none, jpeg(w: 1, h: 9999)).verdict == .exact)
        let pdfSlot = try slot(type: "class10_certificate", formats: #"["pdf"]"#, min: "0", max: "400", dims: #"{"mode":"none"}"#)
        let pdf = FileFacts(format: .pdf, kb: 250, width: nil, height: nil, color: .unknown, progressive: false, docKind: .pdfDocument)
        #expect(verdict(pdfSlot, pdf).verdict == .exact)
        #expect(verdict(pdfSlot, jpeg(kb: 100, kind: .pdfDocument)).verdict == .no)
    }

    @Test("no limits and no dimensions is unknown; a null max is never exact")
    func unknownAndNullMax() throws {
        let unknown = try PresetBundle.decoder().decode(DocSpec.self, from: Data(#"{"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":null,"max":null,"target":null},"dimensions":{"mode":"none"}}"#.utf8))
        #expect(verdict(unknown, jpeg()).verdict == .unknown)
        let open = try PresetBundle.decoder().decode(DocSpec.self, from: Data(#"{"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":5,"max":null,"target":null},"dimensions":{"mode":"preferred","width":140,"height":60}}"#.utf8))
        #expect(verdict(open, jpeg(kb: 500)).verdict == .accepted)
        #expect(verdict(open, jpeg(kb: 4)).verdict == .no)
    }

    @Test("size near misses stop at half the window")
    func sizeNearMisses() throws {
        let s = try slot(min: "10", max: "20")
        #expect(verdict(s, jpeg(kb: 25)).fix == .compressToTarget)
        #expect(verdict(s, jpeg(kb: 25)).verdict == .nearMiss)
        #expect(verdict(s, jpeg(kb: 25.1)).verdict == .no)
        #expect(verdict(s, jpeg(kb: 5)).fix == .enlargeToTarget)
        #expect(verdict(s, jpeg(kb: 4.9)).verdict == .no)
        #expect(verdict(s, jpeg(kb: 22)).issues == [.tooLargeKb])
        #expect(verdict(s, jpeg(kb: 8)).issues == [.tooSmallKb])
    }

    @Test("two failing constraints is never a near miss")
    func twoFailures() throws {
        let r = verdict(try slot(), jpeg(kb: 22, progressive: true))
        #expect(r.verdict == .no)
        #expect(r.failed == [.sizeKb, .encoding])
        #expect(r.issues == [.tooLargeKb, .progressiveJpeg])
    }

    @Test("encoding failures map to their issues; grayscale needs the flag and an ink kind")
    func encoding() throws {
        #expect(verdict(try slot(), jpeg(progressive: true)).issues == [.progressiveJpeg])
        #expect(verdict(try slot(), jpeg(color: .cmyk)).issues == [.cmykColor])
        #expect(verdict(try slot(), jpeg(color: .gray)).issues == [.grayscaleNotAllowed])
        #expect(verdict(try slot(), jpeg(color: .gray), MatchOptions(allowGrayscale: true)).verdict == .exact)
        let photoSlot = try slot(type: "photo", min: "20", max: "50", dims: #"{"mode":"preferred","width":200,"height":230}"#)
        let grayPhoto = jpeg(kb: 35, w: 200, h: 230, color: .gray, kind: .photo)
        #expect(verdict(photoSlot, grayPhoto, MatchOptions(allowGrayscale: true)).verdict == .nearMiss)
    }

    @Test("format fixes only for convertible formats when the slot takes JPEG")
    func formats() throws {
        let png = FileFacts(format: .png, kb: 15, width: 140, height: 60, color: .rgb, progressive: false, docKind: .signature)
        #expect(verdict(try slot(), png).fix == .convertToJpeg)
        #expect(verdict(try slot(formats: #"["pdf"]"#), png).verdict == .no)
        #expect(verdict(try slot(formats: #"["png"]"#), png).verdict == .exact)
        var heic = png
        heic.format = .heic
        #expect(verdict(try slot(), heic).fix == .convertToJpeg)
        var unknown = png
        unknown.format = .unknown
        #expect(verdict(try slot(), unknown).verdict == .no)
        #expect(verdict(try slot(formats: #"["jpeg"]"#), jpeg()).verdict == .exact)
    }

    @Test("a DPI mismatch is an advisory issue only")
    func dpi() throws {
        let s = try slot(dpi: "200")
        let r = verdict(s, jpeg(dpi: 72))
        #expect(r.verdict == .exact)
        #expect(r.issues == [.lowDpiMetadata])
        #expect(verdict(s, jpeg(dpi: 200)).issues.isEmpty)
        #expect(verdict(s, jpeg(dpi: nil)).issues.isEmpty)
    }

    @Test("facts come from an inspected file; dots per cm are not dpi")
    func facts() {
        var inspected = InspectedFile(format: .jpeg, bytes: 2048)
        inspected.width = 140
        inspected.height = 60
        inspected.color = .rgb
        inspected.jfif = JfifDensity(units: 1, xDensity: 200, yDensity: 200)
        let f = FileFacts(inspected, docKind: .signature)
        #expect(f.kb == 2.0 && f.dpi == 200)
        inspected.jfif = JfifDensity(units: 2, xDensity: 79, yDensity: 79)
        #expect(FileFacts(inspected, docKind: .signature).dpi == nil)
    }

    @Test("inactive exams are skipped; order is verdict, popularity, then name by code point")
    func ordering() throws {
        let exams = [try exam("c", "Zeta"), try exam("a", "Alpha"), try exam("b", "Beta"),
                     try exam("hidden", "Hidden", status: "hidden"), try exam("near", "Near", slotJSON: slotJSON(max: "14")),
                     try exam("low", "Low", confidence: "low")]
        let r = MatchEngine.match(jpeg(kb: 15), exams: exams, options: MatchOptions(popularity: ["c", "a"]))
        #expect(r.entries.filter { $0.verdict != .nearMiss }.map(\.examId) == ["c", "a", "b", "low"])
        #expect(r.entries.first { $0.examId == "low" }?.verdict == .accepted)
        #expect(!r.entries.contains { $0.examId == "hidden" })
        #expect(r.entries.last?.verdict == .nearMiss)
        #expect(r.acceptedExamCount == 3 && r.quickFixCount == 1)

        let three = [try exam("x", "b"), try exam("y", "B"), try exam("z", "a")]
        #expect(MatchEngine.match(jpeg(), exams: three).entries.map(\.examId) == ["y", "z", "x"])
        #expect(MatchEngine.match(jpeg(), exams: three, options: MatchOptions(popularity: ["x"])).entries.map(\.examId) == ["x", "y", "z"])
    }

    @Test("every kind maps to slot types; PDFs match by format")
    func kinds() {
        for kind in DocKind.allCases where kind != .pdfDocument { #expect(!MatchEngine.slotTypes(for: kind).isEmpty, "\(kind)") }
        #expect(MatchEngine.slotTypes(for: .pdfDocument).isEmpty)
    }
}
