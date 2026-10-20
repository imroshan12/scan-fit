import Billing
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
    /// My Kit originals and export history (ALGORITHMS 13), backup-excluded like the drafts.
    let kit: any KitStore
    /// Pro purchases (RevenueCat replaces `LocalBilling`, LAUNCH_PLAN week 1) and the free-saves gate (ALGORITHMS 14).
    let billing: any Billing
    let gate: SaveGate
    /// The embedded presets are verified once, at launch, off the main actor. Every reader awaits the
    /// same task, so Home, Settings and the exam screen agree and nothing verifies twice.
    let presetsOutcome: Task<PresetsLoadOutcome, Never>

    @MainActor
    static func live(bundle: Bundle = .main) -> AppContainer {
        #if DEBUG && targetEnvironment(simulator)
        if let testing = uiTesting(bundle: bundle) { return testing }
        #endif
        let repository = PresetsRepository()
        let files = PresetFiles.embedded(in: bundle)
        let support = URL.applicationSupportDirectory.resolvingSymlinksInPath()
        let freeSaves = SaveGate.buildTotal(bundle.object(forInfoDictionaryKey: "MRDFreeSavesTotal"))
        #if DEBUG
        // Debug builds sell fake products that grant Pro for the session, so the paywall can be walked by hand.
        let billing = LocalBilling(fakeProducts: LocalBilling.debugProducts)
        #else
        let billing = LocalBilling()
        #endif
        return AppContainer(
            strings: Strings(),
            presets: repository,
            preferences: UserPreferences(),
            drafts: FileDraftStore(root: support.appendingPathComponent("drafts")),
            kit: FileKitStore(root: support.appendingPathComponent("kit")),
            billing: billing,
            gate: SaveGate(
                store: FileQuotaStore(root: support.appendingPathComponent("quota")), billing: billing,
                total: { [freeSaves] in freeSaves }
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
