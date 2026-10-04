import DesignSystem
import SwiftUI

struct PhotoExportStatusView: View {
    let state: PhotoExportState
    let strings: Strings

    @ViewBuilder
    var body: some View {
        switch state {
        case .idle:
            EmptyView()
        case .saving:
            ProgressView().accessibilityLabel(strings.exportSaving)
        case .saved:
            Label(strings.exportSaved, systemImage: "checkmark.circle.fill")
                .foregroundStyle(ScanFitColor.success)
        case .saveFailed:
            PhotoNotice(text: strings.exportSaveFailed, kind: .error)
        case .verifyFailed:
            PhotoNotice(text: strings.exportVerifyFailed, kind: .error)
        }
    }
}

private struct ExportStatusPreviews: View {
    let strings: Strings

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
            ForEach([PhotoExportState.saving, .saved, .saveFailed, .verifyFailed], id: \.rawValue) { state in
                PhotoExportStatusView(state: state, strings: strings)
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
