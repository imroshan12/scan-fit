import Foundation
import Match
@testable import ScanData
import ScanModel
import Testing
import TestSupport

/// Save all and Share (ALGORITHMS 1.6.2): the shared plan cases, one Files picker for several files, the share stager.
@MainActor
@Suite("Save all and share", .timeLimit(.minutes(1)))
struct SaveAllTests {
    private func types(_ value: Any?) -> [DocType] {
        ((value as? [String]) ?? []).compactMap(DocType.init(rawValue:))
    }

    @Test("every save all case holds")
    func plan() throws {
        let cases = try CasesFile.section("save_all_cases")
        #expect(cases.count >= 5)
        for c in cases {
            let id = try #require(c.string("id"))
            let plan = SaveAllPlan.of(
                documents: types(c["documents"]), ready: Set(types(c["ready"])), saved: Set(types(c["saved"])),
                handwritingConfirmed: c.bool("handwriting_confirmed") ?? false
            )
            let expect = try #require(c.dict("expect"))
            #expect(plan.included == types(expect["included"]), "\(id)")
            #expect(plan.held == types(expect["held"]), "\(id)")
        }
    }

    private func requests() throws -> [ExportRequest] {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        let photo = try #require(exam.documents.first { $0.type == .photo })
        let signature = try #require(exam.documents.first { $0.type == .signature })
        return [
            ExportRequest(bytes: TestJpeg.make(width: 200, height: 230, size: 34 * 1024),
                          filename: "photo_IBPS-PO_200x230_34kb.jpg", spec: photo, kind: .photo),
            ExportRequest(bytes: TestJpeg.make(width: 140, height: 60, size: 16 * 1024),
                          filename: "signature_IBPS-PO_140x60_16kb.jpg", spec: signature, kind: .signature),
        ]
    }

    private func staged(_ exporter: FilesExporter) async throws -> [URL] {
        for _ in 0..<200 {
            if let urls = exporter.document?.urls { return urls }
            try await Task.sleep(for: .milliseconds(5))
        }
        return try #require(exporter.document?.urls)
    }

    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Picked-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test("one picker for every file; renamed copies are matched by their bytes and verified")
    func saveAll() async throws {
        let exporter = FilesExporter()
        let requests = try requests()
        let picked = try folder()
        defer { try? FileManager.default.removeItem(at: picked) }
        let saving = Task { await exporter.saveAll(requests) }
        let urls = try await staged(exporter)
        #expect(urls.map(\.lastPathComponent) == requests.map(\.filename))
        // The picker returns them in another order, one renamed by "Keep both".
        let renamed = picked.appendingPathComponent("photo_IBPS-PO_200x230_34kb 2.jpg")
        let signature = picked.appendingPathComponent(urls[1].lastPathComponent)
        try FileManager.default.copyItem(at: urls[0], to: renamed)
        try FileManager.default.copyItem(at: urls[1], to: signature)
        exporter.completed([signature, renamed])
        let outcome = await saving.value
        #expect(outcome.states == [.saved, .saved])
        #expect(outcome.folder == picked.lastPathComponent)
        #expect(!FileManager.default.fileExists(atPath: urls[0].deletingLastPathComponent().path), "staging cleaned")
    }

    @Test("a returned file that matches no request is not verified for, or deleted as, another slot")
    func unmatched() async throws {
        let exporter = FilesExporter()
        let requests = try requests()
        let picked = try folder()
        defer { try? FileManager.default.removeItem(at: picked) }
        let saving = Task { await exporter.saveAll(requests) }
        let urls = try await staged(exporter)
        let photo = picked.appendingPathComponent(urls[0].lastPathComponent)
        let stranger = picked.appendingPathComponent("other.jpg")
        try FileManager.default.copyItem(at: urls[0], to: photo)
        try Data([1, 2, 3]).write(to: stranger)
        exporter.completed([photo, stranger])
        #expect(await saving.value.states == [.saved, .saveFailed])
        #expect(try Data(contentsOf: stranger) == Data([1, 2, 3]), "someone else's file is left alone")
    }

    @Test("a provider root is not named; a real folder is")
    func folderName() {
        let root = URL(fileURLWithPath: "/tmp/x/File Provider Storage/a.jpg")
        #expect(FilesExporter.folderName(of: root) == nil)
        #expect(FilesExporter.folderName(of: URL(fileURLWithPath: "/tmp/IBPS-PO/a.jpg")) == "IBPS-PO")
    }

    @Test("cancelling saves nothing and returns idle for each file")
    func cancel() async throws {
        let exporter = FilesExporter()
        let requests = try requests()
        let saving = Task { await exporter.saveAll(requests) }
        let urls = try await staged(exporter)
        exporter.cancelled()
        #expect(await saving.value.states == [.idle, .idle])
        #expect(!FileManager.default.fileExists(atPath: urls[0].deletingLastPathComponent().path))
    }

    @Test("share stages exactly the bytes under the name, one file at a time, and refuses a path")
    func share() async throws {
        let bytes = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
        let folder = ownShareFolder()
        let first = try #require(await ShareStager.stage(bytes, filename: "a.jpg", in: folder))
        let written = [UInt8](try Data(contentsOf: first))
        #expect(first.lastPathComponent == "a.jpg" && written == bytes)
        let second = try #require(await ShareStager.stage(bytes, filename: "b.jpg", in: folder))
        #expect(!FileManager.default.fileExists(atPath: first.path), "the previous staged file is gone")
        #expect(await ShareStager.stage(bytes, filename: "../escape.jpg", in: folder) == nil)
        await ShareStager.clear(in: folder)
        #expect(!FileManager.default.fileExists(atPath: second.path))
    }

    // Regression (CI 2026-10-20): suites stage in parallel, and a stage empties its folder, so a test sharing into the
    // app's folder could lose its file to another test. Own folders keep each test's file.
    @Test("stages in separate folders never remove each other's file")
    func separateFolders() async throws {
        let bytes = TestJpeg.make(width: 140, height: 60, size: 16 * 1024)
        let (one, two) = (ownShareFolder(), ownShareFolder())
        async let first = ShareStager.stage(bytes, filename: "a.jpg", in: one)
        async let second = ShareStager.stage(bytes, filename: "b.jpg", in: two)
        let urls = await [first, second].compactMap { $0 }
        #expect(urls.count == 2)
        for url in urls { #expect([UInt8](try Data(contentsOf: url)) == bytes) }
        await ShareStager.clear(in: one)
        await ShareStager.clear(in: two)
    }
}
