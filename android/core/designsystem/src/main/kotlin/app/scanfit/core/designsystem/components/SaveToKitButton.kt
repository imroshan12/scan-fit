package app.scanfit.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/**
 * "Save to My Kit" under Save and Share on a photo or ink review: keeps the picture the review was made from, not the
 * fitted file. A full Kit or a failure is explained under the button, which stays available for a retry.
 */
@Composable
fun SaveToKitButton(
    status: SaveToKitStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = onClick,
            enabled = status != SaveToKitStatus.SAVING && status != SaveToKitStatus.SAVED,
            modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    SaveToKitStatus.SAVING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    SaveToKitStatus.SAVED -> Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    else -> Unit
                }
                Text(
                    stringResource(
                        when (status) {
                            SaveToKitStatus.SAVING -> R.string.kit_saving_original
                            SaveToKitStatus.SAVED -> R.string.kit_saved_original
                            else -> R.string.kit_save_original
                        },
                    ),
                )
            }
        }
        val problem = when (status) {
            SaveToKitStatus.FULL -> R.string.kit_full
            SaveToKitStatus.FAILED -> R.string.kit_save_failed
            else -> null
        }
        if (problem != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(stringResource(problem), style = ScanFitType.caption, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Preview(name = "Save to Kit", showBackground = true)
@Preview(name = "Save to Kit dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Save to Kit large font", showBackground = true, fontScale = 2f)
@Preview(name = "Save to Kit Hindi", showBackground = true, locale = "hi")
@Composable
private fun SaveToKitButtonPreview() {
    ScanFitTheme {
        Surface {
            Column {
                SaveToKitStatus.entries.forEach { SaveToKitButton(it, onClick = {}) }
            }
        }
    }
}
