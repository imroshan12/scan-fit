package app.scanfit.core.pdf

/** A size in PDF points (1/72 in). */
data class PdfSize(
    val width: Double,
    val height: Double,
)

/** A rectangle in points, origin top-left, y down (ALGORITHMS 9.10). */
data class PdfRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** One image placed on a page: where it goes and how many points one source pixel covers. */
data class Placement(
    val rect: PdfRect,
    val scale: Double,
)

/** Pixel dimensions of an image to render or resample. */
data class PixelSize(
    val width: Int,
    val height: Int,
)

/**
 * Page geometry of the PDF engine (ALGORITHMS 9.10, `pdf_layout_cases`). Every formula keeps the operation order of the
 * spec so Kotlin, Swift and the Python reference compute the same doubles.
 */
object PdfLayout {
    const val A4_WIDTH = 595.0
    const val A4_HEIGHT = 842.0
    private const val POINTS_PER_INCH = 72.0
    private const val MM_PER_INCH = 25.4
    private const val MARGIN_FRACTION = 0.05
    private const val CARD_WIDTH_MM = 85.6
    private const val CARD_HEIGHT_MM = 54.0
    private const val CARD_GAP_MM = 15.0

    val DPI_LADDER = listOf(200, 150, 120, 100)

    private const val MARGIN = MARGIN_FRACTION * A4_WIDTH
    private const val MM = POINTS_PER_INCH / MM_PER_INCH
    private const val CARD_WIDTH = CARD_WIDTH_MM * MM
    private const val CARD_HEIGHT = CARD_HEIGHT_MM * MM
    private const val CARD_GAP = CARD_GAP_MM * MM

    /** An image on its own A4 page: portrait unless the image is wider than tall, 5 % margins, centred. */
    fun imagePage(
        w: Int,
        h: Int,
    ): Pair<PdfSize, Placement> {
        val page = if (w <= h) PdfSize(A4_WIDTH, A4_HEIGHT) else PdfSize(A4_HEIGHT, A4_WIDTH)
        val bw = page.width - 2 * MARGIN
        val bh = page.height - 2 * MARGIN
        val s = minOf(bw / w, bh / h)
        val rw = w * s
        val rh = h * s
        return page to Placement(PdfRect((page.width - rw) / 2, (page.height - rh) / 2, rw, rh), s)
    }

    /** Front above back, each contained in an 85.6 × 54 mm box, 15 mm apart, the pair centred on A4 portrait. */
    fun idCardPage(
        front: PixelSize,
        back: PixelSize,
    ): Pair<PdfSize, List<Placement>> {
        val x = (A4_WIDTH - CARD_WIDTH) / 2
        val frontY = (A4_HEIGHT - (2 * CARD_HEIGHT + CARD_GAP)) / 2
        val backY = frontY + CARD_HEIGHT + CARD_GAP
        val placements =
            listOf(front to frontY, back to backY).map { (side, y) ->
                val s = minOf(CARD_WIDTH / side.width, CARD_HEIGHT / side.height)
                val rw = side.width * s
                val rh = side.height * s
                Placement(PdfRect(x + (CARD_WIDTH - rw) / 2, y + (CARD_HEIGHT - rh) / 2, rw, rh), s)
            }
        return PdfSize(A4_WIDTH, A4_HEIGHT) to placements
    }

    /** Pixels of a `w × h` image placed at [scale] points per pixel, rendered at [dpi]: never upscaled, long side. */
    fun pixels(
        w: Int,
        h: Int,
        scale: Double,
        dpi: Int,
    ): PixelSize {
        val k = scale * dpi / POINTS_PER_INCH
        if (k >= 1) return PixelSize(w, h)
        return if (w >= h) {
            val pw = maxOf(1, roundHalfUp(w * k))
            PixelSize(pw, maxOf(1, roundHalfUp(h.toDouble() * pw / w)))
        } else {
            val ph = maxOf(1, roundHalfUp(h * k))
            PixelSize(maxOf(1, roundHalfUp(w.toDouble() * ph / h)), ph)
        }
    }

    /** Pixels of a whole PDF page at [dpi]; a page larger than A4 gets A4's pixel budget. */
    fun pdfPagePixels(
        page: PdfSize,
        dpi: Int,
    ): PixelSize {
        val k = (dpi / POINTS_PER_INCH) * minOf(1.0, A4_HEIGHT / maxOf(page.width, page.height))
        return PixelSize(maxOf(1, roundHalfUp(page.width * k)), maxOf(1, roundHalfUp(page.height * k)))
    }
}

/** round(x) = floor(x + 0.5) (ALGORITHMS 9.1). */
internal fun roundHalfUp(x: Double): Int = kotlin.math.floor(x + HALF).toInt()

private const val HALF = 0.5
