import CoreGraphics
import Foundation
@testable import Kit
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

/// My Kit previews (ALGORITHMS 13.3 previews): row thumbnails and a History file's preview before sharing it.
@MainActor
@Suite("My Kit previews")
struct KitPreviewsTests {
    private let kit = FakeKitStore()
    private let images = FakeKitImages()
    private let fitted = TestJpeg.make(width: 200, height: 230, size: 35 * 1024)
    private let pdf = TestPdf.make(pages: 3, size: 120 * 1024)

    private func jpegExport() throws -> ExportRequest {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        let photo = try #require(exam.documents.first { $0.type == .photo })
        return ExportRequest(bytes: fitted, filename: "photo_IBPS-PO_200x230_35kb.jpg", spec: photo, kind: .photo)
    }

    private func pdfExport() throws -> ExportRequest {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "jee_main" })
        let slot = try #require(exam.documents.first { $0.type == .class10Certificate })
        return ExportRequest(bytes: pdf, filename: "class10_certificate_JEE-Main_120kb.pdf", spec: slot,
                             kind: .pdfDocument, pdf: PdfExport(pages: 3, written: true))
    }

    private func entries() async throws -> [HistoryEntry] { try await kit.history() }

    @Test("a Prepared row shows its draft; a missing draft shows nothing")
    func prepared() async throws {
        let bundle = try SpecPresets.bundle()
        let exam = try #require(bundle.exams.first { $0.id == "ibps_po" })
        let photo = try #require(exam.documents.first { $0.type == .photo })
        let drafts = FakeDraftStore()
        let m = KitViewModel(drafts: drafts, images: images) { bundle }
        await m.refresh()
        let route = FlowRoute(examId: "ibps_po", docType: .photo)
        #expect(await m.thumbnail(route) == nil)
        try await drafts.retain(fitted, examID: "ibps_po", spec: photo, kind: .photo)
        await m.refresh()
        #expect(await m.thumbnail(route) != nil)
        #expect(images.caps.last == KitOriginalsViewModel.thumbnailPixels)
        #expect(await m.thumbnail(FlowRoute(custom: try #require(CustomSize.parse("photo;-;0-50;200")))) == nil)
    }

    @Test("history thumbnails are the image or a PDF's first page, and stop once the copy is gone")
    func history() async throws {
        _ = await kit.recordExport(try jpegExport(), examId: "ibps_po", examName: "IBPS PO / MT")
        _ = await kit.recordExport(try pdfExport(), examId: "jee_main", examName: "JEE Main")
        let m = KitHistoryViewModel(kit: kit, images: images)
        await m.refresh()
        let all = try await entries()
        let jpeg = try #require(all.first { !$0.record.isPdf }).id
        let pdfId = try #require(all.first { $0.record.isPdf }).id
        #expect(await m.thumbnail(jpeg) != nil)
        let page = try #require(await m.thumbnail(pdfId))
        #expect(page.height > page.width)
        await kit.advance(by: 31 * 24 * 3_600_000)
        await m.refresh()
        #expect(await m.thumbnail(jpeg) == nil, "no thumbnail once the copy is no longer kept")
    }

    @Test("an image preview shows the file and shares its exact bytes")
    func imagePreview() async throws {
        _ = await kit.recordExport(try jpegExport(), examId: "ibps_po", examName: "IBPS PO / MT")
        let id = try #require(try await entries().first).id
        let staged = StagedFiles()
        let m = KitHistoryPreviewViewModel(id: id, kit: kit, images: images) { bytes, name in
            staged.add(name, bytes)
            return URL(fileURLWithPath: "/tmp/\(name)")
        }
        await m.load()
        guard case let .ready(record, image, pages) = m.state else { Issue.record("not ready"); return }
        #expect(image != nil && pages == 1 && record.fileName == "photo_IBPS-PO_200x230_35kb.jpg")
        await m.shareCopy()
        #expect(staged.all.first?.0 == "photo_IBPS-PO_200x230_35kb.jpg" && staged.all.first?.1 == fitted)
        #expect(m.share.file != nil)
        m.shareHandled()
        #expect(m.share == .none)
    }

    @Test("a PDF preview renders its pages on demand")
    func pdfPreview() async throws {
        _ = await kit.recordExport(try pdfExport(), examId: "jee_main", examName: "JEE Main")
        let m = KitHistoryPreviewViewModel(id: try #require(try await entries().first).id, kit: kit, images: images)
        await m.load()
        guard case let .ready(_, image, pages) = m.state else { Issue.record("not ready"); return }
        #expect(image == nil && pages == 3)
        let page = try #require(await m.page(1, width: 600))
        #expect(page.width == 600)
        #expect(await m.page(3, width: 600) == nil, "past the last page")
    }

    @Test("a missing or expired copy cannot be previewed")
    func missing() async throws {
        let bad = KitHistoryPreviewViewModel(id: "not-an-id", kit: kit, images: images)
        await bad.load()
        #expect(bad.state == .failed)
        _ = await kit.recordExport(try jpegExport(), examId: "ibps_po", examName: "IBPS PO / MT")
        let id = try #require(try await entries().first).id
        await kit.advance(by: 31 * 24 * 3_600_000)
        let expired = KitHistoryPreviewViewModel(id: id, kit: kit, images: images)
        await expired.load()
        #expect(expired.state == .failed)
    }
}

/// Collects what the preview staged for the share sheet.
private final class StagedFiles: @unchecked Sendable {
    private let lock = NSLock()
    private var files: [(String, [UInt8])] = []

    func add(_ name: String, _ bytes: [UInt8]) { lock.withLock { files.append((name, bytes)) } }

    var all: [(String, [UInt8])] { lock.withLock { files } }
}
