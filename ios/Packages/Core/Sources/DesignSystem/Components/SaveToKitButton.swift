import ScanModel
import SwiftUI

/// "Save to My Kit" under Save and Share on a photo or ink review (ALGORITHMS 13.1): keeps the picture the review was
/// made from, not the fitted file. A full Kit or a failure is explained under the button, which stays for a retry.
public struct SaveToKitButton: View {
    private let status: KitSaveStatus
    private let strings: Strings
    private let action: () -> Void

    public init(status: KitSaveStatus, strings: Strings, action: @escaping () -> Void) {
        self.status = status
        self.strings = strings
        self.action = action
    }

    public var body: some View {
        VStack(spacing: ScanFitSpacing.xs) {
            Button(action: action) {
                HStack(spacing: ScanFitSpacing.sm) {
                    switch status {
                    case .saving: ProgressView()
                    case .saved: Image(systemName: "checkmark.circle.fill").accessibilityHidden(true)
                    default: EmptyView()
                    }
                    Text(title)
                }
                .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
            }
            .disabled(status == .saving || status == .saved)
            if let problem {
                Label(problem, systemImage: "exclamationmark.circle")
                    .scanFitText(.caption)
                    .foregroundStyle(ScanFitColor.error)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var title: String {
        switch status {
        case .saving: strings.kitSavingOriginal
        case .saved: strings.kitSavedOriginal
        default: strings.kitSaveOriginal
        }
    }

    private var problem: String? {
        switch status {
        case .full: strings.kitFull
        case .failed: strings.kitSaveFailed
        default: nil
        }
    }
}

private struct SaveToKitPreviews: View {
    let strings: Strings

    var body: some View {
        VStack(spacing: ScanFitSpacing.lg) {
            ForEach(KitSaveStatus.allCases, id: \.self) { SaveToKitButton(status: $0, strings: strings) {} }
        }
        .padding(ScanFitSpacing.screenMargin)
    }
}

#Preview("Save to Kit - light") {
    SaveToKitPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Save to Kit - dark") {
    SaveToKitPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.dark)
}

#Preview("Save to Kit - Hindi largest") {
    SaveToKitPreviews(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}
