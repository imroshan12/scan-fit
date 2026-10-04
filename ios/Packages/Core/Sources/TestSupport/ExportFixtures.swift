import Foundation
import ScanData

/// `export_cases` from spec/fixtures/cases.json (ALGORITHMS 1.6).
public struct ExportCases: Decodable {
    public let exportCases: [Case]

    enum CodingKeys: String, CodingKey { case exportCases = "export_cases" }

    public struct Case: Decodable, Sendable {
        public let id: String
        public let operation: String
        public let expect: Expect
    }

    public struct Expect: Decodable, Sendable {
        public let state: String
        public let delete: Bool
        public let record: Bool
    }

    public static func load() throws -> [Case] {
        let data = try SpecFiles.data("fixtures/cases.json")
        return try JSONDecoder().decode(Self.self, from: data).exportCases
    }
}

/// A transport that fails as an export case's `operation` says (write, read, corrupt, publish...).
public actor ScriptedExportTransport: DocumentExportTransport {
    public struct Failure: Error {}

    public let operation: String
    public let deletionWorks: Bool
    public private(set) var bytes: [UInt8] = []
    public private(set) var deleted = false
    public private(set) var published = false

    public init(_ operation: String, deletionWorks: Bool = true) {
        self.operation = operation
        self.deletionWorks = deletionWorks
    }

    public func write(_ bytes: [UInt8]) throws {
        self.bytes = bytes
        if operation == "write_failed" { throw Failure() }
    }

    public func read() throws -> [UInt8] {
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

    public func publish() throws {
        if operation == "publish_failed" { throw Failure() }
        published = true
    }

    public func remove() -> Bool {
        deleted = deletionWorks
        return deleted
    }
}
