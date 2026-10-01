import Observation
import Presets

/// Home screen state. Phase 0 shows the embedded-presets status, which is the exit-gate proof that the
/// signed snapshot loaded; search, categories and pinned exams arrive in Phase 2.
@MainActor
@Observable
public final class HomeViewModel {
    public enum UiState: Equatable, Sendable {
        case loading
        case ready(PresetsSummary)
        /// Verification failed or the snapshot is missing. The user sees a plain message, never a raw error.
        case failed
    }

    public private(set) var state: UiState = .loading
    private let loadPresets: @Sendable () async -> PresetsLoadOutcome

    public init(loadPresets: @escaping @Sendable () async -> PresetsLoadOutcome) {
        self.loadPresets = loadPresets
    }

    /// Loads once; later calls (re-appearing views) do nothing.
    public func onAppear() async {
        guard state == .loading else { return }
        switch await loadPresets() {
        case let .ready(summary): state = .ready(summary)
        case .failed, .missingEmbedded: state = .failed
        }
    }
}
