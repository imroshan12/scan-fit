package app.scanfit.core.imaging

import kotlin.math.pow

enum class InkVariant { SIGNATURE, DOCUMENT, THUMB }

/** Outcome of the coverage gate (ALGORITHMS 9.5): the UI warns "too faint" / "too dark or not on white paper". */
enum class InkQuality { OK, TOO_FAINT, TOO_DARK }

data class InkOptions(
    /** Null = the variant's default (signature: on, document: off). */
    val crispBlack: Boolean? = null,
    /** Ink colour = original pixel × this factor when not crisp black (the "Darker ink" slider; lower = darker). */
    val inkFactor: Double = DEFAULT_INK_FACTOR,
) {
    companion object {
        const val DEFAULT_INK_FACTOR = 0.6
    }
}

class InkResult(
    val raster: Raster,
    val quality: InkQuality,
    val coverage: Double,
)

/**
 * Turns a phone photo of ink on paper into black-on-white (ALGORITHMS section 3 / 9.5).
 * The input should already be rectified (document scanner or 4-corner crop) and at most 1600 px on its long side.
 */
object InkCleanup {
    private const val SIGMA_DIVISOR = 30.0
    private const val WINDOW_DIVISOR = 20
    private const val MIN_WINDOW = 15
    private const val SAUVOLA_K = 0.34
    private const val SAUVOLA_R = 128.0
    private const val SPECK_MIN = 4
    private const val SPECK_FRACTION = 0.0002
    private const val TRIM_PADDING = 0.08
    private const val THUMB_SIDE = 1.16
    private const val MIN_COVERAGE = 0.005
    private const val MAX_COVERAGE = 0.35
    private const val GAMMA = 0.8
    private const val LOW_PERCENTILE = 0.02
    private const val HIGH_PERCENTILE = 0.98
    private const val DARK = 128
    private const val BYTE = 255

    fun clean(
        src: Raster,
        variant: InkVariant,
        options: InkOptions = InkOptions(),
    ): InkResult {
        val w = src.width
        val h = src.height
        val luma = src.luma()
        val l = IntArray(w * h) { luma[it].toInt() and BYTE }
        val shortSide = minOf(w, h)
        val bg = ImageOps.gaussianApprox(l, w, h, shortSide / SIGMA_DIVISOR)
        val n = IntArray(w * h) { minOf(BYTE, (l[it] * BYTE + bg[it] / 2) / maxOf(bg[it], 1)) }
        return if (variant == InkVariant.THUMB) thumb(n, w, h) else binarised(src, n, w, h, variant, options)
    }

    private fun binarised(
        src: Raster,
        n: IntArray,
        w: Int,
        h: Int,
        variant: InkVariant,
        options: InkOptions,
    ): InkResult {
        var window = maxOf(MIN_WINDOW, minOf(w, h) / WINDOW_DIVISOR)
        if (window % 2 == 0) window++
        var mask = ImageOps.sauvola(n, w, h, window, SAUVOLA_K, SAUVOLA_R)
        if (variant == InkVariant.DOCUMENT) {
            mask = ImageOps.open3x3(mask, w, h)
            mask = ImageOps.removeSpecks(mask, w, h, maxOf(SPECK_MIN, (SPECK_FRACTION * w * h).toInt()))
        }

        val crisp = options.crispBlack ?: (variant == InkVariant.SIGNATURE)
        val out = ByteArray(w * h * Raster.CHANNELS) { -1 } // white
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x] == 0) continue
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
                val i = (y * w + x) * Raster.CHANNELS
                if (crisp) {
                    out[i] = 0
                    out[i + 1] = 0
                    out[i + 2] = 0
                } else {
                    out[i] = darken(src.r(x, y), options.inkFactor)
                    out[i + 1] = darken(src.g(x, y), options.inkFactor)
                    out[i + 2] = darken(src.b(x, y), options.inkFactor)
                }
            }
        }
        val rendered = Raster(w, h, out)
        if (maxX < 0) return InkResult(rendered, InkQuality.TOO_FAINT, 0.0)
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val pad = roundHalfUp(TRIM_PADDING * maxOf(bw, bh))
        val trimmed = rendered.crop(minX - pad, minY - pad, bw + 2 * pad, bh + 2 * pad)
        var ink = 0
        for (i in mask.indices) ink += mask[i]
        return InkResult(
            trimmed,
            gate(ink.toDouble() / (trimmed.width.toDouble() * trimmed.height)),
            ink.toDouble() / (trimmed.width.toDouble() * trimmed.height),
        )
    }

    private fun darken(
        v: Int,
        factor: Double,
    ) = roundHalfUp(v * factor).coerceIn(0, BYTE).toByte()

    private fun thumb(
        n: IntArray,
        w: Int,
        h: Int,
    ): InkResult {
        val hist = IntArray(BYTE + 1)
        for (v in n) hist[v]++
        val total = w * h
        val p2 = percentile(hist, total, LOW_PERCENTILE)
        val p98 = percentile(hist, total, HIGH_PERCENTILE)
        val lut =
            IntArray(BYTE + 1) { v ->
                val stretched = if (p98 > p2) ((v - p2) * BYTE / (p98 - p2)).coerceIn(0, BYTE) else v
                roundHalfUp(BYTE * (stretched / BYTE.toDouble()).pow(GAMMA)).coerceIn(0, BYTE)
            }
        val grey = ByteArray(w * h * Raster.CHANNELS)
        var count = 0L
        var sx = 0L
        var sy = 0L
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = lut[n[y * w + x]]
                val i = (y * w + x) * Raster.CHANNELS
                grey[i] = v.toByte()
                grey[i + 1] = v.toByte()
                grey[i + 2] = v.toByte()
                if (v < DARK) {
                    count++
                    sx += x
                    sy += y
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        val full = Raster(w, h, grey)
        if (count == 0L) return InkResult(full, InkQuality.TOO_FAINT, 0.0)
        val side = maxOf(1, roundHalfUp(THUMB_SIDE * maxOf(maxX - minX + 1, maxY - minY + 1)))
        val cx = roundHalfUp(sx.toDouble() / count)
        val cy = roundHalfUp(sy.toDouble() / count)
        val square = full.crop(cx - side / 2, cy - side / 2, side, side)
        var inside = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (square.r(x, y) < DARK &&
                    inFull(cx - side / 2 + x, cy - side / 2 + y, w, h)
                ) {
                    inside++
                }
            }
        }
        val coverage = inside.toDouble() / (side.toDouble() * side)
        return InkResult(square, gate(coverage), coverage)
    }

    private fun inFull(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) = x in 0 until w && y in 0 until h

    private fun percentile(
        hist: IntArray,
        total: Int,
        p: Double,
    ): Int {
        val goal = (p * total).toInt()
        var acc = 0
        for (v in hist.indices) {
            acc += hist[v]
            if (acc > goal) return v
        }
        return BYTE
    }

    private fun gate(coverage: Double) = when {
        coverage < MIN_COVERAGE -> InkQuality.TOO_FAINT
        coverage > MAX_COVERAGE -> InkQuality.TOO_DARK
        else -> InkQuality.OK
    }
}
