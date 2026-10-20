package app.scanfit.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/** The three colours of an accent: card background, its text, and the icon tile. */
private class AccentColors(
    val container: Color,
    val content: Color,
    val tile: Color,
)

@Composable
@ReadOnlyComposable
private fun ToolAccent.colors(): AccentColors {
    val c = ScanFitTheme.colors
    return when (this) {
        ToolAccent.EXAM -> AccentColors(c.primaryContainer, c.onPrimaryContainer, c.primary)
        ToolAccent.PHOTO -> AccentColors(c.photoContainer, c.onPhotoContainer, c.photo)
        ToolAccent.INK -> AccentColors(c.inkContainer, c.onInkContainer, c.ink)
        ToolAccent.PDF -> AccentColors(c.pdfContainer, c.onPdfContainer, c.pdf)
        ToolAccent.CHECK -> AccentColors(c.successContainer, c.onSuccessContainer, c.success)
        ToolAccent.MUTED -> AccentColors(c.surfaceVariant, c.onSurfaceVariant, c.outline)
    }
}

/**
 * A large tool card (UI_UX §3 Home): a soft tinted container, a round icon tile in the accent colour, a title and a
 * short description. One accessible button; [badge] (e.g. "Coming soon") replaces the action when [onClick] is null.
 */
@Composable
fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    accent: ToolAccent,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val colors = accent.colors()
    val shape = RoundedCornerShape(CARD_RADIUS)
    Column(
        modifier =
        modifier
            .clip(shape)
            .background(colors.container, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = MIN_HEIGHT)
            .padding(ScanFitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Box(
            modifier = Modifier.size(TILE).background(colors.tile, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.container, modifier = Modifier.size(ICON))
        }
        Text(title, style = ScanFitType.headline, color = colors.content)
        Text(description, style = ScanFitType.caption, color = colors.content)
        if (badge != null) Text(badge, style = ScanFitType.label, color = colors.content)
    }
}

private val CARD_RADIUS = 20.dp
private val MIN_HEIGHT = 148.dp
private val TILE = 44.dp
private val ICON = 24.dp
