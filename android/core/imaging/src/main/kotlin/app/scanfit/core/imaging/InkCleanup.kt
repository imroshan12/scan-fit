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
    private const val BLACK_PERCENTILE = 0.02
    private const val MEDIAN = 0.5
    private const val DARK = 128
    private const val BYTE = 255

    // Thumb (ALGORITHMS 9.5): candidates below this local level, paper blur divisor and its regulariser,
    // white point, mid-grey exponent, visible-ink level for the trim and the robust box percentiles.
    private const val THUMB_CANDIDATE = 230
    private const val PAPER_SIGMA_DIVISOR = 8.0
    private const val PAPER_WEIGHT = 5
    private const val THUMB_WHITE = 235
    private const val THUMB_EXPONENT = 1.25
    private const val THUMB_TRIM_INK = 240
    private const val BOX_LOW = 0.005
    private const val BOX_HIGH = 0.995

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
        val n = normalise(l, bg)
        return if (variant == InkVariant.THUMB) thumb(l, n, w, h) else binarised(src, n, w, h, variant, options)
    }

    private fun normalise(
        l: IntArray,
        bg: IntArray,
    ) = IntArray(l.size) { minOf(BYTE, (l[it] * BYTE + bg[it] / 2) / maxOf(bg[it], 1)) }

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

    /**
     * Thumb (ALGORITHMS 9.5): normalise by a paper-only background, stretch linearly, darken mid-greys slightly, then
     * a square around the robust ink box. [n1] is the local-mean normalisation, used only to find candidates.
     */
    private fun thumb(
        l: IntArray,
        n1: IntArray,
        w: Int,
        h: Int,
    ): InkResult {
        val grey = thumbTone(l, n1, w, h)
        val columns = IntArray(w)
        val rows = IntArray(h)
        var count = 0
        val rgb = ByteArray(w * h * Raster.CHANNELS)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = grey[y * w + x]
                val i = (y * w + x) * Raster.CHANNELS
                rgb[i] = v.toByte()
                rgb[i + 1] = v.toByte()
                rgb[i + 2] = v.toByte()
                if (v < THUMB_TRIM_INK) {
                    columns[x]++
                    rows[y]++
                    count++
                }
            }
        }
        val full = Raster(w, h, rgb)
        if (count == 0) return InkResult(full, InkQuality.TOO_FAINT, 0.0)
        val low = (BOX_LOW * (count - 1)).toInt()
        val high = (BOX_HIGH * (count - 1)).toInt()
        val x0 = position(columns, low)
        val x1 = position(columns, high)
        val y0 = position(rows, low)
        val y1 = position(rows, high)
        val side = maxOf(1, roundHalfUp(THUMB_SIDE * maxOf(x1 - x0 + 1, y1 - y0 + 1)))
        val ox = (x0 + x1 + 1) / 2 - side / 2
        val oy = (y0 + y1 + 1) / 2 - side / 2
        val square = full.crop(ox, oy, side, side)
        var inside = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (square.r(x, y) < DARK && inFull(ox + x, oy + y, w, h)) inside++
            }
        }
        val coverage = inside.toDouble() / (side.toDouble() * side)
        return InkResult(square, gate(coverage), coverage)
    }

    /** Steps 1–4 of the thumb: candidates, paper-only background, normalisation and the tone curve. */
    private fun thumbTone(
        l: IntArray,
        n1: IntArray,
        w: Int,
        h: Int,
    ): IntArray {
        val total = w * h
        val lumaHist = IntArray(BYTE + 1)
        for (v in l) lumaHist[v]++
        val otsuLevel = otsu(lumaHist, total)
        var candidates = IntArray(total) { if (n1[it] < THUMB_CANDIDATE || l[it] <= otsuLevel) 1 else 0 }
        candidates = ImageOps.dilate3x3(ImageOps.dilate3x3(candidates, w, h), w, h)

        val paperHist = IntArray(BYTE + 1)
        var paperCount = 0
        val weights = IntArray(total)
        val paperLuma = IntArray(total)
        for (i in 0 until total) {
            if (candidates[i] == 1) continue
            paperHist[l[i]]++
            paperCount++
            weights[i] = BYTE
            paperLuma[i] = l[i]
        }
        val paper = if (paperCount > 0) percentile(paperHist, paperCount, MEDIAN) else BYTE
        val sigma = minOf(w, h) / PAPER_SIGMA_DIVISOR
        val b = ImageOps.gaussianApprox(weights, w, h, sigma)
        val c = ImageOps.gaussianApprox(paperLuma, w, h, sigma)
        val bg = IntArray(total) { (c[it] * BYTE + PAPER_WEIGHT * paper) / (b[it] + PAPER_WEIGHT) }
        val n = normalise(l, bg)

        val inkHist = IntArray(BYTE + 1)
        var inkCount = 0
        for (i in 0 until total) {
            if (candidates[i] == 1) {
                inkHist[n[i]]++
                inkCount++
            }
        }
        val black = if (inkCount > 0) percentile(inkHist, inkCount, BLACK_PERCENTILE) else 0
        val lut =
            IntArray(BYTE + 1) { v ->
                val s =
                    if (black >= THUMB_WHITE) {
                        v / BYTE.toDouble()
                    } else {
                        (v - black).coerceIn(0, THUMB_WHITE - black).toDouble() / (THUMB_WHITE - black)
                    }
                roundHalfUp(BYTE * s.pow(THUMB_EXPONENT)).coerceIn(0, BYTE)
            }
        return IntArray(total) { lut[n[it]] }
    }

    /** The coordinate at sorted position [index] of the pixels counted per coordinate in [counts]. */
    private fun position(
        counts: IntArray,
        index: Int,
    ): Int {
        var acc = 0
        for (coordinate in counts.indices) {
            acc += counts[coordinate]
            if (acc > index) return coordinate
        }
        return counts.size - 1
    }

    /** Otsu level: the `v` maximising the between-class variance of the split `<= v` / `> v` (first maximum). */
    internal fun otsu(
        hist: IntArray,
        total: Int,
    ): Int {
        var sum = 0.0
        for (v in hist.indices) sum += v.toDouble() * hist[v]
        var sumLow = 0.0
        var low = 0.0
        var best = -1.0
        var level = BYTE
        for (v in 0 until BYTE) {
            low += hist[v]
            sumLow += v.toDouble() * hist[v]
            val high = total - low
            if (low <= 0.0 || high <= 0.0) continue
            val mean0 = sumLow / low
            val mean1 = (sum - sumLow) / high
            val between = low * high * (mean0 - mean1) * (mean0 - mean1)
            if (between > best) {
                best = between
                level = v
            }
        }
        return level
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
