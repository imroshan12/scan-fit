package app.scanfit.core.pdf

import app.scanfit.core.model.SizeKb

/** How the PDF reached its window (analytics enum only). */
enum class PdfStrategy(
    val wire: String,
) {
    QUALITY("quality"),
    LOWER_DPI("lower_dpi"),
    PAD("pad"),
    UNCHANGED("unchanged"),
}

/** Why no PDF was made (ALGORITHMS 9.10 job checks). */
enum class PdfError(
    val wire: String,
) {
    NO_PAGES("no_pages"),
    ENCRYPTED("encrypted"),
    UNREADABLE("unreadable"),
    TOO_MANY_PAGES("too_many_pages"),
    UNKNOWN_LIMIT("unknown_limit"),
    CANNOT_REACH("cannot_reach"),

    /** Encoding or writing produced something the checks refused (a bug, surfaced instead of exported). */
    FAILED("failed"),
}

/** §9.4 window in KB: the goal band and the effective target `T'`. */
class PdfWindow(
    val min: Double,
    val max: Double,
    val goalLo: Double,
    val goalHi: Double,
    val target: Double,
) {
    val hiBytes: Double get() = goalHi * KB
    val loBytes: Double get() = goalLo * KB

    companion object {
        private const val KB = 1024.0
        private const val MARGIN_FRACTION = 0.05

        /** `null` when the slot has no maximum (`UNKNOWN_LIMIT`). */
        fun of(sizeKb: SizeKb): PdfWindow? {
            val max = sizeKb.max ?: return null
            val min = sizeKb.min ?: 0.0
            val margin = maxOf(1.0, MARGIN_FRACTION * (max - min))
            var lo = min + margin
            var hi = max - margin
            if (lo > hi) {
                lo = min
                hi = max
            }
            val target = (sizeKb.target ?: ((min + max) / 2)).coerceIn(lo, hi)
            return PdfWindow(min, max, lo, hi, target)
        }
    }
}

/** The chosen DPI and quality, and the pad target in bytes when the PDF must be made bigger. */
data class PdfChoice(
    val dpi: Int,
    val quality: Int,
    val padTo: Long?,
    val strategy: PdfStrategy,
)

/** The fit selection of ALGORITHMS 9.10 (`pdf_fit_cases`). Pure. */
object PdfFit {
    val QUALITY_GRID = listOf(85, 75, 65, 55, 45, 40)
    const val MAX_PAGES = 20
    private const val KB = 1024.0
    private const val TOP_DPI = 200

    /** [sizes] maps a DPI to the whole PDF's bytes at each grid quality; a DPI with no entry was not measured. */
    fun choose(
        window: PdfWindow,
        sizes: Map<Int, List<Long>>,
    ): PdfChoice? {
        for (dpi in PdfLayout.DPI_LADDER) {
            val atDpi = sizes[dpi] ?: continue
            choose(window, dpi, atDpi)?.let { return it }
        }
        return null
    }

    /** The choice at one DPI, or `null` when even quality 40 is over `goal.hi`. */
    fun choose(
        window: PdfWindow,
        dpi: Int,
        sizes: List<Long>,
    ): PdfChoice? {
        if (sizes.last() > window.hiBytes) return null
        val index = sizes.indexOfFirst { it <= window.hiBytes }
        val size = sizes[index]
        // A Tools limit (no minimum) never pads: only a slot's minimum is a reason to make a PDF bigger.
        val padded = window.min > 0 && size < window.loBytes
        val strategy =
            when {
                padded -> PdfStrategy.PAD
                dpi == TOP_DPI -> PdfStrategy.QUALITY
                else -> PdfStrategy.LOWER_DPI
            }
        return PdfChoice(dpi, QUALITY_GRID[index], if (padded) roundHalfUp(window.target * KB).toLong() else null, strategy)
    }
}
