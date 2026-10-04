package app.scanfit.feature.flowink

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.InkQuality
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.Dimensions
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.SizeKb

// Previews of each step in light, dark, the largest font and Hindi (CLAUDE.md Definition of done).

private fun slot(type: DocType) = InkSlot(
    examId = "ibps_po",
    examName = "IBPS PO / MT",
    unverified = false,
    spec =
    DocSpec(
        type = type,
        required = true,
        formats = listOf(FileFormat.JPG),
        sizeKb = SizeKb(min = 10.0, max = 20.0, target = 16.0),
        dimensions = Dimensions(DimensionMode.PREFERRED, width = 140, height = 60),
    ),
)

private val paper: Raster by lazy { Raster.of(700, 400) { _, _ -> PAPER } }

private const val PAPER = 0xF4F1EA

@Preview(name = "Pick declaration", showBackground = true)
@Preview(name = "Pick declaration dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Pick declaration large font", showBackground = true, fontScale = 2f)
@Preview(name = "Pick declaration Hindi", showBackground = true, locale = "hi")
@Composable
private fun PickPreview() {
    val state = InkUiState.PickSource(slot(DocType.HANDWRITTEN_DECLARATION), openFailed = true)
    ScanFitTheme { InkFlowScreen(state, InkActions()) }
}

@Preview(name = "Crop", showBackground = true)
@Preview(name = "Crop dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Crop large font", showBackground = true, fontScale = 2f)
@Preview(name = "Crop Hindi", showBackground = true, locale = "hi")
@Composable
private fun CropPreview() {
    ScanFitTheme {
        InkFlowScreen(InkUiState.Crop(slot(DocType.SIGNATURE), paper, CropRect(80, 60, 520, 260)), InkActions())
    }
}

@Preview(name = "Review signature", showBackground = true)
@Preview(name = "Review signature dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Review signature large font", showBackground = true, fontScale = 2f)
@Preview(name = "Review signature Hindi", showBackground = true, locale = "hi")
@Composable
private fun ReviewPreview() {
    ScanFitTheme {
        InkFlowScreen(
            InkUiState.Review(
                slot(DocType.SIGNATURE),
                InkReviewOptions(crispBlack = false, inkFactor = 0.5),
                InkReviewResult.Ready(
                    ByteArray(0),
                    kb = 16,
                    width = 140,
                    height = 60,
                    meetsRules = true,
                    quality = InkQuality.TOO_FAINT,
                ),
                handwritingConfirmed = false,
            ),
            InkActions(),
        )
    }
}

@Preview(name = "Review saved", showBackground = true)
@Composable
private fun ReviewSavedPreview() {
    ScanFitTheme {
        InkFlowScreen(
            InkUiState.Review(
                slot(DocType.LEFT_THUMB),
                InkReviewOptions(crispBlack = false),
                InkReviewResult.Ready(
                    ByteArray(0),
                    kb = 34,
                    width = 240,
                    height = 240,
                    meetsRules = true,
                    quality = InkQuality.OK,
                ),
                handwritingConfirmed = true,
                save = SaveResult(SaveState.SAVED),
            ),
            InkActions(),
        )
    }
}

@Preview(name = "Review failed", showBackground = true)
@Composable
private fun ReviewFailedPreview() {
    ScanFitTheme {
        InkFlowScreen(
            InkUiState.Review(
                slot(DocType.SIGNATURE),
                InkReviewOptions(crispBlack = true),
                InkReviewResult.Failed(FitError.TOO_DETAILED),
                handwritingConfirmed = true,
            ),
            InkActions(),
        )
    }
}
