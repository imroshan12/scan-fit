import DesignSystem
import ScanModel
import SwiftUI

public struct KitView: View {
    @Bindable private var model: KitViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var retry = 0
    private let strings: Strings
    private let isActive: Bool

    private struct RefreshID: Equatable {
        let active: Bool
        let retry: Int
    }

    public init(model: KitViewModel, strings: Strings = Strings(), isActive: Bool = true) {
        self.model = model
        self.strings = strings
        self.isActive = isActive
    }

    public var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(ScanFitColor.background)
            .navigationTitle(strings.tabKit)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .task(id: RefreshID(active: isActive, retry: retry)) {
                guard isActive else { return }
                await model.refresh()
                await model.observeDrafts()
            }
            .task(id: scenePhase) {
                if isActive, scenePhase == .active { await model.refresh() }
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
