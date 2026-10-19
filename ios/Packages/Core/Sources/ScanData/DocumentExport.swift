import Foundation
import Inspect
import Match
import ScanModel

/// What the written-file check knows about a PDF (ALGORITHMS 6.5).
public struct PdfExport: Sendable, Equatable {
    public let pages: Int
    /// The app wrote it (an image PDF of `pages` pages); `false` for a PDF kept unchanged (§6.1).
    public let written: Bool

    public init(pages: Int, written: Bool) {
        self.pages = pages
        self.written = written
    }
}

/// One fitted file to save for an exam slot. `kind` is the slot's match kind, used by the written-file check (§9.5).
public struct ExportRequest: Sendable {
    public let bytes: [UInt8]
    public let filename: String
    public let spec: DocSpec
    public let kind: DocKind
    /// Set for a PDF (ALGORITHMS 6.5); nil for a JPEG.
    public let pdf: PdfExport?

    public init(bytes: [UInt8], filename: String, spec: DocSpec, kind: DocKind, pdf: PdfExport? = nil) {
        self.bytes = bytes
        self.filename = filename
        self.spec = spec
        self.kind = kind
        self.pdf = pdf
    }
}

/// Saves a request somewhere the user chose and reports how it went. Shared by every flow that saves a fitted file.
@MainActor
public protocol DocumentExporting: AnyObject {
    func save(_ request: ExportRequest) async -> ExportState
    /// Saves several files (Save all, ALGORITHMS 1.6.2); cancelling saves nothing and returns `.idle` for each.
    func saveAll(_ requests: [ExportRequest]) async -> SaveAllOutcome
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
            guard verifies(written, for: request) else {
                return ExportOutcome(state: .verifyFailed, removed: await transport.remove())
            }
            try await transport.publish()
            return ExportOutcome(state: .saved, removed: false)
        } catch {
            return ExportOutcome(state: .saveFailed, removed: await transport.remove())
        }
    }

    /// The check for the request's kind of file: a JPEG (§1.6) or a PDF (§6.5).
    public static func verifies(_ written: [UInt8], for request: ExportRequest) -> Bool {
        if let pdf = request.pdf { return verifiesPdf(written, expected: request.bytes, spec: request.spec, pdf: pdf) }
        return verifies(written, expected: request.bytes, spec: request.spec, kind: request.kind)
    }

    /// ALGORITHMS 6.5: identical bytes, a PDF without a password, the page count and image-only pages for a PDF the
    /// app wrote, and the slot verdict (kind PDF document, size in the window) EXACT or ACCEPTED.
    public static func verifiesPdf(_ bytes: [UInt8], expected: [UInt8], spec: DocSpec, pdf: PdfExport) -> Bool {
        let file = Inspector.inspect(bytes)
        guard bytes == expected, file.format == .pdf, !file.pdfEncrypted, pdf.pages >= 1 else { return false }
        if pdf.written, file.pdfPages != pdf.pages || !file.pdfImageOnly { return false }
        let verdict = MatchEngine.evaluate(spec, FileFacts(file, docKind: .pdfDocument)).verdict
        return verdict == .exact || verdict == .accepted
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
