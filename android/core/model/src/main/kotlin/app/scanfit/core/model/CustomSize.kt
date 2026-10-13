package app.scanfit.core.model

import kotlin.math.floor

/** Why a Custom size cannot be used yet (ALGORITHMS 11), in the order the codes are reported. */
enum class CustomSizeError(
    val wire: String,
) {
    MAX_KB_MISSING("max_kb_missing"),
    MAX_KB_RANGE("max_kb_range"),
    MIN_KB_RANGE("min_kb_range"),
    SIZE_PAIR("size_pair"),
    WIDTH_RANGE("width_range"),
    HEIGHT_RANGE("height_range"),
    DPI_RANGE("dpi_range"),
}

enum class SizeUnit { PX, CM }

/** What the user typed on the Custom size screen; numbers are `null` when the field is empty. */
data class CustomSizeFields(
    val doc: DocType,
    val unit: SizeUnit = SizeUnit.PX,
    val width: Double? = null,
    val height: Double? = null,
    val minKb: Int? = null,
    val maxKb: Int? = null,
    val dpi: Int? = null,
) {
    /** The size in pixels after the cm conversion, or `null` when the field is empty. */
    fun widthPx(): Int? = width?.let { toPx(it) }

    fun heightPx(): Int? = height?.let { toPx(it) }

    private fun toPx(value: Double): Int {
        val pixels = if (unit == SizeUnit.CM) value * (dpi ?: CustomSize.DEFAULT_DPI) / CM_PER_INCH else value
        return floor(pixels + HALF).toInt()
    }

    /** Every code that applies, in the spec's order; empty = valid. */
    fun errors(): List<CustomSizeError> = buildList {
        val w = widthPx()
        val h = heightPx()
        val dpi = dpi ?: CustomSize.DEFAULT_DPI
        val max = maxKb
        if (max == null) {
            add(CustomSizeError.MAX_KB_MISSING)
        } else if (max !in 1..CustomSize.MAX_KB) {
            add(CustomSizeError.MAX_KB_RANGE)
        }
        val min = minKb
        if (min != null && !(min >= 0 && (max == null || min < max))) add(CustomSizeError.MIN_KB_RANGE)
        if ((w == null) != (h == null)) add(CustomSizeError.SIZE_PAIR)
        if (w != null && w !in CustomSize.PX_RANGE) add(CustomSizeError.WIDTH_RANGE)
        if (h != null && h !in CustomSize.PX_RANGE) add(CustomSizeError.HEIGHT_RANGE)
        if (dpi !in CustomSize.DPI_RANGE) add(CustomSizeError.DPI_RANGE)
    }

    /** The validated size, or `null` while any code applies. */
    fun toSize(): CustomSize? {
        if (doc !in CustomSize.DOCS || errors().isNotEmpty()) return null
        return CustomSize(doc, widthPx(), heightPx(), minKb ?: 0, checkNotNull(maxKb), dpi ?: CustomSize.DEFAULT_DPI)
    }

    private companion object {
        const val CM_PER_INCH = 2.54
        const val HALF = 0.5
    }
}

/**
 * A validated Custom size (ALGORITHMS 11): what the file is and the form's limits. It becomes a synthetic slot so the
 * photo and ink flows run unchanged; [text] is the canonical form used for navigation and the recent sizes.
 */
data class CustomSize(
    val doc: DocType,
    val width: Int?,
    val height: Int?,
    val minKb: Int,
    val maxKb: Int,
    val dpi: Int,
) {
    val text: String
        get() = "${doc.wire};${if (width != null) "${width}x$height" else "-"};$minKb-$maxKb;$dpi"

    /** The synthetic slot the flows run against. */
    val spec: DocSpec
        get() =
            DocSpec(
                type = doc,
                required = true,
                formats = listOf(FileFormat.JPG),
                sizeKb = SizeKb(min = minKb.takeIf { it > 0 }?.toDouble(), max = maxKb.toDouble()),
                dimensions =
                if (width != null && height != null) {
                    Dimensions(DimensionMode.EXACT, width = width, height = height)
                } else {
                    Dimensions(DimensionMode.NONE)
                },
                dpi = dpi,
            )

    companion object {
        /** Exam id and name of the synthetic exam: not translated, they name the file and the folder (§9.8). */
        const val EXAM_ID = "custom"
        const val EXAM_NAME = "Custom"
        const val DEFAULT_DPI = 200
        const val MAX_KB = 5120
        const val MIN_PX = 16
        const val MAX_PX = 2000
        const val MIN_DPI = 72
        const val MAX_DPI = 1200
        val PX_RANGE = MIN_PX..MAX_PX
        val DPI_RANGE = MIN_DPI..MAX_DPI
        val DOCS = listOf(DocType.PHOTO, DocType.SIGNATURE, DocType.THUMB_IMPRESSION, DocType.HANDWRITTEN_DECLARATION)

        /** Recent sizes kept on the device (ALGORITHMS 11). */
        const val RECENT_LIMIT = 5

        private const val NUMBER = "(0|[1-9][0-9]*)"
        private val TEXT = Regex("^([a-z_]+);(?:${NUMBER}x$NUMBER|-);$NUMBER-$NUMBER;$NUMBER$")
        private const val WIDTH_GROUP = 2
        private const val HEIGHT_GROUP = 3
        private const val MIN_GROUP = 4
        private const val MAX_GROUP = 5
        private const val DPI_GROUP = 6

        /** The size written as [text], or `null` unless it is exactly the canonical form of a valid size. */
        fun parse(text: String): CustomSize? {
            val m = TEXT.matchEntire(text) ?: return null
            val doc = DOCS.firstOrNull { it.wire == m.groupValues[1] } ?: return null
            fun number(i: Int) = m.groups[i]?.value?.toIntOrNull()
            return CustomSizeFields(
                doc = doc,
                width = number(WIDTH_GROUP)?.toDouble(),
                height = number(HEIGHT_GROUP)?.toDouble(),
                minKb = number(MIN_GROUP),
                maxKb = number(MAX_GROUP),
                dpi = number(DPI_GROUP),
            ).toSize()
        }

        /** [recent] with [text] moved to the front, distinct, at most [RECENT_LIMIT]. */
        fun remember(
            recent: List<String>,
            text: String,
        ): List<String> = (listOf(text) + recent.filter { it != text }).take(RECENT_LIMIT)
    }
}

/** The preset wire name (`left_thumb`), as in the presets and the routes. */
val DocType.wire: String get() = name.lowercase()
