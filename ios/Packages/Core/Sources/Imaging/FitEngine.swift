import Foundation
import ScanModel

/// Fits a raster into a slot's size window (ALGORITHMS 1.3, 1.4 and 9.4): quality search, downscale ladder, upscale
/// ladder, then COM padding as the last resort. Pure: the encoder is injected, bytes in and bytes out.
public struct FitEngine: Sendable {
    private let encoder: any JpegEncoder

    public init(encoder: any JpegEncoder) {
        self.encoder = encoder
    }

    private struct Window {
        let goalLo: Double, goalHi: Double, target: Double
    }

    private enum Search {
        case hit(quality: Int, bytes: [UInt8])
        case tooBig
        case tooSmall(maxBytes: [UInt8])
    }

    /// Encode + patch, so every size the search sees is the size of the final file (ALGORITHMS 9.4).
    private final class Measure {
        let encoder: any JpegEncoder
        let dpi: Int
        let allowGrayscale: Bool
        var encodes = 0
        var failed = false

        init(_ encoder: any JpegEncoder, dpi: Int, allowGrayscale: Bool) {
            self.encoder = encoder
            self.dpi = dpi
            self.allowGrayscale = allowGrayscale
        }

        func encode(_ raster: Raster, _ quality: Int) -> [UInt8] {
            encodes += 1
            switch JpegPatcher.patch(encoder.encode(raster, quality: quality), dpi: dpi, allowGrayscale: allowGrayscale) {
            case let .success(bytes): return bytes
            case .failure:
                failed = true
                return []
            }
        }
    }

    private static let qLow = 35, qHigh = 95, qMax = 100
    private static let marginFraction = 0.05, ladderDown = 0.85, ladderUp = 1.25

    public func fit(_ source: Raster, spec: DocSpec, options: FitOptions = FitOptions()) -> Result<FitResult, FitError> {
        guard spec.formats.contains(.jpg) || spec.formats.contains(.jpeg) else { return .failure(.unsupportedFormat) }
        guard let max = spec.sizeKb.max else { return .failure(.unknownLimit) }
        let window = Self.window(min: spec.sizeKb.min ?? 0, max: max, target: spec.sizeKb.target)
        let measure = Measure(encoder, dpi: spec.dpi ?? JpegPatcher.defaultDpi, allowGrayscale: options.allowGrayscale)
        let start = Geometry.startSize(spec, srcW: source.width, srcH: source.height)
        let mode = spec.dimensions.mode

        func render(_ scale: Double) -> Raster {
            let s = Geometry.scaled(start, scale)
            return Resampler.resize(source, width: s.w, height: s.h)
        }

        var first = search(render(1.0), window, measure)
        if measure.failed { return .failure(.encodingFailed) }
        var hitScale = 1.0
        var downscaled = false
        var upscaled = false
        var lastSmall: [UInt8]?
        var lastSmallScale = 1.0
        if case let .tooSmall(bytes) = first { lastSmall = bytes }

        if case .tooBig = first {
            if mode == .exact || mode == .preferred { return .failure(.tooDetailed) }
            var k = 1
            while case .tooBig = first {
                let scale = pow(Self.ladderDown, Double(k))
                k += 1
                if Self.belowFloor(spec, Geometry.scaled(start, scale)) { return .failure(.tooDetailed) }
                first = search(render(scale), window, measure)
                if measure.failed { return .failure(.encodingFailed) }
                hitScale = scale
                downscaled = true
            }
            if case let .tooSmall(bytes) = first {
                lastSmall = bytes
                lastSmallScale = hitScale
            }
        }

        if case .tooSmall = first, mode != .exact, options.minFill != .padOnly {
            var k = 1
            while case .tooSmall = first {
                let scale = pow(Self.ladderUp, Double(k))
                k += 1
                if !Geometry.withinUpscaleCap(spec, start: start, candidate: Geometry.scaled(start, scale)) { break }
                first = search(render(scale), window, measure)
                if measure.failed { return .failure(.encodingFailed) }
                upscaled = true
                if case let .tooSmall(bytes) = first {
                    lastSmall = bytes
                    lastSmallScale = scale
                } else {
                    hitScale = scale
                }
            }
        }

        switch first {
        case let .hit(quality, bytes):
            let size = Geometry.scaled(start, hitScale)
            return .success(FitResult(bytes: bytes, width: size.w, height: size.h, quality: quality,
                                      strategy: Self.strategy(down: downscaled, up: upscaled, padded: false), encodes: measure.encodes))
        case let .tooSmall(bytes):
            let size = Geometry.scaled(start, lastSmallScale)
            let padded = JpegPatcher.pad(lastSmall ?? bytes, to: roundHalfUp(window.target * 1024))
            return .success(FitResult(bytes: padded, width: size.w, height: size.h, quality: Self.qMax,
                                      strategy: Self.strategy(down: false, up: upscaled, padded: true), encodes: measure.encodes))
        case .tooBig:
            return .failure(.tooDetailed)
        }
    }

