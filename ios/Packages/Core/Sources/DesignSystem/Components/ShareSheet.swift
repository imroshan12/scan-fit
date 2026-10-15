#if os(iOS)
    import SwiftUI
    import UIKit

    /// The system share sheet for one staged JPEG (ALGORITHMS 1.6.2). The user picks where the file goes; the app sends
    /// nothing itself. `onFinished` runs when the sheet closes, whatever the user chose.
    public struct ShareSheet: UIViewControllerRepresentable {
        private let url: URL
        private let onFinished: @MainActor () -> Void

        public init(url: URL, onFinished: @escaping @MainActor () -> Void) {
            self.url = url
            self.onFinished = onFinished
        }

        public func makeUIViewController(context _: Context) -> UIActivityViewController {
            let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            let finished = onFinished
            controller.completionWithItemsHandler = { _, _, _, _ in
                Task { @MainActor in finished() }
            }
            return controller
        }

        public func updateUIViewController(_: UIActivityViewController, context _: Context) {}
    }
#endif
