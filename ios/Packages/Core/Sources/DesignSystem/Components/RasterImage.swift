import CoreGraphics
import Imaging
import SwiftUI

/// A raster shown as an image, converted off the main actor once per raster (PDF previews and thumbnails).
public struct RasterImage: View {
    let raster: Raster?
    let description: String?

    @State private var image: CGImage?

    public init(raster: Raster?, description: String? = nil) {
        self.raster = raster
        self.description = description
    }

    public var body: some View {
        Group {
            if let image {
                if let description {
                    Image(decorative: image, scale: 1).resizable().scaledToFit().accessibilityLabel(description)
                } else {
                    Image(decorative: image, scale: 1).resizable().scaledToFit()
                }
            } else {
                Color.clear
            }
        }
        .task(id: raster) {
            let source = raster
            image = await Task.detached(priority: .userInitiated) { source?.cgImage }.value
        }
    }
}
