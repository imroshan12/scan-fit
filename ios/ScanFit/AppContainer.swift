import DesignSystem
import Foundation
import Presets
import ScanData
import ScanModel
import SwiftUI

/// Lightweight DI root (ARCHITECTURE §3). Built once at launch on the main actor and handed to `RootView`; tests and
/// previews build their own with fakes.
struct AppContainer: Sendable {
    let strings: Strings
    let presets: PresetsRepository
    let preferences: UserPreferences
    let drafts: any DraftStore
    /// The embedded presets are verified once, at launch, off the main actor. Every reader awaits the
    /// same task, so Home, Settings and the exam screen agree and nothing verifies twice.
    let presetsOutcome: Task<PresetsLoadOutcome, Never>

    @MainActor
    static func live(bundle: Bundle = .main) -> AppContainer {
        let repository = PresetsRepository()
        let files = PresetFiles.embedded(in: bundle)
        return AppContainer(
            strings: Strings(),
            presets: repository,
            preferences: UserPreferences(),
            drafts: FileDraftStore(
                root: URL.applicationSupportDirectory.resolvingSymlinksInPath().appendingPathComponent("drafts")
            ),
            presetsOutcome: Task { await repository.loadEmbedded(files) }
        )
    }

    /// The trusted bundle once verification finished (`nil` if it failed).
    func trustedBundle() async -> PresetBundle? {
        _ = await presetsOutcome.value
        return await presets.bundle
    }
}

private struct AppContainerKey: EnvironmentKey {
    static let defaultValue: AppContainer? = nil
}

extension EnvironmentValues {
    var appContainer: AppContainer? {
        get { self[AppContainerKey.self] }
        set { self[AppContainerKey.self] = newValue }
    }
}
