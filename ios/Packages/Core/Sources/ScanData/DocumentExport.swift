import Foundation
import Inspect
import Match
import ScanModel

/// One fitted file to save for an exam slot. `kind` is the slot's match kind, used by the written-file check (§9.5).
public struct ExportRequest: Sendable {
    public let bytes: [UInt8]
    public let filename: String
    public let spec: DocSpec
    public let kind: DocKind

    public init(bytes: [UInt8], filename: String, spec: DocSpec, kind: DocKind) {
        self.bytes = bytes
        self.filename = filename
        self.spec = spec
        self.kind = kind
    }
}

/// Saves a request somewhere the user chose and reports how it went. Shared by every flow that saves a fitted file.
@MainActor
public protocol DocumentExporting: AnyObject {
    func save(_ request: ExportRequest) async -> ExportState
}

/// Where the bytes go: written, re-read, published, or removed after a failure.
public protocol DocumentExportTransport: Sendable {
    func write(_ bytes: [UInt8]) async throws
    func read() async throws -> [UInt8]
    func publish() async throws
    func remove() async -> Bool
}

public struct ExportOutcome: Sendable {
    public let state: ExportState
    public let removed: Bool
}

/// Write → re-read → verify → publish (ALGORITHMS 1.6, CLAUDE.md rule 3). A nil transport is a cancelled pick.
public enum ExportOperation {
    public static func run(_ request: ExportRequest, transport: (any DocumentExportTransport)?) async -> ExportOutcome {
        guard let transport else { return ExportOutcome(state: .idle, removed: false) }
        do {
            try await transport.write(request.bytes)
            let written = try await transport.read()
            guard verifies(written, expected: request.bytes, spec: request.spec, kind: request.kind) else {
                return ExportOutcome(state: .verifyFailed, removed: await transport.remove())
            }
            try await transport.publish()
            return ExportOutcome(state: .saved, removed: false)
        } catch {
            return ExportOutcome(state: .saveFailed, removed: await transport.remove())
        }
    }

    public static func verifies(_ bytes: [UInt8], expected: [UInt8], spec: DocSpec, kind: DocKind) -> Bool {
        let file = Inspector.inspect(bytes)
        let dpi = spec.dpi ?? 200
        let density = JfifDensity(units: 1, xDensity: dpi, yDensity: dpi)
        let walk = JpegWalk(bytes)
        guard bytes == expected, file.format == .jpeg, file.sof == "SOF0", !file.progressive,
              file.color == .rgb, file.components == 3, !file.hasExif, !file.hasGps, !file.hasXmp,
              file.jfif == density, walk.reachedScan, bytes.suffix(2) == [0xFF, 0xD9],
              !walk.segments.contains(where: { $0.marker == JpegSegment.app1 }) else { return false }
        let verdict = MatchEngine.evaluate(spec, FileFacts(file, docKind: kind)).verdict
        return verdict == .exact || verdict == .accepted
    }
}
