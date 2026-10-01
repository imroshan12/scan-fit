import Foundation
import ScanModel

/// The cleanup applied before fitting (ALGORITHMS 9.9). `plain` = crop (photos) or pad-to-aspect (everything else).
public enum Pipeline: String, Sendable {
    case plain
    case signatureCleanup = "signature_cleanup"
    case thumbCleanup = "thumb_cleanup"
    case documentCleanup = "document_cleanup"

    var ink: InkVariant? {
        switch self {
        case .plain: nil
        case .signatureCleanup: .signature
        case .thumbCleanup: .thumb
        case .documentCleanup: .document
        }
    }
}

public struct PipelineResult: Sendable {
    public let fit: FitResult
    /// The raster that went into the fit: cropped, or cleaned and padded to aspect.
    public let prepared: Raster
    /// Present for the ink pipelines: the coverage gate result for the "too faint / too dark" warning.
    public let ink: InkResult?
}

/// crop/clean -> pad to aspect -> fit (ALGORITHMS sections 1.2, 3, 9.4-9.6). Pure given an injected encoder.
public struct FitPipeline: Sendable {
    private let fit: FitEngine

    public init(encoder: any JpegEncoder) {
        fit = FitEngine(encoder: encoder)
    }

    public func run(_ source: Raster, spec: DocSpec, pipeline: Pipeline = .plain, crop: CropRect? = nil,
                    options: FitOptions = FitOptions(), ink: InkOptions = InkOptions()) -> Result<PipelineResult, FitError> {
        let aspect = Geometry.targetAspect(spec)
        var inkResult: InkResult?
        let prepared: Raster
        if let variant = pipeline.ink {
            let cleaned = InkCleanup.clean(source, variant: variant, options: ink)
            inkResult = cleaned
            prepared = Self.padded(cleaned.raster, aspect)
        } else if FitProfile.cropsToAspect(spec.type) {
            let wanted = crop ?? aspect.map { Geometry.defaultCrop(srcW: source.width, srcH: source.height, aspect: $0) }
            if let rect = Self.clamped(source, wanted) {
                prepared = source.crop(x: rect.x, y: rect.y, width: rect.w, height: rect.h)
            } else {
                prepared = source
            }
        } else {
            prepared = Self.padded(source, aspect)
        }
        switch fit.fit(prepared, spec: spec, options: options) {
        case let .success(result): return .success(PipelineResult(fit: result, prepared: prepared, ink: inkResult))
        case let .failure(error): return .failure(error)
        }
    }

    private static func padded(_ r: Raster, _ aspect: Double?) -> Raster {
        guard let aspect else { return r }
        let plan = Geometry.padToAspect(srcW: r.width, srcH: r.height, aspect: aspect)
        return (plan.w == r.width && plan.h == r.height) ? r : r.crop(x: -plan.x, y: -plan.y, width: plan.w, height: plan.h)
    }

    private static func clamped(_ r: Raster, _ c: CropRect?) -> CropRect? {
        guard let c else { return nil }
        let x = min(max(c.x, 0), r.width - 1)
        let y = min(max(c.y, 0), r.height - 1)
        return CropRect(x: x, y: y, w: min(max(c.w, 1), r.width - x), h: min(max(c.h, 1), r.height - y))
    }
}

public extension CropRect {
    /// Maps a crop given in original-source pixels onto a downsampled raster (ALGORITHMS 9.9).
    func scaled(srcW: Int, srcH: Int, to decoded: Raster) -> CropRect {
        let sx = Double(decoded.width) / Double(srcW)
        let sy = Double(decoded.height) / Double(srcH)
        return CropRect(x: roundHalfUp(Double(x) * sx), y: roundHalfUp(Double(y) * sy),
                        w: max(1, roundHalfUp(Double(w) * sx)), h: max(1, roundHalfUp(Double(h) * sy)))
    }
}
