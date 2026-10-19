package app.scanfit.feature.exams

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.SavedDocument
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Save all on the checklist (ALGORITHMS 1.6.2): every Ready row through the full verified export, failures isolated. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExamSaveAllViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val drafts = FakeDraftStore()
    private val presets = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle)
    private val exam = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
    private val photo = exam.documents.first { it.type == DocType.PHOTO }
    private val signature = exam.documents.first { it.type == DocType.SIGNATURE }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(
        prefs: FakeUserPreferences,
        destinations: FakeExportDestinations = FakeExportDestinations(),
    ): ExamViewModel {
        val m = ExamViewModel(
            SavedStateHandle(mapOf(ExamViewModel.EXAM_ID to exam.id)),
            presets,
            prefs,
            drafts,
            DocumentExporter(destinations, prefs, FakeKitStore()),
            dispatcher,
        )
        backgroundScope.launch(dispatcher) { m.uiState.collect {} }
        return m
    }

    private val ExamViewModel.ready get() = uiState.value as ExamUiState.Ready

    private suspend fun retainBoth() {
        assertTrue(drafts.retain(exam.id, photo, DocKind.PHOTO, TestJpeg.make(200, 230, 35 * 1024)))
        assertTrue(drafts.retain(exam.id, signature, DocKind.SIGNATURE, TestJpeg.make(140, 60, 16 * 1024)))
    }

    @Test
    fun savesEveryReadyRowAndRecordsSaved() = runTest(dispatcher) {
        retainBoth()
        val prefs = FakeUserPreferences(handwritingConfirmed = true)
        val destinations = FakeExportDestinations()
        val m = model(prefs, destinations)
        assertEquals(listOf(DocType.PHOTO, DocType.SIGNATURE), m.ready.saveAllPlan.included)
        m.onSaveAll()
        advanceUntilIdle()
        assertEquals(SaveAllStatus.Done(2, 2, "Downloads/ScanFit/IBPS-PO"), m.ready.saveAll)
        assertEquals(
            setOf(SavedDocument(exam.id, "PHOTO"), SavedDocument(exam.id, "SIGNATURE")),
            prefs.savedDocuments.first(),
        )
        assertEquals("the exact retained bytes are written", 2, destinations.requests.size)
        assertTrue("saved rows leave the plan", m.ready.saveAllPlan.included.isEmpty())
    }

    @Test
    fun aFailureDoesNotStopTheOthersAndRetrySavesOnlyTheRest() = runTest(dispatcher) {
        retainBoth()
        val prefs = FakeUserPreferences(handwritingConfirmed = true)
        val destinations = FakeExportDestinations().apply { failingTypes = setOf(DocType.PHOTO) }
        val m = model(prefs, destinations)
        m.onSaveAll()
        advanceUntilIdle()
        val done = m.ready.saveAll as SaveAllStatus.Done
        assertEquals(1, done.saved)
        assertFalse(done.complete)
        assertEquals(setOf(SavedDocument(exam.id, "SIGNATURE")), prefs.savedDocuments.first())
        destinations.failingTypes = emptySet()
        destinations.requests.clear()
        m.onSaveAll()
        advanceUntilIdle()
        assertEquals(SaveAllStatus.Done(1, 1, "Downloads/ScanFit/IBPS-PO"), m.ready.saveAll)
        assertEquals("only the failed row is written again", listOf(DocType.PHOTO), destinations.requests.map { it.spec.type })
    }

    @Test
    fun anUnconfirmedSignatureIsHeld() = runTest(dispatcher) {
        retainBoth()
        val m = model(FakeUserPreferences(handwritingConfirmed = false))
        assertEquals(listOf(DocType.PHOTO), m.ready.saveAllPlan.included)
        assertEquals(listOf(DocType.SIGNATURE), m.ready.saveAllPlan.held)
    }

    @Test
    fun aDraftThatExpiredSinceLoadingFailsOnlyItsRow() = runTest(dispatcher) {
        retainBoth()
        val prefs = FakeUserPreferences(handwritingConfirmed = true)
        val m = model(prefs)
        drafts.records.remove(exam.id to DocType.PHOTO)
        m.onSaveAll()
        advanceUntilIdle()
        assertEquals(1, (m.ready.saveAll as SaveAllStatus.Done).saved)
    }

    @Test
    fun android8To9SavesRowByRow() = runTest(dispatcher) {
        retainBoth()
        val prefs = FakeUserPreferences(handwritingConfirmed = true)
        val m = model(prefs, FakeExportDestinations(requiresPicker = true))
        assertFalse(m.ready.canSaveAll)
        m.onSaveAll()
        advanceUntilIdle()
        assertEquals(SaveAllStatus.Idle, m.ready.saveAll)
        assertTrue(prefs.savedDocuments.first().isEmpty())
    }

    @Test
    fun theResultIsDismissed() = runTest(dispatcher) {
        retainBoth()
        val m = model(FakeUserPreferences(handwritingConfirmed = true))
        m.onSaveAll()
        advanceUntilIdle()
        m.onSaveAllDismissed()
        assertEquals(SaveAllStatus.Idle, m.ready.saveAll)
    }
}
