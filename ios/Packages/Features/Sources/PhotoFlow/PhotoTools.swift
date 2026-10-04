import Foundation
import Imaging
import ScanModel

/// The platform side of the photo flow (decode, strip, fit), behind a protocol so `PhotoFlowViewModel` is testable
/// with a fake. Same boundary as Android's `PhotoTools`; reading the picked file happens in the view (PhotosPicker).
public protocol PhotoTools: Sendable {
    /// ALGORITHMS 1.1: downsampled on decode, upright, sRGB. nil when the bytes are not an image we can decode.
    func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster?
    /// ALGORITHMS 2.4: the white name/date strip inside the photo's own size.
    func drawStrip(_ photo: Raster, name: String, date: String) -> Raster
    /// ALGORITHMS 9.4: the cropped (and whitened, striped) photo fitted to the slot.
    func fit(_ prepared: Raster, spec: DocSpec) -> Result<PipelineResult, FitError>
    func today() -> Date
}

public struct LivePhotoTools: PhotoTools {
    private let pipeline = FitPipeline(encoder: ImageIOJpegEncoder())

    public init() {}

    public func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster? {
        ImageIODecoder.decode(bytes, maxLongSide: maxLongSide)
    }

    public func drawStrip(_ photo: Raster, name: String, date: String) -> Raster {
        CoreTextStripRenderer().withStrip(photo, name: name, date: date)
    }

    public func fit(_ prepared: Raster, spec: DocSpec) -> Result<PipelineResult, FitError> {
        pipeline.run(prepared, spec: spec, crop: CropRect(x: 0, y: 0, w: prepared.width, h: prepared.height))
    }

    public func today() -> Date { Date() }
}
