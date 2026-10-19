package app.scanfit.core.pdf

import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.SizeKb
import java.io.Closeable

/** What the user is making (ALGORITHMS 6.1). Only [ID_CARD] changes the layout; the others differ in what they accept. */
enum class PdfTool(
    val wire: String,
) {
    IMAGES("images"),
    COMPRESS("compress"),
    MERGE("merge"),
    ID_CARD("id_card"),
    EXAM("exam"),
    ;

    companion object {
        fun of(wire: String): PdfTool? = entries.firstOrNull { it.wire == wire }
    }
}

/** One input file. */
sealed interface PdfSource {
    /**
     * A picture: decoded upright (§1.1), turned [quarterTurns] × 90° clockwise, then cropped to [crop], given in the
     * pixels of a [cropBase]-sized view of the turned picture (the editor's raster), scaled to the engine's decode.
     */
    class Image(
        val bytes: ByteArray,
        val quarterTurns: Int = 0,
        val crop: CropRect? = null,
        val cropBase: PixelSize? = null,
    ) : PdfSource

    /** A PDF: every page, in order. */
    class Document(
        val bytes: ByteArray,
    ) : PdfSource
}

data class PdfOptions(
    /** Default on (decision 2026-10-15): about half the size; seals lose their colour. */
    val grayscale: Boolean = true,
    /** Default on; applies to pictures only. */
    val cleanup: Boolean = true,
)

class PdfJob(
    val tool: PdfTool,
    val sources: List<PdfSource>,
    val sizeKb: SizeKb,
    val options: PdfOptions = PdfOptions(),
)

/** A finished PDF: the exact bytes to save, plus what the result screen shows. */
class PdfMade(
    bytes: ByteArray,
    val pages: Int,
    /** `null` for an unchanged file. */
    val dpi: Int?,
    val quality: Int?,
    val strategy: PdfStrategy,
    /** The first page, at most [PdfEngine.PREVIEW_LONG_SIDE] px on its long side. */
    val preview: Raster?,
) {
    val bytes: ByteArray = bytes.copyOf()
}

sealed interface PdfOutcome {
    class Made(
        val pdf: PdfMade,
    ) : PdfOutcome

    data class Failed(
        val error: PdfError,
    ) : PdfOutcome
}

/** Progress for the making screen. */
sealed interface PdfProgress {
    data class Preparing(
        val done: Int,
        val total: Int,
    ) : PdfProgress

    data class Fitting(
        val page: Int,
        val total: Int,
    ) : PdfProgress
}

/** A PDF opened for rendering: Android's `PdfRenderer` in the app, a fixture renderer in JVM tests. */
interface PdfPages : Closeable {
    val pageCount: Int

    /** The displayed page's size in points. */
    fun pageSize(index: Int): PdfSize

    /** The page scaled to exactly [width] × [height] pixels on white. */
    fun render(
        index: Int,
        width: Int,
        height: Int,
    ): Raster
}

/** Opens PDF bytes for rendering; `null` when the platform cannot read them. */
fun interface PdfOpener {
    fun open(bytes: ByteArray): PdfPages?
}

/** Decodes a picture upright with the long side capped (ALGORITHMS 1.1); `null` when it does not decode. */
fun interface PictureDecoder {
    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster?
}
