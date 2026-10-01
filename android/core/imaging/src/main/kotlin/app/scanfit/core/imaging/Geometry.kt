package app.scanfit.core.imaging

import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType

data class Size(
    val w: Int,
    val h: Int,
) {
    val aspect: Double get() = w.toDouble() / h
}

data class CropRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** Canvas size and where the source sits on it (ALGORITHMS 9.1 "pad to aspect"). */
data class PadPlan(
    val w: Int,
    val h: Int,
    val x: Int,
    val y: Int,
)

/** How a document type is treated by size ladders: its long-side defaults and floors (ALGORITHMS 9.4). */
enum class FitProfile(
    val noneLongSide: Int,
    val noneFloor: Int,
) {
    PHOTO(noneLongSide = 1200, noneFloor = 600),
    INK_SMALL(noneLongSide = 1000, noneFloor = 400),
    DOCUMENT(noneLongSide = 1600, noneFloor = 600),
    ;

    companion object {
        fun of(type: DocType): FitProfile = when (type) {
            DocType.PHOTO, DocType.POSTCARD_PHOTO -> PHOTO

            DocType.SIGNATURE, DocType.TRIPLE_SIGNATURE, DocType.LEFT_THUMB, DocType.THUMB_IMPRESSION,
            DocType.LEFT_HAND_FINGERS_THUMB, DocType.RIGHT_HAND_FINGERS_THUMB,
            -> INK_SMALL

            else -> DOCUMENT
        }

        /** Photos are cropped to the slot aspect; everything else is padded with white (ALGORITHMS 1.2). */
        fun cropsToAspect(type: DocType) = of(type) == PHOTO
    }
}

object Geometry {
    private const val NONE_UPSCALE_CAP = 2000

    /** Target aspect of a slot, or null for `none` (free ratio). Range mode uses the midpoint (ALGORITHMS 9.4). */
    fun targetAspect(spec: DocSpec): Double? {
        val d = spec.dimensions
        return when (d.mode) {
            DimensionMode.EXACT, DimensionMode.PREFERRED -> {
                checkNotNull(d.width).toDouble() / checkNotNull(d.height)
            }

            DimensionMode.RANGE -> {
                d.aspectWOverH?.let { (checkNotNull(it.min) + checkNotNull(it.max)) / 2 }
                    ?: rangeMidAspect(spec)
            }

            DimensionMode.NONE -> {
                null
            }
        }
    }

    private fun rangeMidAspect(spec: DocSpec): Double {
        val d = spec.dimensions
        return ((checkNotNull(d.minW) + checkNotNull(d.maxW)) / 2.0) /
            ((checkNotNull(d.minH) + checkNotNull(d.maxH)) / 2.0)
    }

    /** Largest centred rectangle with aspect [aspect] inside a `W x H` source. */
    fun defaultCrop(
        srcW: Int,
        srcH: Int,
        aspect: Double,
    ): CropRect {
        val w: Int
        val h: Int
        if (srcW.toDouble() / srcH > aspect) {
            h = srcH
            w = roundHalfUp(srcH * aspect)
        } else {
            w = srcW
            h = roundHalfUp(srcW / aspect)
        }
        return CropRect((srcW - w) / 2, (srcH - h) / 2, w, h)
    }

    /** White canvas of aspect [aspect] that contains the source, centred. */
    fun padToAspect(
        srcW: Int,
        srcH: Int,
        aspect: Double,
    ): PadPlan {
        val cw: Int
        val ch: Int
        if (srcW.toDouble() / srcH < aspect) {
            cw = roundHalfUp(srcH * aspect)
            ch = srcH
        } else {
            cw = srcW
            ch = roundHalfUp(srcW / aspect)
        }
        return PadPlan(cw, ch, (cw - srcW) / 2, (ch - srcH) / 2)
    }

    /** The size the fit search starts at (ALGORITHMS 9.4) for an already-cropped input of `srcW x srcH`. */
    fun startSize(
        spec: DocSpec,
        srcW: Int,
        srcH: Int,
    ): Size {
        val d = spec.dimensions
        return when (d.mode) {
            DimensionMode.EXACT, DimensionMode.PREFERRED -> {
                Size(checkNotNull(d.width), checkNotNull(d.height))
            }

            DimensionMode.RANGE -> {
                rangeStart(spec)
            }

            DimensionMode.NONE -> {
                val long = minOf(FitProfile.of(spec.type).noneLongSide, maxOf(srcW, srcH))
                if (srcW >=
                    srcH
                ) {
                    Size(long, roundHalfUp(long.toDouble() * srcH / srcW))
                } else {
                    Size(
                        roundHalfUp(long.toDouble() * srcW / srcH),
                        long,
                    )
                }
            }
        }
    }

    private fun rangeStart(spec: DocSpec): Size {
        val d = spec.dimensions
        val minW = checkNotNull(d.minW)
        val maxW = checkNotNull(d.maxW)
        val minH = checkNotNull(d.minH)
        val maxH = checkNotNull(d.maxH)
        val a = targetAspect(spec) ?: error("range needs an aspect")
        var w = roundHalfUp((minW + maxW) / 2.0)
        var h = roundHalfUp(w / a)
        if (h < minH || h > maxH) {
            h = h.coerceIn(minH, maxH)
            w = roundHalfUp(h * a)
        }
        return Size(w.coerceIn(minW, maxW), h)
    }

    /** Dimensions at [scale] measured from the start size (never compounded). */
    fun scaled(
        start: Size,
        scale: Double,
    ) = Size(
        maxOf(1, roundHalfUp(start.w * scale)),
        maxOf(
            1,
            roundHalfUp(start.h * scale),
        ),
    )

    /** Whether [candidate] is inside the upscale cap of section 9.4 (`exact` never scales). */
    fun withinUpscaleCap(
        spec: DocSpec,
        start: Size,
        candidate: Size,
    ): Boolean {
        val d = spec.dimensions
        return when (d.mode) {
            DimensionMode.EXACT -> false
            DimensionMode.PREFERRED -> candidate.w <= start.w * 2 && candidate.h <= start.h * 2
            DimensionMode.RANGE -> candidate.w <= checkNotNull(d.maxW) && candidate.h <= checkNotNull(d.maxH)
            DimensionMode.NONE -> maxOf(candidate.w, candidate.h) <= NONE_UPSCALE_CAP
        }
    }
}
