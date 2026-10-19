import Foundation
import Imaging
import Inspect
import PDF
@testable import PDFTools
import ScanData
import ScanModel
import Testing
import TestSupport

/// Test double for `PdfMakerTools`: PDFs recognised by their bytes, a configurable engine, and an optional gate to
/// observe the making state.
final class FakePdfMakerTools: PdfMakerTools, @unchecked Sendable {
    private let lock = NSLock()
    private var storedJobs: [PdfJob] = []
    private var storedOutcome: PdfOutcome?
    private var storedGate: AsyncStream<Void>?

    var jobs: [PdfJob] { lock.withLock { storedJobs } }

    var outcome: PdfOutcome? {
        get { lock.withLock { storedOutcome } }
        set { lock.withLock { storedOutcome = newValue } }
    }

    var gate: AsyncStream<Void>? {
        get { lock.withLock { storedGate } }
        set { lock.withLock { storedGate = newValue } }
    }

    func describe(_ bytes: [UInt8]) -> PdfFileInfo? {
        let file = Inspector.inspect(bytes)
        switch file.format {
        case .pdf: return PdfFileInfo(document: true, pages: file.pdfPages ?? 0, thumbnail: nil,
                                      encrypted: file.pdfEncrypted)
        case .jpeg: return PdfFileInfo(document: false, pages: 1, thumbnail: .white(8, 12))
        default: return nil
        }
    }

    func decode(_: [UInt8], maxLongSide _: Int) -> Raster? { .white(400, 600) }

    func make(_ job: PdfJob, progress: @escaping @Sendable (PdfProgress) -> Void) async throws -> PdfOutcome {
        lock.withLock { storedJobs.append(job) }
        progress(.fitting(page: 0, total: 1))
        if let gate { for await _ in gate { break } }
        try Task.checkCancellation()
        if let outcome { return outcome }
        let pages = job.tool == .idCard ? 1 : job.sources.count
        return .made(PdfMade(bytes: TestPdf.make(pages: pages, size: 90 * 1024), pages: pages, dpi: 150, quality: 75,
                             strategy: .lowerDpi, preview: .white(4, 6)))
    }
}

/// Saves through the real export operation over a scripted transport, and remembers the requests.
@MainActor
final class PdfRecordingExporter: DocumentExporting {
    private(set) var requests: [ExportRequest] = []

    func save(_ request: ExportRequest) async -> ExportState {
        requests.append(request)
        return await ExportOperation.run(request, transport: ScriptedExportTransport("success")).state
    }
}

@MainActor
@Suite("PdfMakerViewModel")
struct PdfMakerViewModelTests {
    private let tools = FakePdfMakerTools()
    private let exporter = PdfRecordingExporter()
    private let kit = FakeKitStore()
    private let photo = TestJpeg.make(width: 1200, height: 1600, size: 300 * 1024)
    private let photo2 = TestJpeg.make(width: 1600, height: 1200, size: 300 * 1024)

    private func preferences() -> UserPreferences {
        let name = "scanfit.pdf.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        return UserPreferences(defaults: defaults)
    }

    private func model(_ route: PdfRoute, prefs: UserPreferences? = nil) throws -> PdfMakerViewModel {
        let bundle = try SpecPresets.bundle()
        return PdfMakerViewModel(route: route, tools: tools, preferences: prefs ?? preferences(), exporter: exporter,
                                 kit: kit) { bundle }
    }

    private func exam(_ prefs: UserPreferences? = nil, docType: DocType = .class10Certificate) async throws
        -> PdfMakerViewModel {
        let m = try model(PdfRoute(examId: "jee_main", docType: docType), prefs: prefs)
        for _ in 0..<50 where m.target == nil && !m.notFound { await Task.yield() }
        return m
    }

    private func waitForResult(_ m: PdfMakerViewModel) async {
        for _ in 0..<500 where m.result == nil { try? await Task.sleep(for: .milliseconds(2)) }
    }

