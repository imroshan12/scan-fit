import Imaging
import Match
import SwiftUI

private struct ReviewPreviews: View {
    let strings: Strings
    @State private var before: Raster?
    @State private var after: [UInt8] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ScanFitSpacing.lg) {
                BeforeAfterImage(before: before, after: after, strings: strings)
                ReviewChecksRow(kb: 34, width: 200, height: 230,
                                checks: ReviewChecks(size: true, dimensions: true, jpeg: true), strings: strings)
                ReviewChecksRow(kb: 60, width: 200, height: 230,
                                checks: ReviewChecks(size: false, dimensions: true, jpeg: true), strings: strings)
                ReviewChecksRow(kb: 16, width: 140, height: 60, checks: .unchecked, strings: strings)
            }
            .padding(ScanFitSpacing.screenMargin)
        }
        .background(ScanFitColor.background)
        .task {
            let sample = await Task.detached(priority: .userInitiated) {
                let original = Raster.make(420, 180) { column, row in
                    let stroke = Int(90 + 25 * sin(Double(column) / 18))
                    return (50...370).contains(column) && abs(row - stroke) < 3 ? 0x243440 : 0xE5E7DC
                }
                let cleaned = Raster.make(420, 180) { column, row in
                    let stroke = Int(90 + 25 * sin(Double(column) / 18))
                    return (50...370).contains(column) && abs(row - stroke) < 3 ? 0 : 0xFFFFFF
                }
                return (original, ImageIOJpegEncoder().encode(cleaned, quality: 90))
            }.value
            guard !Task.isCancelled else { return }
            before = sample.0
            after = sample.1
        }
    }
}

#Preview("Review comparison - light") {
    ReviewPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Review comparison - dark") {
    ReviewPreviews(strings: Strings(languageCode: "en")).preferredColorScheme(.dark)
}

#Preview("Review comparison - largest") {
    ReviewPreviews(strings: Strings(languageCode: "en")).dynamicTypeSize(.accessibility5)
}

#Preview("Review comparison - Hindi largest") {
    ReviewPreviews(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}

#Preview("Review comparison - Hindi dark largest") {
    ReviewPreviews(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
        .preferredColorScheme(.dark)
}
