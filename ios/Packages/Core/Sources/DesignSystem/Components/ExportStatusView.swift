import ScanModel
import SwiftUI

/// A save's status under the review (ALGORITHMS 1.6): progress, the verified tick, or a retryable error.
public struct ExportStatusView: View {
    private let state: ExportState
    private let strings: Strings

    public init(state: ExportState, strings: Strings) {
        self.state = state
        self.strings = strings
    }

    @ViewBuilder
    public var body: some View {
        switch state {
        case .idle:
            EmptyView()
        case .saving:
            ProgressView().accessibilityLabel(strings.exportSaving)
        case .saved:
            Label(strings.exportSaved, systemImage: "checkmark.circle.fill")
                .foregroundStyle(ScanFitColor.success)
        case .saveFailed:
            NoticeCard(text: strings.exportSaveFailed, kind: .error)
        case .verifyFailed:
            NoticeCard(text: strings.exportVerifyFailed, kind: .error)
        }
    }
}

private struct ExportStatusPreviews: View {
    let strings: Strings

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
            ForEach([ExportState.saving, .saved, .saveFailed, .verifyFailed], id: \.rawValue) { state in
                ExportStatusView(state: state, strings: strings)
            }
        }
        .padding(ScanFitSpacing.screenMargin)
    }
}

#Preview("Export - light") {
    ExportStatusPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Export - dark") {
    ExportStatusPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.dark)
}

#Preview("Export - Hindi largest") {
    ExportStatusPreviews(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}
