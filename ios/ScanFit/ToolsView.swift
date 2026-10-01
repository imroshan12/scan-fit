import DesignSystem
import SwiftUI

/// Tools tab. It only lists the tools until their features land; navigation to each feature is composed
/// here in the app target because features never depend on each other (ARCHITECTURE §3).
struct ToolsView: View {
    let strings: Strings

    private var tools: [(title: String, symbol: String)] {
        [
            (strings.toolsCustomResize, "arrow.up.left.and.down.right.magnifyingglass"),
            (strings.toolsChecker, "checkmark.shield"),
            (strings.toolsPdf, "doc.richtext"),
            (strings.toolsScan, "doc.viewfinder"),
            (strings.toolsGuideSheet, "printer"),
            (strings.toolsCoach, "face.smiling"),
        ]
    }

    var body: some View {
        NavigationStack {
            List(tools, id: \.title) { tool in
                HStack(spacing: ScanFitSpacing.md) {
                    Image(systemName: tool.symbol)
                        .foregroundStyle(ScanFitColor.primary)
                        .frame(width: ScanFitSpacing.xxl)
                        .accessibilityHidden(true)
                    Text(tool.title).scanFitText(.body)
                    Spacer(minLength: 0)
                    Text(strings.commonComingSoon)
                        .scanFitText(.caption)
                        .foregroundStyle(ScanFitColor.onSurfaceVariant)
                }
                .frame(minHeight: ScanFitSpacing.minTouchTarget)
                .accessibilityElement(children: .combine)
            }
            .navigationTitle(strings.tabTools)
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
