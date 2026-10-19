#if DEBUG
import CoreGraphics
import DesignSystem
import Match
import ScanData
import ScanModel
import SwiftUI

private struct KitPreview: View {
    var state: KitViewModel.State = prepared
    var tab = KitTab.prepared
    var originals: KitOriginalsViewModel.State = KitPreview.originals
    var history: KitHistoryViewModel.State = KitPreview.history
    var language = "en"

    var body: some View {
        NavigationStack {
            KitView(
                model: KitViewModel(previewState: state),
                originals: KitOriginalsViewModel(previewState: originals),
                history: KitHistoryViewModel(previewState: history, share: .failed),
                strings: Strings(languageCode: language), isActive: false, tab: tab
            )
        }
    }

    static let day: Int64 = 1_792_000_000_000

    static let originals: KitOriginalsViewModel.State = .ready([
        KitOriginalGroup(kind: .photo, originals: [
            KitOriginal(id: "p1", kind: .photo, createdAt: day, width: 1800, height: 2400, size: 610_000),
        ]),
        KitOriginalGroup(kind: .signature, originals: [
            KitOriginal(id: "s1", kind: .signature, createdAt: day, width: 2400, height: 1080, size: 380_000),
        ]),
    ])

    static func record(_ id: String, exam: (id: String, name: String), _ type: DocType, kb: Int, size: (Int, Int))
        -> ExportRecord {
        ExportRecord(
            id: id, createdAt: day, examId: exam.id, examName: exam.name, docType: type, kind: DocKind.of(type),
            fileName: "file.jpg", size: kb * 1024, width: size.0, height: size.1
        )
    }

    static let history: KitHistoryViewModel.State = .ready([
        HistoryEntry(record: record("h1", exam: ("ibps_po", "IBPS PO / MT"), .photo, kb: 34, size: (200, 230)),
                     shareable: true),
        HistoryEntry(record: record("h2", exam: (CustomSize.examId, CustomSize.examName), .signature, kb: 16,
                                    size: (140, 60)), shareable: true),
        HistoryEntry(record: record("h3", exam: ("ssc_cgl", "SSC CGL"), .signature, kb: 18, size: (140, 60)),
                     shareable: false),
    ])

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

#Preview("Kit originals - light") {
    KitPreview(tab: .originals).preferredColorScheme(.light)
}

#Preview("Kit originals - dark") {
    KitPreview(tab: .originals).preferredColorScheme(.dark)
}

#Preview("Kit originals - empty Hindi largest") {
    KitPreview(tab: .originals, originals: .empty, language: "hi")
        .environment(\.locale, Locale(identifier: "hi")).dynamicTypeSize(.accessibility5)
}

#Preview("Kit originals - failed") {
    KitPreview(tab: .originals, originals: .failed)
}

#Preview("Kit history - light") {
    KitPreview(tab: .history).preferredColorScheme(.light)
}

#Preview("Kit history - dark") {
    KitPreview(tab: .history).preferredColorScheme(.dark)
}

#Preview("Kit history - Hindi largest") {
    KitPreview(tab: .history, language: "hi")
        .environment(\.locale, Locale(identifier: "hi")).dynamicTypeSize(.accessibility5)
}

#Preview("Kit history - empty") {
    KitPreview(tab: .history, history: .empty)
}

private struct KitOriginalPreview: View {
    let state: KitOriginalViewModel.State
    var language = "en"

    var body: some View {
        NavigationStack {
            KitOriginalView(
                model: KitOriginalViewModel(previewState: state), strings: Strings(languageCode: language),
                onUse: { _ in }, onLeave: {}
            )
        }
    }

    static let ready: KitOriginalViewModel.State = .ready(
        KitOriginal(id: "s1", kind: .signature, createdAt: KitPreview.day, width: 2400, height: 1080, size: 380_000),
        preview: nil
    )
}

#Preview("Kit original - light") {
    KitOriginalPreview(state: KitOriginalPreview.ready).preferredColorScheme(.light)
}

#Preview("Kit original - dark") {
    KitOriginalPreview(state: KitOriginalPreview.ready).preferredColorScheme(.dark)
}

#Preview("Kit original - Hindi largest") {
    KitOriginalPreview(state: KitOriginalPreview.ready, language: "hi")
        .environment(\.locale, Locale(identifier: "hi")).dynamicTypeSize(.accessibility5)
}

#Preview("Kit original - missing") {
    KitOriginalPreview(state: .missing)
}
#endif
