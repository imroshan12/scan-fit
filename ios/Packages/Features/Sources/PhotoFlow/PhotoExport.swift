import Foundation
import Inspect
import Match
import ScanModel

public enum PhotoExportState: String, Sendable, Equatable {
    case idle, saving, saved
    case saveFailed = "save_failed"
    case verifyFailed = "verify_failed"
}

public struct PhotoExportRequest: Sendable {
    public let bytes: [UInt8]
    public let filename: String
    public let spec: DocSpec
}

@MainActor
public protocol PhotoExporting: AnyObject {
    func save(_ request: PhotoExportRequest) async -> PhotoExportState
}

protocol PhotoExportTransport: Sendable {
    func write(_ bytes: [UInt8]) async throws
    func read() async throws -> [UInt8]
    func publish() async throws
    func remove() async -> Bool
}

struct PhotoExportOutcome: Sendable {
    let state: PhotoExportState
    let removed: Bool
}

enum PhotoExportOperation {
    static func run(_ request: PhotoExportRequest, transport: (any PhotoExportTransport)?) async -> PhotoExportOutcome {
        guard let transport else { return PhotoExportOutcome(state: .idle, removed: false) }
        do {
            try await transport.write(request.bytes)
            let written = try await transport.read()
            guard verifies(written, expected: request.bytes, spec: request.spec) else {
                return PhotoExportOutcome(state: .verifyFailed, removed: await transport.remove())
            }
            try await transport.publish()
            return PhotoExportOutcome(state: .saved, removed: false)
        } catch {
            return PhotoExportOutcome(state: .saveFailed, removed: await transport.remove())
        }
    }

    static func verifies(_ bytes: [UInt8], expected: [UInt8], spec: DocSpec) -> Bool {
        let file = Inspector.inspect(bytes)
        let dpi = spec.dpi ?? 200
        let density = JfifDensity(units: 1, xDensity: dpi, yDensity: dpi)
        let walk = JpegWalk(bytes)
        guard bytes == expected, file.format == .jpeg, file.sof == "SOF0", !file.progressive,
              file.color == .rgb, file.components == 3, !file.hasExif, !file.hasGps, !file.hasXmp,
              file.jfif == density, walk.reachedScan, bytes.suffix(2) == [0xFF, 0xD9],
              !walk.segments.contains(where: { $0.marker == JpegSegment.app1 }) else { return false }
        let verdict = MatchEngine.evaluate(spec, FileFacts(file, docKind: .photo)).verdict
        return verdict == .exact || verdict == .accepted
    }
}
