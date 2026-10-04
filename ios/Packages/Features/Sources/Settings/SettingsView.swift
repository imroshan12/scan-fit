import DesignSystem
import Presets
import ScanData
import SwiftUI

/// Settings tab: language (system per-app language), "Show unverified exams", presets version and the
/// non-affiliation disclaimer. Pro, privacy and help arrive with their phases.
public struct SettingsView: View {
    private let strings: Strings
    private let presets: PresetsSummary?
    private let preferences: UserPreferences

    public init(presets: PresetsSummary?, preferences: UserPreferences, strings: Strings = Strings()) {
        self.presets = presets
        self.preferences = preferences
        self.strings = strings
    }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    languageRow
                    // Low-confidence presets stay hidden until the user opts in (PRD F1).
                    Toggle(isOn: Binding(
                        get: { preferences.showUnverified },
                        set: { preferences.setShowUnverified($0) }
                    )) {
                        VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                            Text(strings.settingsShowUnverified).scanFitText(.body)
                            Text(strings.settingsShowUnverifiedHint).scanFitText(.caption)
                                .foregroundStyle(ScanFitColor.onSurfaceVariant)
                        }
                    }
                    if let presets {
                        LabeledContent {
                            Text(presets.examCount, format: .number).scanFitText(.figure)
                        } label: {
                            Text(strings.settingsPresetsVersion(version: presets.version)).scanFitText(.body)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                Section(strings.settingsAbout) {
                    Text(strings.legalDisclaimer)
                        .scanFitText(.caption)
                        .foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
            }
            .navigationTitle(strings.tabSettings)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
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
