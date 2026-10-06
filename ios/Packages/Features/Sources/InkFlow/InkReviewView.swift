import DesignSystem
import Imaging
import SwiftUI

/// UI_UX §3 Review for ink documents: the fitted file, its numbers and verdict, the ink options and the save status.
struct InkReviewView: View {
    let review: InkReviewState
    let model: InkFlowViewModel
    let strings: Strings

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                BeforeAfterImage(before: review.before, after: readyBytes, strings: strings)
                result
                ExportStatusView(state: model.exportState, strings: strings)
                if review.retainFailed { NoticeCard(text: strings.draftRetainFailed, kind: .warning) }
                Group {
                    if review.restored {
                        Button { model.replaceDraft() } label: {
                            Label(strings.draftReplace, systemImage: "arrow.triangle.2.circlepath")
                        }
                    } else if review.slot.hasInkOptions {
                        options
                    }
                    if review.slot.needsHandwritingConfirmation { confirmation }
                }
                .disabled(model.exportState == .saving)
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
        case .working:
            HStack(spacing: ScanFitSpacing.sm) {
                ProgressView()
                Text(strings.flowStepClean).scanFitText(.body)
            }
        case let .ready(ready):
            ReviewChecksRow(kb: ready.kb, width: ready.width, height: ready.height,
                            checks: ready.checks, strings: strings)
            verdict(ready)
            MatchNoteCard(note: ready.note, strings: strings)
            switch ready.quality {
            case .tooFaint: NoticeCard(text: strings.inkTooFaint, kind: .warning)
            case .tooDark: NoticeCard(text: strings.inkTooDark, kind: .warning)
            case .ok: EmptyView()
            }
        case let .failed(error):
            NoticeCard(text: message(error), kind: .error)
        }
    }

    /// Icon + text, never colour alone (UI_UX §6). A low-confidence preset never gets "meets the rules" (rule 6).
    private func verdict(_ ready: InkReady) -> some View {
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

    /// "Crisp black" and, while it is off, "Darker ink" (§9.5). The slider runs light → dark: factor 0.9 → 0.3.
    @ViewBuilder
    private var options: some View {
        let options = review.options
        Toggle(isOn: Binding(get: { options.crispBlack }, set: { model.setCrispBlack($0) })) {
            Text(strings.flowToggleCrispBlack).scanFitText(.body)
        }
        if !options.crispBlack {
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text(strings.flowSliderInk).scanFitText(.body)
                Slider(
                    value: Binding(get: { 1.2 - options.inkFactor }, set: { model.setInkFactor(1.2 - $0) }),
                    in: 0.3...0.9,
                    step: 0.1
                )
                .accessibilityLabel(strings.flowSliderInk)
            }
        }
    }

    /// The one-time "running handwriting" tick (§3, §9.5): required before the first signature save, then remembered.
    private var confirmation: some View {
        Toggle(isOn: Binding(get: { review.handwritingConfirmed }, set: { if $0 { model.confirmHandwriting() } })) {
            Text(strings.flowConfirmHandwriting).scanFitText(.body)
        }
        #if os(iOS)
        .toggleStyle(.switch)
        #endif
        .disabled(review.handwritingConfirmed)
    }

    private func message(_ error: FitError) -> String {
        switch error {
        case .tooDetailed: strings.errorFitTooDetailed(maxKb: Int(review.slot.spec.sizeKb.max ?? 0))
        case .unknownLimit: strings.examSpecUnknown
        case .unsupportedFormat, .encodingFailed: strings.errorGeneric
        }
    }

}
