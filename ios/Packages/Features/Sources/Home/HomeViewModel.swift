import Observation
import Presets
import ScanData
import ScanModel

/// Home (UI_UX §3). With an empty query and no category it shows sections (My exams, Popular now); otherwise a list:
/// search results (ALGORITHMS §10), or the selected category's exams. Same behaviour as Android's `HomeViewModel`.
@MainActor
@Observable
public final class HomeViewModel {
    public enum PresetsStatus: Equatable, Sendable {
        case loading
        case ready(PresetsSummary)
        /// Verification failed or the snapshot is missing. The user sees a plain message, never a raw error.
        case failed
    }

    /// What `load` returns: the verification outcome, and the trusted bundle when it verified.
    public struct Loaded: Sendable {
        public let outcome: PresetsLoadOutcome
        public let bundle: PresetBundle?

        public init(outcome: PresetsLoadOutcome, bundle: PresetBundle?) {
            self.outcome = outcome
            self.bundle = bundle
        }
    }

    public private(set) var presets: PresetsStatus = .loading
    public var query = ""
    public private(set) var category: ExamCategory?
    public let preferences: UserPreferences

    @ObservationIgnored private let load: @Sendable () async -> Loaded
    private var bundle: PresetBundle?
    private var search: ExamSearch?
    private static let popularCount = 6

    public init(preferences: UserPreferences, load: @escaping @Sendable () async -> Loaded) {
        self.preferences = preferences
        self.load = load
    }

    /// Loads once; later calls (re-appearing views) do nothing.
    public func onAppear() async {
        guard presets == .loading else { return }
        let loaded = await load()
        switch loaded.outcome {
        case let .ready(summary):
            bundle = loaded.bundle
            search = loaded.bundle.map { ExamSearch(bundle: $0) }
            presets = .ready(summary)
        case .failed, .missingEmbedded:
            presets = .failed
        }
    }

    public var showsSections: Bool { query.trimmingCharacters(in: .whitespaces).isEmpty && category == nil }

    public var results: [Exam] {
        guard let search else { return [] }
        if !query.trimmingCharacters(in: .whitespaces).isEmpty {
            return search.search(query, category: category, showUnverified: preferences.showUnverified)
        }
        if let category { return search.browse(category, showUnverified: preferences.showUnverified) }
        return []
    }

    /// A pinned exam stays visible even if it is unverified: the user chose it explicitly.
    public var pinned: [Exam] {
        let byId = Dictionary((bundle?.exams ?? []).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return preferences.pinnedExamIds.compactMap { byId[$0] }
    }

    public var popular: [Exam] {
        search?.popular(Self.popularCount, showUnverified: preferences.showUnverified) ?? []
    }

    /// `nil` = "All". Tapping the selected chip again clears it.
    public func select(_ selected: ExamCategory?) {
        category = selected == category ? nil : selected
    }
}
