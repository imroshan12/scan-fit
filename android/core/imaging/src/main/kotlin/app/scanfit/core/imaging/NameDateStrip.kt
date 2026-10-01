package app.scanfit.core.imaging

/** Measures text for the strip layout; the platform text engine implements it (Canvas/Paint on Android). */
fun interface TextMeasurer {
    fun width(text: String, sizePx: Float, bold: Boolean): Float
}

/** One line of strip text, positioned by [centerX] and its [baselineY] in output pixels. */
data class StripLine(val text: String, val sizePx: Float, val bold: Boolean, val centerX: Float, val baselineY: Float)

class StripLayout(val stripTop: Int, val stripHeight: Int, val photoArea: Size, val lines: List<StripLine>)

/** The white name/date strip added inside the target dimensions (ALGORITHMS 2.4 / 9.6). The layout math is pure. */
object NameDateStrip {
    private const val STRIP_FRACTION = 0.18
    private const val NAME_SIZE = 0.38f
    private const val DATE_SIZE = 0.32f
    private const val GAP = 0.08f
    private const val SHRINK_FLOOR = 0.60f
    private const val SHRINK_STEP = 0.02f
    private const val MARGIN = 0.04
    private const val ASCENT = 0.8f
    private const val ELLIPSIS = "…"

    fun layout(name: String, date: String, w: Int, h: Int, measurer: TextMeasurer): StripLayout {
        val stripH = roundHalfUp(STRIP_FRACTION * h)
        val stripTop = h - stripH
        val available = (w * (1 - 2 * MARGIN)).toFloat()
        val nameBase = NAME_SIZE * stripH
        val dateSize = DATE_SIZE * stripH
        val (nameText, nameSize) = fitName(name.uppercase(), nameBase, available, measurer)
        val block = nameSize + GAP * stripH + dateSize
        val top = stripTop + (stripH - block) / 2
        val cx = w / 2f
        val lines = listOf(
            StripLine(nameText, nameSize, true, cx, top + ASCENT * nameSize),
            StripLine(date, dateSize, false, cx, top + nameSize + GAP * stripH + ASCENT * dateSize),
        )
        return StripLayout(stripTop, stripH, Size(w, h - stripH), lines)
    }

    /** Shrinks to 60 % of the base size, then truncates with an ellipsis (never both silently). */
    private fun fitName(text: String, baseSize: Float, available: Float, m: TextMeasurer): Pair<String, Float> {
        if (m.width(text, baseSize, true) <= available) return text to baseSize
        val small = baseSize * SHRINK_FLOOR
        if (m.width(text, small, true) <= available) {
            // the largest size between the floor and the base that fits (shrink only as much as needed)
            var size = baseSize
            while (size > small && m.width(text, size, true) > available) size -= baseSize * SHRINK_STEP
            return text to maxOf(size, small)
        }
        var cut = text
        while (cut.isNotEmpty() && m.width(cut + ELLIPSIS, small, true) > available) cut = cut.dropLast(1)
        return (cut.trimEnd() + ELLIPSIS) to small
    }

    /** Photo scaled uniformly into the area above the strip (letterboxed); the strip stays white for text. */
    fun placePhoto(photo: Raster, layout: StripLayout, w: Int, h: Int): Raster {
        val area = layout.photoArea
        val scale = minOf(area.w.toDouble() / photo.width, area.h.toDouble() / photo.height)
        val pw = maxOf(1, roundHalfUp(photo.width * scale))
        val ph = maxOf(1, roundHalfUp(photo.height * scale))
        val scaled = Resampler.resize(photo, pw, ph)
        return Raster.white(w, h).let { canvas -> paste(canvas, scaled, (w - pw) / 2, (area.h - ph) / 2) }
    }

    private fun paste(canvas: Raster, src: Raster, x: Int, y: Int): Raster {
        val out = canvas.rgb.copyOf()
        for (row in 0 until src.height) {
            val rowBytes = src.width * Raster.CHANNELS
            System.arraycopy(src.rgb, row * rowBytes, out, ((y + row) * canvas.width + x) * Raster.CHANNELS, rowBytes)
        }
        return Raster(canvas.width, canvas.height, out)
    }
}
