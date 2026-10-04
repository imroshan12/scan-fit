#if os(iOS)
    import SwiftUI
    import UIKit

    /// The system camera (UI_UX §3). The photo is never saved to the library: its JPEG bytes go straight to the flow,
    /// which keeps only the decoded, downsampled raster. Needs `NSCameraUsageDescription` (spec/strings infoplist.*).
    /// Shared by the photo and ink flows.
    public struct CameraPicker: UIViewControllerRepresentable {
        private let onFinish: @MainActor ([UInt8]?) -> Void

        public init(onFinish: @escaping @MainActor ([UInt8]?) -> Void) {
            self.onFinish = onFinish
        }

        public static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

        public func makeUIViewController(context: Context) -> UIImagePickerController {
            let picker = UIImagePickerController()
            picker.sourceType = .camera
            picker.cameraCaptureMode = .photo
            picker.delegate = context.coordinator
            return picker
        }

        public func updateUIViewController(_: UIImagePickerController, context _: Context) {}

        public func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

        @MainActor
        public final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
            let onFinish: @MainActor ([UInt8]?) -> Void

            init(onFinish: @escaping @MainActor ([UInt8]?) -> Void) {
                self.onFinish = onFinish
            }

            public func imagePickerController(
                _: UIImagePickerController,
                didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
            ) {
                guard let image = info[.originalImage] as? UIImage else {
                    onFinish(nil)
                    return
                }
                let finish = onFinish
                // JPEG keeps the orientation in EXIF, which the decoder applies (ALGORITHMS 1.1). Encoded off the main
                // actor: a 12 MP frame takes a moment.
                Task {
                    let bytes = await Task.detached(priority: .userInitiated) {
                        image.jpegData(compressionQuality: 0.95).map { [UInt8]($0) }
                    }.value
                    finish(bytes)
                }
            }

            public func imagePickerControllerDidCancel(_: UIImagePickerController) {
                onFinish(nil)
            }
        }
    }
#endif
