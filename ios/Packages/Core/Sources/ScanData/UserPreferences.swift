import Foundation
import Observation

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

    @ObservationIgnored private let defaults: UserDefaults
    private static let pinnedKey = "pinned_exam_ids"
    private static let unverifiedKey = "show_unverified_exams"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        pinnedExamIds = defaults.stringArray(forKey: Self.pinnedKey) ?? []
        showUnverified = defaults.bool(forKey: Self.unverifiedKey)
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
}
