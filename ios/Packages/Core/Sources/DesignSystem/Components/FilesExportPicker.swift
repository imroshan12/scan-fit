#if os(iOS)
    import SwiftUI
    import UIKit

    /// The Files export picker for staged files (ALGORITHMS 1.6; several for Save all, 1.6.2). It only reports the
    /// user's choice: the caller re-opens and verifies the returned URLs. A dismissed sheet is not a cancellation; only
    /// the delegate says so.
    public struct FilesExportPicker: UIViewControllerRepresentable {
        private let urls: [URL]
        private let onPicked: @MainActor ([URL]) -> Void
        private let onCancelled: @MainActor () -> Void

        public init(url: URL, onPicked: @escaping @MainActor ([URL]) -> Void,
                    onCancelled: @escaping @MainActor () -> Void) {
            self.init(urls: [url], onPicked: onPicked, onCancelled: onCancelled)
        }

        public init(urls: [URL], onPicked: @escaping @MainActor ([URL]) -> Void,
                    onCancelled: @escaping @MainActor () -> Void) {
            self.urls = urls
            self.onPicked = onPicked
            self.onCancelled = onCancelled
        }

        public func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
            let picker = UIDocumentPickerViewController(forExporting: urls, asCopy: true)
            picker.delegate = context.coordinator
            return picker
        }

        public func updateUIViewController(_: UIDocumentPickerViewController, context _: Context) {}

        public func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked, onCancelled: onCancelled) }

        public final class Coordinator: NSObject, UIDocumentPickerDelegate {
            private let onPicked: @MainActor ([URL]) -> Void
            private let onCancelled: @MainActor () -> Void

            init(onPicked: @escaping @MainActor ([URL]) -> Void, onCancelled: @escaping @MainActor () -> Void) {
                self.onPicked = onPicked
                self.onCancelled = onCancelled
            }

            public func documentPicker(_: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
                MainActor.assumeIsolated { onPicked(urls) }
            }

            public func documentPickerWasCancelled(_: UIDocumentPickerViewController) {
                MainActor.assumeIsolated { onCancelled() }
            }
        }
    }
#endif
