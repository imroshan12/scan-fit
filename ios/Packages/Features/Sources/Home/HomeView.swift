import DesignSystem
import SwiftUI

public struct HomeView: View {
    @State private var model: HomeViewModel
    private let strings: Strings

    public init(model: HomeViewModel, strings: Strings = Strings()) {
        _model = State(initialValue: model)
        self.strings = strings
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                    Text(strings.homeTitle)
                        .scanFitText(.display)
                        .foregroundStyle(ScanFitColor.onBackground)
                        .accessibilityAddTraits(.isHeader)
                    searchField
                    status
                }
                .padding(ScanFitSpacing.screenMargin)
            }
            .background(ScanFitColor.background)
            .navigationTitle(strings.tabHome)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
        .task { await model.onAppear() }
    }

    /// Search is a Phase 2 feature: this is the visual placeholder, deliberately not interactive yet.
    private var searchField: some View {
        HStack(spacing: ScanFitSpacing.sm) {
            Image(systemName: "magnifyingglass").accessibilityHidden(true)
            Text(strings.homeSearchHint(count: examCount ?? 55))
                .scanFitText(.body)
                .multilineTextAlignment(.leading)
            Spacer(minLength: 0)
        }
        .foregroundStyle(ScanFitColor.onSurfaceVariant)
        .padding(ScanFitSpacing.lg)
        .frame(minHeight: ScanFitSpacing.minTouchTarget)
        .background(ScanFitColor.surfaceVariant, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
        .accessibilityElement(children: .combine)
        .accessibilityHint(strings.commonComingSoon)
    }

    @ViewBuilder
    private var status: some View {
        switch model.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity)
        case let .ready(summary):
            HStack(spacing: ScanFitSpacing.sm) {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundStyle(ScanFitColor.success)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                    Text(strings.homePresetsVerified).scanFitText(.label)
                    Text(strings.homePresetsStatus(count: summary.examCount, version: String(summary.version)))
                        .scanFitText(.figure)
                }
                .foregroundStyle(ScanFitColor.onSuccessContainer)
            }
            .padding(ScanFitSpacing.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(ScanFitColor.successContainer, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
            .accessibilityElement(children: .combine)
        case .failed:
            HStack(spacing: ScanFitSpacing.sm) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(ScanFitColor.error)
                    .accessibilityHidden(true)
                Text(strings.homePresetsUnverified).scanFitText(.label)
                    .foregroundStyle(ScanFitColor.onErrorContainer)
            }
            .padding(ScanFitSpacing.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(ScanFitColor.errorContainer, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
            .accessibilityElement(children: .combine)
        }
    }

    private var examCount: Int? {
        if case let .ready(summary) = model.state { summary.examCount } else { nil }
    }
}
