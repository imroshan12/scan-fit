package app.scanfit.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.model.Confidence

/** One (exam, slot) of the Checker's list (ALGORITHMS 12), also My Kit's "Use for exam…" (ALGORITHMS 13.2). */
@Composable
fun CheckerTargetRow(
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
