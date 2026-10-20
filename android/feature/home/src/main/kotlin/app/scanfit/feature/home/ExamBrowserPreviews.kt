package app.scanfit.feature.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.ExamCategory

// Previews: light, dark, 200% font and Hindi (UI_UX §4): the Home grid and every Exam forms list state.
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
private fun ExamBrowserSectionsPreview() {
    ScanFitTheme {
        ExamBrowserScreen(
            HomeUiState(PresetsStatus.Ready(55, 2), pinned = previewItems.take(1), popular = previewItems),
            {},
            {},
            {},
            HomeLinks(),
        )
    }
}

@Preview(name = "Results", showBackground = true)
@Composable
private fun ExamBrowserResultsPreview() {
    ScanFitTheme {
        ExamBrowserScreen(
            HomeUiState(PresetsStatus.Ready(55, 2), query = "po", results = previewItems),
            {},
            {},
            {},
            HomeLinks(),
        )
    }
}

@Preview(name = "No results", showBackground = true)
@Composable
private fun ExamBrowserNoResultsPreview() {
    ScanFitTheme { ExamBrowserScreen(HomeUiState(PresetsStatus.Ready(55, 2), query = "zzzz"), {}, {}, {}, HomeLinks()) }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun ExamBrowserLoadingPreview() {
    ScanFitTheme { ExamBrowserScreen(HomeUiState(), {}, {}, {}, HomeLinks()) }
}

@Preview(name = "Home", showBackground = true, heightDp = 1400)
@Preview(name = "Home dark", showBackground = true, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Home large font", showBackground = true, heightDp = 2600, fontScale = 2f)
@Preview(name = "Home Hindi", showBackground = true, heightDp = 1400, locale = "hi")
@Composable
private fun HomeGridPreview() {
    ScanFitTheme { HomeScreen(previewItems.take(2), {}, {}) }
}

@Preview(name = "Home, no pinned exams", showBackground = true, heightDp = 1400)
@Composable
private fun HomeNoPinnedPreview() {
    ScanFitTheme { HomeScreen(emptyList(), {}, {}) }
}
