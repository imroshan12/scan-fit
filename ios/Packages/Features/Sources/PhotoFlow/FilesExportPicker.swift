#if os(iOS)
import SwiftUI
import UIKit

struct FilesExportPicker: UIViewControllerRepresentable {
    let url: URL
    let exporter: FilesPhotoExporter

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: UIDocumentPickerViewController, context _: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(exporter: exporter) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let exporter: FilesPhotoExporter

        init(exporter: FilesPhotoExporter) { self.exporter = exporter }

        func documentPicker(_: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            exporter.completed(urls)
        }

        func documentPickerWasCancelled(_: UIDocumentPickerViewController) { exporter.cancelled() }
    }
}
#endif
