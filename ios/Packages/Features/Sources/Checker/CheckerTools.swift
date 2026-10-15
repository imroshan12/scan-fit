import Foundation
import Imaging
import ScanModel

/// The platform side of the Checker (decode, fit), behind a protocol so `CheckerViewModel` is testable with a fake.
public protocol CheckerTools: Sendable {
    /// ALGORITHMS 1.1: downsampled on decode, upright, sRGB. nil when the bytes are not an image we can decode.
    func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster?
    /// The plain §1 pipeline for `spec`: photo kinds crop centred to the aspect, others pad with white.
    func fit(_ image: Raster, spec: DocSpec) -> Result<PipelineResult, FitError>
}

public struct LiveCheckerTools: CheckerTools {
    private let pipeline = FitPipeline(encoder: ImageIOJpegEncoder())

    public init() {}

    public func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster? {
        ImageIODecoder.decode(bytes, maxLongSide: maxLongSide)
    }

    public func fit(_ image: Raster, spec: DocSpec) -> Result<PipelineResult, FitError> {
        pipeline.run(image, spec: spec)
    }
}
