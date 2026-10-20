import CoreGraphics
import DesignSystem
import ScanData
import ScanModel
import SwiftUI

/// A saved file, previewed before it is shared again (ALGORITHMS 13.3, UI_UX §3 My Kit).
public struct KitHistoryPreviewView: View {
    @State private var model: KitHistoryPreviewViewModel
    private let strings: Strings
    @Environment(\.locale) private var locale
    @Environment(\.displayScale) private var scale

    public init(model: KitHistoryPreviewViewModel, strings: Strings = Strings()) {
        _model = State(initialValue: model)
        self.strings = strings
    }

    public var body: some View {
        content
            .navigationTitle(title)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .toolbar {
                if case .ready = model.state {
                    ToolbarItem(placement: .primaryAction) {
                        Button { Task { await model.shareCopy() } } label: {
                            Image(systemName: "square.and.arrow.up").accessibilityLabel(strings.commonShare)
                        }
                    }
                }
            }
            .task { await model.load() }
        #if os(iOS)
            .sheet(item: Binding(get: { model.share.file }, set: { if $0 == nil { model.shareHandled() } })) { file in
                ShareSheet(url: file.url) { model.shareHandled() }
            }
        #endif
    }

    private var title: String {
        if case let .ready(record, _, _) = model.state { return strings.label(record.docType) }
        return ""
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .failed:
            NoticeCard(text: strings.kitPreviewFailed, kind: .error).padding(ScanFitSpacing.screenMargin)
        case let .ready(record, image, pages):
            GeometryReader { geo in
                ScrollView {
                    VStack(alignment: .leading, spacing: ScanFitSpacing.md) {
                        facts(record)
                        if model.share == .failed { NoticeCard(text: strings.exportShareFailed, kind: .error) }
                        if let image {
                            frame(image, label: nil)
                        } else {
                            let width = Int((geo.size.width - 2 * ScanFitSpacing.screenMargin) * scale)
                            ForEach(0..<pages, id: \.self) { index in
                                PdfPageView(model: model, index: index, width: width,
                                            label: strings.kitPreviewPage(number: index + 1, total: pages))
                            }
                        }
                    }
                    .padding(ScanFitSpacing.screenMargin)
                }
            }
        }
    }

    private func facts(_ record: ExportRecord) -> some View {
        let exam = record.examId == CustomSize.examId ? strings.kitHistoryCustom : record.examName
        let kb = (record.size + 512) / 1024
        let size = record.pages.map { strings.pdfHistoryRow(kb: kb, pages: strings.pdfPages(count: $0)) }
            ?? strings.photoResult(kb: kb, width: record.width, height: record.height)
        return VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
            Text(exam).scanFitText(.headline)
            Text(size + " · " + kitDay(record.createdAt, locale: locale))
                .scanFitText(.figure).foregroundStyle(ScanFitColor.onSurfaceVariant)
        }
    }

    private func frame(_ image: CGImage, label: String?) -> some View {
        Image(decorative: image, scale: 1)
            .resizable()
            .scaledToFit()
            .frame(maxWidth: .infinity)
            .background(ScanFitColor.surfaceVariant)
            .clipShape(RoundedRectangle(cornerRadius: ScanFitRadius.card))
            .accessibilityElement()
            .accessibilityLabel(label ?? "")
    }
}

/// One PDF page, drawn when it scrolls into view.
private struct PdfPageView: View {
    let model: KitHistoryPreviewViewModel
    let index: Int
    let width: Int
    let label: String
    @State private var image: CGImage?

    var body: some View {
        Group {
            if let image {
                Image(decorative: image, scale: 1).resizable().scaledToFit()
            } else {
                ZStack {
                    ScanFitColor.surfaceVariant
                    ProgressView()
                }
                .aspectRatio(0.707, contentMode: .fit)
            }
        }
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: ScanFitRadius.card))
        .accessibilityElement()
        .accessibilityLabel(label)
        .task(id: width) { image = await model.page(index, width: width) }
    }
}
