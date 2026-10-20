package app.scanfit.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/**
 * Free saves left to show on a review (ALGORITHMS 14.3): set at the app's root when 5 or fewer are left for a free
 * user, `null` otherwise (Pro, or more left).
 */
val LocalFreeSavesLeft = compositionLocalOf<Int?> { null }

/** "3 free saves left" (or "No free saves left…") under a review's actions; nothing if [LocalFreeSavesLeft] is null. */
@Composable
fun FreeSavesNote(modifier: Modifier = Modifier) {
    val left = LocalFreeSavesLeft.current ?: return
    val text = when {
        left > 0 -> pluralStringResource(R.plurals.quota_left, left, left)
        else -> stringResource(R.string.quota_none)
    }
    Text(
        text = text,
        style = ScanFitType.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(top = ScanFitSpacing.xs),
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, fontScale = 2f, locale = "hi")
@Composable
private fun FreeSavesNotePreview() {
    ScanFitTheme {
        Surface {
            CompositionLocalProvider(LocalFreeSavesLeft provides 3) { FreeSavesNote() }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FreeSavesNoneNotePreview() {
    ScanFitTheme {
        Surface {
            CompositionLocalProvider(LocalFreeSavesLeft provides 0) { FreeSavesNote() }
        }
    }
}
