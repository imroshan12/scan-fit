@testable import Exams
import Foundation
import ScanData
import ScanModel
import Testing
import TestSupport

@MainActor
@Suite("ExamViewModel")
struct ExamViewModelTests {
    private func preferences() -> UserPreferences {
        let name = "scanfit.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        return UserPreferences(defaults: defaults)
    }

    @Test("shows the exam from the trusted presets")
    func found() async throws {
        let bundle = try SpecPresets.bundle()
        let model = ExamViewModel(examId: "ibps_po", preferences: preferences()) { bundle }
        await model.onAppear()
        guard case let .ready(exam) = model.state else { Issue.record("not ready"); return }
        #expect(exam.name == "IBPS PO / MT")
        #expect(!exam.documents.isEmpty)
    }

    @Test("an unknown id is not found, never a crash")
    func notFound() async throws {
        let bundle = try SpecPresets.bundle()
        let model = ExamViewModel(examId: "no_such_exam", preferences: preferences()) { bundle }
        await model.onAppear()
        #expect(model.state == .notFound)
    }

    @Test("pinning is saved and reflected")
    func pinning() async throws {
        let prefs = preferences()
        let bundle = try SpecPresets.bundle()
        let model = ExamViewModel(examId: "ssc_cgl", preferences: prefs) { bundle }
        await model.onAppear()
        model.togglePin()
        #expect(model.isPinned && prefs.pinnedExamIds == ["ssc_cgl"])
        model.togglePin()
        #expect(!model.isPinned && prefs.pinnedExamIds.isEmpty)
    }

    @Test("dates show in the user's calendar without shifting a day")
    func dates() {
        let text = ExamView.formatted("2026-09-30")
        #expect(text.contains("30") && text.contains("2026"))
        #expect(ExamView.formatted("not a date") == "not a date")
        let hindi = ExamView.formatted("2026-09-30", locale: Locale(identifier: "hi"))
        #expect(!hindi.contains("Sep"), "Hindi must not show an English month: \(hindi)")
    }

    @Test("a successful export is reflected in the existing exam model, scoped to its document")
    func savedStatus() async throws {
        let prefs = preferences()
        let bundle = try SpecPresets.bundle()
        let model = ExamViewModel(examId: "ibps_po", preferences: prefs) { bundle }
        await model.onAppear()
        #expect(!model.preferences.isSaved(model.examId, .photo))
        prefs.recordSaved("ibps_po", .photo)
        #expect(model.preferences.isSaved(model.examId, .photo))
        #expect(!model.preferences.isSaved(model.examId, .signature))
    }
}
