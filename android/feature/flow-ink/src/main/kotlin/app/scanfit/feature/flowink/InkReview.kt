package app.scanfit.feature.flowink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import app.scanfit.core.imaging.InkQuality
import app.scanfit.core.imaging.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** UI_UX §3 Review for ink documents: the fitted file, its numbers and verdict, the ink options, the save status. */
@Composable
internal fun InkReviewContent(
    state: InkUiState.Review,
    actions: InkActions,
) {
    val before by key(state.before) {
        produceState<ImageBitmap?>(null, state.before) {
            value = state.before?.let { raster ->
                withContext(Dispatchers.Default) { raster.toBitmap().asImageBitmap() }
            }
        }
    }
    val shown = if (state.rendering) InkReviewResult.Working(state.slot.targetKb) else state.result
    ScrollColumn {
        BeforeAfterImage(before, (shown as? InkReviewResult.Ready)?.bytes)
        when (val result = shown) {
            is InkReviewResult.Working -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.flow_step_clean), style = ScanFitType.body)
                }
            }

            is InkReviewResult.Ready -> {
                ReviewChecksRow(result.kb, result.width, result.height, result.checks)
                ResultSummary(result, state.slot)
                MatchNoteCard(result.note)
                when (result.quality) {
                    InkQuality.TOO_FAINT -> NoticeCard(stringResource(R.string.ink_too_faint), NoticeKind.WARNING)
                    InkQuality.TOO_DARK -> NoticeCard(stringResource(R.string.ink_too_dark), NoticeKind.WARNING)
                    InkQuality.OK -> Unit
                }
            }

            is InkReviewResult.Failed -> {
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
        val editable = state.save.state != SaveState.SAVING
        if (state.retainFailed) NoticeCard(stringResource(R.string.draft_retain_failed), NoticeKind.WARNING)
        if (state.restored) {
            TextButton(onClick = actions.onReplace, enabled = editable) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(stringResource(R.string.draft_replace))
            }
        } else if (state.slot.hasInkOptions) {
            InkOptionsRow(state.options, actions, editable)
        }
        if (state.slot.needsHandwritingConfirmation) {
            HandwritingConfirmation(state.handwritingConfirmed, actions.onConfirmHandwriting, editable)
        }
    }
}

/** `16 KB · 140×60 · JPG` and the verdict: icon + text, never colour alone (UI_UX §6). */
@Composable
private fun ResultSummary(
    result: InkReviewResult.Ready,
    slot: InkSlot,
) {
    val colors = ScanFitTheme.colors
    val missed = stringResource(R.string.photo_misses_rules, slot.examName)
    val met = stringResource(R.string.photo_meets_rules, slot.examName)
    val (icon, tint: Color, text) =
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

/** "Crisp black" and, while it is off, "Darker ink" (§9.5). The slider runs light → dark: factor 0.9 → 0.3. */
@Composable
private fun InkOptionsRow(
    options: InkReviewOptions,
    actions: InkActions,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Text(stringResource(R.string.flow_toggle_crisp_black), style = ScanFitType.body, modifier = Modifier.weight(1f))
        Switch(checked = options.crispBlack, onCheckedChange = actions.onCrispBlack, enabled = enabled)
    }
    if (!options.crispBlack) {
        Text(stringResource(R.string.flow_slider_ink), style = ScanFitType.body)
        Slider(
            value = (DARKNESS_SUM - options.inkFactor).toFloat(),
            onValueChange = { actions.onInkFactor(DARKNESS_SUM - it) },
            valueRange = MIN_DARKNESS..MAX_DARKNESS,
            steps = SLIDER_STEPS,
            enabled = enabled,
        )
    }
}

/** The one-time "running handwriting" tick (§3, §9.5): required before the first signature save, then remembered. */
@Composable
private fun HandwritingConfirmation(
    confirmed: Boolean,
    onConfirm: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .toggleable(value = confirmed, enabled = enabled && !confirmed, role = Role.Checkbox) { onConfirm() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Checkbox(checked = confirmed, onCheckedChange = null)
        Text(stringResource(R.string.flow_confirm_handwriting), style = ScanFitType.body)
    }
}

private const val DARKNESS_SUM = 1.2
private const val MIN_DARKNESS = 0.3f
private const val MAX_DARKNESS = 0.9f
private const val SLIDER_STEPS = 5
