package app.scanfit.feature.pdf

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.Dimensions
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.SizeKb
import app.scanfit.core.pdf.PdfError
import app.scanfit.core.pdf.PdfProgress
import app.scanfit.core.pdf.PdfStrategy
import app.scanfit.core.pdf.PdfTool

private val previewPage = Raster.of(120, 170) { x, y -> if (y % 12 < 3 && x in 12..108) 0x303030 else 0xF7F7F7 }

private val examTarget =
    PdfTarget(
        "preview_entrance",
        "Entrance exam (preview)",
        DocSpec(
            DocType.CLASS10_CERTIFICATE,
            true,
            listOf(FileFormat.PDF),
            SizeKb(50.0, 300.0, 200.0),
            Dimensions(DimensionMode.NONE),
        ),
        unverified = false,
        exam = true,
    )

private val pictures =
    listOf(
        PdfItem(1, document = false, pages = 1, kb = 2400, thumbnail = previewPage),
        PdfItem(2, document = false, pages = 1, kb = 1900, thumbnail = previewPage, crop = CropRect(10, 10, 80, 120)),
        PdfItem(3, document = true, pages = 3, kb = 812, thumbnail = previewPage),
    )

/** Empty tool, an exam slot with files, making, the result after a save, a failure, an ID card, and the crop. */
private class PdfPreviewStates : PreviewParameterProvider<PdfMakerUiState> {
    private val exam = PdfMakerUiState(PdfTool.EXAM, target = examTarget, items = pictures)

    override val values =
        sequenceOf(
            PdfMakerUiState(PdfTool.IMAGES, target = PdfMakerViewModel.toolTarget(PdfTool.IMAGES, 200)),
            exam.copy(addFailure = AddFailure.ENCRYPTED),
            exam.copy(making = PdfProgress.Fitting(1, 5)),
            exam.copy(
                result =
                PdfResult.Ready(ByteArray(0), 182, 5, 150, PdfStrategy.LOWER_DPI, previewPage, meetsRules = true),
                save = SaveResult(SaveState.SAVED),
            ),
            PdfMakerUiState(
                PdfTool.COMPRESS,
                target = PdfMakerViewModel.toolTarget(PdfTool.COMPRESS, 100),
                maxKbText = "100",
                items = listOf(pictures[2]),
                result = PdfResult.Failed(PdfError.CANNOT_REACH),
            ),
            PdfMakerUiState(
                PdfTool.ID_CARD,
                target = PdfMakerViewModel.toolTarget(PdfTool.ID_CARD, 200),
                items = pictures.take(1),
            ),
            exam.copy(crop = PdfCrop(1, previewPage, CropRect(10, 10, 90, 140))),
        )
}

@Preview(name = "PDF maker", showBackground = true)
@Preview(name = "PDF maker dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "PDF maker large font", showBackground = true, fontScale = 2f)
@Preview(name = "PDF maker Hindi", showBackground = true, locale = "hi")
@Composable
private fun PdfMakerPreview(
    @PreviewParameter(PdfPreviewStates::class) state: PdfMakerUiState,
) {
    ScanFitTheme { PdfMakerScreen(state, PdfActions(onTakePhoto = {})) }
}
