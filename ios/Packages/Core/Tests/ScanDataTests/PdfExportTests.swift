import Foundation
import Inspect
import Match
@testable import ScanData
import ScanModel
import Testing
import TestSupport

/// PDF exports (ALGORITHMS 6.5) and PDF entries in My Kit history (§13.3).
@Suite("PDF export")
struct PdfExportTests {
    private final class Clock: @unchecked Sendable {
        var now: Int64 = 1_800_000_000_000
    }

    private func slot() throws -> DocSpec {
        let jee = try #require(SpecPresets.bundle().exams.first { $0.id == "jee_main" })
        return try #require(jee.documents.first { $0.type == .class10Certificate })
    }

    private func root() -> URL {
        FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent(UUID().uuidString)
    }

    @Test func aPdfIsCheckedForItsSlotPagesAndPassword() throws {
        let slot = try slot()
        let made = TestPdf.make(pages: 2, size: 120 * 1024)
        let pdf = PdfExport(pages: 2, written: true)
        #expect(ExportOperation.verifiesPdf(made, expected: made, spec: slot, pdf: pdf))
        var altered = made
        altered[200] = UInt8(ascii: "x")
        #expect(!ExportOperation.verifiesPdf(altered, expected: made, spec: slot, pdf: pdf))
        #expect(!ExportOperation.verifiesPdf(made, expected: made, spec: slot, pdf: PdfExport(pages: 3, written: true)))
        let small = TestPdf.make(pages: 2, size: 20 * 1024)
        #expect(!ExportOperation.verifiesPdf(small, expected: small, spec: slot, pdf: pdf), "under the minimum")
        let locked = TestPdf.make(pages: 2, size: 120 * 1024, encrypted: true)
        #expect(!ExportOperation.verifiesPdf(locked, expected: locked, spec: slot, pdf: pdf), "password")
        let text = TestPdf.make(pages: 2, size: 120 * 1024, font: true)
        #expect(!ExportOperation.verifiesPdf(text, expected: text, spec: slot, pdf: pdf), "the app writes image pages")
        #expect(ExportOperation.verifiesPdf(text, expected: text, spec: slot, pdf: PdfExport(pages: 2, written: false)))
        let request = ExportRequest(bytes: made, filename: "a.pdf", spec: slot, kind: .pdfDocument, pdf: pdf)
        #expect(ExportOperation.verifies(made, for: request))
        #expect(!ExportOperation.verifies(made, expected: made, spec: slot, kind: .pdfDocument), "a PDF is not a JPEG")
    }

    @Test func aPdfExportKeepsAPdfCopyWithItsPageCount() async throws {
        let root = root()
        let clock = Clock()
        let pdf = TestPdf.make(pages: 3, size: 120 * 1024)
        let request = ExportRequest(
            bytes: pdf, filename: "class10_certificate_JEE-Main_120kb.pdf", spec: try slot(), kind: .pdfDocument,
            pdf: PdfExport(pages: 3, written: true)
        )
        let saved = await FileKitStore(root: root, clock: { clock.now })
            .recordExport(request, examId: "jee_main", examName: "JEE Main")
        #expect(saved)
        let reopened = FileKitStore(root: root, clock: { clock.now })
        let first = try await reopened.history().first
        let entry = try #require(first)
        #expect(entry.record.pages == 3 && entry.record.width == 0 && entry.shareable)
        let copy = root.appendingPathComponent("exports/\(entry.record.id).pdf")
        #expect(FileManager.default.fileExists(atPath: copy.path))
        let copied = await reopened.exportCopy(id: entry.record.id)
        #expect(copied == pdf)
        // A copy under the other extension is an orphan and is swept.
        let stray = root.appendingPathComponent("exports/\(entry.record.id).jpg")
        try Data([1, 2, 3]).write(to: stray)
        _ = try await reopened.history()
        #expect(!FileManager.default.fileExists(atPath: stray.path))
    }

    @Test func aLockedPdfIsNotKeptAndOldEntriesStillLoad() async throws {
        let root = root()
        let clock = Clock()
        let locked = TestPdf.make(pages: 1, size: 90 * 1024, encrypted: true)
        let request = ExportRequest(bytes: locked, filename: "a.pdf", spec: try slot(), kind: .pdfDocument,
                                    pdf: PdfExport(pages: 1, written: false))
        let kit = FileKitStore(root: root, clock: { clock.now })
        let saved = await kit.recordExport(request, examId: "jee_main", examName: "JEE Main")
        #expect(!saved)
        let empty = try await kit.history()
        #expect(empty.isEmpty)
        let old = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b", bad = "4f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"
        let fitted = TestJpeg.make(width: 200, height: 230, size: 35 * 1024)
        let common = "\"created_at_ms\":\(clock.now),\"exam_id\":\"ibps_po\",\"exam_name\":\"IBPS PO / MT\","
            + "\"doc_type\":\"photo\",\"kind\":\"photo\","
        let index = "{\"version\":1,\"originals\":[],\"exports\":["
            + "{\"id\":\"\(old)\",\(common)\"file_name\":\"a.jpg\",\"size\":\(fitted.count),\"width\":200,\"height\":230},"
            + "{\"id\":\"\(bad)\",\(common)\"file_name\":\"b.pdf\",\"size\":10,\"width\":200,\"height\":230,"
            + "\"format\":\"pdf\",\"pages\":1}]}"
        try Data(index.utf8).write(to: root.appendingPathComponent("index.json"))
        try Data(fitted).write(to: root.appendingPathComponent("exports/\(old).jpg"))
        let history = try await FileKitStore(root: root, clock: { clock.now }).history()
        #expect(history.map(\.record.id) == [old])
        #expect(history.first?.record.pages == nil)
    }
}
