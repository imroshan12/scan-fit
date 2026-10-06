package app.scanfit.core.match

import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import kotlin.math.ceil
import kotlin.math.floor

/** An exam named in the match note's preview. */
data class ExamRef(
    val id: String,
    val name: String,
)

enum class NeedKind { AT_MOST, AT_LEAST, JPEG, BASELINE }

/** What a near-miss slot needs: `AT_MOST 20` reads "needs ≤ 20 KB". [kb] is set for the size needs only. */
data class Need(
    val kind: NeedKind,
    val kb: Int? = null,
)

data class QuickFix(
    val exam: ExamRef,
    val docType: DocType,
    val need: Need,
)

/** One body's rows in the detail sheet, in result order. */
data class MatchGroup(
    val body: String,
    val entries: List<MatchEntry>,
)

/**
 * The match note under a review's verdict (ALGORITHMS 4 "Match note on review", 9.7 "Match note"): the headline
 * count, the first names, the likely-OK count for unverified exams, the quick fixes with what they need, and the
 * sheet's groups.
 */
data class MatchNote(
    val accepted: Int,
    val preview: List<ExamRef>,
    val more: Int,
    val likelyOk: Int,
    val quickFixes: List<QuickFix>,
    val groups: List<MatchGroup>,
) {
    enum class Headline { ACCEPTED, LIKELY_OK, NONE }

    val headline: Headline
        get() =
            when {
                accepted > 0 -> Headline.ACCEPTED
                likelyOk > 0 -> Headline.LIKELY_OK
                else -> Headline.NONE
            }

    companion object {
        const val PREVIEW_SIZE = 3

        private val ACCEPTING = setOf(Verdict.EXACT, Verdict.ACCEPTED)

        val EMPTY = MatchNote(0, emptyList(), 0, 0, emptyList(), emptyList())

        /** The note for a file of [facts] against [exams], sorted by [popularity] (the bundle's `popular` list). */
        fun of(
            facts: FileFacts,
            exams: List<Exam>,
            popularity: List<String>,
        ): MatchNote = of(MatchEngine.match(facts, exams, MatchOptions(popularity = popularity)))

        /** Summarises a sorted [result]; never re-sorts it. */
        fun of(result: MatchResult): MatchNote {
            val entries = result.entries
            val accepted = LinkedHashMap<String, ExamRef>()
            entries
                .filter { !it.unverified && it.verdict in ACCEPTING }
                .forEach { accepted.getOrPut(it.examId) { ExamRef(it.examId, it.examName) } }
            val likely =
                entries
                    .filter { it.unverified && it.verdict in ACCEPTING && it.examId !in accepted }
                    .map { it.examId }
                    .distinct()
            val fixes =
                entries
                    .filter { !it.unverified && it.verdict == Verdict.NEAR_MISS }
                    .mapNotNull { e -> need(e)?.let { QuickFix(ExamRef(e.examId, e.examName), e.docType, it) } }
            val groups =
                entries
                    .groupBy { it.body } // LinkedHashMap: bodies in order of their first entry
                    .map { (body, rows) -> MatchGroup(body, rows) }
            val preview = accepted.values.take(PREVIEW_SIZE)
            return MatchNote(accepted.size, preview, accepted.size - preview.size, likely.size, fixes, groups)
        }

        /** What a near-miss [entry] needs; `null` for other verdicts or when the slot has no usable limit. */
        fun need(entry: MatchEntry): Need? = when (entry.fix) {
            FixAction.COMPRESS_TO_TARGET -> entry.sizeKb.max?.let { Need(NeedKind.AT_MOST, floor(it).toInt()) }
            FixAction.ENLARGE_TO_TARGET -> entry.sizeKb.min?.let { Need(NeedKind.AT_LEAST, ceil(it).toInt()) }
            FixAction.CONVERT_TO_JPEG -> Need(NeedKind.JPEG)
            FixAction.REENCODE_BASELINE -> Need(NeedKind.BASELINE)
            null -> null
        }
    }
}
