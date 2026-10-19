package app.scanfit.feature.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DocType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** My Kit's three views (ALGORITHMS 13), Prepared first. */
enum class KitTab { PREPARED, ORIGINALS, HISTORY }

/** Everything the Kit screen can ask for. */
internal class KitActions(
    val onTab: (KitTab) -> Unit = {},
    val onRetryPrepared: () -> Unit = {},
    val onOpen: (String, DocType) -> Unit = { _, _ -> },
    val onRetryOriginals: () -> Unit = {},
    val onOpenOriginal: (String) -> Unit = {},
    val onRetryHistory: () -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    /** A small upright copy of an original for its row; `null` while unavailable. */
    val thumbnail: suspend (String) -> Raster? = { null },
)

/** Everything the original's screen can ask for. */
internal class KitOriginalActions(
    val onBack: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onUse: (String, DocType) -> Unit = { _, _ -> },
    val onDelete: () -> Unit = {},
)

internal fun KitTab.labelRes(): Int = when (this) {
    KitTab.PREPARED -> R.string.kit_tab_prepared
    KitTab.ORIGINALS -> R.string.kit_tab_originals
    KitTab.HISTORY -> R.string.kit_tab_history
}

@Composable
internal fun KitRow(
    title: String,
    caption: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = ScanFitSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
            Text(title, style = ScanFitType.body)
            Text(caption, style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
}

@Composable
internal fun Empty(
    title: Int,
    body: Int,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(ScanFitSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
        ) {
            Icon(Icons.Filled.AccountBox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(title),
                style = ScanFitType.title,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(body),
                style = ScanFitType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun Failed(
    message: Int,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(ScanFitSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
        ) {
            Text(stringResource(message), textAlign = TextAlign.Center)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
    }
}

/** A day in the app's language ("15 Oct 2026", "15 अक्टू॰ 2026"), from the composition's locale. */
@Composable
internal fun formatDay(millis: Long): String {
    val locale: Locale = LocalConfiguration.current.locales[0]
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}
