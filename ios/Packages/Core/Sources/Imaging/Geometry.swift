import Foundation
import ScanModel

public struct Size: Sendable, Equatable {
    public let w: Int
    public let h: Int

    public init(_ w: Int, _ h: Int) {
        self.w = w
        self.h = h
    }

    public var aspect: Double { Double(w) / Double(h) }
}

public struct CropRect: Sendable, Equatable {
    public let x: Int, y: Int, w: Int, h: Int

    public init(x: Int, y: Int, w: Int, h: Int) {
        self.x = x
        self.y = y
        self.w = w
        self.h = h
    }
}

/// Canvas size and where the source sits on it (ALGORITHMS 9.1 "pad to aspect").
public struct PadPlan: Sendable, Equatable {
    public let w: Int, h: Int, x: Int, y: Int

    public init(w: Int, h: Int, x: Int, y: Int) {
        self.w = w
        self.h = h
        self.x = x
        self.y = y
    }
}

/// How a document type is treated by size ladders: its long-side default and floor (ALGORITHMS 9.4).
public enum FitProfile: Sendable {
    case photo, inkSmall, document

    public var noneLongSide: Int {
        switch self {
        case .photo: 1200
        case .inkSmall: 1000
        case .document: 1600
        }
    }

    public var noneFloor: Int { self == .inkSmall ? 400 : 600 }

    public static func of(_ type: DocType) -> FitProfile {
        switch type {
        case .photo, .postcardPhoto: .photo
        case .signature, .tripleSignature, .leftThumb, .thumbImpression,
             .leftHandFingersThumb, .rightHandFingersThumb: .inkSmall
        default: .document
        }
    }

    /// Photos are cropped to the slot aspect; everything else is padded with white (ALGORITHMS 1.2).
    public static func cropsToAspect(_ type: DocType) -> Bool { of(type) == .photo }
}

public enum Geometry {
    private static let noneUpscaleCap = 2000

    /// Target aspect of a slot, or nil for `none` (free ratio). Range mode uses the midpoint (ALGORITHMS 9.4).
    public static func targetAspect(_ spec: DocSpec) -> Double? {
        let d = spec.dimensions
        switch d.mode {
        case .exact, .preferred:
            guard let w = d.width, let h = d.height, h > 0 else { return nil }
            return Double(w) / Double(h)
        case .range:
            if let range = d.aspectWOverH, let lo = range.min, let hi = range.max { return (lo + hi) / 2 }
            guard let minW = d.minW, let maxW = d.maxW, let minH = d.minH, let maxH = d.maxH else { return nil }
            return (Double(minW + maxW) / 2) / (Double(minH + maxH) / 2)
        case .none:
            return nil
        }
    }

    /// Largest centred rectangle with aspect `aspect` inside a `W x H` source.
    public static func defaultCrop(srcW: Int, srcH: Int, aspect: Double) -> CropRect {
        let w: Int
        let h: Int
        if Double(srcW) / Double(srcH) > aspect {
            h = srcH
            w = roundHalfUp(Double(srcH) * aspect)
        } else {
            w = srcW
            h = roundHalfUp(Double(srcW) / aspect)
        }
        return CropRect(x: (srcW - w) / 2, y: (srcH - h) / 2, w: w, h: h)
    }

    /// White canvas of aspect `aspect` that contains the source, centred.
    public static func padToAspect(srcW: Int, srcH: Int, aspect: Double) -> PadPlan {
        let cw: Int
        let ch: Int
        if Double(srcW) / Double(srcH) < aspect {
            cw = roundHalfUp(Double(srcH) * aspect)
            ch = srcH
        } else {
            cw = srcW
            ch = roundHalfUp(Double(srcW) / aspect)
        }
        return PadPlan(w: cw, h: ch, x: (cw - srcW) / 2, y: (ch - srcH) / 2)
    }

    /// The size the fit search starts at (ALGORITHMS 9.4), for an already-cropped input of `srcW x srcH`.
    public static func startSize(_ spec: DocSpec, srcW: Int, srcH: Int) -> Size {
        let d = spec.dimensions
        switch d.mode {
        case .exact, .preferred:
            return Size(d.width ?? srcW, d.height ?? srcH)
        case .range:
            return rangeStart(spec, fallback: Size(srcW, srcH))
        case .none:
            let long = min(FitProfile.of(spec.type).noneLongSide, max(srcW, srcH))
            if srcW >= srcH { return Size(long, roundHalfUp(Double(long) * Double(srcH) / Double(srcW))) }
            return Size(roundHalfUp(Double(long) * Double(srcW) / Double(srcH)), long)
        }
    }

    private static func rangeStart(_ spec: DocSpec, fallback: Size) -> Size {
        let d = spec.dimensions
        guard let minW = d.minW, let maxW = d.maxW, let minH = d.minH, let maxH = d.maxH,
              let a = targetAspect(spec) else { return fallback }
        var w = roundHalfUp(Double(minW + maxW) / 2)
        var h = roundHalfUp(Double(w) / a)
        if h < minH || h > maxH {
            h = min(max(h, minH), maxH)
            w = roundHalfUp(Double(h) * a)
        }
        return Size(min(max(w, minW), maxW), h)
    }

    /// Dimensions at `scale` measured from the start size (never compounded).
    public static func scaled(_ start: Size, _ scale: Double) -> Size {
        Size(max(1, roundHalfUp(Double(start.w) * scale)), max(1, roundHalfUp(Double(start.h) * scale)))
    }

    /// Whether `candidate` is still inside the upscale cap of section 9.4. `exact` never scales, so it is never inside.
    public static func withinUpscaleCap(_ spec: DocSpec, start: Size, candidate: Size) -> Bool {
        let d = spec.dimensions
        switch d.mode {
        case .exact: return false
        case .preferred: return candidate.w <= start.w * 2 && candidate.h <= start.h * 2
        case .range: return candidate.w <= (d.maxW ?? 0) && candidate.h <= (d.maxH ?? 0)
        case .none: return max(candidate.w, candidate.h) <= noneUpscaleCap
        }
    }
}
