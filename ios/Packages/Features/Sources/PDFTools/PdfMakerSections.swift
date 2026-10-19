import DesignSystem
import Imaging
import PDF
import ScanModel
import SwiftUI

/// The exam slot (name, window) or the tool's intro.
struct PdfIntro: View {
    let model: PdfMakerViewModel
    let strings: Strings

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
            if model.tool == .exam, let target = model.target {
                Text(target.examName).scanFitText(.headline)
                Text(strings.specSummary(target.spec)).scanFitText(.figure)
            }
            Text(intro).scanFitText(.body)
            if model.notFound { NoticeCard(text: strings.examNotFound, kind: .error) }
        }
    }

    private var intro: String {
        switch model.tool {
        case .images: strings.pdfIntroImages
        case .compress: strings.pdfIntroCompress
        case .merge: strings.pdfIntroMerge
        case .idCard: strings.pdfIntroIdCard
        case .exam: strings.pdfIntroExam
        }
    }
}

/// The files (or the ID card's two sides) with their actions, then the add buttons.
struct PdfFilesSection: View {
    let model: PdfMakerViewModel
    let strings: Strings
    let choose: (PdfMakerView.Source, Int?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            let documents = model.tool == .compress || model.tool == .merge
            Text(documents ? strings.pdfFilesHeading : strings.pdfPagesHeading)
                .scanFitText(.headline).accessibilityAddTraits(.isHeader)
            if model.tool == .idCard {
                ForEach([0, 1], id: \.self) { side in
                    Text(side == 0 ? strings.pdfFront : strings.pdfBack).scanFitText(.label)
                    if side < model.items.count {
                        PdfItemRow(model: model, item: model.items[side], index: side, strings: strings,
                                   label: side == 0 ? strings.pdfFront : strings.pdfBack)
                    } else if side <= model.items.count {
                        addButtons(side: side)
                    }
                }
            } else {
                if model.items.isEmpty {
                    Text(strings.pdfEmpty).scanFitText(.body).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
                ForEach(Array(model.items.enumerated()), id: \.element.id) { index, item in
                    PdfItemRow(model: model, item: item, index: index, strings: strings, label: nil)
                }
                addButtons(side: nil)
            }
            if model.adding { ProgressView() }
            if let failure = model.addFailure {
                NoticeCard(text: text(failure), kind: .error)
            }
        }
    }

    private func text(_ failure: AddFailure) -> String {
        switch failure {
        case .unreadable: strings.pdfErrorUnreadable
        case .encrypted: strings.pdfErrorEncrypted
        case .tooManyPages: strings.pdfErrorTooMany
        }
    }

    private func addButtons(side: Int?) -> some View {
        let pictures = model.tool != .compress && model.tool != .merge
        let files = switch model.tool {
        case .compress: model.items.isEmpty ? strings.pdfChoosePdf : strings.pdfReplace
        case .merge: strings.pdfAddPdf
        default: strings.pdfAddFiles
        }
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: ScanFitSpacing.sm) { buttons(pictures: pictures, files: files, side: side) }
            VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
                buttons(pictures: pictures, files: files, side: side)
            }
        }
        .disabled(model.busy)
    }

    @ViewBuilder
    private func buttons(pictures: Bool, files: String, side: Int?) -> some View {
        if pictures {
            Button(strings.pdfAddGallery) { choose(.photos, side) }.buttonStyle(.borderedProminent)
        }
        Button(files) { choose(.files, side) }.buttonStyle(.bordered)
        #if os(iOS)
            if pictures, CameraPicker.isAvailable {
                Button(strings.pdfAddCamera) { choose(.camera, side) }.buttonStyle(.bordered)
            }
        #endif
    }
}

/// A file: thumbnail, what it is, and its actions (reorder, turn, crop, remove).
struct PdfItemRow: View {
    let model: PdfMakerViewModel
    let item: PdfItem
    let index: Int
    let strings: Strings
    let label: String?

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
            HStack(spacing: ScanFitSpacing.md) {
                RasterImage(raster: item.thumbnail).frame(width: 56, height: 56)
                VStack(alignment: .leading) {
                    Text(label ?? (item.document ? "PDF" : strings.pdfImageLabel(number: index + 1))).scanFitText(.body)
                    Text("\(strings.pdfPages(count: item.pages)) · \(strings.pdfResultKb(kb: item.kb))")
                        .scanFitText(.figure)
                }
                Spacer(minLength: 0)
                if model.tool != .idCard, model.tool != .compress {
                    Button { model.move(item.id, by: -1) } label: { Image(systemName: "chevron.up") }
                        .accessibilityLabel(strings.pdfMoveUp)
                        .disabled(index == 0)
                    Button { model.move(item.id, by: 1) } label: { Image(systemName: "chevron.down") }
                        .accessibilityLabel(strings.pdfMoveDown)
                        .disabled(index == model.items.count - 1)
                }
                Button(role: .destructive) { model.remove(item.id) } label: { Image(systemName: "trash") }
                    .accessibilityLabel(strings.pdfRemove)
            }
            .buttonStyle(.borderless)
            if !item.document {
                HStack(spacing: ScanFitSpacing.md) {
                    Button(strings.pdfCrop) { Task { await model.startCrop(item.id) } }
                    Button(strings.pdfRotate) { model.turn(item.id) }
                }
                .buttonStyle(.borderless)
            }
        }
        .disabled(model.busy)
        .frame(minHeight: ScanFitSpacing.minTouchTarget)
    }
}

