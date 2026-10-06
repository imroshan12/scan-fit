import DesignSystem
import Imaging
import SwiftUI

/// UI_UX §3 Review: the fitted photo, its numbers and verdict, and the photo options.
struct PhotoReviewView: View {
    let review: ReviewState
    let model: PhotoFlowViewModel
    let strings: Strings

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                BeforeAfterImage(before: review.before, after: readyBytes, strings: strings)
                result
                ExportStatusView(state: model.exportState, strings: strings)
                if review.retainFailed { NoticeCard(text: strings.draftRetainFailed, kind: .warning) }
                if review.restored {
                    Button { model.replaceDraft() } label: {
                        Label(strings.draftReplace, systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(model.exportState == .saving)
                } else {
                    options.disabled(model.exportState == .saving)
                }
            }
            .padding(ScanFitSpacing.screenMargin)
        }
    }

    private var readyBytes: [UInt8] {
        if case let .ready(ready) = review.result { ready.bytes } else { [] }
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
            ReviewChecksRow(kb: ready.kb, width: ready.width, height: ready.height,
                            checks: ready.checks, strings: strings)
            verdict(ready)
            MatchNoteCard(note: ready.note, strings: strings)
        case let .failed(error):
            NoticeCard(text: message(error), kind: .error)
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

}
