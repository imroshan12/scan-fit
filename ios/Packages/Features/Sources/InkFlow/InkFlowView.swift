import DesignSystem
import PhotosUI
import ScanData
import ScanModel
import SwiftUI
import UniformTypeIdentifiers

/// The ink flow (UI_UX §3 Capture/crop, Review) for signatures, thumbs, fingers and declarations: one step at a time,
/// with the primary action pinned to the bottom.
public struct InkFlowView: View {
    @State private var model: InkFlowViewModel
    @State private var pickedItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showFiles = false
    @Environment(\.scenePhase) private var scenePhase
    private let strings: Strings
    /// Leaves the flow (Done, or back from the first step); the app pops its navigation path.
    private let onExit: () -> Void

    public init(model: InkFlowViewModel, strings: Strings = Strings(), onExit: @escaping () -> Void) {
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
            .task {
                await model.onAppear()
                await model.observeDrafts()
            }
            .task(id: scenePhase) {
                if scenePhase == .active { await model.onForeground() }
            }
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
                get: { (model.exporter as? FilesExporter)?.document },
                set: { if $0 == nil { (model.exporter as? FilesExporter)?.presentationDismissed() } }
            )) { document in
                if let exporter = model.exporter as? FilesExporter {
                    FilesExportPicker(url: document.url, onPicked: exporter.completed, onCancelled: exporter.cancelled)
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
        case .loading, .opening:
            ProgressView()
        case .notFound:
            Text(strings.examNotFound).scanFitText(.body).padding(ScanFitSpacing.screenMargin)
        case let .pickSource(slot, openFailed):
            pickSource(slot, openFailed)
        case let .crop(crop):
            InkCropView(crop: crop, model: model, strings: strings)
        case let .review(review):
            InkReviewView(review: review, model: model, strings: strings)
        }
    }

    private func pickSource(_ slot: InkSlot, _ openFailed: Bool) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                Text(strings.inkSourceHeading).scanFitText(.title).accessibilityAddTraits(.isHeader)
                Text(strings.specSummary(slot.spec)).scanFitText(.figure)
                if slot.kind == .declaration {
                    // The text to copy by hand comes from the preset; without it, copy it from the notice (§3).
                    Text(strings.inkDeclarationHeading).scanFitText(.headline)
                    NoticeCard(text: slot.spec.declarationText ?? strings.flowDeclarationCopyFromNotice, kind: .info)
                }
                NoticeCard(text: strings.flowTipPaper, kind: .info)
                if openFailed {
                    NoticeCard(text: strings.photoOpenFailed, kind: .error)
                }
                if Self.hasCamera {
                    Button { showCamera = true } label: {
                        Text(strings.photoTake).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                    }
                    .buttonStyle(.borderedProminent)
                }
                PhotosPicker(selection: $pickedItem, matching: .images) {
                    Text(openFailed ? strings.photoChooseAnother : strings.photoChoose)
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
        case .crop:
            primary(strings.commonContinue, busy: false) { Task { await model.cropDone() } }
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