    @Test func aToolStartsEmptyWithA200KbLimitAndWaitsForFiles() async throws {
        let m = try model(PdfRoute(tool: "images"))
        #expect(m.target?.spec.sizeKb.max == 200 && m.target?.examId == "pdf" && m.target?.spec.type == .document)
        #expect(m.missing == .files && !m.canMake)
        await m.add([photo, photo2])
        #expect(m.items.count == 2 && m.canMake)
    }

    @Test func theMaxKbMustBeAWholeNumberFrom1To5120() async throws {
        let m = try model(PdfRoute(tool: "images"))
        await m.add([photo])
        m.setMaxKb("0")
        #expect(m.missing == .maxKb)
        m.setMaxKb("9999")
        #expect(m.missing == .maxKb)
        m.setMaxKb("3 00 KB")
        #expect(m.maxKb == 300 && m.target?.spec.sizeKb.max == 300 && m.canMake)
    }

    @Test func anExamSlotUsesItsOwnWindowAndAnUnknownSlotSaysSo() async throws {
        let jee = try await exam()
        #expect(jee.target?.examName == "JEE Main" && jee.target?.spec.sizeKb.min == 50 && jee.target?.exam == true)
        #expect(jee.tool == .exam)
        let photoSlot = try await exam(docType: .photo)
        #expect(photoSlot.notFound, "a slot that takes no PDF")
    }

    @Test func unreadableLockedAndTooManyPagesAreNotAdded() async throws {
        let m = try await exam()
        await m.add([nil])
        #expect(m.addFailure == .unreadable)
        await m.add([TestPdf.make(pages: 1, size: 50 * 1024, encrypted: true)])
        #expect(m.addFailure == .encrypted)
        await m.add([TestPdf.make(pages: 19, size: 600 * 1024), TestPdf.make(pages: 2, size: 600 * 1024), photo])
        #expect(m.addFailure == .tooManyPages)
        #expect(m.items.map(\.pages) == [19])
        m.dismissAddFailure()
        #expect(m.addFailure == nil)
    }

    @Test func mergeNeedsTwoPdfsAndCompressKeepsOne() async throws {
        let merge = try model(PdfRoute(tool: "merge"))
        await merge.add([TestPdf.make(pages: 2, size: 600 * 1024)])
        #expect(merge.missing == .twoPdfs)
        await merge.add([TestPdf.make(pages: 3, size: 400 * 1024)])
        #expect(merge.missing == nil && merge.pages == 5)
        let compress = try model(PdfRoute(tool: "compress"))
        await compress.add([TestPdf.make(pages: 2, size: 600 * 1024)])
        await compress.add([TestPdf.make(pages: 3, size: 400 * 1024)])
        #expect(compress.items.map(\.pages) == [3])
    }

    @Test func anIdCardTakesAFrontThenABackAndReplacesASide() async throws {
        let m = try model(PdfRoute(tool: "id_card"))
        #expect(m.target?.spec.type == .idCard)
        await m.add([photo], side: 0)
        #expect(m.missing == .bothSides)
        await m.add([photo2], side: 1)
        let back = m.items[1].id
        await m.add([photo], side: 0)
        #expect(m.items.count == 2 && m.items[1].id == back && m.pages == 1 && m.canMake)
    }

    @Test func reorderTurnAndRemoveEditTheList() async throws {
        let m = try model(PdfRoute(tool: "images"))
        await m.add([photo, photo2])
        let first = m.items[0].id, second = m.items[1].id
        m.move(second, by: -1)
        #expect(m.items.map(\.id) == [second, first])
        m.move(second, by: -1)
        #expect(m.items.map(\.id) == [second, first], "cannot move past the top")
        m.turn(first)
        #expect(m.items.first { $0.id == first }?.quarterTurns == 1)
        m.remove(second)
        #expect(m.items.map(\.id) == [first])
    }

