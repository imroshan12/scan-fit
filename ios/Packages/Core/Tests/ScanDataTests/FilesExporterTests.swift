import Foundation
import Match
@testable import ScanData
import ScanModel
import Testing
import TestSupport

@MainActor
@Suite("Files picker lifecycle", .timeLimit(.minutes(1)))
struct FilesExporterTests {
    private func request() throws -> ExportRequest {
        let spec = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" }?
            .documents.first { $0.type == .photo })
        return ExportRequest(
            bytes: TestJpeg.make(width: 200, height: 230, size: 34 * 1024),
            filename: "photo_IBPS-PO_200x230_34kb.jpg", spec: spec, kind: .photo
        )
    }

    private func staged(_ exporter: FilesExporter) async throws -> URL {
        for _ in 0..<200 {
            if let url = exporter.document?.url { return url }
            try await Task.sleep(for: .milliseconds(5))
        }
        return try #require(exporter.document?.url)
    }

    @Test("cancel is silent, removes only staging, preserves existing files and allows retry")
    func cancel() async throws {
        let exporter = FilesExporter()
        let request = try request()
        let existing = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data([1, 2, 3]).write(to: existing)
        defer { try? FileManager.default.removeItem(at: existing) }
        let saving = Task { await exporter.save(request) }
        let url = try await staged(exporter)
        #expect(url.lastPathComponent == request.filename)
        #expect(try Data(contentsOf: url) == Data(request.bytes))
        #expect(await exporter.save(request) == .saveFailed, "duplicate concrete saves are rejected")
        exporter.cancelled()
        exporter.cancelled()
        exporter.completed([existing])
        #expect(await saving.value == .idle)
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
        #expect(try Data(contentsOf: existing) == Data([1, 2, 3]))
        let retry = Task { await exporter.save(request) }
        _ = try await staged(exporter)
        exporter.cancelled()
        #expect(await retry.value == .idle)
    }

    @Test("returned destination is verified, staging is cleaned and duplicate callbacks cannot resume twice")
    func saved() async throws {
        let exporter = FilesExporter()
        let request = try request()
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: destination) }
        let saving = Task { await exporter.save(request) }
        let url = try await staged(exporter)
        try FileManager.default.copyItem(at: url, to: destination)
        exporter.completed([destination])
        exporter.completed([destination])
        exporter.cancelled()
        #expect(await saving.value == .saved)
        #expect(try Data(contentsOf: destination) == Data(request.bytes))
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
        #expect(exporter.document == nil)
    }

    @Test("sheet dismissal before the picker result still verifies a save instead of silently cancelling")
    func dismissalBeforeCompletion() async throws {
        let exporter = FilesExporter()
        let request = try request()
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: destination) }
        let saving = Task { await exporter.save(request) }
        let url = try await staged(exporter)
        try FileManager.default.copyItem(at: url, to: destination)
        exporter.presentationDismissed()
        #expect(exporter.document == nil)
        #expect(FileManager.default.fileExists(atPath: url.path))
        #expect(await exporter.save(request) == .saveFailed)
        exporter.completed([destination])
        #expect(await saving.value == .saved)
        #expect(try Data(contentsOf: destination) == Data(request.bytes))
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
    }

    @Test("picker cancellation after sheet dismissal stays silent and removes staging")
    func dismissalBeforeCancellation() async throws {
        let exporter = FilesExporter()
        let saving = Task { await exporter.save(try request()) }
        let url = try await staged(exporter)
        exporter.presentationDismissed()
        exporter.cancelled()
        #expect(try await saving.value == .idle)
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
    }

    @Test("a corrupted returned destination fails verification and is cleaned")
    func invalid() async throws {
        let exporter = FilesExporter()
        let request = try request()
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: destination) }
        let saving = Task { await exporter.save(request) }
        let url = try await staged(exporter)
        try Data([1]).write(to: destination)
        exporter.completed([destination])
        #expect(await saving.value == .verifyFailed)
        #expect(!FileManager.default.fileExists(atPath: destination.path))
        #expect(!FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
    }
}
