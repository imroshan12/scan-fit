package app.scanfit.feature.kit

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.kit.ExportHistory
import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakeShareStager
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KitHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val kit = FakeKitStore()
    private val share = FakeShareStager()
    private val exam = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
    private val fitted = TestJpeg.make(200, 230, 35 * 1024)
    private val request = ExportRequest(exam.id, exam.name, exam.documents.first(), DocKind.PHOTO, fitted)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model() = KitHistoryViewModel(kit, share, dispatcher)

    private suspend fun ReceiveTurbine<KitHistoryUiState>.resolved(): KitHistoryUiState {
        var state = awaitItem()
        while (state == KitHistoryUiState.Loading) state = awaitItem()
        return state
    }

    @Test
    fun emptyThenAnExportAppears() = runTest(dispatcher) {
        model().uiState.test {
            assertEquals(KitHistoryUiState.Empty, resolved())
            kit.recordExport(request)
            val ready = resolved() as KitHistoryUiState.Ready
            assertEquals(request.fileName, ready.entries.single().record.fileName)
            assertTrue(ready.entries.single().shareable)
        }
    }

    @Test
    fun shareStagesTheExactCopyUnderItsRecordedName() = runTest(dispatcher) {
        kit.recordExport(request)
        val m = model()
        val id = (m.uiState.value as KitHistoryUiState.Ready).entries.single().record.id
        m.onShare(id)
        val ready = m.uiState.value as KitHistoryUiState.Ready
        assertEquals("content://test/share/${request.fileName}", ready.shareUri)
        assertEquals(request.fileName, share.staged?.first)
        assertArrayEquals(fitted, share.staged?.second)
        m.onShareHandled()
        assertNull((m.uiState.value as KitHistoryUiState.Ready).shareUri)
    }

    @Test
    fun aFailedStageSaysSo() = runTest(dispatcher) {
        kit.recordExport(request)
        share.fails = true
        val m = model()
        m.onShare((m.uiState.value as KitHistoryUiState.Ready).entries.single().record.id)
        assertTrue((m.uiState.value as KitHistoryUiState.Ready).shareFailed)
    }

    @Test
    fun anExpiredEntryCannotBeShared() = runTest(dispatcher) {
        kit.recordExport(request)
        kit.now += ExportHistory.SHARE_WINDOW_MILLIS
        val m = model()
        val entry = (m.uiState.value as KitHistoryUiState.Ready).entries.single()
        assertFalse(entry.shareable)
        m.onShare(entry.record.id)
        assertNull(share.staged)
    }

    @Test
    fun clearingEmptiesTheList() = runTest(dispatcher) {
        kit.recordExport(request)
        val m = model()
        m.onClear()
        assertEquals(KitHistoryUiState.Empty, m.uiState.value)
    }

    @Test
    fun aStorageFailureIsRetryable() = runTest(dispatcher) {
        kit.failReads = true
        val m = model()
        assertEquals(KitHistoryUiState.Error, m.uiState.value)
        kit.failReads = false
        m.refresh()
        assertEquals(KitHistoryUiState.Empty, m.uiState.value)
    }
}
