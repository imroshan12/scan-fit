package app.scanfit.core.match

data class ReviewChecks(
    val size: Boolean = false,
    val dimensions: Boolean = false,
    val jpeg: Boolean = false,
) {
    companion object {
        fun of(evaluation: SlotEvaluation): ReviewChecks = if (evaluation.verdict == Verdict.UNKNOWN) {
            ReviewChecks()
        } else {
            ReviewChecks(
                size = Constraint.SIZE_KB !in evaluation.failed,
                dimensions = Constraint.DIMS !in evaluation.failed,
                jpeg = Constraint.FORMAT !in evaluation.failed && Constraint.ENCODING !in evaluation.failed,
            )
        }
    }
}
