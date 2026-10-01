import Foundation
import Inspect
import TestSupport
import Testing

/// Runs every `inspect_cases` entry of spec/fixtures/cases.json (the shared conformance contract).
@Suite("Inspector conformance")
struct InspectorConformanceTests {
    @Test("every inspect case matches the spec")
    func everyCase() throws {
        let cases = try CasesFile.section("inspect_cases")
        #expect(cases.count >= 16)
        for c in cases {
            let id = try #require(c.string("id"))
            let name = try #require(c.string("input"))
            let expect = try #require(c.dict("expect"))
            let f = Inspector.inspect(try CasesFile.image(name), fileName: name)
            #expect(f.format.rawValue == expect.string("format"), "\(id): format")
            if let color = expect.string("color") { #expect(f.color.rawValue == color, "\(id): color") }
            if let value = expect.bool("progressive") { #expect(f.progressive == value, "\(id): progressive") }
            if let value = expect.int("width") { #expect(f.width == value, "\(id): width") }
            if let value = expect.int("height") { #expect(f.height == value, "\(id): height") }
            if let value = expect.int("exif_orientation") { #expect(f.exifOrientation == value, "\(id): orientation") }
            if let value = expect.bool("has_exif") { #expect(f.hasExif == value, "\(id): has_exif") }
            if let value = expect.bool("has_gps") { #expect(f.hasGps == value, "\(id): has_gps") }
            if let value = expect.bool("has_icc") { #expect(f.hasIcc == value, "\(id): has_icc") }
            #expect(f.issues.map(\.rawValue) == expect.strings("issues"), "\(id): issues")
        }
    }
}
