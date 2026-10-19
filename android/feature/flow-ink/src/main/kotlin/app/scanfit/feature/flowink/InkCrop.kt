package app.scanfit.feature.flowink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.FreeCropEditor
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType

/** UI_UX §3 Capture/crop for ink documents: a free rectangle (§9.5), Rotate and Reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InkCropContent(
    state: InkUiState.Crop,
    actions: InkActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Text(
            stringResource(R.string.photo_crop_heading),
            style = ScanFitType.headline,
            modifier = Modifier.semantics { heading() },
        )
        FreeCropEditor(
            image = state.image,
            rect = state.rect,
            onMove = actions.onMove,
            onResize = actions.onResize,
            description = stringResource(R.string.ink_crop_hint),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            stringResource(R.string.ink_crop_hint),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            TextButton(onClick = actions.onRotate) { Text(stringResource(R.string.photo_rotate)) }
            TextButton(onClick = actions.onResetCrop) { Text(stringResource(R.string.photo_reset)) }
        }
    }
}
