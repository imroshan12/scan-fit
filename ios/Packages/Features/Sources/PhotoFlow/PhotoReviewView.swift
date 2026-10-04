import CoreGraphics
import DesignSystem
import Imaging
import ImageIO
import SwiftUI

/// UI_UX §3 Review: the fitted photo, its numbers and verdict, and the photo options.
struct PhotoReviewView: View {
    let review: ReviewState
    let model: PhotoFlowViewModel
    let strings: Strings

    @State private var image: CGImage?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                preview
                result
                PhotoExportStatusView(state: model.exportState, strings: strings)
                options
                    .disabled(model.exportState == .saving)
            }
            .padding(ScanFitSpacing.screenMargin)
        }
        .task(id: readyBytes) {
            let bytes = readyBytes
            image = await Task.detached(priority: .userInitiated) { Self.decode(bytes) }.value
        }
    }

    private var readyBytes: [UInt8] {
        if case let .ready(ready) = review.result { ready.bytes } else { [] }
    }

    /// The fitted photo as it will be written, at most 280 pt tall; a placeholder while it is being made.
    private var preview: some View {
        let aspect = image.map { CGFloat($0.width) / CGFloat($0.height) } ?? 200.0 / 230.0
        return ZStack {
            RoundedRectangle(cornerRadius: ScanFitRadius.card).fill(ScanFitColor.surfaceVariant)
            if let image, case .ready = review.result {
                Image(decorative: image, scale: 1).resizable().scaledToFit()
            }
        }
        .aspectRatio(aspect, contentMode: .fit)
        .frame(maxWidth: .infinity, maxHeight: 280)
    }

    @ViewBuilder
    private var result: some View {
        switch review.result {
        case let .working(targetKb):
            HStack(spacing: ScanFitSpacing.sm) {
                ProgressView()
                if let targetKb { Text(strings.flowStepFit(kb: targetKb)).scanFitText(.body) }
            }
        case let .ready(ready):
            Text(strings.photoResult(kb: ready.kb, width: ready.width, height: ready.height))
                .scanFitText(.figure)
                .accessibilityLabel(strings.photoResultA11y(
                    count: ready.kb, width: String(ready.width), height: String(ready.height)
                ))
            verdict(ready)
        case let .failed(error):
            PhotoNotice(text: message(error), kind: .error)
        }
    }

    /// Icon + text, never colour alone (UI_UX §6). A low-confidence preset never gets "meets the rules" (rule 6).
    private func verdict(_ ready: ReviewReady) -> some View {
        let slot = review.slot
        let ok = ready.meetsRules && !slot.unverified
        let text = if !ready.meetsRules {
            strings.photoMissesRules(exam: slot.examName)
        } else if slot.unverified {
            strings.matchLikelyOk
        } else {
            strings.photoMeetsRules(exam: slot.examName)
        }
        return Label {
            Text(text).scanFitText(.body)
        } icon: {
            Image(systemName: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .foregroundStyle(ok ? ScanFitColor.success : ScanFitColor.warning)
        }
    }

    @ViewBuilder
    private var options: some View {
        let options = review.options
        Toggle(isOn: Binding(get: { options.whiteBackground }, set: { model.setWhiteBackground($0) })) {
            Text(strings.flowToggleWhiteBg).scanFitText(.body)
        }
        .disabled(!options.whiteBackgroundAvailable)
        if !options.whiteBackgroundAvailable {
            Text(strings.photoWhiteBgUnavailable).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
        }
        Toggle(isOn: Binding(get: { options.nameDate }, set: { model.setNameDate($0) })) {
            Text(strings.flowToggleNameDate).scanFitText(.body)
        }
        if review.slot.spec.nameDateStrip?.required == true, !options.nameDate {
            Text(strings.photoStripHint).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
        }
        if options.nameDate {
            TextField(strings.photoNameLabel, text: Binding(get: { options.name }, set: { model.setName($0) }))
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
            #if os(iOS)
                .textInputAutocapitalization(.characters)
            #endif
            TextField(strings.photoDateLabel, text: Binding(get: { options.date }, set: { model.setDate($0) }))
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
            #if os(iOS)
                .keyboardType(.numbersAndPunctuation)
            #endif
        }
    }

    private func message(_ error: FitError) -> String {
        switch error {
        case .tooDetailed: strings.errorFitTooDetailed(maxKb: Int(review.slot.spec.sizeKb.max ?? 0))
        case .unknownLimit: strings.examSpecUnknown
        case .unsupportedFormat, .encodingFailed: strings.errorGeneric
        }
    }

    private nonisolated static func decode(_ bytes: [UInt8]) -> CGImage? {
        guard !bytes.isEmpty, let source = CGImageSourceCreateWithData(Data(bytes) as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}
