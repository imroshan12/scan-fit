package app.scanfit.core.match

import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.Exam
import app.scanfit.core.model.ExamSearch
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.PresetBundle

/** What the Checker says about a file for one exam slot (ALGORITHMS 12). */
enum class CheckerStatus {
    OK,
    LIKELY_OK,
    FIX,
    NO_FIX,
    UNKNOWN,
    ;

    companion object {
        private val DECODABLE = setOf(DetectedFormat.JPEG, DetectedFormat.PNG, DetectedFormat.WEBP, DetectedFormat.HEIC)

        fun of(
            format: DetectedFormat,
            slot: DocSpec,
            verdict: Verdict,
            unverified: Boolean,
            /** The Inspector's `encrypted` flag of a PDF: no PDF can be made from it (ALGORITHMS 6.1). */
            encrypted: Boolean = false,
        ): CheckerStatus = when {
            verdict == Verdict.UNKNOWN -> UNKNOWN
            verdict == Verdict.EXACT || verdict == Verdict.ACCEPTED -> if (unverified) LIKELY_OK else OK
            format in DECODABLE && (FileFormat.JPG in slot.formats || FileFormat.JPEG in slot.formats) -> FIX
            FileFormat.PDF in slot.formats && pdfSource(format, encrypted) -> FIX
            else -> NO_FIX
        }

        /** A file the PDF engine can make a PDF from: a decodable picture or a PDF without a password. */
        fun pdfSource(
            format: DetectedFormat,
            encrypted: Boolean,
        ): Boolean = format in DECODABLE || (format == DetectedFormat.PDF && !encrypted)
    }
}

/** One (exam, slot) the Checker can compare a file with. */
data class CheckerTarget(
    val exam: Exam,
    val slot: DocSpec,
)

object CheckerTargets {
    /**
     * Active exams with a slot of [kind] (an exam with two matching slots appears twice), in popularity order then
     * name, or in search order for a non-blank [query] (ALGORITHMS 10). Unverified exams are included, with their
     * badge.
     */
    fun of(
        bundle: PresetBundle,
        kind: DocKind,
        query: String = "",
    ): List<CheckerTarget> {
        val exams =
            if (query.isBlank()) {
                val rank = bundle.popular.withIndex().associate { (i, id) -> id to i }
                bundle.exams
                    .filter { it.status == ExamStatus.ACTIVE }
                    .sortedWith(compareBy<Exam> { rank[it.id] ?: Int.MAX_VALUE }.thenBy { it.name })
            } else {
                ExamSearch(bundle).search(query, showUnverified = true)
            }
        return exams.flatMap { exam ->
            exam.documents.filter { DocKind.of(it.type) == kind && slotTakes(kind, it) }.map { CheckerTarget(exam, it) }
        }
    }

    /** A PDF document compares with slots that accept PDF; every other kind with its own slot types (ALGORITHMS 4). */
    private fun slotTakes(
        kind: DocKind,
        slot: DocSpec,
    ) = kind != DocKind.PDF_DOCUMENT || FileFormat.PDF in slot.formats
}
