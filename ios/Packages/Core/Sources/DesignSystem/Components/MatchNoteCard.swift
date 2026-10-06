import Match
import ScanModel
import SwiftUI

/// The match note under a review's verdict (ALGORITHMS 4 "Match note on review"): "Accepted by N exams", the first
/// names and the quick-fix count. Tapping it opens the detail sheet, grouped by body.
public struct MatchNoteCard: View {
    private let note: MatchNote
    private let strings: Strings
    @State private var open = false

    public init(note: MatchNote, strings: Strings) {
        self.note = note
        self.strings = strings
    }

    public var body: some View {
        let hasDetails = !note.groups.isEmpty
        Button { open = true } label: {
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                HStack(spacing: ScanFitSpacing.sm) {
                    let accepted = note.headline == .accepted
                    Image(systemName: accepted ? "checkmark.circle.fill" : "info.circle")
                        .foregroundStyle(accepted ? ScanFitColor.success : ScanFitColor.onSurfaceVariant)
                        .accessibilityHidden(true)
                    Text(headline).scanFitText(.body).frame(maxWidth: .infinity, alignment: .leading)
                    if hasDetails {
                        Image(systemName: "chevron.forward").accessibilityHidden(true)
                    }
                }
                if !note.preview.isEmpty {
                    Text(previewLine).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
                if !note.quickFixes.isEmpty {
                    HStack(spacing: ScanFitSpacing.sm) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(ScanFitColor.warning)
                            .accessibilityHidden(true)
                        Text(strings.matchQuickFix(count: note.quickFixes.count)).scanFitText(.body)
                    }
                }
            }
            .foregroundStyle(ScanFitColor.onSurface)
            .frame(maxWidth: .infinity, minHeight: ScanFitSpacing.minTouchTarget, alignment: .leading)
            .padding(ScanFitSpacing.lg)
            .background(ScanFitColor.surfaceVariant, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
        }
        .buttonStyle(.plain)
        .disabled(!hasDetails)
        .accessibilityHint(hasDetails ? strings.matchSheetTitle : "")
        .sheet(isPresented: $open) {
            MatchNoteSheet(note: note, strings: strings) { open = false }
        }
    }

    private var headline: String {
        switch note.headline {
        case .accepted: strings.matchAccepted(count: note.accepted)
        case .likelyOk: strings.matchLikelyOkCount(count: note.likelyOk)
        case .none: strings.matchNone
        }
    }

    private var previewLine: String {
        let names = note.preview.map(\.name).joined(separator: " · ")
        return note.more > 0 ? "\(names) \(strings.matchPreviewMore(count: note.more))" : names
    }
}

/// The detail sheet: every entry grouped by body. Near misses say what they need; there is no Fix in an exam flow.
struct MatchNoteSheet: View {
    let note: MatchNote
    let strings: Strings
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(note.groups, id: \.body) { group in
                    Section {
                        ForEach(Array(group.entries.enumerated()), id: \.offset) { _, entry in
                            row(entry)
                        }
                    } header: {
                        Text(group.body).accessibilityAddTraits(.isHeader)
                    }
                }
                if note.groups.contains(where: { $0.entries.contains { $0.verdict == .nearMiss } }) {
                    Section {
                        Text(strings.matchSheetOtherExam).scanFitText(.caption)
                            .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    }
                }
            }
            .navigationTitle(strings.matchSheetTitle)
            #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
            #endif
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(strings.commonClose, action: onClose)
                    }
                }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ entry: MatchEntry) -> some View {
        let warn = entry.verdict == .nearMiss || entry.unverified
        return HStack(alignment: .top, spacing: ScanFitSpacing.sm) {
            Image(systemName: warn ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .foregroundStyle(warn ? ScanFitColor.warning : ScanFitColor.success)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: ScanFitSpacing.xs) {
                Text("\(entry.examName) · \(strings.label(entry.docType))").scanFitText(.body)
                Text(status(entry)).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func status(_ entry: MatchEntry) -> String {
        if entry.verdict == .nearMiss, let need = MatchNote.need(entry) {
            return switch need {
            case let .atMost(kb): strings.matchNeedAtMost(maxKb: kb)
            case let .atLeast(kb): strings.matchNeedAtLeast(minKb: kb)
            case .jpeg: strings.matchNeedJpeg
            case .baseline: strings.matchNeedBaseline
            }
        }
        if entry.unverified { return strings.matchLikelyOk }
        return entry.verdict == .exact ? strings.matchVerdictExact : strings.matchVerdictAccepted
    }
}

/// Sample notes for previews of the review screens (no presets are loaded in a preview).
public enum MatchNoteSamples {
    private static func entry(
        _ id: String, _ name: String, _ body: String, _ verdict: Verdict,
        unverified: Bool = false, fix: FixAction? = nil
    ) -> MatchEntry {
        MatchEntry(
            examId: id, examName: name, body: body, docType: .signature, verdict: verdict,
            unverified: unverified, fix: fix, failed: [], issues: [], sizeKb: SizeKB(min: 10, max: 20)
        )
    }

    /// Five verified exams, one unverified, one near miss.
    public static let accepted = MatchNote.of(MatchResult(entries: [
        entry("ibps_po", "IBPS PO / MT", "IBPS", .exact),
        entry("sbi_po", "SBI PO", "SBI", .exact),
        entry("rbi_grade_b", "RBI Grade B (DR)", "RBI", .accepted),
        entry("lic_aao", "LIC AAO", "LIC", .accepted),
        entry("ibps_clerk", "IBPS Clerk (CSA)", "IBPS", .accepted),
        entry("upsc_ese", "UPSC Engineering Services (ESE)", "UPSC", .accepted, unverified: true),
        entry("ssc_cgl", "SSC CGL", "SSC", .nearMiss, fix: .compressToTarget),
    ]))

    /// Only an unverified exam accepts the file.
    public static let likelyOk = MatchNote.of(MatchResult(entries: [
        entry("upsc_ese", "UPSC Engineering Services (ESE)", "UPSC", .accepted, unverified: true),
    ]))
}

private struct MatchNotePreviews: View {
    let strings: Strings

    var body: some View {
        VStack(spacing: ScanFitSpacing.md) {
            MatchNoteCard(note: MatchNoteSamples.accepted, strings: strings)
            MatchNoteCard(note: MatchNoteSamples.likelyOk, strings: strings)
            MatchNoteCard(note: .empty, strings: strings)
        }
        .padding(ScanFitSpacing.lg)
    }
}

#Preview("Match note - light") {
    MatchNotePreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Match note - dark") {
    MatchNotePreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.dark)
}

#Preview("Match note - Hindi largest") {
    MatchNotePreviews(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}

#Preview("Match note sheet") {
    MatchNoteSheet(note: MatchNoteSamples.accepted, strings: Strings(languageCode: "en")) {}
}
