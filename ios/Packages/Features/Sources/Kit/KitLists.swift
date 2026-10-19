import CoreGraphics
import DesignSystem
import ScanData
import ScanModel
import SwiftUI

/// A day in the app's language ("15 Oct 2026", "15 अक्टू॰ 2026").
func kitDay(_ milliseconds: Int64, locale: Locale) -> String {
    Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        .formatted(Date.FormatStyle(date: .abbreviated, time: .omitted).locale(locale))
}

/// An empty view or a load failure with Retry, shared by the Kit views.
struct KitMessage: View {
    let title: String?
    let message: String
    var strings = Strings()
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: ScanFitSpacing.md) {
            if let title {
                Image(systemName: "tray").font(.largeTitle)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant).accessibilityHidden(true)
                Text(title).scanFitText(.title).multilineTextAlignment(.center)
            }
            Text(message).scanFitText(.body)
                .foregroundStyle(title == nil ? ScanFitColor.onBackground : ScanFitColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
            if let retry {
                Button(action: retry) { Label(strings.commonRetry, systemImage: "arrow.clockwise") }
                    .frame(minHeight: ScanFitSpacing.minTouchTarget)
            }
        }
        .padding(ScanFitSpacing.xl)
    }
}

/// My Kit's Originals view (ALGORITHMS 13.1): grouped by kind, newest first.
struct KitOriginalsList: View {
    @Bindable var model: KitOriginalsViewModel
    let strings: Strings
    @Environment(\.locale) private var locale

    var body: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .empty:
            KitMessage(title: strings.kitOriginalsEmptyTitle, message: strings.kitOriginalsEmptyBody)
        case .failed:
            KitMessage(title: nil, message: strings.kitOriginalsLoadFailed, strings: strings) {
                Task { await model.refresh() }
            }
        case let .ready(groups):
            List {
                ForEach(groups) { group in
                    Section {
                        ForEach(group.originals) { original in
                            NavigationLink(value: KitOriginalRoute(id: original.id)) {
                                KitOriginalRow(original: original, model: model, strings: strings, locale: locale)
                            }
                        }
                    } header: {
                        Text(strings.label(group.kind)).scanFitText(.headline).textCase(nil)
                    }
                }
            }
            .listStyle(.plain)
        }
    }
}

private struct KitOriginalRow: View {
    let original: KitOriginal
    let model: KitOriginalsViewModel
    let strings: Strings
    let locale: Locale
    @State private var thumbnail: CGImage?

    var body: some View {
        HStack(spacing: ScanFitSpacing.md) {
            Group {
                if let thumbnail {
                    Image(decorative: thumbnail, scale: 1).resizable().scaledToFill()
                } else {
                    ScanFitColor.surfaceVariant
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text(strings.kitSavedOn(date: kitDay(original.createdAt, locale: locale))).scanFitText(.body)
                Text(strings.kitOriginalSize(width: original.width, height: original.height)).scanFitText(.caption)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
        }
        .frame(minHeight: 72, alignment: .leading)
        .accessibilityElement(children: .combine)
        .task(id: original.id) { thumbnail = await model.thumbnail(original.id) }
    }
}

/// My Kit's History view (ALGORITHMS 13.3): every verified save, newest first, shareable for 30 days.
struct KitHistoryList: View {
    @Bindable var model: KitHistoryViewModel
    let strings: Strings
    @Environment(\.locale) private var locale

    var body: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .empty:
            KitMessage(title: strings.kitHistoryEmptyTitle, message: strings.kitHistoryEmptyBody)
        case .failed:
            KitMessage(title: nil, message: strings.kitHistoryLoadFailed, strings: strings) {
                Task { await model.refresh() }
            }
        case let .ready(entries):
            List {
                if model.share == .failed {
                    NoticeCard(text: strings.exportShareFailed, kind: .error).listRowSeparator(.hidden)
                }
                ForEach(entries) { entry in row(entry) }
            }
            .listStyle(.plain)
        }
    }

    private func row(_ entry: HistoryEntry) -> some View {
        let record = entry.record
        let exam = record.examId == CustomSize.examId ? strings.kitHistoryCustom : record.examName
        let kb = (record.size + 512) / 1024
        return HStack(spacing: ScanFitSpacing.sm) {
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text(strings.label(record.docType)).scanFitText(.body)
                Text(exam).scanFitText(.caption)
                Text(strings.photoResult(kb: kb, width: record.width, height: record.height) + " · "
                    + kitDay(record.createdAt, locale: locale))
                    .scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                if !entry.shareable {
                    Text(strings.kitHistoryExpired).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            if entry.shareable {
                Button { Task { await model.share(entry.id) } } label: {
                    Image(systemName: "square.and.arrow.up").accessibilityLabel(strings.commonShare)
                }
                .buttonStyle(.borderless)
                .frame(minWidth: ScanFitSpacing.minTouchTarget, minHeight: ScanFitSpacing.minTouchTarget)
            }
        }
        .frame(minHeight: 72, alignment: .leading)
    }
}
