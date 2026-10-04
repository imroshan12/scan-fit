import Exams
import Foundation
import Imaging
@testable import PhotoFlow
import ScanData
import ScanModel
import Testing
import TestSupport

@MainActor
@Suite("Files saved status", .timeLimit(.minutes(1)))
struct FilesSavedStatusTests {
    @Test("a completed Files save after sheet dismissal updates review, the existing exam and persisted status")
    func savedStatus() async throws {
        let suite = "scanfit.files.status.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = UserPreferences(defaults: defaults)
        let bundle = try SpecPresets.bundle()
        let exam = ExamViewModel(examId: "ibps_po", preferences: preferences) { bundle }
        await exam.onAppear()
        let exporter = FilesExporter()
        let model = PhotoFlowViewModel(
            examId: "ibps_po", docType: .photo, tools: FakePhotoTools(),
            faces: FakeFaceDetector(faces: [Face(x: 400, y: 500, w: 200, h: 240)]),
            preferences: preferences, exporter: exporter
        ) { bundle }
        await model.onAppear()
        await model.imageSelected([1])
        await model.cropDone()
        let saving = Task { await model.save() }
        defer { exporter.cancelled() }
        for _ in 0..<200 {
            if exporter.document != nil { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        let staged = try #require(exporter.document?.url)
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: destination) }
        try FileManager.default.copyItem(at: staged, to: destination)
        exporter.presentationDismissed()
        #expect(model.exportState == .saving)
        #expect(!exam.preferences.isSaved(exam.examId, .photo))
        exporter.completed([destination])
        await saving.value
        #expect(model.exportState == .saved)
        #expect(exam.preferences.isSaved(exam.examId, .photo))
        #expect(!exam.preferences.isSaved(exam.examId, .signature))
        #expect(UserPreferences(defaults: defaults).isSaved("ibps_po", .photo))
        #expect(!FileManager.default.fileExists(atPath: staged.deletingLastPathComponent().path))
        #expect(model.back())
        #expect(exam.preferences.isSaved(exam.examId, .photo))
    }
}
