package app.scanfit.feature.kit

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import app.scanfit.core.data.kit.ExportRecord
import app.scanfit.core.data.kit.HistoryEntry
import app.scanfit.core.data.kit.KitOriginal
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.imaging.Raster
import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.CustomSize
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.Dimensions
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import app.scanfit.core.model.ExamCategory
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.SizeKb

// Previews of every state in light, dark, the largest font and Hindi (CLAUDE.md Definition of done).

private const val DAY = 1_792_000_000_000L

private val previewPrepared = KitUiState.Ready(
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

private val previewOriginals = KitOriginalsUiState.Ready(
    listOf(
        KitOriginalGroup(
            DocKind.PHOTO,
            listOf(KitOriginal("00000000-0000-4000-8000-000000000001", DocKind.PHOTO, DAY, 1800, 2400, 610_000)),
        ),
        KitOriginalGroup(
            DocKind.SIGNATURE,
            listOf(KitOriginal("00000000-0000-4000-8000-000000000002", DocKind.SIGNATURE, DAY, 2400, 1080, 380_000)),
        ),
    ),
)

private const val HOUR = 3_600_000L
private const val KB = 1024

private fun record(id: Int, exam: String, name: String, type: DocType, kb: Int, w: Int, h: Int) = ExportRecord(
    "00000000-0000-4000-8000-%012d".format(id), DAY - id * HOUR, exam, name, type, DocKind.of(type),
    "file.jpg", kb * KB, w, h,
)

private val pdfRecord =
    ExportRecord(
        "00000000-0000-4000-8000-000000000009", DAY, "jee_main", "JEE Main", DocType.CLASS10_CERTIFICATE,
        DocKind.PDF_DOCUMENT, "class10_certificate_JEE-Main_182kb.pdf", 182 * KB, 0, 0, pages = 3,
    )

private val previewHistory = KitHistoryUiState.Ready(
    listOf(
        HistoryEntry(record(1, "ibps_po", "IBPS PO / MT", DocType.PHOTO, 34, 200, 230), shareable = true),
        HistoryEntry(record(2, CustomSize.EXAM_ID, CustomSize.EXAM_NAME, DocType.SIGNATURE, 16, 140, 60), true),
        HistoryEntry(record(3, "ssc_cgl", "SSC CGL", DocType.SIGNATURE, 18, 140, 60), shareable = false),
    ),
    shareFailed = true,
)

private val gradient: Raster by lazy {
    Raster.of(160, 120) { x, y -> ((x * 255 / 160) shl 16) or ((y * 255 / 120) shl 8) or 0x80 }
}

internal class KitPreviewStates : PreviewParameterProvider<Triple<KitTab, Any, String>> {
    override val values = sequenceOf(
        Triple(KitTab.PREPARED, KitUiState.Loading, "loading"),
        Triple(KitTab.PREPARED, KitUiState.Empty, "prepared empty"),
        Triple(KitTab.PREPARED, KitUiState.Error, "prepared error"),
        Triple(KitTab.PREPARED, previewPrepared, "prepared"),
        Triple(KitTab.ORIGINALS, KitOriginalsUiState.Empty, "originals empty"),
        Triple(KitTab.ORIGINALS, KitOriginalsUiState.Error, "originals error"),
        Triple(KitTab.ORIGINALS, previewOriginals, "originals"),
        Triple(KitTab.HISTORY, KitHistoryUiState.Empty, "history empty"),
        Triple(KitTab.HISTORY, KitHistoryUiState.Error, "history error"),
        Triple(KitTab.HISTORY, previewHistory, "history"),
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, fontScale = 2f)
@Preview(showBackground = true, locale = "hi")
@Preview(showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun KitPreview(
    @PreviewParameter(KitPreviewStates::class) state: Triple<KitTab, Any, String>,
) {
    val (tab, shown) = state
    ScanFitTheme {
        KitScreen(
            tab,
            shown as? KitUiState ?: previewPrepared,
            shown as? KitOriginalsUiState ?: previewOriginals,
            shown as? KitHistoryUiState ?: previewHistory,
            KitActions(
                thumbnail = { gradient },
                preparedThumbnail = { _, _ -> gradient },
                historyThumbnail = { gradient },
            ),
        )
    }
}

internal class KitOriginalPreviewStates : PreviewParameterProvider<KitOriginalUiState> {
    override val values = sequenceOf(
        KitOriginalUiState.Loading,
        KitOriginalUiState.Missing,
        KitOriginalUiState.Ready(
            KitOriginal("00000000-0000-4000-8000-000000000002", DocKind.SIGNATURE, DAY, 2400, 1080, 380_000),
            gradient,
            targets = listOf(
                CheckerTarget(previewExam("ibps_po", "IBPS PO / MT", Confidence.HIGH), previewSlot()),
                CheckerTarget(previewExam("upsc_ese", "UPSC Engineering Services", Confidence.LOW), previewSlot()),
            ),
        ),
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, fontScale = 2f)
@Preview(showBackground = true, locale = "hi")
@Preview(showBackground = true, locale = "hi", fontScale = 2f)
@Composable
private fun KitOriginalPreview(
    @PreviewParameter(KitOriginalPreviewStates::class) state: KitOriginalUiState,
) {
    ScanFitTheme { KitOriginalScreen(state, KitOriginalActions()) }
}

private fun previewSlot() = DocSpec(
    DocType.SIGNATURE,
    true,
    listOf(FileFormat.JPG),
    SizeKb(min = 10.0, max = 20.0),
    Dimensions(DimensionMode.EXACT, width = 140, height = 60),
)

private fun previewExam(id: String, name: String, confidence: Confidence) = Exam(
    id = id,
    name = name,
    body = "Preview",
    category = ExamCategory.BANKING,
    status = ExamStatus.ACTIVE,
    documents = listOf(previewSlot()),
    specialRules = emptyList(),
    sources = emptyList(),
    confidence = confidence,
    confidenceNote = "",
    lastVerified = "2026-10-01",
)

/** A kept photo, a three-page PDF and a copy that is gone. */
private class KitHistoryPreviewStates : PreviewParameterProvider<KitHistoryPreviewUiState> {
    override val values =
        sequenceOf(
            KitHistoryPreviewUiState.Ready(
                record(1, "ibps_po", "IBPS PO / MT", DocType.PHOTO, 34, 200, 230),
                gradient,
                1,
            ),
            KitHistoryPreviewUiState.Ready(pdfRecord, null, 3),
            KitHistoryPreviewUiState.Failed,
        )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, fontScale = 2f)
@Preview(showBackground = true, locale = "hi")
@Composable
private fun KitHistoryPreviewPreview(
    @PreviewParameter(KitHistoryPreviewStates::class) state: KitHistoryPreviewUiState,
) {
    ScanFitTheme { KitHistoryPreviewScreen(state, {}, {}, { _, w -> Raster.white(w, (w * 1.41).toInt()) }) }
}