    @Test func cropIsKeptInThePicturesPixelsAndATurnStartsItAgain() async throws {
        let m = try model(PdfRoute(tool: "images"))
        await m.add([photo])
        let id = m.items[0].id
        await m.startCrop(id)
        #expect(m.crop?.rect == CropRect(x: 0, y: 0, w: 400, h: 600))
        m.resizeCrop(.bottomRight, dx: -100, dy: -200)
        m.moveCrop(dx: 50, dy: 0)
        await m.finishCrop()
        #expect(m.crop == nil)
        #expect(m.items[0].crop == CropRect(x: 50, y: 0, w: 300, h: 400))
        #expect(m.items[0].cropBase == PixelSize(width: 400, height: 600))
        await m.startCrop(id)
        m.cancelCrop()
        #expect(m.items[0].crop == CropRect(x: 50, y: 0, w: 300, h: 400), "cancel keeps the crop")
        m.turn(id)
        #expect(m.items[0].crop == nil)
    }

    @Test func makingShowsProgressThenAResultThatAnyEditDrops() async throws {
        let m = try model(PdfRoute(tool: "images"))
        await m.add([photo])
        m.turn(m.items[0].id)
        m.setGrayscale(false)
        let (gate, open) = AsyncStream<Void>.makeStream()
        tools.gate = gate
        m.make()
        #expect(m.making != nil && !m.canMake)
        m.remove(m.items[0].id)
        #expect(m.items.count == 1, "no edits while making")
        open.yield()
        await waitForResult(m)
        #expect(m.making == nil)
        guard case let .ready(_, _, pages, _, _, _, meets) = m.result else { Issue.record("no result"); return }
        #expect(pages == 1 && meets)
        let job = try #require(tools.jobs.first)
        #expect(!job.options.grayscale)
        guard case let .image(_, turns, _, _) = job.sources[0] else { Issue.record("not a picture"); return }
        #expect(turns == 1)
        m.setCleanup(false)
        #expect(m.result == nil, "an edit drops the result")
    }

    @Test func cancellingKeepsTheFilesAndAFailureSaysWhy() async throws {
        let m = try model(PdfRoute(tool: "compress"))
        await m.add([TestPdf.make(pages: 2, size: 600 * 1024)])
        let (gate, _) = AsyncStream<Void>.makeStream()
        tools.gate = gate
        m.make()
        m.cancelMaking()
        #expect(m.making == nil && m.result == nil && m.items.count == 1)
        tools.gate = nil
        tools.outcome = .failed(.cannotReach)
        m.make()
        await waitForResult(m)
        #expect(m.result == .failed(.cannotReach) && !m.canSave)
    }

    @Test func anExamPdfIsSavedVerifiedMarkedSavedAndListed() async throws {
        let prefs = preferences()
        let m = try await exam(prefs)
        await m.add([photo])
        m.make()
        await waitForResult(m)
        #expect(m.canSave)
        await m.save()
        #expect(m.exportState == .saved)
        #expect(exporter.requests.last?.filename == "class10_certificate_JEE-Main_90kb.pdf")
        #expect(exporter.requests.last?.pdf == PdfExport(pages: 1, written: true))
        #expect(prefs.isSaved("jee_main", .class10Certificate))
        #expect(await kit.exports.map(\.pages) == [1])
    }

    @Test func aToolPdfIsSavedWithoutAChecklistAndCanBeShared() async throws {
        let prefs = preferences()
        let m = try model(PdfRoute(tool: "id_card"), prefs: prefs)
        await m.add([photo], side: 0)
        await m.add([photo2], side: 1)
        m.make()
        await waitForResult(m)
        #expect(m.request()?.filename == "id_card_PDF_90kb.pdf")
        await m.save()
        #expect(m.exportState == .saved)
        #expect(!prefs.isSaved("pdf", .idCard), "tools have no checklist")
    }
}
