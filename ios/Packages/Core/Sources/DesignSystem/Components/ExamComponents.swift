import ScanModel
import SwiftUI

public extension Strings {
    func label(_ category: ExamCategory) -> String {
        switch category {
        case .banking: categoryBanking
        case .insurance: categoryInsurance
        case .regulator: categoryRegulator
        case .ssc: categorySsc
        case .upsc: categoryUpsc
        case .railway: categoryRailway
        case .defence: categoryDefence
        case .teaching: categoryTeaching
        case .statePsc: categoryStatePsc
        case .entrance: categoryEntrance
        }
    }

    func label(_ type: DocType) -> String {
        switch type {
        case .photo: docPhoto
        case .postcardPhoto: docPostcardPhoto
        case .signature: docSignature
        case .tripleSignature: docTripleSignature
        case .leftThumb: docLeftThumb
        case .thumbImpression: docThumbImpression
        case .leftHandFingersThumb: docLeftHandFingersThumb
        case .rightHandFingersThumb: docRightHandFingersThumb
        case .handwrittenDeclaration: docHandwrittenDeclaration
        case .photoId: docPhotoId
        case .idProof: docIdProof
        case .class10Certificate: docClass10Certificate
        case .categoryCertificate: docCategoryCertificate
        case .pwdCertificate: docPwdCertificate
        case .scStCertificate: docScStCertificate
        }
    }

    /// `IBPS · Banking`, or just `SSC` when the body and the category read the same.
    func subtitle(body: String, category: ExamCategory) -> String {
        let parts = [body, label(category)]
        return (parts[0] == parts[1] ? [parts[0]] : parts).joined(separator: " · ")
    }

    /// `20–50 KB · 200×230` (UI_UX §3 `SpecSummary`), or "Spec not verified yet" when a slot has no limits at all.
    func specSummary(_ doc: DocSpec) -> String {
        let size: String? = switch (doc.sizeKb.min, doc.sizeKb.max) {
        case let (min?, max?): examSizeRange(min: Self.kb(min), max: Self.kb(max))
        case let (nil, max?): examSizeMax(max: Self.kb(max))
        case let (min?, nil): examSizeMin(min: Self.kb(min))
        case (nil, nil): nil
        }
        let d = doc.dimensions
        let dims: String? = switch d.mode {
        case .exact, .preferred: d.width.flatMap { w in d.height.map { "\(w)×\($0)" } }
        case .range: Self.range(d.minW, d.maxW).flatMap { w in Self.range(d.minH, d.maxH).map { "\(w) × \($0)" } }
        case .none: nil
        }
        if size == nil, d.mode == .none { return examSpecUnknown }
        return [size, dims, doc.formats.contains(.pdf) ? "PDF" : nil].compactMap { $0 }.joined(separator: " · ")
    }

    private static func kb(_ value: Double) -> String {
        value.rounded() == value ? String(Int(value)) : String(value)
    }

    private static func range(_ min: Int?, _ max: Int?) -> String? {
        guard let min, let max else { return nil }
        return min == max ? "\(min)" : "\(min)–\(max)"
    }
}

/// The trust level of a preset (UI_UX §4 `ConfidenceBadge`). Low confidence always reads "Unverified — check
/// notice" (CLAUDE.md rule 6). Icon and text, never colour alone (UI_UX §6).
public struct ConfidenceBadge: View {
    private let confidence: Confidence
    private let strings: Strings

    public init(_ confidence: Confidence, strings: Strings = Strings()) {
        self.confidence = confidence
        self.strings = strings
    }

    public var body: some View {
        let style = Self.style(confidence, strings)
        Label(style.text, systemImage: style.icon)
            .scanFitText(.caption)
            .foregroundStyle(style.content)
            .padding(.horizontal, ScanFitSpacing.sm)
            .padding(.vertical, ScanFitSpacing.xs)
            .background(style.container, in: Capsule())
            .accessibilityElement(children: .combine)
    }

    private struct Style {
        let container: Color
        let content: Color
        let icon: String
        let text: String
    }

    private static func style(_ confidence: Confidence, _ strings: Strings) -> Style {
        switch confidence {
        case .high:
            Style(
                container: ScanFitColor.successContainer,
                content: ScanFitColor.onSuccessContainer,
                icon: "checkmark.seal.fill",
                text: strings.examConfidenceHigh
            )
        case .medium:
            Style(
                container: ScanFitColor.surfaceVariant,
                content: ScanFitColor.onSurfaceVariant,
                icon: "info.circle.fill",
                text: strings.examConfidenceMedium
            )
        case .low:
            Style(
                container: ScanFitColor.warningContainer,
                content: ScanFitColor.onWarningContainer,
                icon: "exclamationmark.triangle.fill",
                text: strings.examConfidenceLow
            )
        }
    }
}
