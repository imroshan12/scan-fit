package app.scanfit.core.model

/**
 * What a photo or ink flow makes a file for (ALGORITHMS 11): one slot of a trusted exam preset, or a Custom size.
 * The flows resolve it to a [FlowSlot] and branch on [FlowSlot.custom] only where the two differ (drafts, Saved status,
 * the handwriting tick, the verdict text).
 */
sealed interface FlowTarget {
    data class ExamSlot(
        val examId: String,
        /** The preset wire name, e.g. `left_thumb`. */
        val docType: String,
    ) : FlowTarget

    data class Custom(
        val size: CustomSize,
    ) : FlowTarget

    /** The slot to work on, or `null` when an exam slot is not (or no longer) in the trusted [bundle]. */
    fun resolve(bundle: PresetBundle?): FlowSlot? = when (this) {
        is Custom -> FlowSlot(CustomSize.EXAM_ID, CustomSize.EXAM_NAME, unverified = false, size.spec, custom = true)

        is ExamSlot -> {
            val exam = bundle?.exams?.firstOrNull { it.id == examId && it.status == ExamStatus.ACTIVE }
            exam?.documents?.firstOrNull { it.type.wire == docType }?.let {
                FlowSlot(exam.id, exam.name, exam.isUnverified, it, custom = false)
            }
        }
    }

    companion object {
        /** Navigation argument names: `exam/{examId}/<flow>/{docType}` or `custom/<flow>?custom={text}`. */
        const val EXAM_ID = "examId"
        const val DOC_TYPE = "docType"
        const val CUSTOM = "custom"

        /** The target named by a route's arguments; `null` when they name neither a slot nor a valid Custom size. */
        fun of(
            examId: String?,
            docType: String?,
            custom: String?,
        ): FlowTarget? = when {
            custom != null -> CustomSize.parse(custom)?.let(::Custom)
            examId != null && docType != null -> ExamSlot(examId, docType)
            else -> null
        }
    }
}

/** A resolved slot: the exam (or the synthetic Custom exam) and the document spec the flow fits to. */
data class FlowSlot(
    val examId: String,
    val examName: String,
    /** Low-confidence preset: never "meets the rules" (CLAUDE.md rule 6). Always false for Custom. */
    val unverified: Boolean,
    val spec: DocSpec,
    val custom: Boolean,
)
