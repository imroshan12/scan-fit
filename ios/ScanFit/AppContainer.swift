import DesignSystem
import Foundation
import Presets
import SwiftUI

/// Lightweight DI root (ARCHITECTURE §3). Built once at launch and passed through the SwiftUI
/// Environment; tests and previews build their own with fakes.
struct AppContainer: Sendable {
    let strings: Strings
    let presets: PresetsRepository
    /// The embedded presets are verified once, at launch, off the main actor. Every reader awaits the
    /// same task, so Home and Settings agree and nothing verifies twice.
    let presetsOutcome: Task<PresetsLoadOutcome, Never>

    static func live(bundle: Bundle = .main) -> AppContainer {
        let repository = PresetsRepository()
        let files = PresetFiles.embedded(in: bundle)
        return AppContainer(
            strings: Strings(),
            presets: repository,
            presetsOutcome: Task { await repository.loadEmbedded(files) }
        )
    }
}

private struct AppContainerKey: EnvironmentKey {
    static var defaultValue: AppContainer { AppContainer.live() }
}

extension EnvironmentValues {
    var appContainer: AppContainer {
        get { self[AppContainerKey.self] }
        set { self[AppContainerKey.self] = newValue }
    }
}
