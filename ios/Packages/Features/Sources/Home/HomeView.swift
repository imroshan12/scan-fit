import DesignSystem
import ScanModel
import SwiftUI

/// Home tab content. The app target owns the `NavigationStack` and maps `ExamRoute` to the exam screen, so Home never
/// imports another feature (ARCHITECTURE §3).
public struct HomeView: View {
    @Bindable private var model: HomeViewModel
    private let strings: Strings

    public init(model: HomeViewModel, strings: Strings = Strings()) {
        self.model = model
        self.strings = strings
    }

    public var body: some View {
        List {
            Section {
                Text(strings.homeTitle)
                    .scanFitText(.display)
                    .foregroundStyle(ScanFitColor.onBackground)
                    .accessibilityAddTraits(.isHeader)
                categoryChips
            }
            .listRowSeparator(.hidden)
            content
        }
        .listStyle(.plain)
        .searchable(text: $model.query, prompt: strings.homeSearchHint(count: examCount))
        // Exam codes (IBPS, CGL) must reach the search as typed: no autocorrect, no auto-capitals.
        .autocorrectionDisabled()
        #if os(iOS)
            .textInputAutocapitalization(.never)
        #endif
        .navigationTitle(strings.tabHome)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .task { await model.onAppear() }
    }

    @ViewBuilder
    private var content: some View {
        if model.showsSections {
            if !model.pinned.isEmpty {
                Section(strings.homeSectionPinned) { rows(model.pinned, prefix: "pinned") }
            }
            if !model.popular.isEmpty {
                Section(strings.homeSectionPopular) { rows(model.popular, prefix: "popular") }
            }
            Section { status }.listRowSeparator(.hidden)
        } else if model.presets == .loading || model.presets == .failed {
            // Nothing to search until the signed presets are verified: say that, never "no matches".
            Section { status }.listRowSeparator(.hidden)
        } else if model.results.isEmpty {
            Text(strings.homeNoResults(query: model.query.trimmingCharacters(in: .whitespaces)))
                .scanFitText(.body)
                .foregroundStyle(ScanFitColor.onSurfaceVariant)
                .listRowSeparator(.hidden)
        } else {
            rows(model.results, prefix: "result")
        }
    }

    private func rows(_ exams: [Exam], prefix: String) -> some View {
        ForEach(exams, id: \.id) { exam in
            NavigationLink(value: ExamRoute(examId: exam.id)) {
                VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                    Text(exam.name).scanFitText(.headline)
                    HStack(spacing: ScanFitSpacing.sm) {
                        Text(strings.subtitle(body: exam.body, category: exam.category))
                            .scanFitText(.caption)
                            .foregroundStyle(ScanFitColor.onSurfaceVariant)
                        // Only the unverified state needs a badge in a list (CLAUDE.md rule 6).
                        if exam.confidence == .low { ConfidenceBadge(.low, strings: strings) }
                    }
                }
                .frame(minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
            }
            .id("\(prefix):\(exam.id)")
        }
    }

    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: ScanFitSpacing.sm) {
                chip(strings.homeCategoryAll, selected: model.category == nil) { model.select(nil) }
                ForEach(ExamCategory.allCases, id: \.self) { category in
                    chip(strings.label(category), selected: model.category == category) { model.select(category) }
                }
            }
        }
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .scanFitText(.label)
                .padding(.horizontal, ScanFitSpacing.md)
                .frame(minHeight: ScanFitSpacing.minTouchTarget)
                .foregroundStyle(selected ? ScanFitColor.onPrimaryContainer : ScanFitColor.onSurfaceVariant)
                .background(selected ? ScanFitColor.primaryContainer : ScanFitColor.surfaceVariant, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder
    private var status: some View {
        switch model.presets {
        case .loading:
            // Same card shape as the loaded state, so nothing jumps when the result arrives.
            HStack(spacing: ScanFitSpacing.sm) {
                ProgressView()
                Text(strings.homePresetsVerifying).scanFitText(.label)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
            .padding(ScanFitSpacing.lg)
            .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
            .background(ScanFitColor.surfaceVariant, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
            .accessibilityElement(children: .combine)
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

    private var examCount: Int {
        if case let .ready(summary) = model.presets { summary.examCount } else { 55 }
    }
}
