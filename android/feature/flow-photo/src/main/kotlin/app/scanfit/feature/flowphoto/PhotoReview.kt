package app.scanfit.feature.flowphoto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.BeforeAfterImage
import app.scanfit.core.designsystem.components.MatchNoteCard
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.ReviewChecksRow
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** UI_UX §3 Review: the fitted photo, its numbers and verdict, and the photo options. */
@Composable
internal fun ReviewContent(
    state: PhotoUiState.Review,
    actions: PhotoActions,
) {
    val before by key(state.before) {
        produceState<ImageBitmap?>(null, state.before) {
            value = state.before?.let { raster ->
                withContext(Dispatchers.Default) { raster.toBitmap().asImageBitmap() }
            }
        }
    }
    val shown = if (state.rendering) ReviewResult.Working(state.slot.targetKb) else state.result
    ScrollColumn {
        BeforeAfterImage(before, (shown as? ReviewResult.Ready)?.bytes)
        when (val result = shown) {
            is ReviewResult.Working -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    result.targetKb?.let { Text(stringResource(R.string.flow_step_fit, it), style = ScanFitType.body) }
                }
            }

            is ReviewResult.Ready -> {
                ReviewChecksRow(result.kb, result.width, result.height, result.checks)
                ResultSummary(result, state.slot)
                MatchNoteCard(result.note)
            }

            is ReviewResult.Failed -> {
                val max = state.slot.spec.sizeKb.max?.roundToInt() ?: 0
                val text =
                    when (result.error) {
                        FitError.TOO_DETAILED -> stringResource(R.string.error_fit_too_detailed, max)
                        FitError.UNKNOWN_LIMIT -> stringResource(R.string.exam_spec_unknown)
                        else -> stringResource(R.string.error_generic)
                    }
                NoticeCard(text, NoticeKind.ERROR)
            }
        }
        when (state.save.state) {
            SaveState.SAVED -> NoticeCard(stringResource(R.string.export_saved), NoticeKind.INFO)
            SaveState.SAVE_FAILED -> NoticeCard(stringResource(R.string.export_save_failed), NoticeKind.ERROR)
            SaveState.VERIFY_FAILED -> NoticeCard(stringResource(R.string.export_verify_failed), NoticeKind.ERROR)
            else -> Unit
        }
        if (state.retainFailed) NoticeCard(stringResource(R.string.draft_retain_failed), NoticeKind.WARNING)
        if (state.restored) {
            TextButton(onClick = actions.onReplace, enabled = state.save.state != SaveState.SAVING) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(stringResource(R.string.draft_replace))
            }
        } else {
            Options(
                state.options,
                stripHint = state.slot.spec.nameDateStrip?.required == true,
                actions = actions,
                enabled = state.save.state != SaveState.SAVING,
            )
        }
    }
}

/** `34 KB · 200×230 · JPG` and the verdict: icon + text, never colour alone (UI_UX §6). */
@Composable
private fun ResultSummary(
    result: ReviewResult.Ready,
    slot: PhotoSlot,
) {
    val colors = ScanFitTheme.colors
    val missed = stringResource(R.string.photo_misses_rules, slot.examName)
    val met = stringResource(R.string.photo_meets_rules, slot.examName)
    val (icon, tint, text) =
        when {
            !result.meetsRules -> Triple(Icons.Filled.Warning, colors.warning, missed)

            // A low-confidence preset never gets "meets the rules" (CLAUDE.md rule 6).
            slot.unverified -> Triple(Icons.Filled.Warning, colors.warning, stringResource(R.string.match_likely_ok))

            else -> Triple(Icons.Filled.CheckCircle, colors.success, met)
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(text, style = ScanFitType.body)
    }
}

@Composable
private fun Options(
    options: PhotoOptions,
    stripHint: Boolean,
    actions: PhotoActions,
    enabled: Boolean,
) {
    ToggleRow(
        label = stringResource(R.string.flow_toggle_white_bg),
        checked = options.whiteBackground,
        enabled = enabled && options.whiteBackgroundAvailable,
        onChange = actions.onWhiteBackground,
    )
    if (!options.whiteBackgroundAvailable) {
        Text(
            stringResource(R.string.photo_white_bg_unavailable),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ToggleRow(stringResource(R.string.flow_toggle_name_date), options.nameDate, enabled, actions.onNameDate)
    if (stripHint && !options.nameDate) {
        Text(
            stringResource(R.string.photo_strip_hint),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (options.nameDate) {
        OutlinedTextField(
            value = options.name,
            enabled = enabled,
            onValueChange = actions.onName,
            label = { Text(stringResource(R.string.photo_name_label)) },
            singleLine = true,
            keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = options.date,
            enabled = enabled,
            onValueChange = actions.onDate,
            label = { Text(stringResource(R.string.photo_date_label)) },
            singleLine = true,
            keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Text(label, style = ScanFitType.body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
