#if DEBUG
import DesignSystem
import ScanModel
import SwiftUI

private struct KitPreview: View {
    let state: KitViewModel.State
    var language = "en"

    var body: some View {
        NavigationStack {
            KitView(model: KitViewModel(previewState: state), strings: Strings(languageCode: language), isActive: false)
        }
    }

    static let prepared: KitViewModel.State = .ready([
        KitGroup(id: "preview-ibps", name: "IBPS PO / MT", confidence: .high, rows: [
            KitRow(route: FlowRoute(examId: "preview-ibps", docType: .photo), roundedKB: 34),
            KitRow(route: FlowRoute(examId: "preview-ibps", docType: .signature), roundedKB: 16),
        ]),
        KitGroup(id: "preview-upsc", name: "UPSC Engineering Services (ESE)", confidence: .low, rows: [
            KitRow(route: FlowRoute(examId: "preview-upsc", docType: .tripleSignature), roundedKB: 68),
        ]),
    ])
}

#Preview("Kit - light") {
    KitPreview(state: KitPreview.prepared).preferredColorScheme(.light)
}

#Preview("Kit - dark largest") {
    KitPreview(state: KitPreview.prepared).preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}

#Preview("Kit - Hindi light largest") {
    KitPreview(state: KitPreview.prepared, language: "hi")
        .environment(\.locale, Locale(identifier: "hi")).preferredColorScheme(.light)
        .dynamicTypeSize(.accessibility5)
}

#Preview("Kit - Hindi dark largest") {
    KitPreview(state: KitPreview.prepared, language: "hi")
        .environment(\.locale, Locale(identifier: "hi")).preferredColorScheme(.dark)
        .dynamicTypeSize(.accessibility5)
}

#Preview("Kit - empty Hindi largest") {
    KitPreview(state: .empty, language: "hi").dynamicTypeSize(.accessibility5)
}

#Preview("Kit - loading") {
    KitPreview(state: .loading)
}

#Preview("Kit - failed Hindi dark largest") {
    KitPreview(state: .failed, language: "hi").preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}
#endif
