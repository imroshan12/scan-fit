import DesignSystem
import ScanModel
import SwiftUI

/// Tools tab. Custom size (ALGORITHMS 11), the Checker (ALGORITHMS 12) and the PDF tools (ALGORITHMS 6) have landed;
/// the other tools are listed until their features land.
/// Navigation to each feature is composed in the app target because features never depend on each other
/// (ARCHITECTURE §3), so the stack and its destinations live in `RootView`.
struct ToolsView: View {
    let strings: Strings

    private var comingSoon: [(title: String, symbol: String)] {
        [
            (strings.toolsScan, "doc.viewfinder"),
            (strings.toolsGuideSheet, "printer"),
            (strings.toolsCoach, "face.smiling"),
        ]
    }

    var body: some View {
        List {
            NavigationLink(value: CustomSizeRoute()) {
                row(strings.toolsCustomResize, "arrow.up.left.and.down.right.magnifyingglass")
            }
            NavigationLink(value: CheckerRoute()) { row(strings.toolsChecker, "checkmark.shield") }
            Section(strings.toolsPdf) {
                NavigationLink(value: PdfRoute(tool: "images")) {
                    row(strings.toolsPdfImages, "photo.on.rectangle", strings.toolsPdfImagesBody)
                }
                NavigationLink(value: PdfRoute(tool: "compress")) {
                    row(strings.toolsPdfCompress, "arrow.down.doc", strings.toolsPdfCompressBody)
                }
                NavigationLink(value: PdfRoute(tool: "merge")) {
                    row(strings.toolsPdfMerge, "doc.on.doc", strings.toolsPdfMergeBody)
                }
                NavigationLink(value: PdfRoute(tool: "id_card")) {
                    row(strings.toolsPdfIdCard, "person.text.rectangle", strings.toolsPdfIdCardBody)
                }
            }
            ForEach(comingSoon, id: \.title) { tool in
                HStack(spacing: ScanFitSpacing.md) {
                    row(tool.title, tool.symbol)
                    Spacer(minLength: 0)
                    Text(strings.commonComingSoon)
                        .scanFitText(.caption)
                        .foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .navigationTitle(strings.tabTools)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row(_ title: String, _ symbol: String, _ body: String? = nil) -> some View {
        HStack(spacing: ScanFitSpacing.md) {
            Image(systemName: symbol)
                .foregroundStyle(ScanFitColor.primary)
                .frame(width: ScanFitSpacing.xxl)
                .accessibilityHidden(true)
            VStack(alignment: .leading) {
                Text(title).scanFitText(.body)
                if let body {
                    Text(body).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
            }
        }
        .frame(minHeight: ScanFitSpacing.minTouchTarget)
    }
}
