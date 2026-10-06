package app.scanfit.feature.flowink

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.tooling.preview.Preview
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.components.MatchNoteSamples
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.InkQuality
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

private val paper: Raster by lazy {
    Raster.of(700, 400) { column, row ->
        if (column in 80..620 && kotlin.math.abs(row - (200 + 60 * kotlin.math.sin(column / 35.0))) < 3) {
            0x202040
        } else {
            PAPER
        }
    }
}

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
@Preview(name = "Review signature Hindi large font", showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun ReviewPreview() {
    ReviewPreviewContent(restored = false)
}

@Preview(name = "Retained signature", showBackground = true)
@Preview(name = "Retained signature dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Retained signature large font", showBackground = true, fontScale = 2f)
@Preview(name = "Retained signature Hindi", showBackground = true, locale = "hi")
@Preview(name = "Retained signature Hindi large font", showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun RestoredReviewPreview() {
    ReviewPreviewContent(restored = true)
}

@Composable
private fun ReviewPreviewContent(restored: Boolean) {
    val bytes by produceState<ByteArray?>(null) {
        value = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { output ->
                paper.toBitmap().compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
        }
    }
    ScanFitTheme {
        InkFlowScreen(
            InkUiState.Review(
                slot(DocType.SIGNATURE),
                InkReviewOptions(crispBlack = false, inkFactor = 0.5),
                InkReviewResult.Ready(
                    bytes ?: ByteArray(0),
                    kb = 16,
                    width = 140,
                    height = 60,
                    meetsRules = true,
                    quality = if (restored) InkQuality.OK else InkQuality.TOO_FAINT,
                    note = MatchNoteSamples.accepted,
                    checks = ReviewChecks(true, true, true),
                ),
                handwritingConfirmed = false,
                before = paper.takeUnless { restored },
                restored = restored,
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
                    checks = ReviewChecks(true, true, true),
                ),
                handwritingConfirmed = true,
                save = SaveResult(SaveState.SAVED),
                before = paper,
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
