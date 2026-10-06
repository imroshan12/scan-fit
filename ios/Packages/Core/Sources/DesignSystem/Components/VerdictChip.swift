import SwiftUI

public struct VerdictChip: View {
    private let label: String
    private let description: String
    private let passes: Bool
    private let strings: Strings

    public init(label: String, description: String, passes: Bool, strings: Strings) {
        self.label = label
        self.description = description
        self.passes = passes
        self.strings = strings
    }

    public var body: some View {
        HStack(spacing: ScanFitSpacing.xs) {
            Image(systemName: passes ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .accessibilityHidden(true)
            Text(label).scanFitText(.figure)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(passes ? ScanFitColor.onSuccessContainer : ScanFitColor.onWarningContainer)
        .padding(.horizontal, ScanFitSpacing.md)
        .padding(.vertical, ScanFitSpacing.sm)
        .background(
            passes ? ScanFitColor.successContainer : ScanFitColor.warningContainer,
            in: RoundedRectangle(cornerRadius: ScanFitRadius.chip)
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(passes
            ? strings.reviewCheckPass(value: description)
            : strings.reviewCheckFail(value: description))
    }
}
