import Foundation
import Imaging
@testable import PhotoFlow
import ScanModel
import Testing
import TestSupport

struct ExportCases: Decodable {
    let exportCases: [Case]

    enum CodingKeys: String, CodingKey { case exportCases = "export_cases" }

    struct Case: Decodable {
        let id: String
        let operation: String
        let expect: Expect
    }

    struct Expect: Decodable {
        let state: String
        let delete: Bool
        let record: Bool
    }

    static func load() throws -> [Case] {
        let data = try SpecFiles.data("fixtures/cases.json")
        return try JSONDecoder().decode(Self.self, from: data).exportCases
    }
}

actor ExportTransport: PhotoExportTransport {
    struct Failure: Error {}

    let operation: String
    let deletionWorks: Bool
    private(set) var bytes: [UInt8] = []
    private(set) var deleted = false
    private(set) var published = false

    init(_ operation: String, deletionWorks: Bool = true) {
        self.operation = operation
        self.deletionWorks = deletionWorks
    }

    func write(_ bytes: [UInt8]) throws {
        self.bytes = bytes
        if operation == "write_failed" { throw Failure() }
    }

    func read() throws -> [UInt8] {
        switch operation {
        case "read_failed": throw Failure()
        case "corrupt": return [0xFF, 0xD8, 0xFF]
        case "wrong_dpi":
            var changed = bytes
            changed[15] = 72
            return changed
        case "exif": return Array(bytes.prefix(2)) + [0xFF, 0xE1, 0, 8, 69, 120, 105, 102, 0, 0] + bytes.dropFirst(2)
        default: return bytes
        }
    }

    func publish() throws {
        if operation == "publish_failed" { throw Failure() }
        published = true
    }

    func remove() -> Bool {
        deleted = deletionWorks
        return deleted
    }
}

@Suite("Photo export conformance")
struct PhotoExportTests {
    private func request(_ bytes: [UInt8]? = nil) throws -> PhotoExportRequest {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        let spec = try #require(exam.documents.first { $0.type == .photo })
        return PhotoExportRequest(
            bytes: bytes ?? FakePhotoTools.jpeg(width: 200, height: 230, size: 34 * 1024),
            filename: "photo_IBPS-PO_200x230_34kb.jpg", spec: spec
        )
    }

    @Test("every shared export case exercises transport, verification, cleanup and publication")
    func conformance() async throws {
        let cases = try ExportCases.load()
        #expect(cases.count == 8)
        for test in cases {
            let transport = ExportTransport(test.operation)
            let result = await PhotoExportOperation.run(
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
        let transport = ExportTransport("corrupt", deletionWorks: false)
        let result = await PhotoExportOperation.run(try request(), transport: transport)
        #expect(result.state == .verifyFailed && !result.removed)
    }

    @Test("exact byte equality is required even when both JPEGs match")
    func equality() throws {
        let request = try request()
        var different = request.bytes
        different[different.count - 3] = 0x56
        #expect(!PhotoExportOperation.verifies(different, expected: request.bytes, spec: request.spec))
        #expect(PhotoExportOperation.verifies(request.bytes, expected: request.bytes, spec: request.spec))
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
        let tooLarge = FakePhotoTools.jpeg(width: 200, height: 230, size: 60 * 1024)
        let wrongDims = FakePhotoTools.jpeg(width: 400, height: 230, size: 34 * 1024)
        for bytes in [wrongDpi, wrongUnits, wrongY, progressive, gray, cmyk, exif, xmp, tooLarge, wrongDims] {
            #expect(!PhotoExportOperation.verifies(bytes, expected: bytes, spec: request.spec))
        }
    }

    @Test("explicit slot density overrides the default 200 dpi")
    func slotDensity() throws {
        let encoded = try JSONEncoder().encode(request().spec)
        var object = try #require(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        object["dpi"] = 300
        let spec = try JSONDecoder().decode(DocSpec.self, from: JSONSerialization.data(withJSONObject: object))
        var bytes = FakePhotoTools.jpeg(width: 200, height: 230, size: 34 * 1024)
        #expect(!PhotoExportOperation.verifies(bytes, expected: bytes, spec: spec))
        bytes[14] = 1
        bytes[15] = 44
        bytes[16] = 1
        bytes[17] = 44
        #expect(PhotoExportOperation.verifies(bytes, expected: bytes, spec: spec))
    }
}
