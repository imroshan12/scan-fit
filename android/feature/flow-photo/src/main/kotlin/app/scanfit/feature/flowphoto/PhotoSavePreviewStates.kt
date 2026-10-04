package app.scanfit.feature.flowphoto

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import app.scanfit.core.data.export.SaveState

internal class PhotoSavePreviewStates : PreviewParameterProvider<SaveState> {
    override val values = sequenceOf(
        SaveState.SAVING,
        SaveState.SAVED,
        SaveState.SAVE_FAILED,
        SaveState.VERIFY_FAILED,
    )
}
