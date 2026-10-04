import Foundation
import ScanData
import Testing

@MainActor
@Suite("User preferences")
struct UserPreferencesTests {
    private func fresh() -> UserDefaults {
        let name = "scanfit.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test("defaults are empty and unverified exams shown")
    func defaults() {
        let prefs = UserPreferences(defaults: fresh())
        #expect(prefs.pinnedExamIds.isEmpty)
        #expect(prefs.showUnverified)
    }

    @Test("the newest pin comes first, re-pinning does not duplicate, and choices survive a restart")
    func pinning() {
        let defaults = fresh()
        let prefs = UserPreferences(defaults: defaults)
        prefs.setPinned("ibps_po", true)
        prefs.setPinned("ssc_cgl", true)
        prefs.setPinned("ibps_po", true)
        #expect(prefs.pinnedExamIds == ["ibps_po", "ssc_cgl"])
        prefs.setPinned("ibps_po", false)
        prefs.setShowUnverified(false)
        let reopened = UserPreferences(defaults: defaults)
        #expect(reopened.pinnedExamIds == ["ssc_cgl"])
        #expect(!reopened.showUnverified, "turning it off is stored, not overridden by the default")
    }

    @Test("verified exports persist per exam and document, without bytes, paths or duplicate records")
    func savedDocuments() {
        let defaults = fresh()
        let prefs = UserPreferences(defaults: defaults)
        #expect(!prefs.isSaved("ibps_po", .photo))
        prefs.recordSaved("ibps_po", .photo)
        prefs.recordSaved("ibps_po", .photo)
        prefs.recordSaved("ibps_po", .signature)
        let reopened = UserPreferences(defaults: defaults)
        #expect(reopened.isSaved("ibps_po", .photo))
        #expect(reopened.isSaved("ibps_po", .signature))
        #expect(!reopened.isSaved("jee_main", .photo))
        #expect(!reopened.isSaved("ibps_po", .leftThumb))
        #expect(reopened.savedDocuments["ibps_po"] == ["photo", "signature"])
    }
}
