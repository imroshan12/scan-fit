import Billing
import Config
import DesignSystem
import Presets
import ScanData
import SwiftUI

/// Settings tab: Pro and free saves, language (system per-app language), "Show unverified exams", presets version,
/// privacy (analytics opt-out, policy, terms), help, "Report a problem" and the non-affiliation disclaimer.
public struct SettingsView: View {
    private let strings: Strings
    private let presets: PresetsSummary?
    @Bindable private var model: SettingsViewModel
    @Environment(\.openURL) private var openURL
    @State private var noEmail = false

    public init(model: SettingsViewModel, presets: PresetsSummary?, strings: Strings = Strings()) {
        self.model = model
        self.presets = presets
        self.strings = strings
    }

    public var body: some View {
        NavigationStack {
            List {
                proSection
                Section {
                    languageRow
                    // Low-confidence presets stay hidden until the user opts in (PRD F1).
                    toggle(strings.settingsShowUnverified, strings.settingsShowUnverifiedHint, Binding(
                        get: { model.preferences.showUnverified },
                        set: { model.preferences.setShowUnverified($0) }
                    ))
                    if let presets {
                        LabeledContent {
                            Text(presets.examCount, format: .number).scanFitText(.figure)
                        } label: {
                            Text(strings.settingsPresetsVersion(version: presets.version)).scanFitText(.body)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                Section(strings.settingsPrivacy) {
                    Text(strings.settingsPrivacyNote).scanFitText(.caption)
                        .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    toggle(strings.settingsAnalytics, strings.settingsAnalyticsHint, Binding(
                        get: { model.preferences.analyticsEnabled },
                        set: { model.preferences.setAnalyticsEnabled($0) }
                    ))
                    link(strings.legalPrivacyPolicy, AppLinks.privacyPolicy)
                    link(strings.legalTerms, AppLinks.terms)
                }
                Section(strings.settingsHelp) {
                    link(strings.settingsHelp, AppLinks.help)
                    Button(strings.settingsReportProblem) { reportProblem() }
                        .frame(minHeight: ScanFitSpacing.minTouchTarget)
                    if noEmail {
                        Text(strings.settingsNoEmail(email: AppLinks.supportEmail)).scanFitText(.caption)
                            .foregroundStyle(ScanFitColor.onSurfaceVariant)
                            .textSelection(.enabled)
                    }
                }
                Section(strings.settingsAbout) {
                    Text(strings.legalDisclaimer)
                        .scanFitText(.caption)
                        .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    if let version = Self.appVersion {
                        Text(strings.settingsVersion(version: version)).scanFitText(.caption)
                            .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    }
                }
            }
            .navigationTitle(strings.tabSettings)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
    }

    /// Pro status, free saves left, Get Pro and Restore purchases (ALGORITHMS 14.3).
    private var proSection: some View {
        Section(strings.settingsPro) {
            if let quota = model.quota {
                if quota.pro {
                    Text(strings.settingsProActive).scanFitText(.body)
                        .accessibilityIdentifier("settings.pro_active")
                } else {
                    Text(quota.remaining > 0 ? strings.quotaLeft(count: quota.remaining) : strings.quotaNone)
                        .scanFitText(.body)
                        .accessibilityIdentifier("settings.free_saves")
                    Button { model.getPro() } label: {
                        Text(strings.settingsGetPro)
                            .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("settings.get_pro")
                }
            }
            Button {
                Task { await model.restorePurchases() }
            } label: {
                HStack {
                    Text(strings.paywallRestore)
                    if model.restore == .restoring { Spacer(); ProgressView() }
                }
                .frame(minHeight: ScanFitSpacing.minTouchTarget)
            }
            .disabled(model.restore == .restoring)
            if let note = restoreNote {
                Text(note).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
        }
    }

    private var restoreNote: String? {
        switch model.restore {
        case .restored: strings.paywallThanks
        case .nothing: strings.paywallRestoreNone
        case .failed: strings.paywallUnavailable
        case .idle, .restoring: nil
        }
    }

    private func toggle(_ title: String, _ hint: String, _ isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text(title).scanFitText(.body)
                Text(hint).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
        }
    }

    @ViewBuilder
    private func link(_ title: String, _ address: String) -> some View {
        if let url = URL(string: address) {
            Link(title, destination: url).frame(minHeight: ScanFitSpacing.minTouchTarget)
        }
    }

    private static var appVersion: String? {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    }

    private func reportProblem() {
        let system = ProcessInfo.processInfo.operatingSystemVersion
        let body = strings.settingsReportBody(
            app: Self.appVersion ?? "-",
            os: "iOS \(system.majorVersion).\(system.minorVersion).\(system.patchVersion)",
            presets: presets.map { String($0.version) } ?? "-"
        )
        guard let url = SettingsViewModel.reportURL(subject: strings.settingsReportSubject, body: body) else { return }
        openURL(url) { accepted in noEmail = !accepted }
    }

    /// iOS keeps the app language in the system's per-app settings; this opens it.
    @ViewBuilder
    private var languageRow: some View {
        #if canImport(UIKit)
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } label: {
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text(strings.settingsLanguage).scanFitText(.body)
                Text(strings.settingsLanguageHint).scanFitText(.caption)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
            .frame(minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
        }
        #else
        Text(strings.settingsLanguage).scanFitText(.body)
        #endif
    }
}

@MainActor
private func previewSettings(used: Int, pro: Bool = false) -> (SettingsViewModel, SaveGate) {
    let billing = LocalBilling(fakeProducts: pro ? LocalBilling.debugProducts : [])
    if pro { Task { _ = await billing.purchase(.yearly) } }
    let gate = SaveGate(store: MemoryQuotaStore(QuotaState(used: used, allowance: 20)), billing: billing)
    return (SettingsViewModel(preferences: UserPreferences(), gate: gate, billing: billing), gate)
}

#Preview("Free, 3 left") {
    let (model, gate) = previewSettings(used: 17)
    SettingsView(model: model, presets: nil).task { await gate.load() }
}

#Preview("Pro, dark") {
    let (model, gate) = previewSettings(used: 4, pro: true)
    SettingsView(model: model, presets: nil).task { await gate.load() }.preferredColorScheme(.dark)
}

#Preview("None left, Hindi, large text") {
    let (model, gate) = previewSettings(used: 20)
    SettingsView(model: model, presets: nil, strings: Strings(languageCode: "hi"))
        .environment(\.dynamicTypeSize, .accessibility3)
        .task { await gate.load() }
}
