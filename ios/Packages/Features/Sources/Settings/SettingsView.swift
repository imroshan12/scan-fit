import DesignSystem
import Presets
import SwiftUI

/// Settings tab. Phase 0: language (system per-app language), presets version and the non-affiliation
/// disclaimer. Pro, privacy, help and "Show unverified" arrive with their phases.
public struct SettingsView: View {
    private let strings: Strings
    private let presets: PresetsSummary?

    public init(presets: PresetsSummary?, strings: Strings = Strings()) {
        self.presets = presets
        self.strings = strings
    }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    languageRow
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