    private static func strategy(down: Bool, up: Bool, padded: Bool) -> FitStrategy {
        if padded { return up ? .upscaleThenPad : .pad }
        if up { return .upscale }
        return down ? .downscale : .qualitySearch
    }

    private static func window(min: Double, max: Double, target: Double?) -> Window {
        let margin = Swift.max(1.0, marginFraction * (max - min))
        var lo = min + margin
        var hi = max - margin
        if lo > hi { // window < 2 KB: a zero-width band could never be hit, so use the whole window
            lo = min
            hi = max
        }
        let t = Swift.min(Swift.max(target ?? ((min + max) / 2), lo), hi)
        return Window(goalLo: lo, goalHi: hi, target: t)
    }

    private static func belowFloor(_ spec: DocSpec, _ s: Size) -> Bool {
        let d = spec.dimensions
        if d.mode == .range { return s.w < (d.minW ?? 0) || s.h < (d.minH ?? 0) }
        return Swift.max(s.w, s.h) < FitProfile.of(spec.type).noneFloor
    }

    /// The search at fixed dimensions (ALGORITHMS 9.4): at most 8 encodes.
    private func search(_ raster: Raster, _ w: Window, _ m: Measure) -> Search {
        func kb(_ b: [UInt8]) -> Double { Double(b.count) / 1024.0 }
        let e95 = m.encode(raster, Self.qHigh)
        if m.failed { return .tooBig }
        if kb(e95) < w.goalLo {
            let e100 = m.encode(raster, Self.qMax)
            return (w.goalLo...w.goalHi).contains(kb(e100)) ? .hit(quality: Self.qMax, bytes: e100) : .tooSmall(maxBytes: e100)
        }
        if kb(e95) <= w.goalHi, kb(e95) <= w.target { return .hit(quality: Self.qHigh, bytes: e95) }
        let e35 = m.encode(raster, Self.qLow)
        if kb(e35) > w.goalHi { return .tooBig }
        if kb(e35) > w.target { return .hit(quality: Self.qLow, bytes: e35) }
        var lo = Self.qLow, hi = Self.qHigh
        var eLo = e35, eHi = e95
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            let e = m.encode(raster, mid)
            if kb(e) <= w.target {
                lo = mid
                eLo = e
            } else {
                hi = mid
                eHi = e
            }
        }
        let hiValid = (w.goalLo...w.goalHi).contains(kb(eHi))
        let loValid = (w.goalLo...w.goalHi).contains(kb(eLo))
        if loValid && hiValid {
            return abs(kb(eHi) - w.target) < abs(kb(eLo) - w.target) ? .hit(quality: hi, bytes: eHi) : .hit(quality: lo, bytes: eLo)
        }
        if hiValid { return .hit(quality: hi, bytes: eHi) }
        if loValid { return .hit(quality: lo, bytes: eLo) }
        return .tooBig
    }
}
