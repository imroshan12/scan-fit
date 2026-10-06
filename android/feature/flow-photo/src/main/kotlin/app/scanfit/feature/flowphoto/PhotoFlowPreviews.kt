package app.scanfit.feature.flowphoto

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.components.MatchNoteSamples
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import app.scanfit.core.match.ReviewChecks
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.Dimensions
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.SizeKb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// Previews of each step in light, dark, the largest font and Hindi (CLAUDE.md Definition of done).

private val previewSlot =
    PhotoSlot(
        examId = "ibps_po",
        examName = "IBPS PO / MT",
        unverified = false,
        spec =
        DocSpec(
            type = DocType.PHOTO,
            required = true,
            formats = listOf(FileFormat.JPG),
            sizeKb = SizeKb(20.0, 50.0, 38.0),
            dimensions = Dimensions(DimensionMode.PREFERRED, width = 200, height = 230),
        ),
    )

private val previewImage: Raster by lazy {
    Raster.of(600, 800) { x, y -> ((x * 255 / 600) shl 16) or ((y * 255 / 800) shl 8) or 0x80 }
}

@Preview(name = "Pick", showBackground = true)
@Preview(name = "Pick dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Pick large font", showBackground = true, fontScale = 2f)
@Preview(name = "Pick Hindi", showBackground = true, locale = "hi")
@Composable
private fun PickPreview() {
    ScanFitTheme { PhotoFlowScreen(PhotoUiState.PickSource(previewSlot, SourceProblem.NO_FACE), PhotoActions()) }
}

@Preview(name = "Crop", showBackground = true)
@Preview(name = "Crop dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Crop large font", showBackground = true, fontScale = 2f)
@Preview(name = "Crop Hindi", showBackground = true, locale = "hi")
@Composable
private fun CropPreview() {
    ScanFitTheme {
        PhotoFlowScreen(
            PhotoUiState.Crop(previewSlot, previewImage, CropRect(150, 150, 300, 345), tight = true),
            PhotoActions(),
        )
    }
}

@Preview(name = "Review", showBackground = true)
@Preview(name = "Review dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Review large font", showBackground = true, fontScale = 2f)
@Preview(name = "Review Hindi", showBackground = true, locale = "hi")
@Preview(name = "Review Hindi large font", showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun ReviewPreview() {
    ReviewPreviewContent(restored = false)
}

@Preview(name = "Retained review", showBackground = true)
@Preview(name = "Retained review dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Retained review large font", showBackground = true, fontScale = 2f)
@Preview(name = "Retained review Hindi", showBackground = true, locale = "hi")
@Preview(name = "Retained review Hindi large font", showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun RestoredReviewPreview() {
    ReviewPreviewContent(restored = true)
}

@Composable
private fun ReviewPreviewContent(restored: Boolean) {
    val bytes by produceState<ByteArray?>(null) {
        value = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { output ->
                previewImage.toBitmap().compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
        }
    }
    ScanFitTheme {
        PhotoFlowScreen(
            PhotoUiState.Review(
                previewSlot,
                PhotoOptions(nameDate = true, name = "Asha Rao", date = "03/10/2026"),
                ReviewResult.Ready(
                    bytes ?: ByteArray(0),
                    kb = 34,
                    width = 200,
                    height = 230,
                    meetsRules = true,
                    note = MatchNoteSamples.accepted,
                    checks = ReviewChecks(true, true, true),
                ),
                before = previewImage.takeUnless { restored },
                restored = restored,
            ),
            PhotoActions(),
        )
    }
}

@Preview(name = "Review failed", showBackground = true)
@Composable
private fun ReviewFailedPreview() {
    ScanFitTheme {
        PhotoFlowScreen(
            PhotoUiState.Review(previewSlot, PhotoOptions(), ReviewResult.Failed(FitError.TOO_DETAILED)),
            PhotoActions(),
        )
    }
}

@Preview(name = "Export", showBackground = true)
@Preview(name = "Export dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Export large font", showBackground = true, fontScale = 2f)
@Preview(name = "Export Hindi", showBackground = true, locale = "hi")
@Composable
private fun PhotoSavePreview(@PreviewParameter(PhotoSavePreviewStates::class) save: SaveState) {
    ScanFitTheme {
        PhotoFlowScreen(
            PhotoUiState.Review(
                previewSlot,
                PhotoOptions(nameDate = true, name = "Asha Rao", date = "03/10/2026"),
                ReviewResult.Ready(
                    ByteArray(0),
                    kb = 34,
                    width = 200,
                    height = 230,
                    meetsRules = true,
                    checks = ReviewChecks(true, true, true),
                ),
                save = SaveResult(save),
                before = previewImage,
            ),
            PhotoActions(),
        )
    }
}
