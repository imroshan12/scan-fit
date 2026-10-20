#if DEBUG && targetEnvironment(simulator)
import Billing
import DesignSystem
import Foundation
import Presets
import ScanData

extension AppContainer {
    /// Simulator-only smoke tests never use the real preferences, quota, drafts or My Kit.
    @MainActor
    static func uiTesting(bundle: Bundle) -> AppContainer? {
        let process = ProcessInfo.processInfo
        guard process.arguments.contains("--ui-testing") else { return nil }
        let suite = "app.scanfit.ui-testing"
        guard let defaults = UserDefaults(suiteName: suite),
              let used = Int(process.environment["SCANFIT_UI_TEST_SAVES_USED"] ?? "0"), used >= 0 else {
            preconditionFailure("Invalid UI test configuration")
        }
        defaults.removePersistentDomain(forName: suite)
        let repository = PresetsRepository()
        let files = PresetFiles.embedded(in: bundle)
        let products = process.environment["SCANFIT_UI_TEST_STORE"] == "unavailable" ? [] : LocalBilling.debugProducts
        let billing = LocalBilling(fakeProducts: products)
        return AppContainer(
            strings: Strings(), presets: repository, preferences: UserPreferences(defaults: defaults),
            drafts: NoDraftStore(), kit: NoKitStore(), billing: billing,
            gate: SaveGate(store: MemoryQuotaStore(QuotaState(used: used, allowance: 20)), billing: billing),
            presetsOutcome: Task { await repository.loadEmbedded(files) }
        )
    }
}
#endif
