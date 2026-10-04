package app.scanfit.feature.flowphoto

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.FitError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** UI_UX §3 Review: the fitted photo, its numbers and verdict, and the photo options. */
@Composable
internal fun ReviewContent(
    state: PhotoUiState.Review,
    actions: PhotoActions,
) {
    ScrollColumn {
        when (val result = state.result) {
            is ReviewResult.Working -> {
                ResultImage(null)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    result.targetKb?.let { Text(stringResource(R.string.flow_step_fit, it), style = ScanFitType.body) }
                }
            }

            is ReviewResult.Ready -> {
                ResultImage(result.bytes)
                ResultSummary(result, state.slot)
            }

            is ReviewResult.Failed -> {
                ResultImage(null)
                val max = state.slot.spec.sizeKb.max?.roundToInt() ?: 0
                val text =
                    when (result.error) {
                        FitError.TOO_DETAILED -> stringResource(R.string.error_fit_too_detailed, max)
                        FitError.UNKNOWN_LIMIT -> stringResource(R.string.exam_spec_unknown)
                        else -> stringResource(R.string.error_generic)
                    }
                Notice(text, NoticeKind.ERROR)
            }
        }
        when (state.save.state) {
            PhotoSaveState.SAVED -> Notice(stringResource(R.string.export_saved), NoticeKind.INFO)
            PhotoSaveState.SAVE_FAILED -> Notice(stringResource(R.string.export_save_failed), NoticeKind.ERROR)
            PhotoSaveState.VERIFY_FAILED -> Notice(stringResource(R.string.export_verify_failed), NoticeKind.ERROR)
            else -> Unit
        }
        Options(
            state.options,
            stripHint = state.slot.spec.nameDateStrip?.required == true,
            actions = actions,
            enabled = state.save.state != PhotoSaveState.SAVING,
        )
    }
}

/** The fitted photo as it will be written, at most 280 dp tall; a placeholder while it is being made. */
@Composable
private fun ResultImage(bytes: ByteArray?) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value =
            bytes?.let {
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
    }
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: PLACEHOLDER_ASPECT
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

/** `34 KB · 200×230 · JPG` and the verdict: icon + text, never colour alone (UI_UX §6). */
@Composable
private fun ResultSummary(
    result: ReviewResult.Ready,
    slot: PhotoSlot,
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

private const val PLACEHOLDER_ASPECT = 200f / 230f
private val PREVIEW_MAX_HEIGHT = 280.dp
