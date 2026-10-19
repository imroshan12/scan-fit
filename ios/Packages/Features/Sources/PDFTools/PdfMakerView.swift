import DesignSystem
import Imaging
import PDF
import PhotosUI
import ScanData
import ScanModel
import SwiftUI
import UniformTypeIdentifiers

/// The PDF maker (ALGORITHMS 6, UI_UX §3): files (or the ID card's two sides), options, Make PDF, then the result with
/// Save and Share. One screen for the four tools and an exam's PDF slot.
public struct PdfMakerView: View {
    @State var model: PdfMakerViewModel
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showCamera = false
    /// The ID card side a picker or the camera fills.
    @State private var side: Int?
    let strings: Strings
    private let onDone: () -> Void

    public init(model: PdfMakerViewModel, strings: Strings = Strings(), onDone: @escaping () -> Void = {}) {
        _model = State(initialValue: model)
        self.strings = strings
        self.onDone = onDone
    }

    public var body: some View {
        Group {
            if let crop = model.crop {
                VStack(alignment: .leading, spacing: ScanFitSpacing.md) {
                    FreeCropEditor(
                        raster: crop.image, rect: crop.rect, description: strings.inkCropHint,
                        onMove: { model.moveCrop(dx: $0, dy: $1) },
                        onResize: { model.resizeCrop($0, dx: $1, dy: $2) }
                    )
                    Text(strings.inkCropHint).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
                .padding(ScanFitSpacing.screenMargin)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                        PdfIntro(model: model, strings: strings)
                        if !model.notFound {
                            PdfFilesSection(model: model, strings: strings, choose: choose)
                            PdfOptionsSection(model: model, strings: strings)
                            PdfResultSection(model: model, strings: strings)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(ScanFitSpacing.screenMargin)
                }
            }
        }
        .navigationTitle(title)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .safeAreaInset(edge: .bottom) { bottomBar }
            .onDisappear { model.cancelMaking() }
            .photosPicker(isPresented: $showPhotos, selection: $photoItems,
                          maxSelectionCount: single ? 1 : PdfFit.maxPages, matching: .images)
            .onChange(of: photoItems) { _, items in
                guard !items.isEmpty else { return }
                photoItems = []
                let side = self.side
                Task {
                    var files: [[UInt8]?] = []
                    for item in items {
                        files.append((try? await item.loadTransferable(type: Data.self)).map { [UInt8]($0) })
                    }
                    await model.add(files, side: side)
                }
            }
            // Files (iCloud Drive, Downloads, USB...): read off the main actor, inside the security scope.
            .fileImporter(
                isPresented: $showFiles, allowedContentTypes: acceptedTypes, allowsMultipleSelection: !single
            ) { result in
                guard case let .success(urls) = result, !urls.isEmpty else { return }
                let side = self.side
                Task {
                    let files = await Task.detached(priority: .userInitiated) { urls.map(Self.read) }.value
                    await model.add(files, side: side)
                }
            }
        #if os(iOS)
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { bytes in
                    showCamera = false
                    guard let bytes else { return }
                    let side = self.side
                    Task { await model.add([bytes], side: side) }
                }
                .ignoresSafeArea()
            }
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

    private var single: Bool { model.tool == .compress || model.tool == .idCard }

    private var acceptedTypes: [UTType] {
        switch model.tool {
        case .images, .idCard: [.image]
        case .compress, .merge: [.pdf]
        case .exam: [.image, .pdf]
        }
    }

    private var title: String {
        switch model.tool {
        case .images: strings.toolsPdfImages
        case .compress: strings.toolsPdfCompress
        case .merge: strings.toolsPdfMerge
        case .idCard: strings.toolsPdfIdCard
        case .exam: model.target.map { strings.label($0.spec.type) } ?? strings.toolsPdf
        }
    }

    /// Where a file comes from, and for an ID card which side it fills.
    enum Source { case photos, files, camera }

    private func choose(_ source: Source, side: Int?) {
        self.side = side
        switch source {
        case .photos: showPhotos = true
        case .files: showFiles = true
        case .camera: showCamera = true
        }
    }

    /// Crop: Done and Reset. Making: progress. Result: Save (Done after a verified save) and Share. Otherwise Make PDF.
    @ViewBuilder
    private var bottomBar: some View {
        VStack(spacing: ScanFitSpacing.sm) {
            if model.crop != nil {
                wide(strings.commonDone, prominent: true) { Task { await model.finishCrop() } }
                HStack {
                    Button(strings.commonCancel) { model.cancelCrop() }
                    Spacer()
                    Button(strings.photoReset) { model.resetCrop() }
                }
            } else if let making = model.making {
                switch making {
                case .preparing: Text(strings.pdfPreparing).scanFitText(.body)
                case let .fitting(page, total): Text(strings.pdfMaking(page: page + 1, total: total)).scanFitText(.body)
                }
                ProgressView().frame(maxWidth: .infinity)
            } else if case .ready = model.result {
                let saved = model.exportState == .saved
                Button {
                    if saved { onDone() } else { Task { await model.save() } }
                } label: {
                    Group {
                        if model.exportState == .saving { ProgressView() } else {
                            Text(saved ? strings.commonDone : strings.flowSaveFiles)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!saved && !model.canSave)
                Button(strings.commonShare) { Task { await model.sharePdf() } }
                    .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                    .disabled(!model.canSave)
            } else if !model.notFound {
                if let hint = missingHint {
                    Text(hint).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
                wide(strings.pdfMake, prominent: true) { model.make() }
                    .disabled(!model.canMake)
            }
        }
        .padding(ScanFitSpacing.screenMargin)
        .background(.bar)
    }

    private var missingHint: String? {
        switch model.missing {
        case .twoPdfs: strings.pdfNeedTwo
        case .bothSides: strings.pdfNeedBothSides
        case .maxKb: strings.pdfMaxKbError
        default: nil
        }
    }

    private func wide(_ title: String, prominent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
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
