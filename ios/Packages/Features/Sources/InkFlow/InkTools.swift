import Foundation
import Imaging
import ScanModel

/// The platform side of the ink flow (decode, cleanup + fit), behind a protocol so `InkFlowViewModel` is testable with
/// a fake. Same boundary as Android's `InkTools`; reading the picked file happens in the view.
public protocol InkTools: Sendable {
    /// ALGORITHMS 1.1: downsampled on decode, upright, sRGB. nil when the bytes are not an image we can decode.
    func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster?
    /// ALGORITHMS 3 / 9.5: cleanup by `pipeline` with `ink` options, pad to aspect, then fit (§9.4).
    func fit(_ cropped: Raster, spec: DocSpec, pipeline: Pipeline, ink: InkOptions) -> Result<PipelineResult, FitError>
}

public struct LiveInkTools: InkTools {
    private let pipeline = FitPipeline(encoder: ImageIOJpegEncoder())

    public init() {}

    public func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster? {
        ImageIODecoder.decode(bytes, maxLongSide: maxLongSide)
    }

    public func fit(
        _ cropped: Raster, spec: DocSpec, pipeline: Pipeline, ink: InkOptions
    ) -> Result<PipelineResult, FitError> {
        self.pipeline.run(
            cropped, spec: spec, pipeline: pipeline,
            crop: CropRect(x: 0, y: 0, w: cropped.width, h: cropped.height), ink: ink
        )
    }
}
