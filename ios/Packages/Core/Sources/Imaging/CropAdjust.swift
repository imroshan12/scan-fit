import Foundation

/// The user's adjustments on the crop screen (ALGORITHMS 9.6 "Manual adjust"): the crop stays aspect-locked and inside
/// the image, and its short side never drops below 64 px. Pure, so both apps can be checked against `crop_cases`.
public enum CropAdjust {
    private static let minShortSide = 64

    /// Moves by (`dx`, `dy`) raster pixels, clamped so the crop stays inside the `imgW×imgH` image.
    public static func move(_ rect: CropRect, dx: Double, dy: Double, imgW: Int, imgH: Int) -> CropRect {
        CropRect(
            x: min(max(roundHalfUp(Double(rect.x) + dx), 0), max(0, imgW - rect.w)),
            y: min(max(roundHalfUp(Double(rect.y) + dy), 0), max(0, imgH - rect.h)),
            w: rect.w,
            h: rect.h
        )
    }

    /// Zooms by `factor` (> 1 zooms in, i.e. a smaller crop) around the crop's centre, between 64 px and the
    /// largest crop.
    public static func zoom(_ rect: CropRect, factor: Double, aspect: Double, imgW: Int, imgH: Int) -> CropRect {
        precondition(factor > 0 && aspect > 0, "invalid zoom input")
        let maxH = min(imgH, Int((Double(imgW) / aspect).rounded(.down)))
        let minH = min(maxH, aspect >= 1 ? minShortSide : Int((Double(minShortSide) / aspect).rounded(.up)))
        let h = min(max(roundHalfUp(Double(rect.h) / factor), minH), maxH)
        let w = min(imgW, roundHalfUp(Double(h) * aspect))
        let x = roundHalfUp(Double(rect.x) + Double(rect.w) / 2 - Double(w) / 2)
        let y = roundHalfUp(Double(rect.y) + Double(rect.h) / 2 - Double(h) / 2)
        return CropRect(x: min(max(x, 0), imgW - w), y: min(max(y, 0), imgH - h), w: w, h: h)
    }

    /// The raster turned 90° clockwise (EXIF transform 6), for the crop screen's Rotate button.
    public static func rotateClockwise(_ raster: Raster) -> Raster { Orientation.apply(raster, 6) }
}
