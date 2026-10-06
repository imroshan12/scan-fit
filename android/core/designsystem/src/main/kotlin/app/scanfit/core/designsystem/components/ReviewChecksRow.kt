package app.scanfit.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.match.ReviewChecks

@Composable
fun VerdictChip(
    label: String,
    description: String,
    passes: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ScanFitTheme.colors
    val background = if (passes) colors.successContainer else colors.warningContainer
    val foreground = if (passes) colors.onSuccessContainer else colors.onWarningContainer
    val status = stringResource(if (passes) R.string.review_check_pass else R.string.review_check_fail, description)
    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = status }
            .background(background, CircleShape)
            .padding(horizontal = ScanFitSpacing.md, vertical = ScanFitSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (passes) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(18.dp),
        )
        Text(label, style = ScanFitType.figure, color = foreground)
    }
}

@Composable
fun ReviewChecksRow(
    kb: Int,
    width: Int,
    height: Int,
    checks: ReviewChecks,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        VerdictChip(
            stringResource(R.string.review_size, kb),
            pluralStringResource(R.plurals.review_size_a11y, kb, kb),
            checks.size,
        )
        VerdictChip(
            stringResource(R.string.review_dimensions, width, height),
            stringResource(R.string.review_dimensions_a11y, width.toString(), height.toString()),
            checks.dimensions,
        )
        VerdictChip(
            stringResource(R.string.review_jpeg),
            stringResource(R.string.review_jpeg_a11y),
            checks.jpeg,
        )
    }
}

@Preview(name = "Review checks", showBackground = true)
@Preview(name = "Review checks dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Review checks large font", showBackground = true, fontScale = 2f)
@Preview(name = "Review checks Hindi", showBackground = true, locale = "hi")
@Preview(name = "Review checks Hindi large font", showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun ReviewChecksPreview() {
    ScanFitTheme {
        Column(Modifier.padding(ScanFitSpacing.lg), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md)) {
            ReviewChecksRow(34, 200, 230, ReviewChecks(true, true, true))
            ReviewChecksRow(60, 200, 230, ReviewChecks(false, true, true))
            ReviewChecksRow(16, 140, 60, ReviewChecks())
        }
    }
}
