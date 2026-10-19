package app.scanfit.core.data.export

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.inspect.JfifDensity
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocSpec

/** The written-file check of ALGORITHMS 1.6, evaluated as the slot's match [DocKind] (§9.5). */
object ExportVerifier {
    /** The check for [request]'s kind of file: a JPEG (§1.6) or a PDF (§6.5). */
    fun verifies(
        request: ExportRequest,
        written: ByteArray,
    ): Boolean = request.pdf?.let { verifiesPdf(request.bytes, written, request.spec, it) }
        ?: verifies(request.bytes, written, request.spec, request.kind)

    /**
     * ALGORITHMS 6.5: identical bytes, a PDF without a password, the page count and image-only pages for a PDF the app
     * wrote, and the slot verdict (kind PDF document, size in the window) EXACT or ACCEPTED.
     */
    fun verifiesPdf(
        made: ByteArray,
        written: ByteArray,
        spec: DocSpec,
        pdf: PdfExport,
    ): Boolean {
        if (!made.contentEquals(written)) return false
        val file = Inspector.inspect(written)
        if (file.format != DetectedFormat.PDF || file.pdfEncrypted || pdf.pages < 1) return false
        if (pdf.written && (file.pdfPages != pdf.pages || !file.pdfImageOnly)) return false
        val verdict = MatchEngine.evaluate(spec, FileFacts.of(file, DocKind.PDF_DOCUMENT)).verdict
        return verdict in setOf(Verdict.EXACT, Verdict.ACCEPTED)
    }

    fun verifies(
        review: ByteArray,
        written: ByteArray,
        spec: DocSpec,
        kind: DocKind,
    ): Boolean {
        if (!review.contentEquals(written)) return false
        val file = Inspector.inspect(written)
        val dpi = spec.dpi ?: DEFAULT_DPI
        val encoding = file.format == DetectedFormat.JPEG && file.sof == "SOF0" && file.color == ColorKind.RGB
        val privateMetadata = file.hasExif || file.hasGps || file.hasXmp
        val density = file.jfif == JfifDensity(1, dpi, dpi)
        val verdict = MatchEngine.evaluate(spec, FileFacts.of(file, kind)).verdict
        return encoding && !privateMetadata && density && verdict in setOf(Verdict.EXACT, Verdict.ACCEPTED)
    }

    private const val DEFAULT_DPI = 200
}
