import Match
import ScanModel
import SwiftUI

public extension Strings {
    /// The kind a file is checked as (Checker chips, My Kit originals).
    func label(_ kind: DocKind) -> String {
        switch kind {
        case .photo: checkerKindPhoto
        case .signature: checkerKindSignature
        case .thumb: checkerKindThumb
        case .fingers: checkerKindFingers
        case .declaration: checkerKindDeclaration
        case .pdfDocument: checkerKindPdf
        }
    }
}

/// One (exam, slot) of the Checker's list (ALGORITHMS 12), also My Kit's "Use for exam…" (ALGORITHMS 13.2).
public struct CheckerTargetRow: View {
    private let target: CheckerTarget
    private let strings: Strings

    public init(target: CheckerTarget, strings: Strings) {
        self.target = target
        self.strings = strings
    }

    public var body: some View {
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
}
