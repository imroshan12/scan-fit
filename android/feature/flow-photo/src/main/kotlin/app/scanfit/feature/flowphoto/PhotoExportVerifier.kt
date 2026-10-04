package app.scanfit.feature.flowphoto

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.inspect.JfifDensity
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocSpec

object PhotoExportVerifier {
    fun verifies(
        review: ByteArray,
        written: ByteArray,
        spec: DocSpec,
    ): Boolean {
        if (!review.contentEquals(written)) return false
        val file = Inspector.inspect(written)
        val dpi = spec.dpi ?: DEFAULT_DPI
        val encoding = file.format == DetectedFormat.JPEG && file.sof == "SOF0" && file.color == ColorKind.RGB
        val privateMetadata = file.hasExif || file.hasGps || file.hasXmp
        val density = file.jfif == JfifDensity(1, dpi, dpi)
        val verdict = MatchEngine.evaluate(spec, FileFacts.of(file, DocKind.PHOTO)).verdict
        return encoding && !privateMetadata && density && verdict in setOf(Verdict.EXACT, Verdict.ACCEPTED)
    }

    private const val DEFAULT_DPI = 200
}
