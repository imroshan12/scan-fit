package app.scanfit.feature.kit

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.PdfExport
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeShareStager
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import app.scanfit.core.testing.TestPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** My Kit previews (ALGORITHMS 13.3 previews): row thumbnails and a History file's preview before sharing it. */
@OptIn(ExperimentalCoroutinesApi::class)
class KitPreviewsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val kit = FakeKitStore()
    private val share = FakeShareStager()
    private val images = FakeKitImages(pdfPageCount = 3)
    private val bank = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
    private val jee = SpecPresets.bundle.exams.first { it.id == "jee_main" }
    private val photo = bank.documents.first { it.type == DocType.PHOTO }
    private val certificate = jee.documents.first { it.type == DocType.CLASS10_CERTIFICATE }
    private val fitted = TestJpeg.make(200, 230, 35 * 1024)
    private val pdf = TestPdf.make(3, 120 * 1024)
    private val jpegExport = ExportRequest(bank.id, bank.name, photo, DocKind.PHOTO, fitted)
    private val pdfExport =
        ExportRequest(jee.id, jee.name, certificate, DocKind.PDF_DOCUMENT, pdf, pdf = PdfExport(3, written = true))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun preview(id: String) = KitHistoryPreviewViewModel(SavedStateHandle(mapOf(KitHistoryPreviewViewModel.EXPORT_ID to id)), kit, share, images, dispatcher)

    @Test
    fun aPreparedRowShowsItsDraftAndAMissingDraftShowsNothing() = runTest(dispatcher) {
        val drafts = FakeDraftStore()
        val presets = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(2, 2)), SpecPresets.bundle)
        val m = KitViewModel(presets, drafts, dispatcher, images)
        assertNull(m.thumbnail(bank.id, DocType.PHOTO))
        drafts.retain(bank.id, photo, DocKind.PHOTO, fitted)
        assertNotNull(m.thumbnail(bank.id, DocType.PHOTO))
        assertEquals(KitViewModel.THUMBNAIL_PX, images.caps.last())
        assertNull("an unknown exam", m.thumbnail("nope", DocType.PHOTO))
    }

    @Test
    fun historyThumbnailsAreTheImageOrAPdfsFirstPage() = runTest(dispatcher) {
        kit.recordExport(jpegExport)
        kit.recordExport(pdfExport)
        val m = KitHistoryViewModel(kit, share, dispatcher, images)
        val entries = (m.uiState.value as KitHistoryUiState.Ready).entries
        val jpeg = entries.first { !it.record.isPdf }.record.id
        val pdfId = entries.first { it.record.isPdf }.record.id
        assertNotNull(m.thumbnail(jpeg))
        val page = requireNotNull(m.thumbnail(pdfId))
        assertEquals(KitViewModel.THUMBNAIL_PX, page.height)
        assertEquals("the PDF is closed after its thumbnail", images.opened, images.closed)
        kit.now += 31L * 24 * 3600 * 1000
        m.refresh()
        assertNull("no thumbnail once the copy is no longer kept", m.thumbnail(jpeg))
    }

    @Test
    fun anImagePreviewShowsTheFileAndSharesItsExactBytes() = runTest(dispatcher) {
        kit.recordExport(jpegExport)
        val id = kit.history().single().record.id
        val m = preview(id)
        val ready = m.uiState.value as KitHistoryPreviewUiState.Ready
        assertNotNull(ready.image)
        assertEquals(1, ready.pages)
        m.onShare()
        assertEquals(jpegExport.fileName, share.staged?.first)
        assertArrayEquals(fitted, share.staged?.second)
        assertEquals("content://test/share/${jpegExport.fileName}", (m.uiState.value as KitHistoryPreviewUiState.Ready).shareUri)
        m.onShareHandled()
        assertNull((m.uiState.value as KitHistoryPreviewUiState.Ready).shareUri)
    }

    @Test
    fun aPdfPreviewRendersItsPagesOnDemand() = runTest(dispatcher) {
        kit.recordExport(pdfExport)
        val m = preview(kit.history().single().record.id)
        val ready = m.uiState.value as KitHistoryPreviewUiState.Ready
        assertNull(ready.image)
        assertEquals(3, ready.pages)
        val page = requireNotNull(m.page(1, 600))
        assertEquals(600, page.width)
        assertEquals((600 * 842.0 / 595).toInt(), page.height)
        assertNull("past the last page", m.page(3, 600))
    }

    @Test
    fun aMissingOrExpiredCopyCannotBePreviewed() = runTest(dispatcher) {
        assertEquals(KitHistoryPreviewUiState.Failed, preview("not-an-id").uiState.value)
        kit.recordExport(jpegExport)
        val id = kit.history().single().record.id
        kit.now += 31L * 24 * 3600 * 1000
        assertEquals(KitHistoryPreviewUiState.Failed, preview(id).uiState.value)
    }
}
