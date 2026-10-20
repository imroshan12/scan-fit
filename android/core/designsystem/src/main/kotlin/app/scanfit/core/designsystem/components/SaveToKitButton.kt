package app.scanfit.core.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Save to My Kit" under Save and Share on a photo or ink review: keeps the picture the review was made from, not the
 * fitted file. With [preview], a tap first shows that picture and saves only after "Save to My Kit" (ALGORITHMS 13.3
 * previews); Cancel changes nothing. A full Kit or a failure is explained under the button, which stays available.
 */
@Composable
fun SaveToKitButton(
    status: SaveToKitStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: (suspend () -> Raster?)? = null,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming && preview != null) {
        SaveToKitDialog(
            preview = preview,
            onSave = {
                confirming = false
                onClick()
            },
            onCancel = { confirming = false },
        )
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = { if (preview != null) confirming = true else onClick() },
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

/** The picture that would be kept, decoded off the main thread, with Save to My Kit and Cancel. */
@Composable
private fun SaveToKitDialog(
    preview: suspend () -> Raster?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, preview) {
        value = preview()?.let { withContext(Dispatchers.Default) { it.toBitmap().asImageBitmap() } }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.kit_save_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md)) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = PREVIEW_HEIGHT, max = PREVIEW_HEIGHT)
                        .clip(RoundedCornerShape(ScanFitRadius.card))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val picture = image
                    if (picture == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(picture, contentDescription = null, contentScale = ContentScale.Fit)
                    }
                }
                Text(stringResource(R.string.kit_save_preview_body), style = ScanFitType.body)
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.kit_save_original)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private val PREVIEW_HEIGHT = 240.dp

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
