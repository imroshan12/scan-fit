package app.scanfit.feature.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.SlotVerdict
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.pdf.PdfError
import app.scanfit.core.pdf.PdfStrategy

/** The made PDF (first page, KB, pages, DPI, verdict and notes) or why it could not be made. */
@Composable
internal fun ResultSection(state: PdfMakerUiState) {
    val target = state.target ?: return
    when (val result = state.result) {
        null -> Unit

        is PdfResult.Failed -> NoticeCard(errorText(result.error, target), NoticeKind.ERROR)

        is PdfResult.Ready -> Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            Heading(stringResource(R.string.pdf_result_heading))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Thumbnail(result.preview, PREVIEW_DP, stringResource(R.string.pdf_preview))
            }
            val facts =
                listOfNotNull(
                    stringResource(R.string.pdf_result_kb, result.kb),
                    pluralStringResource(R.plurals.pdf_pages, result.pages, result.pages),
                    result.dpi?.let { stringResource(R.string.pdf_result_dpi, it) },
                ).joinToString(" · ")
            Text(facts, style = ScanFitType.figure)
            if (target.exam) {
                SlotVerdict(result.meetsRules, target.examName, target.unverified, custom = false)
            } else {
                ToolVerdict(result.meetsRules, target)
            }
            val note =
                when (result.strategy) {
                    PdfStrategy.UNCHANGED -> R.string.pdf_unchanged
                    PdfStrategy.PAD -> R.string.pdf_padded
                    else -> R.string.pdf_text_as_image
                }
            Text(stringResource(note), style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.save.state) {
                SaveState.SAVED -> NoticeCard(stringResource(R.string.export_saved), NoticeKind.INFO)
                SaveState.SAVE_FAILED -> NoticeCard(stringResource(R.string.export_save_failed), NoticeKind.ERROR)
                SaveState.VERIFY_FAILED -> NoticeCard(stringResource(R.string.export_verify_failed), NoticeKind.ERROR)
                else -> Unit
            }
            if (state.shareFailed) NoticeCard(stringResource(R.string.export_share_failed), NoticeKind.ERROR)
        }
    }
}

@Composable
private fun ToolVerdict(
    fits: Boolean,
    target: PdfTarget,
) {
    val max = target.spec.sizeKb.max?.toInt() ?: 0
    if (fits) {
        Text(stringResource(R.string.pdf_fits, max), style = ScanFitType.body, color = ScanFitTheme.colors.success)
    } else {
        NoticeCard(stringResource(R.string.pdf_error_cannot_reach, max), NoticeKind.ERROR)
    }
}

@Composable
private fun errorText(
    error: PdfError,
    target: PdfTarget,
): String = when (error) {
    PdfError.ENCRYPTED -> stringResource(R.string.pdf_error_encrypted)
    PdfError.UNREADABLE, PdfError.NO_PAGES -> stringResource(R.string.pdf_error_unreadable)
    PdfError.TOO_MANY_PAGES -> stringResource(R.string.pdf_error_too_many)
    PdfError.UNKNOWN_LIMIT -> stringResource(R.string.pdf_error_unknown_limit)
    PdfError.CANNOT_REACH -> stringResource(R.string.pdf_error_cannot_reach, target.spec.sizeKb.max?.toInt() ?: 0)
    PdfError.FAILED -> stringResource(R.string.pdf_error_failed)
}

private const val PREVIEW_DP = 240
