package app.scanfit.feature.kit

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.DocType

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, locale = "hi")
@Preview(showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun KitLoadingPreview() {
    ScanFitTheme { KitScreen(KitUiState.Loading, {}, { _, _ -> }) }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, locale = "hi")
@Preview(showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun KitEmptyPreview() {
    ScanFitTheme { KitScreen(KitUiState.Empty, {}, { _, _ -> }) }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, locale = "hi")
@Preview(showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun KitErrorPreview() {
    ScanFitTheme { KitScreen(KitUiState.Error, {}, { _, _ -> }) }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, locale = "hi")
@Preview(showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun KitPopulatedPreview() {
    val state = KitUiState.Ready(
        listOf(
            KitExam(
                "ibps_po",
                "IBPS PO / MT",
                Confidence.HIGH,
                listOf(KitDocument(DocType.PHOTO, 35), KitDocument(DocType.SIGNATURE, 16)),
            ),
            KitExam(
                "upsc_ese",
                "UPSC Engineering Services",
                Confidence.LOW,
                listOf(KitDocument(DocType.TRIPLE_SIGNATURE, 40)),
            ),
        ),
    )
    ScanFitTheme { KitScreen(state, {}, { _, _ -> }) }
}