/// Clean up (pictures only), Black and white, and Max KB for the tools (ALGORITHMS 6.1).
struct PdfOptionsSection: View {
    let model: PdfMakerViewModel
    let strings: Strings

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            Text(strings.pdfOptions).scanFitText(.headline).accessibilityAddTraits(.isHeader)
            if model.hasPictures || model.tool == .images || model.tool == .idCard {
                toggle(strings.pdfCleanup, strings.pdfCleanupHint, model.options.cleanup, cleanup: true)
            }
            toggle(strings.pdfGrayscale, strings.pdfGrayscaleHint, model.options.grayscale, cleanup: false)
            if model.tool != .exam {
                Text(strings.pdfMaxKb).scanFitText(.label)
                TextField(strings.pdfMaxKb, text: Binding(get: { model.maxKbText }, set: { model.setMaxKb($0) }))
                    .textFieldStyle(.roundedBorder)
                #if os(iOS)
                    .keyboardType(.numberPad)
                #endif
                let invalid = !model.maxKbText.isEmpty && model.maxKb == nil
                Text(invalid ? strings.pdfMaxKbError : strings.pdfMaxKbHint)
                    .scanFitText(.caption)
                    .foregroundStyle(invalid ? ScanFitColor.error : ScanFitColor.onSurfaceVariant)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: ScanFitSpacing.sm) { chips }
                    VStack(alignment: .leading, spacing: ScanFitSpacing.sm) { chips }
                }
            }
        }
        .disabled(model.busy)
    }

    @ViewBuilder
    private var chips: some View {
        ForEach(PdfMakerViewModel.quickMaxKb, id: \.self) { kb in
            let selected = model.maxKb == kb
            Button { model.setMaxKb(String(kb)) } label: {
                HStack(spacing: ScanFitSpacing.xs) {
                    if selected { Image(systemName: "checkmark").accessibilityHidden(true) }
                    Text(strings.pdfResultKb(kb: kb))
                }
            }
            .buttonStyle(.bordered)
            .tint(selected ? ScanFitColor.primary : ScanFitColor.onSurfaceVariant)
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
    }

    private func toggle(_ title: String, _ hint: String, _ on: Bool, cleanup: Bool) -> some View {
        let model = self.model
        return Toggle(isOn: Binding(get: { on }, set: { cleanup ? model.setCleanup($0) : model.setGrayscale($0) })) {
            VStack(alignment: .leading) {
                Text(title).scanFitText(.body)
                Text(hint).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
        }
    }
}

/// The made PDF (first page, KB, pages, DPI, verdict and notes) or why it could not be made.
struct PdfResultSection: View {
    let model: PdfMakerViewModel
    let strings: Strings

    var body: some View {
        if let target = model.target {
            switch model.result {
            case nil: EmptyView()
            case let .failed(error):
                let max = Int(target.spec.sizeKb.max ?? 0)
                NoticeCard(text: Self.text(error, max: max, strings: strings), kind: .error)
            case let .ready(_, kb, pages, dpi, strategy, preview, meets):
                VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
                    Text(strings.pdfResultHeading).scanFitText(.headline).accessibilityAddTraits(.isHeader)
                    RasterImage(raster: preview, description: strings.pdfPreview)
                        .frame(maxWidth: .infinity, maxHeight: 240)
                    let facts = [strings.pdfResultKb(kb: kb), strings.pdfPages(count: pages)]
                        + (dpi.map { [strings.pdfResultDpi(dpi: $0)] } ?? [])
                    Text(facts.joined(separator: " · ")).scanFitText(.figure)
                    if target.exam {
                        SlotVerdict(meetsRules: meets, examName: target.examName, unverified: target.unverified,
                                    custom: false, strings: strings)
                    } else if meets {
                        Label(strings.pdfFits(max: maxKb(target)), systemImage: "checkmark.circle.fill")
                            .foregroundStyle(ScanFitColor.success)
                    } else {
                        NoticeCard(text: strings.pdfErrorCannotReach(max: maxKb(target)), kind: .error)
                    }
                    Text(strategy == .unchanged ? strings.pdfUnchanged
                        : strategy == .pad ? strings.pdfPadded : strings.pdfTextAsImage)
                        .scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                    ExportStatusView(state: model.exportState, strings: strings)
                    if model.share == .failed { NoticeCard(text: strings.exportShareFailed, kind: .error) }
                }
            }
        }
    }

    private func maxKb(_ target: PdfTarget) -> Int { Int(target.spec.sizeKb.max ?? 0) }

    static func text(_ error: PdfError, max: Int, strings: Strings) -> String {
        switch error {
        case .encrypted: strings.pdfErrorEncrypted
        case .cannotReach: strings.pdfErrorCannotReach(max: max)
        case .unreadable, .noPages: strings.pdfErrorUnreadable
        case .tooManyPages: strings.pdfErrorTooMany
        case .unknownLimit: strings.pdfErrorUnknownLimit
        case .failed: strings.pdfErrorFailed
        }
    }
}
