import DesignSystem
import PhotosUI
import ScanModel
import SwiftUI
import UniformTypeIdentifiers

/// The photo flow (UI_UX §3 Capture/crop, Review): one step at a time, with the primary action pinned to the bottom.
public struct PhotoFlowView: View {
    @State private var model: PhotoFlowViewModel
    @State private var pickedItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showFiles = false
    private let strings: Strings
    /// Leaves the flow (Done, or back from the first step). The app pops its navigation path: `@Environment(\.dismiss)`
    /// is not used because, after the camera's full-screen cover or a picker, it can target that presentation instead.
    private let onExit: () -> Void

    public init(model: PhotoFlowViewModel, strings: Strings = Strings(), onExit: @escaping () -> Void) {
        _model = State(initialValue: model)
        self.strings = strings
        self.onExit = onExit
    }

    public var body: some View {
        content
            .navigationTitle(model.state.slot.map { strings.label($0.spec.type) } ?? "")
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        if !model.back() { onExit() }
                    } label: {
                        Label(strings.commonBack, systemImage: "chevron.backward")
                    }
                    .disabled(model.exportState == .saving)
                }
            }
            .safeAreaInset(edge: .bottom) { bottomAction }
            .task { await model.onAppear() }
            .onChange(of: pickedItem) { _, item in
                guard let item else { return }
                pickedItem = nil
                Task {
                    let data = try? await item.loadTransferable(type: Data.self)
                    await model.imageSelected(data.map { [UInt8]($0) })
                }
            }
            // Files (iCloud Drive, Downloads, USB...): read once, off the main actor, inside the security scope.
            .fileImporter(isPresented: $showFiles, allowedContentTypes: [.image]) { result in
                guard case let .success(url) = result else { return }
                Task {
                    let bytes = await Task.detached(priority: .userInitiated) { Self.read(url) }.value
                    await model.imageSelected(bytes)
                }
            }
        #if os(iOS)
            .sheet(item: Binding(
                get: { (model.exporter as? FilesPhotoExporter)?.document },
                set: { if $0 == nil { (model.exporter as? FilesPhotoExporter)?.cancelled() } }
            )) { document in
                if let exporter = model.exporter as? FilesPhotoExporter {
                    FilesExportPicker(url: document.url, exporter: exporter)
                        .interactiveDismissDisabled()
                }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { bytes in
                    showCamera = false
                    if let bytes { Task { await model.imageSelected(bytes) } }
                }
                .ignoresSafeArea()
            }
        #endif
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
        case .notFound:
            Text(strings.examNotFound).scanFitText(.body).padding(ScanFitSpacing.screenMargin)
        case let .pickSource(slot, problem):
            pickSource(slot, problem)
        case .findingFace:
            VStack(spacing: ScanFitSpacing.md) {
                ProgressView()
                Text(strings.photoFindingFace).scanFitText(.body)
            }
        case let .crop(crop):
            PhotoCropView(crop: crop, model: model, strings: strings)
        case let .review(review):
            PhotoReviewView(review: review, model: model, strings: strings)
        }
    }

    private func pickSource(_ slot: PhotoSlot, _ problem: SourceProblem?) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                Text(strings.photoSourceHeading).scanFitText(.title).accessibilityAddTraits(.isHeader)
                Text(strings.specSummary(slot.spec)).scanFitText(.figure)
                PhotoNotice(text: strings.photoTips, kind: .info)
                if let problem {
                    PhotoNotice(text: message(problem), kind: .error)
                }
                if Self.hasCamera {
                    Button { showCamera = true } label: {
                        Text(strings.photoTake).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                    }
                    .buttonStyle(.borderedProminent)
                }
                PhotosPicker(selection: $pickedItem, matching: .images) {
                    Text(problem == nil ? strings.photoChoose : strings.photoChooseAnother)
                        .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                }
                .buttonStyle(.bordered)
                Button { showFiles = true } label: {
                    Text(strings.photoChooseFile).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                }
                .buttonStyle(.bordered)
            }
            .padding(ScanFitSpacing.screenMargin)
        }
    }

    @ViewBuilder
    private var bottomAction: some View {
        switch model.state {
        case let .crop(crop):
            primary(strings.commonContinue, busy: crop.checking) { Task { await model.cropDone() } }
        case .review:
            if model.exportState == .saved {
                primary(strings.commonDone, busy: false) { onExit() }
            } else {
                primary(strings.flowSaveFiles, busy: model.exportState == .saving) {
                    Task { await model.save() }
                }
                .disabled(!model.canSave)
            }
        default:
            EmptyView()
        }
    }

    private func primary(_ title: String, busy: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if busy { ProgressView() } else { Text(title) }
            }
            .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .disabled(busy)
        .padding(ScanFitSpacing.screenMargin)
        .background(.bar)
    }

    private func message(_ problem: SourceProblem) -> String {
        switch problem {
        case .openFailed: strings.photoOpenFailed
        case .noFace: strings.errorFaceNone
        case .failed: strings.errorGeneric
        }
    }

    /// The file's bytes, or nil when it cannot be read (no access, gone, or larger than any photo should be).
    private nonisolated static func read(_ url: URL) -> [UInt8]? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe), data.count <= 64 * 1024 * 1024 else {
            return nil
        }
        return [UInt8](data)
    }

    private static var hasCamera: Bool {
        #if os(iOS)
            CameraPicker.isAvailable
        #else
            false
        #endif
    }
}

/// A message card: text on a tinted background (the text carries the meaning, not the colour).
struct PhotoNotice: View {
    enum Kind { case info, warning, error }

    let text: String
    let kind: Kind

    var body: some View {
        let (background, foreground): (Color, Color) = switch kind {
        case .info: (ScanFitColor.surfaceVariant, ScanFitColor.onSurfaceVariant)
        case .warning: (ScanFitColor.warningContainer, ScanFitColor.onWarningContainer)
        case .error: (ScanFitColor.errorContainer, ScanFitColor.onErrorContainer)
        }
        Text(text)
            .scanFitText(.body)
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(ScanFitSpacing.lg)
            .background(background, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
    }
}
