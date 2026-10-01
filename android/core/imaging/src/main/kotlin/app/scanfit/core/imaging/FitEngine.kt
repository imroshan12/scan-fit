package app.scanfit.core.imaging

import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.FileFormat
import kotlin.math.abs
import kotlin.math.pow

/**
 * Fits a raster into a slot's size window (ALGORITHMS 1.3, 1.4 and 9.4): quality search, downscale ladder, upscale
 * ladder, then COM padding as the last resort. Pure: the encoder is injected, bytes in and bytes out.
 */
class FitEngine(
    private val encoder: JpegEncoder,
) {
    private class Window(
        val goalLo: Double,
        val goalHi: Double,
        val target: Double,
    )

    private sealed interface Search {
        class Hit(
            val quality: Int,
            val bytes: ByteArray,
        ) : Search

        object TooBig : Search

        class TooSmall(
            val maxBytes: ByteArray,
        ) : Search
    }

    fun fit(
        source: Raster,
        spec: DocSpec,
        options: FitOptions = FitOptions(),
    ): FitOutcome {
        if (FileFormat.JPG !in spec.formats &&
            FileFormat.JPEG !in spec.formats
        ) {
            return failure(FitError.UNSUPPORTED_FORMAT)
        }
        val max = spec.sizeKb.max ?: return failure(FitError.UNKNOWN_LIMIT)
        val window = window(spec.sizeKb.min ?: 0.0, max, spec.sizeKb.target)
        val dpi = spec.dpi ?: JpegPatcherDefaults.DPI
        val counter = intArrayOf(0)
        val measure = Measure(encoder, dpi, options.allowGrayscale, counter)
        val start = Geometry.startSize(spec, source.width, source.height)
        val mode = spec.dimensions.mode

        fun render(scale: Double): Raster {
            val s = Geometry.scaled(start, scale)
            return Resampler.resize(source, s.w, s.h)
        }

        return try {
            var raster = render(1.0)
            var first = search(raster, window, measure)
            var hitScale = 1.0
            var downscaled = false
            var upscaled = false
            var lastSmall = first as? Search.TooSmall
            var lastSmallScale = 1.0

            if (first is Search.TooBig) {
                if (mode == DimensionMode.EXACT ||
                    mode == DimensionMode.PREFERRED
                ) {
                    return failure(FitError.TOO_DETAILED)
                }
                var k = 1
                while (first is Search.TooBig) {
                    val scale = LADDER_DOWN.pow(k++)
                    if (belowFloor(spec, start, Geometry.scaled(start, scale))) return failure(FitError.TOO_DETAILED)
                    raster = render(scale)
                    first = search(raster, window, measure)
                    hitScale = scale
                    downscaled = true
                }
                lastSmall = first as? Search.TooSmall
                lastSmallScale = hitScale
            }

            if (first is Search.TooSmall && mode != DimensionMode.EXACT &&
                options.minFill != MinFillStrategy.PAD_ONLY
            ) {
                var k = 1
                while (first is Search.TooSmall) {
                    val scale = LADDER_UP.pow(k++)
                    if (!Geometry.withinUpscaleCap(spec, start, Geometry.scaled(start, scale))) break
                    raster = render(scale)
                    first = search(raster, window, measure)
                    upscaled = true
                    if (first is Search.TooSmall) {
                        lastSmall = first
                        lastSmallScale = scale
                    } else {
                        hitScale = scale
                    }
                }
            }

            when (val r = first) {
                is Search.Hit -> {
                    success(
                        r.bytes,
                        r.quality,
                        Geometry.scaled(start, hitScale),
                        counter[0],
                        strategy(downscaled, upscaled, false),
                    )
                }

                is Search.TooSmall -> {
                    val finalSize = Geometry.scaled(start, lastSmallScale)
                    val padded = JpegPatcher.pad((lastSmall ?: r).maxBytes, roundHalfUp(window.target * BYTES_PER_KB))
                    success(padded, MAX_QUALITY, finalSize, counter[0], strategy(false, upscaled, true))
                }

                Search.TooBig -> {
                    failure(FitError.TOO_DETAILED)
                }
            }
        } catch (e: EncodingFailure) {
            failure(e.error)
        }
    }

    private fun strategy(
        down: Boolean,
        up: Boolean,
        padded: Boolean,
    ) = when {
        padded && up -> FitStrategy.UPSCALE_THEN_PAD
        padded -> FitStrategy.PAD
        up -> FitStrategy.UPSCALE
        down -> FitStrategy.DOWNSCALE
        else -> FitStrategy.QUALITY_SEARCH
    }

    private fun success(
        bytes: ByteArray,
        q: Int,
        size: Size,
        encodes: Int,
        strategy: FitStrategy,
    ) = FitOutcome.Success(FitResult(bytes, size.w, size.h, q, strategy, encodes))

    private fun failure(e: FitError) = FitOutcome.Failure(e)

    private fun window(
        min: Double,
        max: Double,
        target: Double?,
    ): Window {
        val margin = maxOf(1.0, MARGIN_FRACTION * (max - min))
        var lo = min + margin
        var hi = max - margin
        if (lo > hi) { // window < 2 KB: a zero-width band could never be hit, so use the whole window
            lo = min
            hi = max
        }
        val t = (target ?: ((min + max) / 2)).coerceIn(lo, hi)
        return Window(lo, hi, t)
    }

    private fun belowFloor(
        spec: DocSpec,
        start: Size,
        s: Size,
    ): Boolean {
        val d = spec.dimensions
        return when (d.mode) {
            DimensionMode.RANGE -> s.w < checkNotNull(d.minW) || s.h < checkNotNull(d.minH)
            else -> maxOf(s.w, s.h) < FitProfile.of(spec.type).noneFloor
        }.also { check(start.w > 0) }
    }

    /** The search at fixed dimensions (ALGORITHMS 9.4): at most 8 encodes. */
    private fun search(
        raster: Raster,
        w: Window,
        m: Measure,
    ): Search {
        val e95 = m.encode(raster, Q_HIGH)
        val s95 = kb(e95)
        if (s95 < w.goalLo) {
            val e100 = m.encode(raster, MAX_QUALITY)
            return if (kb(e100) in w.goalLo..w.goalHi) Search.Hit(MAX_QUALITY, e100) else Search.TooSmall(e100)
        }
        if (s95 <= w.goalHi && s95 <= w.target) return Search.Hit(Q_HIGH, e95)
        val e35 = m.encode(raster, Q_LOW)
        if (kb(e35) > w.goalHi) return Search.TooBig
        if (kb(e35) > w.target) return Search.Hit(Q_LOW, e35)
        var lo = Q_LOW
        var hi = Q_HIGH
        var eLo = e35
        var eHi = e95
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            val e = m.encode(raster, mid)
            if (kb(e) <= w.target) {
                lo = mid
                eLo = e
            } else {
                hi = mid
                eHi = e
            }
        }
        val hiValid = kb(eHi) in w.goalLo..w.goalHi
        val loValid = kb(eLo) in w.goalLo..w.goalHi
        return when {
            loValid && hiValid -> {
                if (abs(kb(eHi) - w.target) <
                    abs(kb(eLo) - w.target)
                ) {
                    Search.Hit(hi, eHi)
                } else {
                    Search.Hit(lo, eLo)
                }
            }

            hiValid -> {
                Search.Hit(hi, eHi)
            }

            loValid -> {
                Search.Hit(lo, eLo)
            }

            else -> {
                Search.TooBig
            }
        }
    }

    private fun kb(b: ByteArray) = b.size / BYTES_PER_KB

    /** Encode + patch, so every size the search sees is the size of the final file (ALGORITHMS 9.4). */
    private class Measure(
        val encoder: JpegEncoder,
        val dpi: Int,
        val allowGrayscale: Boolean,
        val counter: IntArray,
    ) {
        fun encode(
            r: Raster,
            q: Int,
        ): ByteArray {
            counter[0]++
            return when (val p = JpegPatcher.patch(encoder.encode(r, q), dpi, allowGrayscale)) {
                is PatchResult.Ok -> p.bytes
                is PatchResult.Failed -> throw EncodingFailure(FitError.ENCODING_FAILED)
            }
        }
    }

    private class EncodingFailure(
        val error: FitError,
    ) : RuntimeException()

    private companion object {
        const val Q_LOW = 35
        const val Q_HIGH = 95
        const val MAX_QUALITY = 100
        const val MARGIN_FRACTION = 0.05
        const val LADDER_DOWN = 0.85
        const val LADDER_UP = 1.25
        const val BYTES_PER_KB = 1024.0
    }
}

internal object JpegPatcherDefaults {
    const val DPI = 200
}
