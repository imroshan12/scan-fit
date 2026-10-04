import Foundation

/// Remote Config `min_fill_strategy` (ALGORITHMS 1.4).
public enum MinFillStrategy: Sendable { case upscaleThenPad, padOnly }

/// How the final file reached its window. The raw value is the analytics `fit_strategy` value.
public enum FitStrategy: String, Sendable, Equatable {
    case qualitySearch = "quality_search"
    case downscale
    case upscale
    case pad
    case upscaleThenPad = "upscale_then_pad"
}

public enum FitError: String, Error, Sendable, Equatable {
    /// Cannot get under the maximum even at the lowest quality and smallest allowed size:
    /// re-crop or plainer background.
    case tooDetailed = "TOO_DETAILED"
    /// The slot has no maximum size, so there is nothing to fit to (the spec is not verified yet).
    case unknownLimit = "UNKNOWN_LIMIT"
    /// The slot does not accept JPEG (PDF slots use the PDF engine).
    case unsupportedFormat = "UNSUPPORTED_FORMAT"
    /// The encoder produced bytes the patcher refused (a pipeline bug, surfaced instead of exported).
    case encodingFailed = "ENCODING_FAILED"
}

public struct FitOptions: Sendable, Equatable {
    public var minFill: MinFillStrategy
    public var allowGrayscale: Bool

    public init(minFill: MinFillStrategy = .upscaleThenPad, allowGrayscale: Bool = false) {
        self.minFill = minFill
        self.allowGrayscale = allowGrayscale
    }
}

public struct FitResult: Sendable, Equatable {
    /// The final, patched (and possibly padded) JPEG, ready to write.
    public let bytes: [UInt8]
    public let width: Int
    public let height: Int
    /// JPEG quality used (35-100).
    public let quality: Int
    public let strategy: FitStrategy
    public let encodes: Int

    public init(bytes: [UInt8], width: Int, height: Int, quality: Int, strategy: FitStrategy, encodes: Int) {
        self.bytes = bytes
        self.width = width
        self.height = height
        self.quality = quality
        self.strategy = strategy
        self.encodes = encodes
    }

    public var kb: Double { Double(bytes.count) / 1024.0 }
}

/// Produces JPEG bytes for a raster at a quality (35-100). Platform encoders (and test encoders) implement this.
public protocol JpegEncoder: Sendable {
    func encode(_ raster: Raster, quality: Int) -> [UInt8]
}
