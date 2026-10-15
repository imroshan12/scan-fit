package app.scanfit.feature.exams

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.model.DocType

/**
 * The checklist's bottom action (UI_UX §3, ALGORITHMS 1.6.2): "Save all (N)", progress while saving, then the result
 * with Open folder or a retry. Nothing shows when no row can be saved.
 */
@Composable
internal fun SaveAllBar(
    state: ExamUiState.Ready,
    onSaveAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val plan = state.saveAllPlan
    val status = state.saveAll
    val idleWithNothing = status == SaveAllStatus.Idle && (!state.canSaveAll || plan.included.isEmpty())
    if (idleWithNothing && plan.held.isEmpty()) return
    Surface(tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(ScanFitSpacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        ) {
            when (status) {
                is SaveAllStatus.Saving -> Saving(status)

                is SaveAllStatus.Done -> Done(status, onSaveAll, onDismiss)

                SaveAllStatus.Idle -> {
                    if (plan.held.isNotEmpty()) {
                        Text(
                            stringResource(R.string.export_save_all_held),
                            style = ScanFitType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.canSaveAll && plan.included.isNotEmpty()) {
                        Button(
                            onClick = onSaveAll,
                            modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
                        ) { Text(stringResource(R.string.exam_save_all, plan.included.size)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Saving(status: SaveAllStatus.Saving) {
    Text(
        "${stringResource(R.string.export_saving)} ${status.done + 1}/${status.total}",
        style = ScanFitType.body,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    LinearProgressIndicator(
        progress = { status.done.toFloat() / status.total },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Done(
    status: SaveAllStatus.Done,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val text =
        if (status.complete) {
            pluralStringResource(R.plurals.flow_saved_to, status.saved, status.saved, status.folder)
        } else {
            stringResource(R.string.export_save_all_partial, status.saved, status.total)
        }
    NoticeCard(
        text,
        if (status.complete) NoticeKind.INFO else NoticeKind.ERROR,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        if (status.complete) {
            TextButton(onClick = {
                // The system Downloads view: a specific subfolder cannot be opened portably (ALGORITHMS 1.6.2).
                try {
                    context.startActivity(
                        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: ActivityNotFoundException) {
                    // No Downloads app: the message already says where the files are.
                }
                onDismiss()
            }) { Text(stringResource(R.string.flow_open_folder)) }
        } else {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
    }
}

/** Ready with a held signature, saving, all saved, and a partial failure. */
private class SaveAllPreviewStates : PreviewParameterProvider<ExamUiState.Ready> {
    private val base =
        ExamUiState.Ready(
            previewExam,
            pinned = false,
            readyDocuments = mapOf(
                DocType.PHOTO to 34,
                DocType.SIGNATURE to 16,
            ),
            canSaveAll = true,
        )

    override val values =
        sequenceOf(
            base,
            base.copy(saveAll = SaveAllStatus.Saving(1, 2)),
            base.copy(saveAll = SaveAllStatus.Done(2, 2, "Downloads/ScanFit/IBPS-PO")),
            base.copy(saveAll = SaveAllStatus.Done(1, 2, "Downloads/ScanFit/IBPS-PO")),
        )
}

@Preview(name = "Save all", showBackground = true)
@Preview(name = "Save all dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Save all large font", showBackground = true, fontScale = 2f)
@Preview(name = "Save all Hindi", showBackground = true, locale = "hi")
@Composable
private fun SaveAllBarPreview(
    @PreviewParameter(SaveAllPreviewStates::class) state: ExamUiState.Ready,
) {
    ScanFitTheme { SaveAllBar(state, {}, {}) }
}
