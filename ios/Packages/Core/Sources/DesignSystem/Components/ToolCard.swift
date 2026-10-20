import SwiftUI

/// A tool's colour family (UI_UX §3 Home, tokens `photo`, `ink`, `pdf`; exams use primary, the Checker success).
public enum ToolAccent: Sendable {
    case exam, photo, ink, pdf, check, muted

    var container: Color {
        switch self {
        case .exam: ScanFitColor.primaryContainer
        case .photo: ScanFitColor.photoContainer
        case .ink: ScanFitColor.inkContainer
        case .pdf: ScanFitColor.pdfContainer
        case .check: ScanFitColor.successContainer
        case .muted: ScanFitColor.surfaceVariant
        }
    }

    var content: Color {
        switch self {
        case .exam: ScanFitColor.onPrimaryContainer
        case .photo: ScanFitColor.onPhotoContainer
        case .ink: ScanFitColor.onInkContainer
        case .pdf: ScanFitColor.onPdfContainer
        case .check: ScanFitColor.onSuccessContainer
        case .muted: ScanFitColor.onSurfaceVariant
        }
    }

    var tile: Color {
        switch self {
        case .exam: ScanFitColor.primary
        case .photo: ScanFitColor.photo
        case .ink: ScanFitColor.ink
        case .pdf: ScanFitColor.pdf
        case .check: ScanFitColor.success
        case .muted: ScanFitColor.outline
        }
    }
}

/// A large tool card (UI_UX §3 Home): a soft tinted container, a round icon tile in the accent colour, a title and a
/// short description. Fills the height it is given, so every card in a grid row is as tall as the tallest. Wrap it in
/// a `NavigationLink` or `Button`; `badge` (e.g. "Coming soon") marks a card without an action.
public struct ToolCard: View {
    let title: String
    let description: String
    let symbol: String
    let accent: ToolAccent
    let badge: String?

    public init(title: String, description: String, symbol: String, accent: ToolAccent, badge: String? = nil) {
        self.title = title
        self.description = description
        self.symbol = symbol
        self.accent = accent
        self.badge = badge
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.sm) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(accent.container)
                .frame(width: 44, height: 44)
                .background(accent.tile, in: Circle())
                .accessibilityHidden(true)
            Text(title).scanFitText(.headline).foregroundStyle(accent.content)
            Text(description).scanFitText(.caption).foregroundStyle(accent.content)
            if let badge {
                Text(badge).scanFitText(.label).foregroundStyle(accent.content)
            }
            Spacer(minLength: 0)
        }
        .multilineTextAlignment(.leading)
        .padding(ScanFitSpacing.lg)
        .frame(maxWidth: .infinity, minHeight: 148, maxHeight: .infinity, alignment: .topLeading)
        .background(accent.container, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
