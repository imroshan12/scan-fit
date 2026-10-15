package app.scanfit.core.data.export

import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType

/**
 * Which checklist rows "Save all" writes (ALGORITHMS 1.6.2), in preset document order: every Ready row (a verified
 * draft, no Saved record), except an exam signature still waiting for the one-time handwriting tick, which is held.
 */
data class SaveAllPlan(
    val included: List<DocType>,
    val held: List<DocType>,
) {
    companion object {
        val EMPTY = SaveAllPlan(emptyList(), emptyList())

        fun of(
            documents: List<DocType>,
            ready: Set<DocType>,
            saved: Set<DocType>,
            handwritingConfirmed: Boolean,
        ): SaveAllPlan {
            val candidates = documents.filter { it in ready && it !in saved }
            val (held, included) =
                candidates.partition { DocKind.of(it) == DocKind.SIGNATURE && !handwritingConfirmed }
            return SaveAllPlan(included, held)
        }
    }
}
