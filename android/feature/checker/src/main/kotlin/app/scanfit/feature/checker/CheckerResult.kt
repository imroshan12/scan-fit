package app.scanfit.feature.checker

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.ConfidenceBadge
import app.scanfit.core.designsystem.components.MatchNoteCard
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.ReviewChecksRow
import app.scanfit.core.designsystem.components.SlotVerdict
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.FitError
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Issue
import app.scanfit.core.match.CheckerStatus
import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.FileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** With a kind: who accepts the file as it is, then the exam list or the chosen exam's result (ALGORITHMS 12). */
@Composable
internal fun TargetSection(
    state: CheckerUiState,
    actions: CheckerActions,
) {
    val result = state.result
    if (result == null) {
        MatchNoteCard(state.note)
        Heading(stringResource(R.string.checker_which))
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQuery,
            placeholder = { Text(stringResource(R.string.checker_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Column {
            state.targets.forEach { target ->
                TargetRow(target, actions.onTarget)
                HorizontalDivider()
            }
        }
    } else {
        ResultCard(state, result, actions)
        FixSection(state, actions)
    }
}

@Composable
private fun TargetRow(
    target: CheckerTarget,
    onTarget: (CheckerTarget) -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable { onTarget(target) }
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .padding(vertical = ScanFitSpacing.sm)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
            Text(target.exam.name, style = ScanFitType.body)
            Text(
                stringResource(target.slot.type.labelRes()),
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only the unverified state needs a badge in a list (CLAUDE.md rule 6).
            if (target.exam.confidence == Confidence.LOW) ConfidenceBadge(Confidence.LOW)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** The file against the chosen slot: chips, verdict and every problem, then what can be done. */
@Composable
private fun ResultCard(
    state: CheckerUiState,
    result: TargetResult,
    actions: CheckerActions,
) {
    val file = state.file ?: return
    val target = result.target
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Heading(target.exam.name)
                Text(stringResource(target.slot.type.labelRes()), style = ScanFitType.caption)
            }
            TextButton(onClick = actions.onChangeExam) { Text(stringResource(R.string.checker_change_exam)) }
        }
        ReviewChecksRow(file.kb, file.inspected.width ?: 0, file.inspected.height ?: 0, result.checks)
        when (result.status) {
            CheckerStatus.UNKNOWN -> NoticeCard(stringResource(R.string.exam_spec_unknown), NoticeKind.WARNING)

            CheckerStatus.OK, CheckerStatus.LIKELY_OK ->
                SlotVerdict(true, target.exam.name, target.exam.isUnverified, custom = false)

            CheckerStatus.FIX, CheckerStatus.NO_FIX ->
                SlotVerdict(false, target.exam.name, target.exam.isUnverified, custom = false)
        }
        (file.inspected.issues + result.evaluation.issues).distinct().forEach { IssueRow(it, file, target.slot) }
        if (result.status == CheckerStatus.NO_FIX) {
            val needsPdf = target.slot.formats.all { it == FileFormat.PDF }
            NoticeCard(
                stringResource(if (needsPdf) R.string.checker_needs_pdf else R.string.checker_no_fix),
                NoticeKind.INFO,
            )
        }
        if (result.status == CheckerStatus.FIX && state.fix == FixState.None) {
            Button(
                onClick = actions.onFix,
                modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
            ) { Text(stringResource(R.string.checker_fix, target.exam.name)) }
        }
    }
}

/** "Make it fit": progress, then the fitted copy as a review would show it. */
@Composable
private fun FixSection(
    state: CheckerUiState,
    actions: CheckerActions,
) {
    when (val fix = state.fix) {
        FixState.None -> Unit

        FixState.Working ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.checker_fixing), style = ScanFitType.body)
            }

        is FixState.Failed -> {
            val text =
                when (fix.error) {
                    FitError.TOO_DETAILED -> {
                        val max = state.result?.target?.slot?.sizeKb?.max?.roundToInt() ?: 0
                        stringResource(R.string.error_fit_too_detailed, max)
                    }

                    null -> stringResource(R.string.checker_open_failed)

                    else -> stringResource(R.string.error_generic)
                }
            NoticeCard(text, NoticeKind.ERROR)
        }

        is FixState.Ready -> FixedCopy(state, fix, actions)
    }
}

