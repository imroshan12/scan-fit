package app.scanfit.feature.checker

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import app.scanfit.core.designsystem.components.MatchNoteSamples
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.InspectedFile
import app.scanfit.core.inspect.Issue
import app.scanfit.core.match.CheckerStatus
import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.match.Constraint
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.ReviewChecks
import app.scanfit.core.match.SlotEvaluation
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.Dimensions
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import app.scanfit.core.model.ExamCategory
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.SizeKb

private val previewSlot =
    DocSpec(
        DocType.SIGNATURE,
        true,
        listOf(FileFormat.JPG),
        SizeKb(10.0, 20.0),
        Dimensions(DimensionMode.EXACT, 140, 60),
    )

private val previewExam =
    Exam(
        id = "preview_bank",
        name = "Bank PO (preview)",
        body = "Preview",
        category = ExamCategory.BANKING,
        status = ExamStatus.ACTIVE,
        documents = listOf(previewSlot),
        specialRules = emptyList(),
        sources = emptyList(),
        confidence = Confidence.HIGH,
        confidenceNote = "",
        lastVerified = "2026-10-01",
    )

private val previewFile =
    CheckedFile(
        "IMG_2041.jpeg",
        InspectedFile(
            DetectedFormat.JPEG,
            48 * 1024,
            900,
            400,
            progressive = true,
            issues = listOf(Issue.PROGRESSIVE_JPEG),
        ),
    )

private val tooBig =
    SlotEvaluation(Verdict.NO, listOf(Constraint.SIZE_KB, Constraint.DIMS), issues = listOf(Issue.TOO_LARGE_KB))

/** Nothing picked, a file with the exam list, a fixable result, and the fitted copy. */
private class CheckerPreviewStates : PreviewParameterProvider<CheckerUiState> {
    private val listed =
        CheckerUiState(
            file = previewFile,
            kind = DocKind.SIGNATURE,
            note = MatchNoteSamples.likelyOk,
            targets = listOf(CheckerTarget(previewExam, previewSlot)),
        )
    private val result =
        TargetResult(CheckerTarget(previewExam, previewSlot), tooBig, ReviewChecks.of(tooBig), CheckerStatus.FIX)

    override val values =
        sequenceOf(
            CheckerUiState(openFailed = true),
            listed,
            listed.copy(result = result),
            listed.copy(
                result = result,
                fix = FixState.Ready(
                    ByteArray(0),
                    16,
                    140,
                    60,
                    true,
                    ReviewChecks(true, true, true),
                    MatchNoteSamples.accepted,
                ),
                handwritingConfirmed = false,
            ),
        )
}

@Preview(name = "Checker", showBackground = true)
@Preview(name = "Checker dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Checker large font", showBackground = true, fontScale = 2f)
@Preview(name = "Checker Hindi", showBackground = true, locale = "hi")
@Composable
private fun CheckerPreview(
    @PreviewParameter(CheckerPreviewStates::class) state: CheckerUiState,
) {
    ScanFitTheme { CheckerScreen(state, CheckerActions()) }
}
