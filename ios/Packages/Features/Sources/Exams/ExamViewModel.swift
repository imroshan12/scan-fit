import Observation
import ScanData
import ScanModel

/// The exam checklist (UI_UX §3). Same states as Android's `ExamViewModel`.
@MainActor
@Observable
public final class ExamViewModel {
    public enum State: Equatable, Sendable {
        case loading
        /// The id is not in the trusted presets (a stale link, or a retired exam).
        case notFound
        case ready(Exam)
    }

    public private(set) var state: State = .loading
    public let examId: String
    public let preferences: UserPreferences
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?

    public init(examId: String, preferences: UserPreferences, load: @escaping @Sendable () async -> PresetBundle?) {
        self.examId = examId
        self.preferences = preferences
        self.load = load
    }

    public var isPinned: Bool { preferences.isPinned(examId) }

    /// Loads once; later calls (re-appearing views) do nothing.
    public func onAppear() async {
        guard state == .loading else { return }
        let id = examId
        if let exam = await load()?.exams.first(where: { $0.id == id }) {
            state = .ready(exam)
        } else {
            state = .notFound
        }
    }

    public func togglePin() {
        preferences.setPinned(examId, !isPinned)
    }
}
