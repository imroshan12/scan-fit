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

    /**
     * The ink flow's free crop (ALGORITHMS 9.5): the dragged [corner] moves by ([dx], [dy]), the opposite corner stays,
     * and each side stays at least 32 px (or the whole image when it is smaller) and inside the image.
     */
    fun resize(
        rect: CropRect,
        corner: CropCorner,
        dx: Double,
        dy: Double,
        imgW: Int,
        imgH: Int,
    ): CropRect {
        val minW = minOf(MIN_FREE_SIDE, imgW)
        val minH = minOf(MIN_FREE_SIDE, imgH)
        var left = rect.x
        var top = rect.y
        var right = rect.x + rect.w
        var bottom = rect.y + rect.h
        if (corner.left) {
            left = roundHalfUp(rect.x + dx).coerceIn(0, maxOf(0, right - minW))
        } else {
            right = roundHalfUp(rect.x + rect.w + dx).coerceIn(minOf(left + minW, imgW), imgW)
        }
        if (corner.top) {
            top = roundHalfUp(rect.y + dy).coerceIn(0, maxOf(0, bottom - minH))
        } else {
            bottom = roundHalfUp(rect.y + rect.h + dy).coerceIn(minOf(top + minH, imgH), imgH)
        }
        return CropRect(left, top, right - left, bottom - top)
    }

    /** The raster turned 90° clockwise (EXIF transform 6), for the crop screen's Rotate button. */
    fun rotateClockwise(raster: Raster): Raster = Orientation.apply(raster, ROTATE_CW)

    private const val ROTATE_CW = 6
    private const val MIN_FREE_SIDE = 32
}

/** A corner of the free crop. `wire` is the name used in `crop_cases`. */
enum class CropCorner(
    val wire: String,
    val left: Boolean,
    val top: Boolean,
) {
    TOP_LEFT("tl", left = true, top = true),
    TOP_RIGHT("tr", left = false, top = true),
    BOTTOM_LEFT("bl", left = true, top = false),
    BOTTOM_RIGHT("br", left = false, top = false),
    ;

    companion object {
        fun of(wire: String): CropCorner = entries.first { it.wire == wire }
    }
}
