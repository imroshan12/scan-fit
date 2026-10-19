import DesignSystem
import ScanData
import ScanModel
import SwiftUI

/// My Kit's three views (ALGORITHMS 13), Prepared first.
public enum KitTab: Hashable, CaseIterable, Sendable { case prepared, originals, history }

/// Navigation value for one My Kit original (ALGORITHMS 13.2), pushed from the Originals view.
public struct KitOriginalRoute: Hashable, Sendable {
    public let id: String

    public init(id: String) {
        self.id = id
    }
}

public struct KitView: View {
    @Bindable private var model: KitViewModel
    @Bindable private var originals: KitOriginalsViewModel
    @Bindable private var history: KitHistoryViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var retry = 0
    /// Survives switching app tabs; a relaunch starts on Prepared.
    @State private var tab = KitTab.prepared
    @State private var confirmClear = false
    private let strings: Strings
    private let isActive: Bool

    private struct RefreshID: Equatable {
        let active: Bool
        let retry: Int
    }

    public init(
        model: KitViewModel,
        originals: KitOriginalsViewModel = KitOriginalsViewModel(kit: NoKitStore()),
        history: KitHistoryViewModel = KitHistoryViewModel(kit: NoKitStore()),
        strings: Strings = Strings(),
        isActive: Bool = true,
        tab: KitTab = .prepared
    ) {
        self.model = model
        self.originals = originals
        self.history = history
        self.strings = strings
        self.isActive = isActive
        _tab = State(initialValue: tab)
    }

    public var body: some View {
        VStack(spacing: 0) {
            Picker(strings.tabKit, selection: $tab) {
                Text(strings.kitTabPrepared).tag(KitTab.prepared)
                Text(strings.kitTabOriginals).tag(KitTab.originals)
                Text(strings.kitTabHistory).tag(KitTab.history)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, ScanFitSpacing.screenMargin)
            .padding(.vertical, ScanFitSpacing.sm)
            Group {
                switch tab {
                case .prepared: content
                case .originals: KitOriginalsList(model: originals, strings: strings)
                case .history: KitHistoryList(model: history, strings: strings)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(ScanFitColor.background)
        .navigationTitle(strings.tabKit)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .toolbar {
                if tab == .history, case .ready = history.state {
                    Button(strings.kitHistoryClear) { confirmClear = true }
                }
            }
            .confirmationDialog(strings.kitHistoryClearTitle, isPresented: $confirmClear, titleVisibility: .visible) {
                Button(strings.kitHistoryClear, role: .destructive) { Task { await history.clear() } }
                Button(strings.commonCancel, role: .cancel) {}
            } message: {
                Text(strings.kitHistoryClearBody)
            }
        #if os(iOS)
            .sheet(item: Binding(get: { history.share.file }, set: { if $0 == nil { history.shareHandled() } })) {
                ShareSheet(url: $0.url) { history.shareHandled() }
            }
        #endif
            .task(id: RefreshID(active: isActive, retry: retry)) {
                guard isActive else { return }
                await model.refresh()
                await model.observeDrafts()
            }
            .task(id: RefreshID(active: isActive, retry: retry)) {
                guard isActive else { return }
                await originals.refresh()
                await originals.observe()
            }
            .task(id: RefreshID(active: isActive, retry: retry)) {
                guard isActive else { return }
                await history.refresh()
                await history.observe()
            }
            .task(id: scenePhase) {
                guard isActive, scenePhase == .active else { return }
                await model.refresh()
                await originals.refresh()
                await history.refresh()
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .empty:
            VStack(spacing: ScanFitSpacing.md) {
                Image(systemName: "tray").font(.largeTitle)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant).accessibilityHidden(true)
                Text(strings.kitEmptyTitle).scanFitText(.title)
                Text(strings.kitEmptyBody).scanFitText(.body)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant).multilineTextAlignment(.center)
            }
            .padding(ScanFitSpacing.xl)
        case .failed:
            VStack(spacing: ScanFitSpacing.md) {
                Text(strings.kitLoadFailed).scanFitText(.body).multilineTextAlignment(.center)
                Button {
                    retry += 1
                } label: {
                    Label(strings.commonRetry, systemImage: "arrow.clockwise")
                }
                .frame(minHeight: ScanFitSpacing.minTouchTarget)
            }
            .padding(ScanFitSpacing.xl)
        case let .ready(groups):
            List {
                ForEach(groups) { group in
                    Section {
                        ForEach(group.rows) { row in
                            NavigationLink(value: row.route) {
                                VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                                    Label(strings.label(row.route.docType), systemImage: icon(row.route.docType))
                                        .scanFitText(.headline)
                                    Text(strings.reviewSize(kb: row.roundedKB)).scanFitText(.figure)
                                        .foregroundStyle(ScanFitColor.onSurfaceVariant)
                                }
                                .frame(minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
                                .accessibilityElement(children: .combine)
                            }
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                            Text(group.name).scanFitText(.headline).textCase(nil)
                            ConfidenceBadge(group.confidence, strings: strings)
                        }
                        .padding(.vertical, ScanFitSpacing.xs)
                    }
                }
            }
            .listStyle(.plain)
        }
    }

    private func icon(_ type: DocType) -> String {
        switch type {
        case .photo, .postcardPhoto: "photo"
        case .signature, .tripleSignature: "signature"
        case .handwrittenDeclaration: "doc.text"
        default: "hand.raised"
        }
    }
}
