package app.scanfit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.match.MatchEntry
import app.scanfit.core.match.MatchNote
import app.scanfit.core.match.Need
import app.scanfit.core.match.NeedKind
import app.scanfit.core.match.Verdict

/**
 * The match note under a review's verdict (ALGORITHMS 4 "Match note on review"): "Accepted by N exams", the first
 * names and the quick-fix count. Tapping it opens the detail sheet, grouped by body.
 */
@Composable
fun MatchNoteCard(
    note: MatchNote,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val hasDetails = note.groups.isNotEmpty()
    val colors = ScanFitTheme.colors
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ScanFitRadius.card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = hasDetails, onClickLabel = stringResource(R.string.match_sheet_title)) { open = true }
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .padding(ScanFitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (icon, tint) =
                when (note.headline) {
                    MatchNote.Headline.ACCEPTED -> Icons.Filled.CheckCircle to colors.success
                    else -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
                }
            Icon(icon, contentDescription = null, tint = tint)
            Text(headline(note), style = ScanFitType.body, modifier = Modifier.weight(1f))
            if (hasDetails) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        if (note.preview.isNotEmpty()) {
            val names = note.preview.joinToString(" · ") { it.name }
            val more = pluralStringResource(R.plurals.match_preview_more, note.more, note.more)
            Text(
                if (note.more > 0) "$names $more" else names,
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (note.quickFixes.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.warning)
                val count = note.quickFixes.size
                Text(pluralStringResource(R.plurals.match_quick_fix, count, count), style = ScanFitType.body)
            }
        }
    }
    if (open) MatchNoteSheet(note) { open = false }
}

@Composable
private fun headline(note: MatchNote): String = when (note.headline) {
    MatchNote.Headline.ACCEPTED -> pluralStringResource(R.plurals.match_accepted, note.accepted, note.accepted)
    MatchNote.Headline.LIKELY_OK -> pluralStringResource(R.plurals.match_likely_ok_count, note.likelyOk, note.likelyOk)
    MatchNote.Headline.NONE -> stringResource(R.string.match_none)
}

/** The detail sheet: every entry grouped by body. Near misses say what they need; there is no Fix in an exam flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchNoteSheet(
    note: MatchNote,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScanFitSpacing.screenMargin)
                .padding(bottom = ScanFitSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
        ) {
            Text(
                stringResource(R.string.match_sheet_title),
                style = ScanFitType.headline,
                modifier = Modifier.semantics { heading() },
            )
            for (group in note.groups) {
                Text(group.body, style = ScanFitType.label, modifier = Modifier.semantics { heading() })
                group.entries.forEach { EntryRow(it) }
            }
            if (note.groups.any { group -> group.entries.any { it.verdict == Verdict.NEAR_MISS } }) {
                NoticeCard(stringResource(R.string.match_sheet_other_exam), NoticeKind.INFO)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.common_close))
            }
        }
    }
}

@Composable
private fun EntryRow(entry: MatchEntry) {
    val exam = entry.examName
    val doc = stringResource(entry.docType.labelRes())
    val need = MatchNote.need(entry)
    val status =
        when {
            entry.verdict == Verdict.NEAR_MISS && need != null -> needText(need)
            entry.unverified -> stringResource(R.string.match_likely_ok)
            entry.verdict == Verdict.EXACT -> stringResource(R.string.match_verdict_exact)
            else -> stringResource(R.string.match_verdict_accepted)
        }
    val colors = ScanFitTheme.colors
    val (icon, tint) =
        when {
            entry.verdict == Verdict.NEAR_MISS || entry.unverified -> Icons.Filled.Warning to colors.warning
            else -> Icons.Filled.CheckCircle to colors.success
        }
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column(Modifier.weight(1f)) {
            Text("$exam · $doc", style = ScanFitType.body)
            Text(status, style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun needText(need: Need): String = when (need.kind) {
    NeedKind.AT_MOST -> stringResource(R.string.match_need_at_most, need.kb ?: 0)
    NeedKind.AT_LEAST -> stringResource(R.string.match_need_at_least, need.kb ?: 0)
    NeedKind.JPEG -> stringResource(R.string.match_need_jpeg)
    NeedKind.BASELINE -> stringResource(R.string.match_need_baseline)
}
