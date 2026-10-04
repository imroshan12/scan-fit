package app.scanfit.feature.flowink

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.InkQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** UI_UX §3 Review for ink documents: the fitted file, its numbers and verdict, the ink options, the save status. */
@Composable
internal fun InkReviewContent(
    state: InkUiState.Review,
    actions: InkActions,
) {
    ScrollColumn {
        when (val result = state.result) {
            is InkReviewResult.Working -> {
                ResultImage(null, state)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.flow_step_clean), style = ScanFitType.body)
                }
            }

            is InkReviewResult.Ready -> {
                ResultImage(result.bytes, state)
                ResultSummary(result, state.slot)
                when (result.quality) {
                    InkQuality.TOO_FAINT -> NoticeCard(stringResource(R.string.ink_too_faint), NoticeKind.WARNING)
                    InkQuality.TOO_DARK -> NoticeCard(stringResource(R.string.ink_too_dark), NoticeKind.WARNING)
                    InkQuality.OK -> Unit
                }
            }

            is InkReviewResult.Failed -> {
                ResultImage(null, state)
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
        if (state.slot.hasInkOptions) InkOptionsRow(state.options, actions, editable)
        if (state.slot.needsHandwritingConfirmation) {
            HandwritingConfirmation(state.handwritingConfirmed, actions.onConfirmHandwriting, editable)
        }
    }
}

/** The fitted file as it will be written, at most 200 dp tall, on a light card so white paper reads as paper. */
@Composable
private fun ResultImage(
    bytes: ByteArray?,
    state: InkUiState.Review,
) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value =
            bytes?.let {
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
    }
    val w = state.slot.spec.dimensions.width
    val h = state.slot.spec.dimensions.height
    val fallback = if (w != null && h != null) w.toFloat() / h else PLACEHOLDER_ASPECT
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: fallback
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(max = PREVIEW_MAX_HEIGHT)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(ScanFitRadius.card)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
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
    val a11y =
        pluralStringResource(
            R.plurals.photo_result_a11y,
            result.kb,
            result.kb,
            result.width.toString(),
            result.height.toString(),
        )
    Text(
        stringResource(R.string.photo_result, result.kb, result.width, result.height),
        style = ScanFitType.figure,
        modifier = Modifier.semantics { contentDescription = a11y },
    )
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

private const val PLACEHOLDER_ASPECT = 7f / 3f
private val PREVIEW_MAX_HEIGHT = 200.dp
private const val DARKNESS_SUM = 1.2
private const val MIN_DARKNESS = 0.3f
private const val MAX_DARKNESS = 0.9f
private const val SLIDER_STEPS = 5
