import Match
import SwiftUI

public struct ReviewChecksRow: View {
    private let kb: Int
    private let width: Int
    private let height: Int
    private let checks: ReviewChecks
    private let strings: Strings

    public init(kb: Int, width: Int, height: Int, checks: ReviewChecks, strings: Strings) {
        self.kb = kb
        self.width = width
        self.height = height
        self.checks = checks
        self.strings = strings
    }

    public var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: ScanFitSpacing.sm) { chips }
                .fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: ScanFitSpacing.sm) { chips }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var chips: some View {
        VerdictChip(label: strings.reviewSize(kb: kb), description: strings.reviewSizeA11y(count: kb),
                    passes: checks.size, strings: strings)
        VerdictChip(
            label: strings.reviewDimensions(width: width, height: height),
            description: strings.reviewDimensionsA11y(width: String(width), height: String(height)),
            passes: checks.dimensions, strings: strings
        )
        VerdictChip(label: strings.reviewJpeg, description: strings.reviewJpegA11y,
                    passes: checks.jpeg, strings: strings)
    }
}
