import DesignSystem
import SwiftUI

/// My Kit tab. Phase 0: the empty state only (originals, "Use for exam…" and history arrive in Phase 2).
public struct KitView: View {
    private let strings: Strings

    public init(strings: Strings = Strings()) {
        self.strings = strings
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: ScanFitSpacing.md) {
                Image(systemName: "tray")
                    .font(.system(size: 44))
                    .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    .accessibilityHidden(true)
                Text(strings.kitEmptyTitle).scanFitText(.title)
                    .foregroundStyle(ScanFitColor.onBackground)
                Text(strings.kitEmptyBody).scanFitText(.body)
                    .foregroundStyle(ScanFitColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
            }
            .padding(ScanFitSpacing.xl)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(ScanFitColor.background)
            .navigationTitle(strings.tabKit)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
    }
}
