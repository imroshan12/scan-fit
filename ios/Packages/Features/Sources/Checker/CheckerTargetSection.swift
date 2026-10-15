import DesignSystem
import Imaging
import Match
import ScanModel
import SwiftUI

/// With a kind: who accepts the file as it is, then the exam list or the chosen exam's result (ALGORITHMS 12).
struct CheckerTargetSection: View {
    let model: CheckerViewModel
    let strings: Strings

    var body: some View {
        if let result = model.result {
            resultCard(result)
            fixSection(result)
        } else {
            MatchNoteCard(note: model.note, strings: strings)
            Text(strings.checkerWhich).scanFitText(.headline).accessibilityAddTraits(.isHeader)
            TextField(strings.checkerSearch, text: Binding(
                get: { model.query },
                set: { text in Task { await model.search(text) } }
            ))
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled()
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(model.targets, id: \.self) { target in
                    Button { model.choose(target: target) } label: { row(target) }
                        .buttonStyle(.plain)
                    Divider()
                }
            }
        }
    }

    private func row(_ target: CheckerTarget) -> some View {
        HStack(spacing: ScanFitSpacing.sm) {
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text(target.exam.name).scanFitText(.body)
                Text(strings.label(target.slot.type)).scanFitText(.caption)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant)
                // Only the unverified state needs a badge in a list (CLAUDE.md rule 6).
                if target.exam.confidence == .low { ConfidenceBadge(.low, strings: strings) }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.forward").accessibilityHidden(true)
        }
        .frame(minHeight: ScanFitSpacing.minTouchTarget)
        .padding(.vertical, ScanFitSpacing.sm)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    /// The file against the chosen slot: chips, verdict and every problem, then what can be done.
    @ViewBuilder
    private func resultCard(_ result: TargetResult) -> some View {
        let target = result.target
        if let file = model.file {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading) {
                    Text(target.exam.name).scanFitText(.headline).accessibilityAddTraits(.isHeader)
                    Text(strings.label(target.slot.type)).scanFitText(.caption)
                }
                Spacer(minLength: 0)
                Button(strings.checkerChangeExam) { model.changeExam() }
            }
            ReviewChecksRow(
                kb: file.kb, width: file.inspected.width ?? 0, height: file.inspected.height ?? 0,
                checks: result.checks, strings: strings
            )
            switch result.status {
            case .unknown:
                NoticeCard(text: strings.examSpecUnknown, kind: .warning)
            case .ok, .likelyOk, .fix, .noFix:
                SlotVerdict(
                    meetsRules: result.status == .ok || result.status == .likelyOk, examName: target.exam.name,
                    unverified: target.exam.isUnverified, custom: false, strings: strings
                )
            }
            let issues = file.inspected.issues + result.evaluation.issues.filter { !file.inspected.issues.contains($0) }
            ForEach(issues, id: \.self) { IssueRow(issue: $0, file: file, slot: target.slot, strings: strings) }
            if result.status == .noFix {
                let needsPdf = target.slot.formats.allSatisfy { $0 == .pdf }
                NoticeCard(text: needsPdf ? strings.checkerNeedsPdf : strings.checkerNoFix, kind: .info)
            }
            if result.status == .fix, model.fix == .none {
                Button { Task { await model.makeItFit() } } label: {
                    Text(strings.checkerFix(exam: target.exam.name))
                        .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    /// "Make it fit": progress, then the fitted copy as a review would show it.
    @ViewBuilder
    private func fixSection(_ result: TargetResult) -> some View {
        switch model.fix {
        case .none:
            EmptyView()
        case .working:
            HStack(spacing: ScanFitSpacing.sm) {
                ProgressView()
                Text(strings.checkerFixing).scanFitText(.body)
            }
        case let .failed(error):
            let max = Int(result.target.slot.sizeKb.max ?? 0)
            NoticeCard(
                text: error == .tooDetailed ? strings.errorFitTooDetailed(maxKb: max)
                    : error == nil ? strings.checkerOpenFailed : strings.errorGeneric,
                kind: .error
            )
        case let .ready(copy):
            Text(strings.checkerFixedHeading).scanFitText(.headline).accessibilityAddTraits(.isHeader)
            BeforeAfterImage(before: nil, after: copy.bytes, strings: strings)
            ReviewChecksRow(kb: copy.kb, width: copy.width, height: copy.height, checks: copy.checks, strings: strings)
            SlotVerdict(
                meetsRules: copy.meetsRules, examName: result.target.exam.name,
                unverified: result.target.exam.isUnverified, custom: false, strings: strings
            )
            MatchNoteCard(note: copy.note, strings: strings)
            Text(strings.checkerOriginalKept).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            if model.kind == .signature {
                Toggle(isOn: Binding(
                    get: { !model.needsHandwriting },
                    set: { if $0 { model.confirmHandwriting() } }
                )) {
                    Text(strings.flowConfirmHandwriting).scanFitText(.body)
                }
                .disabled(!model.needsHandwriting)
            }
            ExportStatusView(state: model.exportState, strings: strings)
            if model.share == .failed { NoticeCard(text: strings.exportShareFailed, kind: .error) }
        }
    }
}
