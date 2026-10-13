package app.scanfit.feature.custom

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.model.CustomSize
import app.scanfit.core.model.CustomSizeError
import app.scanfit.core.model.DocType
import app.scanfit.core.model.SizeUnit

/** Empty first visit, a filled form in centimetres with recent sizes, and Continue tried with errors. */
private class CustomPreviewStates : PreviewParameterProvider<CustomUiState> {
    private val recent =
        listOfNotNull(CustomSize.parse("signature;140x60;10-20;200"), CustomSize.parse("photo;-;0-50;200"))

    override val values =
        sequenceOf(
            CustomUiState(errors = listOf(CustomSizeError.MAX_KB_MISSING)),
            CustomUiState(
                form = CustomForm(DocType.PHOTO, SizeUnit.CM, "3.5", "4.5", "20", "50", "200"),
                pixels = 276 to 354,
                recent = recent,
            ),
            CustomUiState(
                form = CustomForm(DocType.SIGNATURE, width = "140", minKb = "30", maxKb = "20"),
                errors = listOf(CustomSizeError.MIN_KB_RANGE, CustomSizeError.SIZE_PAIR),
                attempted = true,
            ),
        )
}

@Preview(name = "Custom", showBackground = true)
@Preview(name = "Custom dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Custom large font", showBackground = true, fontScale = 2f)
@Preview(name = "Custom Hindi", showBackground = true, locale = "hi")
@Composable
private fun CustomPreview(
    @PreviewParameter(CustomPreviewStates::class) state: CustomUiState,
) {
    ScanFitTheme { CustomScreen(state, CustomActions()) }
}
