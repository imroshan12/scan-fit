import Foundation
import Imaging
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

@Suite("Export conformance")
struct ExportConformanceTests {
    private func request(_ bytes: [UInt8]? = nil) throws -> ExportRequest {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        let spec = try #require(exam.documents.first { $0.type == .photo })
        return ExportRequest(
            bytes: bytes ?? TestJpeg.make(width: 200, height: 230, size: 34 * 1024),
            filename: "photo_IBPS-PO_200x230_34kb.jpg", spec: spec, kind: .photo
        )
    }

    @Test("every shared export case exercises transport, verification, cleanup and publication")
    func conformance() async throws {
        let cases = try ExportCases.load()
        #expect(cases.count == 8)
        for test in cases {
            let transport = ScriptedExportTransport(test.operation)
            let result = await ExportOperation.run(
                try request(), transport: test.operation == "cancel" ? nil : transport
            )
            #expect(result.state.rawValue == test.expect.state, "\(test.id)")
            #expect(result.removed == test.expect.delete, "\(test.id)")
            #expect(await transport.deleted == test.expect.delete, "\(test.id)")
            #expect(await transport.published == test.expect.record, "\(test.id)")
        }
    }

    @Test("failed best-effort cleanup does not report removal")
    func failedCleanup() async throws {
        let transport = ScriptedExportTransport("corrupt", deletionWorks: false)
        let result = await ExportOperation.run(try request(), transport: transport)
        #expect(result.state == .verifyFailed && !result.removed)
    }

    @Test("exact byte equality is required even when both JPEGs match")
    func equality() throws {
        let request = try request()
        var different = request.bytes
        different[different.count - 3] = 0x56
        #expect(!ExportOperation.verifies(different, expected: request.bytes, spec: request.spec, kind: .photo))
        #expect(ExportOperation.verifies(request.bytes, expected: request.bytes, spec: request.spec, kind: .photo))
    }

    @Test("metadata and matching are checked independently of byte equality")
    func metadata() throws {
        let request = try request()
        var wrongDpi = request.bytes
        wrongDpi[15] = 72
        var wrongUnits = request.bytes
        wrongUnits[13] = 0
        var wrongY = request.bytes
        wrongY[17] = 72
        var progressive = request.bytes
        progressive[21] = 0xC2
        var gray = request.bytes
        gray[29] = 1
        var cmyk = request.bytes
        cmyk[29] = 4
        let exif = Array(request.bytes.prefix(2)) + [0xFF, 0xE1, 0, 8, 69, 120, 105, 102, 0, 0]
            + request.bytes.dropFirst(2)
        let xmpBody = Array("http://ns.adobe.com/xap/1.0/\0private".utf8)
        let xmp = Array(request.bytes.prefix(2)) + [0xFF, 0xE1, 0, UInt8(xmpBody.count + 2)]
            + xmpBody + request.bytes.dropFirst(2)
        let tooLarge = TestJpeg.make(width: 200, height: 230, size: 60 * 1024)
        let wrongDims = TestJpeg.make(width: 400, height: 230, size: 34 * 1024)
        for bytes in [wrongDpi, wrongUnits, wrongY, progressive, gray, cmyk, exif, xmp, tooLarge, wrongDims] {
            #expect(!ExportOperation.verifies(bytes, expected: bytes, spec: request.spec, kind: .photo))
        }
    }

    @Test("explicit slot density overrides the default 200 dpi")
    func slotDensity() throws {
        let encoded = try JSONEncoder().encode(request().spec)
        var object = try #require(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        object["dpi"] = 300
        let spec = try JSONDecoder().decode(DocSpec.self, from: JSONSerialization.data(withJSONObject: object))
        var bytes = TestJpeg.make(width: 200, height: 230, size: 34 * 1024)
        #expect(!ExportOperation.verifies(bytes, expected: bytes, spec: spec, kind: .photo))
        bytes[14] = 1
        bytes[15] = 44
        bytes[16] = 1
        bytes[17] = 44
        #expect(ExportOperation.verifies(bytes, expected: bytes, spec: spec, kind: .photo))
    }

    @Test("an ink slot is checked against its own rules")
    func inkSlot() throws {
        // IBPS signature: 140x60 preferred, 10-20 KB. A signature-sized file passes; a photo-sized one does not.
        let exam = try #require(try SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        let signature = try #require(exam.documents.first { $0.type == .signature })
        let ok = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
        #expect(ExportOperation.verifies(ok, expected: ok, spec: signature, kind: .signature))
        let photoSized = TestJpeg.make(width: 200, height: 230, size: 16 * 1024)
        #expect(!ExportOperation.verifies(photoSized, expected: photoSized, spec: signature, kind: .signature))
    }
}
