package app.scanfit.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType

/** A section title in the primary colour, read as a heading. */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = ScanFitType.label,
        color = MaterialTheme.colorScheme.primary,
        modifier =
        Modifier
            .padding(horizontal = ScanFitSpacing.screenMargin)
            .padding(top = ScanFitSpacing.xl, bottom = ScanFitSpacing.xs)
            .semantics { heading() },
    )
}

/** A tappable row: a title and an optional caption under it. */
@Composable
internal fun LinkRow(
    title: String,
    caption: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.md),
    ) {
        Text(title, style = ScanFitType.body)
        caption?.let {
            Text(it, style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A switch row: the whole row toggles, not just the switch. */
@Composable
internal fun SwitchRow(
    title: String,
    caption: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ScanFitType.body)
            Text(caption, style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A caption paragraph inside a section. */
@Composable
internal fun Note(text: String) {
    Text(
        text,
        style = ScanFitType.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.sm),
    )
}
