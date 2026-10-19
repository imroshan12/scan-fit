import Foundation
import Imaging
@testable import PDF
import ScanModel
import TestSupport
import Testing

/// Engine behaviour beyond the conformance cases: checks, progress, cancellation, turns, crop and colour.
@Suite("PDF engine")
struct PdfEngineTests {
    private let engine = PdfJobConformanceTests.engine
    private let limit = SizeKB(max: 200)

    private func picture(_ w: Int, _ h: Int) -> [UInt8] {
        ImageIOJpegEncoder().encode(Raster.make(w, h) { x, _ in x < w / 2 ? 0xC03030 : 0xF0F0F0 }, quality: 90)
    }

    private func made(_ job: PdfJob) async throws -> PdfMade {
        guard case let .made(pdf) = try await engine.make(job) else { throw Failure() }
        return pdf
    }

    private func error(_ job: PdfJob) async throws -> PdfError? {
        if case let .failed(error) = try await engine.make(job) { return error }
        return nil
    }

    private struct Failure: Error {}

    private func text(_ bytes: [UInt8]) -> String { String(decoding: bytes, as: UTF8.self) }

    @Test func anIdCardNeedsExactlyTwoPictures() async throws {
        #expect(try await error(PdfJob(tool: .idCard, sources: [.image(bytes: picture(40, 30))], sizeKb: limit)) == .noPages)
        let pdf = PdfSource.document([UInt8](try SpecFiles.data("fixtures/pdfs/text_letter_1p.pdf")))
        let mixed = PdfJob(tool: .idCard, sources: [.image(bytes: picture(40, 30)), pdf], sizeKb: limit)
        #expect(try await error(mixed) == .noPages)
    }

    @Test func moreThanTwentyPagesAreRefusedBeforeAnyWork() async throws {
        let job = PdfJob(tool: .images, sources: Array(repeating: .image(bytes: []), count: 21), sizeKb: limit)
        #expect(try await error(job) == .tooManyPages)
    }

    @Test func undecodablePicturesAndUnreadablePdfsAreUnreadable() async throws {
        #expect(try await error(PdfJob(tool: .images, sources: [.image(bytes: [1, 2, 3])], sizeKb: limit)) == .unreadable)
        let damaged = PdfSource.document(Array("%PDF-1.4\nnot really".utf8))
        #expect(try await error(PdfJob(tool: .compress, sources: [damaged], sizeKb: limit)) == .unreadable)
    }

    @Test func aSlotWithoutMaximumIsUnknown() async throws {
        let job = PdfJob(tool: .exam, sources: [.image(bytes: picture(40, 30))], sizeKb: SizeKB(min: 50))
        #expect(try await error(job) == .unknownLimit)
    }

    @Test func progressCoversPreparingAndEveryPageAndPagesTurnWithThePicture() async throws {
        let events = Events()
        let job = PdfJob(tool: .images, sources: [.image(bytes: picture(60, 80)), .image(bytes: picture(80, 60))],
                         sizeKb: limit)
        guard case let .made(pdf) = try await engine.make(job, progress: { events.add($0) }) else {
            Issue.record("no PDF"); return
        }
        #expect(pdf.pages == 2)
        #expect(events.all.contains(.preparing(done: 1, total: 2)))
        #expect(events.all.contains(.fitting(page: 1, total: 2)))
        #expect(text(pdf.bytes).contains("/MediaBox [0 0 595 842]"))
        #expect(text(pdf.bytes).contains("/MediaBox [0 0 842 595]"))
    }

    @Test func cancellingStopsTheJob() async throws {
        let job = PdfJob(tool: .images, sources: Array(repeating: .image(bytes: picture(60, 80)), count: 3), sizeKb: limit)
        let task = Task { try await engine.make(job) }
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
    }

    @Test func turnsAndCropApplyBeforeLayout() async throws {
        let source = PdfSource.image(
            bytes: picture(200, 100), quarterTurns: 1, crop: CropRect(x: 0, y: 0, w: 100, h: 100),
            cropBase: PixelSize(width: 100, height: 200)
        )
        let pdf = try await made(PdfJob(tool: .images, sources: [source], sizeKb: limit,
                                        options: PdfOptions(grayscale: false, cleanup: false)))
        #expect(text(pdf.bytes).contains("/Width 100 /Height 100"))
        #expect(text(pdf.bytes).contains("/MediaBox [0 0 595 842]"))
    }

    @Test func colourIsKeptOnlyWithGrayscaleOff() async throws {
        func redness(_ r: Raster) -> Int {
            var most = 0
            for y in 0..<r.height { for x in 0..<r.width { most = max(most, r.r(x, y) - r.g(x, y)) } }
            return most
        }
        // Black "text", a red stamp and paper: cleanup maps the darkest ink to black, so the stamp is not the darkest.
        let page = Raster.make(120, 160) { x, _ in x < 30 ? 0x101010 : (x < 60 ? 0xC03030 : 0xF0F0F0) }
        let sources = [PdfSource.image(bytes: ImageIOJpegEncoder().encode(page, quality: 90))]
        let colour = try await made(PdfJob(tool: .images, sources: sources, sizeKb: limit,
                                           options: PdfOptions(grayscale: false, cleanup: false)))
        let grey = try await made(PdfJob(tool: .images, sources: sources, sizeKb: limit,
                                         options: PdfOptions(grayscale: true, cleanup: false)))
        let cleaned = try await made(PdfJob(tool: .images, sources: sources, sizeKb: limit,
                                            options: PdfOptions(grayscale: false, cleanup: true)))
        #expect(redness(try #require(colour.preview)) > 80)
        #expect(redness(try #require(grey.preview)) <= 2)
        #expect(redness(try #require(cleaned.preview)) > 40)
    }

    @Test func anUnchangedPdfStillHasAPreviewAndItsPageCount() async throws {
        let bytes = [UInt8](try SpecFiles.data("fixtures/pdfs/marksheet_scan_2p.pdf"))
        let pdf = try await made(PdfJob(tool: .compress, sources: [.document(bytes)], sizeKb: SizeKB(max: 2000)))
        #expect(pdf.strategy == .unchanged)
        #expect(pdf.pages == 2)
        #expect(try #require(pdf.preview).height <= Int(PdfEngine.previewLongSide))
    }

    @Test func aRotatedPageRendersTurned() throws {
        let landscape = PdfWriter.write([WriterPage(size: PdfSize(width: 300, height: 200), images: [])])
        let rotated = Array(String(decoding: landscape, as: UTF8.self)
            .replacingOccurrences(of: "/MediaBox [0 0 300 200]", with: "/MediaBox [0 0 300 200] /Rotate 90").utf8)
        let pages = try #require(CGPdfOpener().open(rotated))
        #expect(pages.pageSize(0) == PdfSize(width: 200, height: 300))
        #expect(pages.render(0, width: 20, height: 30)?.width == 20)
    }
}

/// Collects progress events from the engine's callback.
private final class Events: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [PdfProgress] = []

    func add(_ event: PdfProgress) { lock.withLock { events.append(event) } }

    var all: [PdfProgress] { lock.withLock { events } }
}
