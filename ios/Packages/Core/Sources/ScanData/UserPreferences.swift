import Foundation
import Observation
import ScanModel

/// Small user choices that outlive the app process (UI_UX §2: "My exams", Settings "Show unverified exams"),
/// kept in `UserDefaults`. Same rules as Android's `DataStoreUserPreferences`: newest pin first, no duplicates,
/// unverified exams hidden until the user opts in.
@MainActor
@Observable
public final class UserPreferences {
    /// Pinned exam ids, most recently pinned first.
    public private(set) var pinnedExamIds: [String]
    /// Low-confidence presets are hidden until the user opts in (PRD F1, CLAUDE.md rule 6).
    public private(set) var showUnverified: Bool
    public private(set) var savedDocuments: [String: [String]]
    /// The one-time "I signed in running handwriting, not CAPITAL letters" tick (ALGORITHMS 9.5).
    public private(set) var handwritingConfirmed: Bool
    /// Custom sizes in their canonical text, newest first, at most 5 (ALGORITHMS 11).
    public private(set) var recentCustomSizes: [String]
    /// Settings "Share anonymous usage data" (ARCHITECTURE §9): on unless the user opts out.
    public private(set) var analyticsEnabled: Bool

    @ObservationIgnored private let defaults: UserDefaults
    private static let pinnedKey = "pinned_exam_ids"
    private static let unverifiedKey = "show_unverified_exams"
    private static let savedKey = "saved_documents"
    private static let handwritingKey = "handwriting_confirmed"
    private static let recentCustomKey = "recent_custom_sizes"
    private static let analyticsKey = "analytics_enabled"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        pinnedExamIds = defaults.stringArray(forKey: Self.pinnedKey) ?? []
        // On by default (ALGORITHMS §10): an unverified exam is listed with its badge rather than missing.
        showUnverified = defaults.object(forKey: Self.unverifiedKey) as? Bool ?? true
        savedDocuments = defaults.dictionary(forKey: Self.savedKey) as? [String: [String]] ?? [:]
        handwritingConfirmed = defaults.bool(forKey: Self.handwritingKey)
        recentCustomSizes = defaults.stringArray(forKey: Self.recentCustomKey) ?? []
        analyticsEnabled = defaults.object(forKey: Self.analyticsKey) as? Bool ?? true
    }

    public func setAnalyticsEnabled(_ enabled: Bool) {
        analyticsEnabled = enabled
        defaults.set(enabled, forKey: Self.analyticsKey)
    }

    /// Moves `text` (a canonical Custom size) to the front of `recentCustomSizes`.
    public func rememberCustomSize(_ text: String) {
        precondition(CustomSize.parse(text)?.text == text, "not a canonical Custom size")
        recentCustomSizes = CustomSize.remember(recentCustomSizes, text)
        defaults.set(recentCustomSizes, forKey: Self.recentCustomKey)
    }

    public func isPinned(_ examId: String) -> Bool { pinnedExamIds.contains(examId) }

    public func setPinned(_ examId: String, _ pinned: Bool) {
        let others = pinnedExamIds.filter { $0 != examId }
        pinnedExamIds = pinned ? [examId] + others : others
        defaults.set(pinnedExamIds, forKey: Self.pinnedKey)
    }

    public func setShowUnverified(_ show: Bool) {
        showUnverified = show
        defaults.set(show, forKey: Self.unverifiedKey)
    }

    public func isSaved(_ examId: String, _ docType: DocType) -> Bool {
        savedDocuments[examId]?.contains(docType.rawValue) == true
    }

    /// Remembered on the device; the confirmation is never asked again (ALGORITHMS 9.5).
    public func confirmHandwriting() {
        handwritingConfirmed = true
        defaults.set(true, forKey: Self.handwritingKey)
    }

    public func recordSaved(_ examId: String, _ docType: DocType) {
        guard !isSaved(examId, docType) else { return }
        savedDocuments[examId, default: []].append(docType.rawValue)
        defaults.set(savedDocuments, forKey: Self.savedKey)
    }
}
