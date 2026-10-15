import DesignSystem
import Imaging
import Inspect
import Match
import PhotosUI
import ScanData
import ScanModel
import SwiftUI
import UniformTypeIdentifiers

/// The pre-submit Checker (ALGORITHMS 12, UI_UX §3): the file's own problems, what it is, which exam, and on request a
/// fitted copy with Save and Share. The original file is never modified.
public struct CheckerView: View {
    @State private var model: CheckerViewModel
    @State private var pickedItem: PhotosPickerItem?
    @State private var showFiles = false
    private let strings: Strings

    public init(model: CheckerViewModel, strings: Strings = Strings()) {
        _model = State(initialValue: model)
        self.strings = strings
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                if model.opening {
                    ProgressView().frame(maxWidth: .infinity)
                } else if let file = model.file {
                    fileSection(file)
                    kindSection
                    if model.kind != nil { CheckerTargetSection(model: model, strings: strings) }
                } else {
                    pickSection
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(ScanFitSpacing.screenMargin)
        }
        .navigationTitle(strings.checkerTitle)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .safeAreaInset(edge: .bottom) { fixBar }
            .onChange(of: pickedItem) { _, item in
                guard let item else { return }
                pickedItem = nil
                Task {
                    let data = try? await item.loadTransferable(type: Data.self)
                    await model.fileSelected(data.map { [UInt8]($0) }, name: "")
                }
            }
            // Files (iCloud Drive, Downloads, USB...): read once, off the main actor, inside the security scope.
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.image, .pdf]) { result in
                guard case let .success(url) = result else { return }
                Task {
                    let bytes = await Task.detached(priority: .userInitiated) { Self.read(url) }.value
                    await model.fileSelected(bytes, name: url.lastPathComponent)
                }
            }
        #if os(iOS)
            .sheet(item: Binding(
                get: { (model.exporter as? FilesExporter)?.document },
                set: { if $0 == nil { (model.exporter as? FilesExporter)?.presentationDismissed() } }
            )) { document in
                if let exporter = model.exporter as? FilesExporter {
                    FilesExportPicker(url: document.url, onPicked: exporter.completed, onCancelled: exporter.cancelled)
                        .interactiveDismissDisabled()
                }
            }
            .sheet(item: Binding(get: { model.share.file }, set: { if $0 == nil { model.shareHandled() } })) { file in
                ShareSheet(url: file.url) { model.shareHandled() }
            }
        #endif
    }

    private var pickSection: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
            Text(strings.checkerIntro).scanFitText(.body)
            if model.openFailed { NoticeCard(text: strings.checkerOpenFailed, kind: .error) }
            PhotosPicker(selection: $pickedItem, matching: .images) {
                Text(strings.photoChoose).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
            }
            .buttonStyle(.borderedProminent)
            Button { showFiles = true } label: {
                Text(strings.photoChooseFile).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
            }
            .buttonStyle(.bordered)
        }
    }

    /// The file's facts and its own problems (ALGORITHMS 5): shown before any exam is chosen.
    private func fileSection(_ file: CheckedFile) -> some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            Text(strings.checkerFileHeading).scanFitText(.headline).accessibilityAddTraits(.isHeader)
            let dims = file.inspected.width.flatMap { w in file.inspected.height.map { " · \(w)×\($0)" } } ?? ""
            Text(strings.checkerFacts(format: Self.formatName(file), kb: file.kb) + dims).scanFitText(.figure)
            if file.inspected.issues.isEmpty {
                Text(strings.checkerNoIssues).scanFitText(.body).foregroundStyle(ScanFitColor.onSurfaceVariant)
            } else {
                ForEach(file.inspected.issues, id: \.self) {
                    IssueRow(issue: $0, file: file, slot: nil, strings: strings)
                }
            }
            Button(strings.checkerChooseAnother) { showFiles = true }
        }
    }

    /// What the file is (ALGORITHMS 12): the user picks, the app never guesses.
    private var kindSection: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            Text(strings.checkerWhat).scanFitText(.headline).accessibilityAddTraits(.isHeader)
            CheckerKindChips(selected: model.kind, strings: strings) { kind in Task { await model.choose(kind: kind) } }
        }
    }

    /// Save and Share for a fitted copy; Done after a verified save goes back to the exam list.
    @ViewBuilder
    private var fixBar: some View {
        if case .ready = model.fix {
            VStack(spacing: 0) {
                Button {
                    if model.exportState == .saved { model.changeExam() } else { Task { await model.save() } }
                } label: {
                    Group {
                        if model.exportState == .saving { ProgressView() } else {
                            Text(model.exportState == .saved ? strings.commonDone : strings.flowSaveFiles)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.exportState != .saved && !model.canSave)
                Button(strings.commonShare) { Task { await model.shareCopy() } }
                    .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                    .disabled(!model.canSave)
            }
            .padding(ScanFitSpacing.screenMargin)
            .background(.bar)
        }
    }

    static func formatName(_ file: CheckedFile) -> String {
        switch file.inspected.format {
        case .jpeg: "JPG"
        case .unknown: "?"
        default: file.inspected.format.rawValue.uppercased()
        }
    }

    /// The file's bytes, or nil when it cannot be read (no access, gone, or larger than any upload should be).
    private nonisolated static func read(_ url: URL) -> [UInt8]? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe), data.count <= 64 * 1024 * 1024 else {
            return nil
        }
        return [UInt8](data)
    }
}

/// One problem, with its numbers when the slot gives them (icon + text, never colour alone).
struct IssueRow: View {
    let issue: Issue
    let file: CheckedFile
    let slot: DocSpec?
    let strings: Strings

    var body: some View {
        Label {
            Text(text).scanFitText(.body)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(ScanFitColor.warning)
        }
    }

    private var text: String {
        let w = file.inspected.width ?? 0, h = file.inspected.height ?? 0
        switch issue {
        case .extensionMismatch: return strings.issueExtensionMismatch
        case .cmykColor: return strings.issueCmykColor
        case .progressiveJpeg: return strings.issueProgressiveJpeg
        case .hasGpsExif: return strings.issueHasGpsExif
        case .rotatedByExif: return strings.issueRotatedByExif
        case .heicNotAccepted: return strings.issueHeicNotAccepted
        case .pdfEncrypted: return strings.issuePdfEncrypted
        case .tooSmallKb: return strings.issueTooSmallKb(kb: file.kb, minKb: Int((slot?.sizeKb.min ?? 0).rounded()))
        case .tooLargeKb: return strings.issueTooLargeKb(kb: file.kb, maxKb: Int(slot?.sizeKb.max ?? 0))
        case .wrongDimensions:
            if let tw = slot?.dimensions.width, let th = slot?.dimensions.height {
                return strings.issueWrongDimensions(w: w, h: h, targetW: tw, targetH: th)
            }
            return strings.issueWrongAspect
        case .wrongAspect: return strings.issueWrongAspect
        case .lowDpiMetadata: return strings.issueLowDpiMetadata(dpi: slot?.dpi ?? 200)
        case .grayscaleNotAllowed: return strings.issueGrayscaleNotAllowed
        }
    }
}
