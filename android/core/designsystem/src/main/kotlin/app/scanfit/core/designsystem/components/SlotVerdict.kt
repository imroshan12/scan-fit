package app.scanfit.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/**
 * The review's verdict for its own slot: icon + text, never colour alone (UI_UX §6). A low-confidence preset never
 * "meets the rules" (CLAUDE.md rule 6); a Custom size meets "your size" (ALGORITHMS 11).
 */
@Composable
fun SlotVerdict(
    meetsRules: Boolean,
    examName: String,
    unverified: Boolean,
    custom: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ScanFitTheme.colors
    val (ok, text) =
        when {
            !meetsRules && custom -> false to stringResource(R.string.custom_misses)
            !meetsRules -> false to stringResource(R.string.photo_misses_rules, examName)
            custom -> true to stringResource(R.string.custom_meets)
            unverified -> false to stringResource(R.string.match_likely_ok)
            else -> true to stringResource(R.string.photo_meets_rules, examName)
        }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) colors.success else colors.warning,
        )
        Text(text, style = ScanFitType.body)
    }
}