@Composable
private fun FixedCopy(
    state: CheckerUiState,
    fix: FixState.Ready,
    actions: CheckerActions,
) {
    val target = state.result?.target ?: return
    Heading(stringResource(R.string.checker_fixed_heading))
    FixedImage(fix.bytes)
    ReviewChecksRow(fix.kb, fix.width, fix.height, fix.checks)
    SlotVerdict(fix.meetsRules, target.exam.name, target.exam.isUnverified, custom = false)
    MatchNoteCard(fix.note)
    Text(
        stringResource(R.string.checker_original_kept),
        style = ScanFitType.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.kind == app.scanfit.core.match.DocKind.SIGNATURE) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ScanFitSpacing.minTouchTarget)
                .toggleable(
                    value = state.handwritingConfirmed,
                    enabled = !state.handwritingConfirmed,
                    role = Role.Checkbox,
                ) { actions.onConfirmHandwriting() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        ) {
            Checkbox(checked = state.handwritingConfirmed, onCheckedChange = null)
            Text(stringResource(R.string.flow_confirm_handwriting), style = ScanFitType.body)
        }
    }
    when (state.save.state) {
        SaveState.SAVED -> NoticeCard(stringResource(R.string.export_saved), NoticeKind.INFO)
        SaveState.SAVE_FAILED -> NoticeCard(stringResource(R.string.export_save_failed), NoticeKind.ERROR)
        SaveState.VERIFY_FAILED -> NoticeCard(stringResource(R.string.export_verify_failed), NoticeKind.ERROR)
        else -> Unit
    }
    if (state.shareFailed) NoticeCard(stringResource(R.string.export_share_failed), NoticeKind.ERROR)
}

/** The fitted copy as it will be written, at most 200 dp tall, on a light card. */
@Composable
private fun FixedImage(bytes: ByteArray) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value =
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
    }
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(ScanFitRadius.card))
            .padding(ScanFitSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit) }
    }
}

/** One problem, with its numbers when the slot gives them (icon + text, never colour alone). */
@Composable
internal fun IssueRow(
    issue: Issue,
    file: CheckedFile,
    slot: DocSpec?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = ScanFitTheme.colors.warning)
        Text(issueText(issue, file, slot), style = ScanFitType.body)
    }
}

@Composable
private fun issueText(
    issue: Issue,
    file: CheckedFile,
    slot: DocSpec?,
): String {
    val w = file.inspected.width ?: 0
    val h = file.inspected.height ?: 0
    return when (issue) {
        Issue.EXTENSION_MISMATCH -> stringResource(R.string.issue_extension_mismatch)

        Issue.CMYK_COLOR -> stringResource(R.string.issue_cmyk_color)

        Issue.PROGRESSIVE_JPEG -> stringResource(R.string.issue_progressive_jpeg)

        Issue.HAS_GPS_EXIF -> stringResource(R.string.issue_has_gps_exif)

        Issue.ROTATED_BY_EXIF -> stringResource(R.string.issue_rotated_by_exif)

        Issue.HEIC_NOT_ACCEPTED -> stringResource(R.string.issue_heic_not_accepted)

        Issue.PDF_ENCRYPTED -> stringResource(R.string.issue_pdf_encrypted)

        Issue.TOO_SMALL_KB -> stringResource(R.string.issue_too_small_kb, file.kb, slot?.sizeKb?.min?.roundToInt() ?: 0)

        Issue.TOO_LARGE_KB -> stringResource(R.string.issue_too_large_kb, file.kb, slot?.sizeKb?.max?.toInt() ?: 0)

        Issue.WRONG_DIMENSIONS -> {
            val tw = slot?.dimensions?.width
            val th = slot?.dimensions?.height
            if (tw != null && th != null) {
                stringResource(R.string.issue_wrong_dimensions, w, h, tw, th)
            } else {
                stringResource(R.string.issue_wrong_aspect)
            }
        }

        Issue.WRONG_ASPECT -> stringResource(R.string.issue_wrong_aspect)

        Issue.LOW_DPI_METADATA -> stringResource(R.string.issue_low_dpi_metadata, slot?.dpi ?: DEFAULT_DPI)

        Issue.GRAYSCALE_NOT_ALLOWED -> stringResource(R.string.issue_grayscale_not_allowed)
    }
}

internal fun formatName(file: CheckedFile): String = when (file.inspected.format) {
    DetectedFormat.JPEG -> "JPG"
    DetectedFormat.UNKNOWN -> "?"
    else -> file.inspected.format.name
}

private const val DEFAULT_DPI = 200
