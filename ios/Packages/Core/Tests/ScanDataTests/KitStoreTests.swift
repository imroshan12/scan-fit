import Foundation
import Imaging
import Inspect
import Match
@testable import ScanData
import ScanModel
import Testing
import TestSupport

/// My Kit (ALGORITHMS 13): the shared kit cases and the on-disk store.
@Suite("My Kit store")
struct KitStoreTests {
    private let picture = TestJpeg.make(width: 1600, height: 1200, size: 300 * 1024)
    private let fitted = TestJpeg.make(width: 200, height: 230, size: 35 * 1024)

    private final class Clock: @unchecked Sendable {
        var now: Int64 = 1_800_000_000_000
    }

    private func root() -> URL {
        FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent(UUID().uuidString)
    }

    private func store(_ root: URL, clock: Clock = Clock(), beforeIndex: @escaping @Sendable () throws -> Void = {})
        -> FileKitStore {
        FileKitStore(root: root, clock: { clock.now }, beforeIndex: beforeIndex)
    }

    private func request() throws -> ExportRequest {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        let photo = try #require(exam.documents.first { $0.type == .photo })
        return ExportRequest(bytes: fitted, filename: "photo_IBPS-PO_200x230_35kb.jpg", spec: photo, kind: .photo)
    }

    private func files(_ url: URL) -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: url.path)) ?? []
    }

    // MARK: Conformance

    @Test("every kit size case holds")
    func sizeCases() throws {
        let cases = try CasesFile.section("kit_size_cases")
        #expect(cases.count >= 5)
        for c in cases {
            let size = KitOriginalImage.storedSize(width: c.int("width") ?? 0, height: c.int("height") ?? 0)
            let expect = try #require(c.dict("expect"))
            #expect(size.width == expect.int("width") && size.height == expect.int("height"), "\(c.string("id") ?? "")")
        }
    }

    @Test("every kit history case holds")
    func historyCases() throws {
        let cases = try CasesFile.section("kit_history_cases")
        #expect(cases.count >= 5)
        for c in cases {
            let entries = ((c["entries"] as? [[String: Any]]) ?? []).map {
                ExportRecord(
                    id: $0.string("id") ?? "", createdAt: Int64($0.int("created_at_ms") ?? 0), examId: "ibps_po",
                    examName: "IBPS PO", docType: .photo, kind: .photo, fileName: "photo.jpg", size: 1, width: 1,
                    height: 1
                )
            }
            let now = Int64(truncating: (c["now_ms"] as? NSNumber) ?? 0)
            let pruned = ExportHistory.prune(entries, now: now, maxEntries: c.int("max_entries") ?? 0)
            let expect = try #require(c.dict("expect"))
            #expect(pruned.map(\.record.id) == expect.strings("kept"), "\(c.string("id") ?? "")")
            #expect(pruned.filter(\.shareable).map(\.record.id) == expect.strings("shareable"), "\(c.string("id") ?? "")")
        }
    }

    @Test("every kit id case holds, and new ids are valid")
    func idCases() throws {
        let cases = try CasesFile.section("kit_id_cases")
        #expect(cases.count >= 5)
        for c in cases {
            #expect(KitIds.isValid(c.string("id") ?? "") == c.bool("expect"), "\(c.string("id") ?? "")")
        }
        for _ in 0..<100 { #expect(KitIds.isValid(KitIds.newID())) }
    }

    // MARK: Originals

    @Test("a saved original survives a relaunch byte for byte")
    func relaunch() async throws {
        let root = root()
        guard case let .saved(id) = await store(root).saveOriginal(kind: .signature, jpeg: picture) else {
            Issue.record("not saved")
            return
        }
        let reopened = store(root)
        let listed = try await reopened.originals()
        #expect(listed == [KitOriginal(id: id, kind: .signature, createdAt: 1_800_000_000_000, width: 1600,
                                       height: 1200, size: picture.count)])
        #expect(await reopened.original(id: id) == picture)
    }

    @Test("a full kit writes nothing")
    func full() async throws {
        let root = root()
        let kit = store(root)
        for _ in 0..<KitLimits.maxOriginals {
            guard case .saved = await kit.saveOriginal(kind: .photo, jpeg: picture) else {
                Issue.record("not saved")
                return
            }
        }
        #expect(await kit.saveOriginal(kind: .photo, jpeg: picture) == .full)
        #expect(files(root.appendingPathComponent("originals")).count == KitLimits.maxOriginals)
    }

    @Test("pictures that are not clean originals are refused")
    func refused() async throws {
        let kit = store(root())
        let exif = Array(picture.prefix(2)) + [0xFF, 0xE1, 0, 8, 69, 120, 105, 102, 0, 0] + Array(picture.dropFirst(2))
        #expect(await kit.saveOriginal(kind: .photo, jpeg: exif) == .failed)
        #expect(await kit.saveOriginal(kind: .photo, jpeg: TestJpeg.make(width: 2401, height: 100, size: 10 * 1024))
            == .failed)
        #expect(await kit.saveOriginal(kind: .pdfDocument, jpeg: picture) == .failed)
        #expect(try await kit.originals().isEmpty)
    }

    @Test("death between the blob and the index leaves no orphan")
    func orphan() async throws {
        let root = root()
        struct Death: Error {}
        let dying = store(root) { throw Death() }
        #expect(await dying.saveOriginal(kind: .photo, jpeg: picture) == .failed)
        #expect(files(root.appendingPathComponent("originals")).count == 1)
        #expect(try await store(root).originals().isEmpty)
        #expect(files(root.appendingPathComponent("originals")).isEmpty)
    }

    @Test("a damaged original is removed when read")
    func damaged() async throws {
        let root = root()
        let kit = store(root)
        guard case let .saved(id) = await kit.saveOriginal(kind: .photo, jpeg: picture) else { return }
        try Data(TestJpeg.make(width: 1600, height: 1199, size: picture.count))
            .write(to: root.appendingPathComponent("originals/\(id).jpg"))
        #expect(await kit.original(id: id) == nil)
        #expect(try await kit.originals().isEmpty)
    }

    @Test("bad ids never reach a path")
    func badIDs() async throws {
        let root = root()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let kit = store(root)
        #expect(await kit.original(id: "../index") == nil)
        await kit.deleteOriginal(id: "../index")
        #expect(await kit.exportCopy(id: "../index") == nil)
    }

    @Test("a corrupt index counts as empty and is replaced")
    func corrupt() async throws {
        let root = root()
        _ = await store(root).saveOriginal(kind: .photo, jpeg: picture)
        try Data("{not json".utf8).write(to: root.appendingPathComponent("index.json"))
        #expect(try await store(root).originals().isEmpty)
        #expect(files(root.appendingPathComponent("originals")).isEmpty)
        guard case .saved = await store(root).saveOriginal(kind: .photo, jpeg: picture) else {
            Issue.record("not saved")
            return
        }
    }

    @Test("a symlinked root is not used")
    func symlink() async throws {
        let real = root(), link = root()
        try FileManager.default.createDirectory(at: real, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: real)
        let kit = store(link)
        #expect(await kit.saveOriginal(kind: .photo, jpeg: picture) == .failed)
        #expect(await kit.recordExport(try request(), examId: "ibps_po", examName: "IBPS PO") == false)
    }

    // MARK: History

    @Test("exports are recorded with a shareable copy")
    func recorded() async throws {
        let root = root()
        let kit = store(root)
        #expect(await kit.recordExport(try request(), examId: "ibps_po", examName: "IBPS PO / MT"))
        let entry = try #require(try await store(root).history().first)
        #expect(entry.shareable)
        #expect(entry.record.examId == "ibps_po" && entry.record.docType == .photo)
        #expect(entry.record.fileName == "photo_IBPS-PO_200x230_35kb.jpg")
        #expect(entry.record.width == 200 && entry.record.height == 230)
        #expect(await kit.exportCopy(id: entry.record.id) == fitted)
    }

    @Test("copies expire after 30 days but the entry stays")
    func expiry() async throws {
        let root = root(), clock = Clock()
        let kit = store(root, clock: clock)
        await kit.recordExport(try request(), examId: "ibps_po", examName: "IBPS PO")
        clock.now += ExportHistory.shareWindow
        let entry = try #require(try await kit.history().first)
        #expect(!entry.shareable)
        #expect(await kit.exportCopy(id: entry.record.id) == nil)
        #expect(files(root.appendingPathComponent("exports")).isEmpty)
    }

    @Test("history keeps the newest hundred")
    func cap() async throws {
        let root = root(), clock = Clock()
        let kit = store(root, clock: clock)
        let request = try request()
        for _ in 0..<(ExportHistory.maxEntries + 2) {
            clock.now += 1
            #expect(await kit.recordExport(request, examId: "ibps_po", examName: "IBPS PO"))
        }
        let history = try await kit.history()
        #expect(history.count == ExportHistory.maxEntries)
        #expect(history.first?.record.createdAt == clock.now)
        #expect(files(root.appendingPathComponent("exports")).count == ExportHistory.maxEntries)
    }

    @Test("clearing history keeps originals")
    func clear() async throws {
        let root = root()
        let kit = store(root)
        _ = await kit.saveOriginal(kind: .photo, jpeg: picture)
        await kit.recordExport(try request(), examId: "ibps_po", examName: "IBPS PO")
        await kit.clearHistory()
        #expect(try await kit.history().isEmpty)
        #expect(files(root.appendingPathComponent("exports")).isEmpty)
        #expect(try await kit.originals().count == 1)
    }
}
