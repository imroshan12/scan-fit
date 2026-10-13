import Foundation
import ScanModel
import TestSupport
import Testing

/// Runs every `custom_size_cases` entry of spec/fixtures/cases.json (ALGORITHMS 11).
@Suite("Custom size conformance")
struct CustomSizeConformanceTests {
    private func fields(_ f: [String: Any]) throws -> CustomSizeFields {
        CustomSizeFields(
            doc: try #require(DocType(rawValue: f.string("doc") ?? "")),
            unit: f.string("unit") == "cm" ? .cm : .px,
            width: f.double("width"), height: f.double("height"),
            minKb: f.int("min_kb"), maxKb: f.int("max_kb"), dpi: f.int("dpi")
        )
    }

    @Test("every custom size case holds")
    func everyCase() throws {
        let cases = try CasesFile.section("custom_size_cases")
        #expect(cases.count >= 15)
        for c in cases {
            let id = try #require(c.string("id"))
            let expect = try #require(c.dict("expect"))
            if let parse = c.string("parse") {
                #expect(CustomSize.parse(parse)?.text == expect.string("text"), "\(id)")
                continue
            }
            let input = try fields(try #require(c.dict("fields")))
            if let errors = expect.strings("errors") {
                #expect(input.errors.map(\.rawValue) == errors, "\(id)")
                #expect(input.size == nil, "\(id)")
                continue
            }
            let size = try #require(input.size, "\(id) should be valid: \(input.errors)")
            #expect(size.text == expect.string("text"), "\(id)")
            #expect(CustomSize.parse(size.text) == size, "\(id) round-trips")
            let slot = try #require(expect.dict("slot"))
            let spec = size.spec
            #expect(spec.type.rawValue == slot.string("type"), "\(id)")
            #expect(spec.dimensions.mode.rawValue == slot.string("mode"), "\(id)")
            #expect(spec.dimensions.width == slot.int("width") && spec.dimensions.height == slot.int("height"), "\(id)")
            #expect(spec.sizeKb.min == slot.double("min_kb") && spec.sizeKb.max == slot.double("max_kb"), "\(id)")
            #expect(spec.dpi == slot.int("dpi") && spec.formats == [.jpg], "\(id)")
        }
    }

    @Test("recent sizes keep the last five distinct, newest first")
    func recent() {
        var recent: [String] = []
        for text in ["a", "b", "c", "a", "d", "e", "f"] { recent = CustomSize.remember(recent, text) }
        #expect(recent == ["f", "e", "d", "a", "c"])
    }
}
