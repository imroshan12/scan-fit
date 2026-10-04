package app.scanfit.feature.flowphoto

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

internal class PhotoSavePreviewStates : PreviewParameterProvider<PhotoSaveState> {
    override val values = sequenceOf(
        PhotoSaveState.SAVING,
        PhotoSaveState.SAVED,
        PhotoSaveState.SAVE_FAILED,
        PhotoSaveState.VERIFY_FAILED,
    )
}
