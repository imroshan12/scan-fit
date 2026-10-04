import SwiftUI

/// A message card: text on a tinted background (the text carries the meaning, not the colour).
public struct NoticeCard: View {
    public enum Kind: Sendable { case info, warning, error }

    private let text: String
    private let kind: Kind

    public init(text: String, kind: Kind) {
        self.text = text
        self.kind = kind
    }

    public var body: some View {
        let (background, foreground): (Color, Color) = switch kind {
        case .info: (ScanFitColor.surfaceVariant, ScanFitColor.onSurfaceVariant)
        case .warning: (ScanFitColor.warningContainer, ScanFitColor.onWarningContainer)
        case .error: (ScanFitColor.errorContainer, ScanFitColor.onErrorContainer)
        }
        Text(text)
            .scanFitText(.body)
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(ScanFitSpacing.lg)
            .background(background, in: RoundedRectangle(cornerRadius: ScanFitRadius.card))
    }
}
