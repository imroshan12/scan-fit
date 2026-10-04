package app.scanfit.core.imaging

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The user's adjustments on the crop screen (ALGORITHMS 9.6 "Manual adjust"): the crop stays aspect-locked and
 * inside the image, and its short side never drops below 64 px. Pure, so both apps are checked against `crop_cases`.
 */
object CropAdjust {
    private const val MIN_SHORT_SIDE = 64

    /** Moves by ([dx], [dy]) raster pixels, clamped so the crop stays inside the `imgW×imgH` image. */
    fun move(
        rect: CropRect,
        dx: Double,
        dy: Double,
        imgW: Int,
        imgH: Int,
    ): CropRect = rect.copy(
        x = roundHalfUp(rect.x + dx).coerceIn(0, maxOf(0, imgW - rect.w)),
        y = roundHalfUp(rect.y + dy).coerceIn(0, maxOf(0, imgH - rect.h)),
    )

    /** Zooms by [factor] (> 1 zooms in: a smaller crop) about the centre, between 64 px and the largest crop. */
    fun zoom(
        rect: CropRect,
        factor: Double,
        aspect: Double,
        imgW: Int,
        imgH: Int,
    ): CropRect {
        require(factor > 0 && aspect > 0)
        val maxH = minOf(imgH, floor(imgW / aspect).toInt())
        val minH = minOf(maxH, if (aspect >= 1) MIN_SHORT_SIDE else ceil(MIN_SHORT_SIDE / aspect).toInt())
        val h = roundHalfUp(rect.h / factor).coerceIn(minH, maxH)
        val w = minOf(imgW, roundHalfUp(h * aspect))
        return CropRect(
            x = roundHalfUp(rect.x + rect.w / 2.0 - w / 2.0).coerceIn(0, imgW - w),
            y = roundHalfUp(rect.y + rect.h / 2.0 - h / 2.0).coerceIn(0, imgH - h),
            w = w,
            h = h,
        )
    }

    /** The raster turned 90° clockwise (EXIF transform 6), for the crop screen's Rotate button. */
    fun rotateClockwise(raster: Raster): Raster = Orientation.apply(raster, ROTATE_CW)

    private const val ROTATE_CW = 6
}
