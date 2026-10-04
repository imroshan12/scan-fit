package app.scanfit.feature.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.ExamCategory

// Previews: light, dark, 200% font and Hindi (UI_UX §4), plus every list state.
private val previewItems =
    listOf(
        ExamListItem("ibps_po", "IBPS PO / MT", "IBPS", ExamCategory.BANKING, Confidence.HIGH),
        ExamListItem("sbi_po", "SBI PO", "SBI", ExamCategory.BANKING, Confidence.HIGH),
        ExamListItem("rrb_alp", "RRB ALP", "RRB", ExamCategory.RAILWAY, Confidence.LOW),
    )

@Preview(name = "Sections", showBackground = true)
@Preview(name = "Sections dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Sections large font", showBackground = true, fontScale = 2f)
@Preview(name = "Sections Hindi", showBackground = true, locale = "hi")
@Composable
private fun HomeSectionsPreview() {
    ScanFitTheme {
        HomeScreen(
            HomeUiState(PresetsStatus.Ready(55, 2), pinned = previewItems.take(1), popular = previewItems),
            {},
            {},
            {},
        )
    }
}

@Preview(name = "Results", showBackground = true)
@Composable
private fun HomeResultsPreview() {
    ScanFitTheme {
        HomeScreen(HomeUiState(PresetsStatus.Ready(55, 2), query = "po", results = previewItems), {}, {}, {})
    }
}

@Preview(name = "No results", showBackground = true)
@Composable
private fun HomeNoResultsPreview() {
    ScanFitTheme { HomeScreen(HomeUiState(PresetsStatus.Ready(55, 2), query = "zzzz"), {}, {}, {}) }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun HomeLoadingPreview() {
    ScanFitTheme { HomeScreen(HomeUiState(), {}, {}, {}) }
}
