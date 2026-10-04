import Foundation
@testable import PhotoFlow
import Testing
import TestSupport

@Suite("Files export destination")
struct FilesExportTransportTests {
    @Test("coordinated destination reading and verification run off the main actor")
    func coordinatedRead() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("photo.jpg")
        let request = try request()
        try Data(request.bytes).write(to: url)
        let result = await Task.detached {
            #expect(!Self.isMainThread())
            return await PhotoExportOperation.run(request, transport: FilesExportTransport(url: url))
        }.value
        #expect(result.state == .saved && !result.removed)
        #expect(try Data(contentsOf: url) == Data(request.bytes))
    }

    @Test("invalid newly exported destinations are removed, without affecting adjacent existing files")
    func cleanup() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("export.jpg")
        let existing = directory.appendingPathComponent("existing.jpg")
        try Data([1, 2, 3]).write(to: url)
        try Data([4, 5, 6]).write(to: existing)
        let request = try request()
        let result = await Task.detached {
            await PhotoExportOperation.run(request, transport: FilesExportTransport(url: url))
        }.value
        #expect(result.state == .verifyFailed && result.removed)
        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(try Data(contentsOf: existing) == Data([4, 5, 6]))
    }

    @Test("an unreadable destination is a save failure and a failed delete is not reported as removal")
    func unreadable() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let request = try request()
        let result = await Task.detached {
            await PhotoExportOperation.run(request, transport: FilesExportTransport(url: url))
        }.value
        #expect(result.state == .saveFailed && !result.removed)
    }

    private static func isMainThread() -> Bool { Thread.isMainThread }

    private func request() throws -> PhotoExportRequest {
        let spec = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" }?
            .documents.first { $0.type == .photo })
        return PhotoExportRequest(
            bytes: FakePhotoTools.jpeg(width: 200, height: 230, size: 34 * 1024),
            filename: "photo.jpg", spec: spec
        )
    }
}
