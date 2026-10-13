import DesignSystem
import ScanModel
import SwiftUI

/// Tools tab. Custom size has landed (ALGORITHMS 11); the other tools are listed until their features land.
/// Navigation to each feature is composed in the app target because features never depend on each other
/// (ARCHITECTURE §3), so the stack and its destinations live in `RootView`.
struct ToolsView: View {
    let strings: Strings

    private var comingSoon: [(title: String, symbol: String)] {
        [
            (strings.toolsChecker, "checkmark.shield"),
            (strings.toolsPdf, "doc.richtext"),
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

    private func row(_ title: String, _ symbol: String) -> some View {
        HStack(spacing: ScanFitSpacing.md) {
            Image(systemName: symbol)
                .foregroundStyle(ScanFitColor.primary)
                .frame(width: ScanFitSpacing.xxl)
                .accessibilityHidden(true)
            Text(title).scanFitText(.body)
        }
        .frame(minHeight: ScanFitSpacing.minTouchTarget)
    }
}
