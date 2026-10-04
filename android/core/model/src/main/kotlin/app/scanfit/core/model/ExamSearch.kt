package app.scanfit.core.model

import java.text.Normalizer
import java.util.Locale

/**
 * Exam search and browse for Home (ALGORITHMS §10). Pure and deterministic; the same rules run in Swift (`ExamSearch`)
 * and in the reference `spec/tools/exam_search.py`, and all three must agree on `search_cases` in
 * `spec/fixtures/cases.json`.
 *
 * Build one per bundle: the index is computed once, so searching on every keystroke is cheap.
 */
class ExamSearch(
    private val bundle: PresetBundle,
) {
    private val rank: Map<String, Int> = bundle.popular.withIndex().associate { (i, id) -> id to i }
    private val entries: List<Entry> =
        bundle.exams.map { exam ->
            Entry(exam, indexTokens(exam).map(::codePoints), nameKey(exam))
        }

    /** Matching exams, best first. A blank query gives no results: the screen shows its browse sections instead. */
    fun search(
        query: String,
        category: ExamCategory? = null,
        showUnverified: Boolean = false,
    ): List<Exam> {
        val q = norm(query).map(::codePoints)
        if (q.isEmpty()) return emptyList()
        val joinedQuery = norm(query).joinToString(" ")
        return entries
            .asSequence()
            .filter { visible(it.exam, showUnverified) && (category == null || it.exam.category == category) }
            .mapNotNull { entry ->
                val best = q.map { token -> entry.tokens.maxOfOrNull { level(token, it) } ?: 0 }
                if (best.min() >= 1) Scored(entry, best.sum(), entry.nameKey.startsWith(joinedQuery)) else null
            }.sortedWith(scoredOrder)
            .map { it.entry.exam }
            .toList()
    }

    /** The visible exams of one category: popularity, then name, then id. */
    fun browse(
        category: ExamCategory,
        showUnverified: Boolean = false,
    ): List<Exam> = entries
        .filter { visible(it.exam, showUnverified) && it.exam.category == category }
        .sortedWith(browseOrder)
        .map { it.exam }

    /** The first [n] visible exams in the bundle's popularity order; unknown or hidden ids are skipped. */
    fun popular(
        n: Int,
        showUnverified: Boolean = false,
    ): List<Exam> {
        val byId = entries.filter { visible(it.exam, showUnverified) }.associateBy { it.exam.id }
        return bundle.popular.mapNotNull { byId[it]?.exam }.take(n)
    }

    private fun popularity(exam: Exam): Int = rank[exam.id] ?: bundle.popular.size

    private val browseOrder: Comparator<Entry> =
        compareBy<Entry> { popularity(it.exam) }
            .thenComparator { a, b -> compareByCodePoint(a.nameKey, b.nameKey) }
            .thenBy { it.exam.id }

    private val scoredOrder: Comparator<Scored> =
        compareByDescending<Scored> { it.score }
            .thenBy { if (it.startsName) 0 else 1 }
            .thenBy { popularity(it.entry.exam) }
            .thenComparator { a, b -> compareByCodePoint(a.entry.nameKey, b.entry.nameKey) }
            .thenBy { it.entry.exam.id }

    private fun indexTokens(exam: Exam): List<String> {
        val categoryKey = categoryKey(exam.category)
        val phrases = listOf(exam.name) + exam.aliases
        val tokens =
            listOf(exam.name, exam.body, exam.id, categoryKey).flatMap(::norm) +
                bundle.categories[categoryKey]?.aliases.orEmpty().flatMap(::norm) +
                exam.aliases.flatMap(::norm) +
                phrases.map { norm(it).joinToString("") }
        return tokens.filter { it.isNotEmpty() }
    }

    private class Entry(
        val exam: Exam,
        val tokens: List<IntArray>,
        val nameKey: String,
    )

    private class Scored(
        val entry: Entry,
        val score: Int,
        val startsName: Boolean,
    )

    companion object {
        private const val EXACT = 3
        private const val PREFIX = 2
        private const val FUZZY = 1
        private const val FUZZY_MIN_LENGTH = 4
        private val DELETED = setOf(0x093C, 0x200C, 0x200D) // Devanagari nukta, ZWNJ, ZWJ

        /** ALGORITHMS §10: NFKC, default lower-casing, drop nukta/ZWNJ/ZWJ, non letter/mark/digit → space. */
        fun norm(text: String): List<String> {
            val lowered = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            val out = StringBuilder(lowered.length)
            lowered.codePoints().forEach { cp ->
                when {
                    cp in DELETED -> Unit
                    isLetterMarkOrDigit(cp) -> out.appendCodePoint(cp)
                    else -> out.append(' ')
                }
            }
            return out.split(' ').filter { it.isNotEmpty() }
        }

        /** 3 exact, 2 prefix, 1 one typo against a prefix of [t] (only for queries of 4+ scalars), else 0. */
        fun level(
            q: String,
            t: String,
        ): Int = level(codePoints(q), codePoints(t))

        internal fun level(
            q: IntArray,
            t: IntArray,
        ): Int = when {
            q.contentEquals(t) -> EXACT
            q.size <= t.size && q.contentEquals(t.copyOfRange(0, q.size)) -> PREFIX
            q.size >= FUZZY_MIN_LENGTH && oneTypoFromPrefix(q, t) -> FUZZY
            else -> 0
        }

        private fun oneTypoFromPrefix(
            q: IntArray,
            t: IntArray,
        ): Boolean = (q.size - 1..q.size + 1).any { k -> k <= t.size && osa(q, t.copyOfRange(0, k)) <= 1 }

        /** Optimal string alignment distance: Levenshtein plus adjacent transposition, each costing 1. */
        internal fun osa(
            a: IntArray,
            b: IntArray,
        ): Int {
            val d = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in 0..a.size) d[i][0] = i
            for (j in 0..b.size) d[0][j] = j
            for (i in 1..a.size) {
                for (j in 1..b.size) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                    }
                }
            }
            return d[a.size][b.size]
        }

        /** The category's JSON name (`state_psc`), as used for `PresetBundle.categories` keys. */
        fun categoryKey(category: ExamCategory): String {
            val descriptor = ExamCategory.serializer().descriptor
            return descriptor.getElementName(category.ordinal)
        }

        private fun visible(
            exam: Exam,
            showUnverified: Boolean,
        ) = exam.status == ExamStatus.ACTIVE && (showUnverified || exam.confidence != Confidence.LOW)

        private fun nameKey(exam: Exam) = norm(exam.name).joinToString(" ")

        private fun codePoints(s: String): IntArray = s.codePoints().toArray()

        private fun compareByCodePoint(
            a: String,
            b: String,
        ): Int {
            val x = codePoints(a)
            val y = codePoints(b)
            for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
            return x.size.compareTo(y.size)
        }

        private val LETTER_MARK_DIGIT: Set<Byte> =
            setOf(
                Character.UPPERCASE_LETTER,
                Character.LOWERCASE_LETTER,
                Character.TITLECASE_LETTER,
                Character.MODIFIER_LETTER,
                Character.OTHER_LETTER,
                Character.NON_SPACING_MARK,
                Character.ENCLOSING_MARK,
                Character.COMBINING_SPACING_MARK,
                Character.DECIMAL_DIGIT_NUMBER,
            )

        private fun isLetterMarkOrDigit(cp: Int): Boolean = Character.getType(cp).toByte() in LETTER_MARK_DIGIT
    }
}
