package app.scanfit.core.match

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.InspectedFile
import app.scanfit.core.inspect.Issue

/** What the file is for: from the flow that produced it, or the user's pick in the Checker (ALGORITHMS 4). */
enum class DocKind { PHOTO, SIGNATURE, THUMB, DECLARATION, FINGERS, PDF_DOCUMENT }

enum class Verdict { EXACT, ACCEPTED, NEAR_MISS, NO, UNKNOWN }

/** One-tap fixes offered for a near miss. `wire` is the name used in the shared cases. */
enum class FixAction(
    val wire: String,
) {
    COMPRESS_TO_TARGET("compress_to_target"),
    ENLARGE_TO_TARGET("enlarge_to_target"),
    CONVERT_TO_JPEG("convert_to_jpeg"),
    REENCODE_BASELINE("reencode_baseline"),
}

/** The four hard constraints of section 9.7. `wire` matches `param` in the shared cases. */
enum class Constraint(
    val wire: String,
) {
    FORMAT("format"),
    SIZE_KB("size_kb"),
    ENCODING("encoding"),
    DIMS("dims"),
}

/** The facts matching needs about a file. [width]/[height] are null for PDFs; [dpi] only feeds an advisory issue. */
data class FileFacts(
    val format: DetectedFormat,
    val kb: Double,
    val width: Int?,
    val height: Int?,
    val color: ColorKind,
    val progressive: Boolean,
    val docKind: DocKind,
    val dpi: Int? = null,
) {
    companion object {
        fun of(
            file: InspectedFile,
            docKind: DocKind,
        ) = FileFacts(
            format = file.format,
            kb = file.kb,
            width = file.width,
            height = file.height,
            color = file.color,
            progressive = file.progressive,
            docKind = docKind,
            dpi = file.jfif?.takeIf { it.units == 1 }?.xDensity,
        )
    }
}

data class MatchOptions(
    /** Remote Config `allow_grayscale_docs`. */
    val allowGrayscale: Boolean = false,
    /** Exam ids in popularity order (Remote Config `popular_exam_order`). */
    val popularity: List<String> = emptyList(),
)

/** The verdict for one file against one slot of one exam. */
data class SlotEvaluation(
    val verdict: Verdict,
    val failed: List<Constraint> = emptyList(),
    val fix: FixAction? = null,
    /** Slot-specific issue codes for the Checker (ALGORITHMS 5): TOO_SMALL_KB ... GRAYSCALE_NOT_ALLOWED. */
    val issues: List<Issue> = emptyList(),
)

data class MatchEntry(
    val examId: String,
    val examName: String,
    val body: String,
    val docType: app.scanfit.core.model.DocType,
    val verdict: Verdict,
    /** True for low-confidence exams: shown as "likely OK", never counted in the headline numbers. */
    val unverified: Boolean,
    val fix: FixAction?,
    val failed: List<Constraint>,
    val issues: List<Issue>,
)

data class MatchResult(
    /** Only EXACT, ACCEPTED and NEAR_MISS entries, sorted (ALGORITHMS 9.7). */
    val entries: List<MatchEntry>,
) {
    /** Exams with at least one verified EXACT/ACCEPTED slot: the headline "Accepted by N exams". */
    val acceptedExamIds: List<String>
        get() = entries.filter { !it.unverified && it.verdict in ACCEPTING }.map { it.examId }.distinct()

    val acceptedExamCount: Int get() = acceptedExamIds.size

    val quickFixCount: Int get() = entries.count { !it.unverified && it.verdict == Verdict.NEAR_MISS }

    private companion object {
        val ACCEPTING = setOf(Verdict.EXACT, Verdict.ACCEPTED)
    }
}
