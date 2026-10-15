import DesignSystem
import Foundation
import ScanData
import ScanModel
import SwiftUI

/// Exam checklist screen: header with the confidence badge and source, one row per document with its requirement
/// summary, and the "Before you upload" card. A row links to its flow (`FlowRoute`) when the app says the flow exists.
public struct ExamView: View {
    @State private var model: ExamViewModel
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    private let strings: Strings
    private let openable: Set<DocType>

    /// - Parameter openable: document types whose flow exists (the app decides: features never import each other).
    public init(model: ExamViewModel, strings: Strings = Strings(), openable: Set<DocType> = []) {
        _model = State(initialValue: model)
        self.strings = strings
        self.openable = openable
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
                        Button { model.togglePin() } label: {
                            Image(systemName: model.isPinned ? "heart.fill" : "heart")
                        }
                        .accessibilityLabel(model.isPinned ? strings.examUnpin : strings.examPin)
                    }
                }
            }
            .safeAreaInset(edge: .bottom) { saveAllBar }
        #if os(iOS)
            .sheet(item: Binding(
                get: { (model.exporter as? FilesExporter)?.document },
                set: { if $0 == nil { (model.exporter as? FilesExporter)?.presentationDismissed() } }
            )) { document in
                if let exporter = model.exporter as? FilesExporter {
                    FilesExportPicker(
                        urls: document.urls, onPicked: exporter.completed, onCancelled: exporter.cancelled
                    )
                        .interactiveDismissDisabled()
                }
            }
        #endif
            .task {
                await model.onAppear()
                await model.observeDrafts()
            }
            .task(id: scenePhase) {
                if scenePhase == .active { await model.onAppear() }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .notFound:
            Text(strings.examNotFound).scanFitText(.body).padding(ScanFitSpacing.screenMargin)
        case let .ready(exam):
            List {
                Section { header(exam) }
                if exam.livePhotoCapture == true {
                    Section {
                        Label(strings.examLivePhotoRow, systemImage: "person.crop.square")
                            .scanFitText(.body)
                            .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    }
                }
                Section(strings.examDocuments) {
                    ForEach(exam.documents) { doc in
                        if openable.contains(doc.type) {
                            NavigationLink(value: FlowRoute(examId: exam.id, docType: doc.type)) { docRow(doc) }
                        } else {
                            docRow(doc)
                        }
                    }
                }
                if !exam.specialRules.isEmpty {
                    Section(strings.examBeforeUpload) {
                        // Rules are preset data, shown exactly as written (presets are data, CLAUDE.md rule 5).
                        ForEach(exam.specialRules, id: \.self) { rule in
                            Text("• \(rule)").scanFitText(.body).foregroundStyle(ScanFitColor.onWarningContainer)
                        }
                    }
                    .listRowBackground(ScanFitColor.warningContainer)
                }
            }
        }
    }

    /// "Save all (N)" (UI_UX §3, ALGORITHMS 1.6.2), progress while the picker and checks run, then the result.
    @ViewBuilder
    private var saveAllBar: some View {
        let plan = model.saveAllPlan
        let status = model.saveAllStatus
        if case .ready = model.state, status != .idle || !plan.included.isEmpty || !plan.held.isEmpty {
            VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
                switch status {
                case .saving:
                    HStack(spacing: ScanFitSpacing.sm) {
                        ProgressView()
                        Text(strings.exportSaving).scanFitText(.body)
                    }
                    .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                case let .done(saved, total, folder):
                    NoticeCard(
                        text: saved == total
                            ? strings.flowSavedTo(count: saved, folder: folder ?? strings.exportFilesApp)
                            : strings.exportSaveAllPartial(saved: saved, total: total),
                        kind: saved == total ? .info : .error
                    )
                    HStack(spacing: ScanFitSpacing.md) {
                        if saved != total {
                            Button(strings.commonRetry) { Task { await model.saveAll() } }
                        }
                        Button(strings.commonClose) { model.saveAllDismissed() }
                    }
                case .idle:
                    if !plan.held.isEmpty {
                        Text(strings.exportSaveAllHeld).scanFitText(.caption)
                            .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    }
                    if !plan.included.isEmpty {
                        Button { Task { await model.saveAll() } } label: {
                            Text(strings.examSaveAll(count: plan.included.count))
                                .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
            .padding(ScanFitSpacing.screenMargin)
            .background(.bar)
        }
    }

    private func header(_ exam: Exam) -> some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            Text(exam.name).scanFitText(.title).accessibilityAddTraits(.isHeader)
            Text(strings.subtitle(body: exam.body, category: exam.category))
                .scanFitText(.caption)
                .foregroundStyle(ScanFitColor.onSurfaceVariant)
            ConfidenceBadge(exam.confidence, strings: strings)
            HStack(spacing: ScanFitSpacing.sm) {
                Text(strings.examVerifiedOn(date: Self.formatted(exam.lastVerified, locale: locale)))
                    .scanFitText(.caption)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant)
                if let url = (exam.sources.first { $0.kind == .official } ?? exam.sources.first)?.url {
                    Link(strings.examSource, destination: url).scanFitText(.label)
                }
            }
        }
        .padding(.vertical, ScanFitSpacing.xs)
    }

    private func docRow(_ doc: DocSpec) -> some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
            HStack(spacing: ScanFitSpacing.sm) {
                Text(strings.label(doc.type)).scanFitText(.headline)
                if !doc.required {
                    Text(strings.examOptional).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
            }
            Text(strings.specSummary(doc)).scanFitText(.figure)
            switch model.status(doc.type) {
            case .saved:
                Label(strings.examStatusSaved, systemImage: "checkmark.circle.fill")
                    .scanFitText(.caption).foregroundStyle(ScanFitColor.success)
            case let .ready(kb):
                Label(strings.draftReadySize(kb: kb), systemImage: "checkmark.circle")
                    .scanFitText(.caption).foregroundStyle(ScanFitColor.success)
            case .notStarted:
                Text(strings.examStatusNotStarted).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
        }
        .frame(minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        if case let .ready(exam) = model.state { exam.name } else { "" }
    }

    /// `2026-09-30` in the view's locale ("30 Sept 2026", "30 सित॰ 2026"). Built from date components, so no time
    /// zone can shift the day.
    static func formatted(_ iso: String, locale: Locale = .current) -> String {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3,
              let date = Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
        else { return iso }
        return date.formatted(.dateTime.day().month(.abbreviated).year().locale(locale))
    }
}
