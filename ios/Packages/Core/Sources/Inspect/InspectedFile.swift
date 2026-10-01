import Foundation

public enum DetectedFormat: String, Sendable, Equatable {
    case jpeg, png, pdf, heic, webp, unknown
}

public enum ColorKind: String, Sendable, Equatable {
    case rgb, gray, cmyk, unknown
}

/// Issue codes from ALGORITHMS section 5. Slot-specific ones are produced by the match engine (section 9.7).
public enum Issue: String, Sendable, Equatable, CaseIterable {
    case extensionMismatch = "EXTENSION_MISMATCH"
    case cmykColor = "CMYK_COLOR"
    case progressiveJpeg = "PROGRESSIVE_JPEG"
    case hasGpsExif = "HAS_GPS_EXIF"
    case rotatedByExif = "ROTATED_BY_EXIF"
    case heicNotAccepted = "HEIC_NOT_ACCEPTED"
    case pdfEncrypted = "PDF_ENCRYPTED"
    case tooSmallKb = "TOO_SMALL_KB"
    case tooLargeKb = "TOO_LARGE_KB"
    case wrongDimensions = "WRONG_DIMENSIONS"
    case wrongAspect = "WRONG_ASPECT"
    case lowDpiMetadata = "LOW_DPI_METADATA"
    case grayscaleNotAllowed = "GRAYSCALE_NOT_ALLOWED"
}

/// JFIF density as stored: `units` 0 = aspect only, 1 = dots/inch, 2 = dots/cm.
public struct JfifDensity: Sendable, Equatable {
    public let units: Int
    public let xDensity: Int
    public let yDensity: Int

    public init(units: Int, xDensity: Int, yDensity: Int) {
        self.units = units
        self.xDensity = xDensity
        self.yDensity = yDensity
    }
}

/// Everything the inspector learned about a file (ALGORITHMS 9.2). Fields that do not apply are nil/false.
public struct InspectedFile: Sendable, Equatable {
    public var format: DetectedFormat
    public var bytes: Int
    public var width: Int?
    public var height: Int?
    public var color: ColorKind = .unknown
    public var components: Int?
    public var progressive = false
    public var sof: String?
    public var jfif: JfifDensity?
    public var exifOrientation: Int?
    public var hasExif = false
    public var hasGps = false
    public var hasIcc = false
    public var hasXmp = false
    public var hasAdobe = false
    public var pdfPages: Int?
    public var pdfEncrypted = false
    public var pdfImageOnly = false
    public var issues: [Issue] = []

    public init(format: DetectedFormat, bytes: Int) {
        self.format = format
        self.bytes = bytes
    }

    public var kb: Double { Double(bytes) / 1024.0 }
}
