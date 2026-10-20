import Billing
import Config
import Foundation
import ScanData
@testable import Settings
import Testing
import TestSupport

@MainActor
@Suite("Settings")
struct SettingsViewModelTests {
    private func preferences() -> UserPreferences {
        let suite = "settings-tests-\(UUID().uuidString)"
        return UserPreferences(defaults: UserDefaults(suiteName: suite) ?? .standard)
    }

    @Test("free saves left, then Pro after a purchase")
    func quota() async {
        let billing = FakeBilling()
        let model = SettingsViewModel(
            preferences: preferences(), gate: await testSaveGate(used: 7, billing: billing), billing: billing
        )
        #expect(model.quota == QuotaStatus(pro: false, remaining: 13, showsLeft: false))
        _ = await billing.purchase(.yearly)
        #expect(model.quota == QuotaStatus(pro: true, remaining: 13, showsLeft: false))
    }

    @Test("Get Pro opens the paywall from Settings")
    func getPro() async {
        let gate = await testSaveGate()
        SettingsViewModel(preferences: preferences(), gate: gate, billing: FakeBilling()).getPro()
        #expect(gate.paywall.current == .settings)
    }

    @Test("restore reports what it found")
    func restore() async {
        let billing = FakeBilling()
        let model = SettingsViewModel(preferences: preferences(), gate: await testSaveGate(), billing: billing)
        await model.restorePurchases()
        #expect(model.restore == .nothing)
        billing.restoreOutcome = .failed
        await model.restorePurchases()
        #expect(model.restore == .failed)
        billing.restoreOutcome = .restored
        await model.restorePurchases()
        #expect(model.restore == .restored)
    }

    @Test("the analytics opt-out is stored")
    func analytics() {
        let prefs = preferences()
        #expect(prefs.analyticsEnabled)
        prefs.setAnalyticsEnabled(false)
        #expect(!prefs.analyticsEnabled)
    }

    @Test("Report a problem is an email with no attachment")
    func report() throws {
        let url = try #require(SettingsViewModel.reportURL(subject: "Mr Doc problem", body: "App 0.9 · iOS 17"))
        #expect(url.scheme == "mailto")
        #expect(url.absoluteString.hasPrefix("mailto:\(AppLinks.supportEmail)"))
        #expect(url.absoluteString.contains("subject=Mr%20Doc%20problem"))
    }
}
