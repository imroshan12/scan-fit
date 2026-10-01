import Foundation

/// A detected face box in raster pixels (the ML wrappers return this type; the framing math stays pure).
public struct Face: Sendable, Equatable {
    public let x: Double, y: Double, w: Double, h: Double

    public init(x: Double, y: Double, w: Double, h: Double) {
        self.x = x
        self.y = y
        self.w = w
        self.h = h
    }

    public var centerX: Double { x + w / 2 }
}

public struct Framing: Sendable, Equatable {
    public let crop: CropRect
    public let coverageAdjusted: Bool
}

/// Face-aware crop (ALGORITHMS 2.2 / 9.6): face 60-75 % of the height, centred, crown 10 % from the top.
public enum AutoFraming {
    public static let defaultCoverage = 0.675
    private static let chinToHairline = 1.25
    private static let crownAboveFace = 0.125
    private static let crownFromTop = 0.10

    /// - Parameters:
    ///   - aspect: output width / height of the slot
    ///   - coverage: fraction of the crop height taken by the chin-to-hairline span
    ///     (a preset `face_coverage` overrides)
    public static func frame(
        _ face: Face,
        imgW: Int,
        imgH: Int,
        aspect: Double,
        coverage: Double = defaultCoverage
    ) -> Framing {
        precondition(imgW > 0 && imgH > 0 && aspect > 0 && coverage > 0, "invalid framing input")
        var h = roundHalfUp(chinToHairline * face.h / coverage)
        var w = roundHalfUp(Double(h) * aspect)
        var shrunk = false
        if w > imgW || h > imgH {
            let s = min(Double(imgW) / Double(w), Double(imgH) / Double(h))
            h = max(1, Int((Double(h) * s).rounded(.down)))
            w = roundHalfUp(Double(h) * aspect)
            if w > imgW {
                w = imgW
                h = max(1, roundHalfUp(Double(w) / aspect))
            }
            shrunk = true
        }
        let crown = face.y - crownAboveFace * face.h
        let x = min(max(roundHalfUp(face.x + face.w / 2 - Double(w) / 2), 0), imgW - w)
        let y = min(max(roundHalfUp(crown - crownFromTop * Double(h)), 0), imgH - h)
        return Framing(crop: CropRect(x: x, y: y, w: w, h: h), coverageAdjusted: shrunk)
    }
}
