import DesignSystem
import Match
import ScanData
import ScanModel
import SwiftUI

/// One My Kit original (ALGORITHMS 13.1, 13.2): its preview, "Use for exam…" and Delete.
public struct KitOriginalView: View {
    @State private var model: KitOriginalViewModel
    @State private var choosing = false
    @State private var confirmDelete = false
    @Environment(\.locale) private var locale
    private let strings: Strings
    private let onUse: (FlowRoute) -> Void
    private let onLeave: () -> Void

    /// `onUse` opens the slot's flow starting from this original; `onLeave` pops this screen after a delete.
    public init(
        model: KitOriginalViewModel, strings: Strings = Strings(),
        onUse: @escaping (FlowRoute) -> Void, onLeave: @escaping () -> Void
    ) {
        _model = State(initialValue: model)
        self.strings = strings
        self.onUse = onUse
        self.onLeave = onLeave
    }

    public var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(ScanFitColor.background)
            .navigationTitle(title)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .toolbar {
                if case .ready = model.state {
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label(strings.kitDelete, systemImage: "trash")
                    }
                }
            }
            .confirmationDialog(strings.kitDeleteTitle, isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(strings.kitDelete, role: .destructive) { Task { await model.delete() } }
                Button(strings.commonCancel, role: .cancel) {}
            } message: {
                Text(strings.kitDeleteBody)
            }
            .sheet(isPresented: $choosing) { targets }
            .task { await model.onAppear() }
            .onChange(of: model.state) { _, state in if state == .deleted { onLeave() } }
    }

    private var title: String {
        if case let .ready(original, _) = model.state { strings.label(original.kind) } else { "" }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading, .deleted:
            ProgressView()
        case .missing:
            KitMessage(title: nil, message: strings.kitOriginalMissing)
        case let .ready(original, preview):
            ScrollView {
                VStack(spacing: ScanFitSpacing.md) {
                    if let preview {
                        Image(decorative: preview, scale: 1).resizable().scaledToFit().frame(maxHeight: 320)
                    }
                    Text(strings.kitSavedOn(date: kitDay(original.createdAt, locale: locale))).scanFitText(.body)
                    Text(strings.kitOriginalSize(width: original.width, height: original.height))
                        .scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
                .padding(ScanFitSpacing.screenMargin)
            }
            .safeAreaInset(edge: .bottom) {
                Button { choosing = true } label: {
                    Text(strings.kitUseForExam).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                }
                .buttonStyle(.borderedProminent)
                .padding(ScanFitSpacing.screenMargin)
                .background(.bar)
            }
        }
    }

    /// "Use for exam…" (ALGORITHMS 13.2): the Checker's list for the original's kind, with search.
    private var targets: some View {
        NavigationStack {
            List(model.targets, id: \.self) { target in
                Button {
                    choosing = false
                    onUse(FlowRoute(examId: target.exam.id, docType: target.slot.type, original: model.id))
                } label: {
                    CheckerTargetRow(target: target, strings: strings)
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
            .searchable(text: $model.query, prompt: strings.checkerSearch)
            .navigationTitle(strings.kitUseTitle)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(strings.commonCancel) { choosing = false } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
