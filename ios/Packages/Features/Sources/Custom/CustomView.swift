import DesignSystem
import ScanModel
import SwiftUI

/// Custom size (ALGORITHMS 11, UI_UX §3): Continue hands the validated size to the app, which opens the flow.
public struct CustomView: View {
    @State private var model: CustomViewModel
    private let strings: Strings
    private let onContinue: (CustomSize) -> Void

    @FocusState private var focus: CustomViewModel.Field?

    public init(model: CustomViewModel, strings: Strings = Strings(), onContinue: @escaping (CustomSize) -> Void) {
        _model = State(initialValue: model)
        self.strings = strings
        self.onContinue = onContinue
    }

    public var body: some View {
        Form {
            Section {
                Text(strings.customIntro).scanFitText(.body).foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
            if !model.recent.isEmpty { recent }
            Section(strings.customWhat) {
                Picker(strings.customWhat, selection: $model.form.doc) {
                    ForEach(CustomSize.docs, id: \.self) { doc in Text(label(doc)).tag(doc) }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            dimensions
            fileSize
        }
        .navigationTitle(strings.customTitle)
        #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
        #endif
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                // A number pad has no return key: Next walks the fields, Done closes the keyboard.
                ToolbarItemGroup(placement: .keyboard) {
                    if let next = focus.flatMap(Self.next) {
                        Button(strings.commonNext) { focus = next }
                    }
                    Spacer()
                    Button(strings.commonDone) { focus = nil }
                }
            }
            .safeAreaInset(edge: .bottom) {
                // Hidden while typing so the keyboard leaves room for the fields; the keyboard bar closes it.
                if focus == nil {
                    Button {
                        if let size = model.continueTapped() { onContinue(size) }
                    } label: {
                        Text(strings.commonContinue)
                            .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(ScanFitSpacing.screenMargin)
                    .background(.bar)
                }
            }
    }

    private var recent: some View {
        Section(strings.customRecent) {
            ForEach(model.recent, id: \.self) { size in
                Button { model.useRecent(size) } label: {
                    Text("\(label(size.doc)) · \(summary(size))").scanFitText(.body)
                        .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
                }
            }
        }
    }

    private var dimensions: some View {
        Section {
            Picker(strings.customSizeHeading, selection: $model.form.unit) {
                Text(strings.customUnitPx).tag(SizeUnit.px)
                Text(strings.customUnitCm).tag(SizeUnit.cm)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            number(strings.customWidth, .width, errors: [.widthRange], decimal: model.form.unit == .cm)
            number(strings.customHeight, .height, errors: [.heightRange], decimal: model.form.unit == .cm)
            number(strings.customDpi, .dpi, errors: [.dpiRange])
            if let pixels = model.pixels {
                Text(strings.customInPixels(width: pixels.width, height: pixels.height)).scanFitText(.figure)
            }
        } header: {
            Text(strings.customSizeHeading)
        } footer: {
            if model.shows(.sizePair) {
                Text(strings.customErrorSizePair).foregroundStyle(ScanFitColor.error)
            } else {
                Text(strings.customSizeHint)
            }
        }
    }

    private var fileSize: some View {
        Section(strings.customKbHeading) {
            number(strings.customMinKb, .minKb, errors: [.minKbRange])
            number(strings.customMaxKb, .maxKb, errors: [.maxKbMissing, .maxKbRange])
        }
    }

    /// A labelled number field with its first shown error under it (icon + text, never colour alone).
    private func number(
        _ title: String, _ field: CustomViewModel.Field, errors: [CustomSizeError], decimal: Bool = false
    ) -> some View {
        let shown = errors.first(where: model.shows)
        let model = model
        return VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
            LabeledContent(title) {
                TextField(title, text: Binding(get: { model.text(field) }, set: { model.update(field, $0) }))
                    .multilineTextAlignment(.trailing)
                    .scanFitText(.figure)
                    .focused($focus, equals: field)
                #if os(iOS)
                    .keyboardType(decimal ? .decimalPad : .numberPad)
                #endif
            }
            if let shown {
                Label(message(shown), systemImage: "exclamationmark.circle")
                    .scanFitText(.caption)
                    .foregroundStyle(ScanFitColor.error)
            }
        }
        .frame(minHeight: ScanFitSpacing.minTouchTarget)
    }

    /// The field after `field` in screen order, or nil for the last one.
    private static func next(_ field: CustomViewModel.Field) -> CustomViewModel.Field? {
        switch field {
        case .width: .height
        case .height: .dpi
        case .dpi: .minKb
        case .minKb: .maxKb
        case .maxKb: nil
        }
    }

    /// `10–20 KB · 140×60`, or `0–50 KB · Any size` when the size keeps the picture's own dimensions.
    private func summary(_ size: CustomSize) -> String {
        let text = strings.specSummary(size.spec)
        return size.width == nil ? "\(text) · \(strings.customAnySize)" : text
    }

    private func label(_ doc: DocType) -> String {
        switch doc {
        case .signature: strings.customDocSignature
        case .thumbImpression: strings.customDocThumb
        case .handwrittenDeclaration: strings.customDocDocument
        default: strings.customDocPhoto
        }
    }

    private func message(_ error: CustomSizeError) -> String {
        switch error {
        case .maxKbMissing: strings.customErrorMaxKbMissing
        case .maxKbRange: strings.customErrorMaxKbRange
        case .minKbRange: strings.customErrorMinKbRange
        case .sizePair: strings.customErrorSizePair
        case .widthRange: strings.customErrorWidthRange
        case .heightRange: strings.customErrorHeightRange
        case .dpiRange: strings.customErrorDpiRange
        }
    }
}
