import SwiftUI

public extension EnvironmentValues {
    /// Free saves left to show on a review (ALGORITHMS 14.3): set at the app's root when 5 or fewer are left for a free
    /// user, `nil` otherwise (Pro, or more left).
    @Entry var freeSavesLeft: Int?
}

/// "3 free saves left" (or "No free saves left…") under a review's actions; nothing when `freeSavesLeft` is nil.
public struct FreeSavesNote: View {
    @Environment(\.freeSavesLeft) private var left
    private let strings: Strings

    public init(strings: Strings) {
        self.strings = strings
    }

    public var body: some View {
        if let left {
            Text(left > 0 ? strings.quotaLeft(count: left) : strings.quotaNone)
                .scanFitText(.caption)
                .foregroundStyle(ScanFitColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }
}

#Preview("3 left") {
    FreeSavesNote(strings: Strings()).environment(\.freeSavesLeft, 3).padding()
}

#Preview("None left, Hindi, large text") {
    FreeSavesNote(strings: Strings(languageCode: "hi")).environment(\.freeSavesLeft, 0)
        .environment(\.dynamicTypeSize, .accessibility3).padding()
}

#Preview("Dark") {
    FreeSavesNote(strings: Strings()).environment(\.freeSavesLeft, 1).padding().preferredColorScheme(.dark)
}
