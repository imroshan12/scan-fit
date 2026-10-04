package app.scanfit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/** A message card: text on a tinted background (the text carries the meaning, not the colour, UI_UX §6). */
@Composable
fun NoticeCard(
    text: String,
    kind: NoticeKind,
    modifier: Modifier = Modifier,
) {
    val colors = ScanFitTheme.colors
    val (background, foreground) =
        when (kind) {
            NoticeKind.INFO -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            NoticeKind.WARNING -> colors.warningContainer to colors.onWarningContainer
            NoticeKind.ERROR -> colors.errorContainer to colors.onErrorContainer
        }
    Text(
        text,
        style = ScanFitType.body,
        color = foreground,
        modifier =
        modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(ScanFitRadius.card))
            .padding(ScanFitSpacing.lg),
    )
}
