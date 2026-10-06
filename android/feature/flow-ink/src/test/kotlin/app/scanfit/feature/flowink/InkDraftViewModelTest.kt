package app.scanfit.feature.flowink

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.match.DocKind
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class InkDraftViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val drafts = FakeDraftStore()
    private val tools = FakeInkTools().apply { files["photo"] = byteArrayOf(1) }
    private val preferences = FakeUserPreferences()
    private val destinations = FakeExportDestinations()
    private val presets = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle)
    private val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first { it.type.name == "SIGNATURE" }
    private val bytes = TestJpeg.make(140, 60, 16 * 1024)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(destination: FakeExportDestinations = destinations) = InkFlowViewModel(
        SavedStateHandle(mapOf("examId" to "ibps_po", "docType" to "signature")),
        presets,
        tools,
        preferences,
        DocumentExporter(destination, preferences),
        dispatcher,
        drafts,
    )

    private val InkFlowViewModel.review get() = uiState.value as InkUiState.Review

    @Test
    fun renderRetainsSignatureBeforeConfirmationWithoutRecordingSaved() = runTest(dispatcher) {
        val model = model()
        model.onImageSelected("photo")
        model.onCropDone()
        advanceUntilIdle()
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.SIGNATURE)).bytes)
        assertFalse(model.review.canSave)
        assertFalse(model.review.restored)
        assertTrue(model.review.before != null)
        assertTrue(preferences.savedDocuments.value.isEmpty())
    }

    @Test
    fun restoredSignatureSkipsCleanupButRequiresConfirmationAndExportsIdenticalBytes() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.SIGNATURE, bytes)
        preferences.recordSaved("ibps_po", "SIGNATURE")
        val model = model()
        advanceUntilIdle()
        assertTrue(model.review.restored)
        assertNull(model.review.before)
        assertTrue(tools.fits.isEmpty())
        assertTrue((model.review.result as InkReviewResult.Ready).note.accepted > 0)
        assertEquals(SaveState.IDLE, model.review.save.state)
        model.onCrispBlack(true)
        model.onInkFactor(0.9)
        assertTrue(tools.fits.isEmpty())
        model.onSave()
        assertNull(destinations.lastRequest)
        model.onConfirmHandwriting()
        assertTrue(model.review.canSave)
        model.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, model.review.save.state)
        assertArrayEquals(bytes, destinations.lastRequest?.bytes)
    }

    @Test
    fun replaceAndRestoredBackKeepDraftAndNeverInventOriginalOptions() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.SIGNATURE, bytes)
        val model = model()
        advanceUntilIdle()
        model.onReplace()
        model.refreshDraft()
        advanceUntilIdle()
        assertTrue(model.uiState.value is InkUiState.PickSource)
        model.onPickCancelled()
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.SIGNATURE)).bytes)
        val reopened = model()
        advanceUntilIdle()
        assertTrue(reopened.onBack())
        assertTrue(reopened.uiState.value is InkUiState.PickSource)
    }

    @Test
    fun retentionFailureKeepsPreviousDraftAndNewReviewRemainsSaveable() = runTest(dispatcher) {
        val model = model()
        model.onImageSelected("photo")
        model.onCropDone()
        advanceUntilIdle()
        drafts.failWrites = true
        val newer = TestJpeg.make(140, 60, 18 * 1024)
        tools.fit = { tools.success(it, newer) }
        model.onCrispBlack(false)
        advanceUntilIdle()
        assertTrue(model.review.retainFailed)
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.SIGNATURE)).bytes)
        model.onConfirmHandwriting()
        model.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, model.review.save.state)
        assertArrayEquals(newer, destinations.lastRequest?.bytes)
    }

    @Test
    fun malformedAndWrongDpiInkIsNotRetainedOrSaveable() = runTest(dispatcher) {
        preferences.confirmHandwriting()
        for (invalid in listOf(byteArrayOf(1, 2), bytes.copyOf().apply { this[15] = 72 })) {
            val model = model()
            tools.fit = { tools.success(it, invalid) }
            model.onImageSelected("photo")
            model.onCropDone()
            advanceUntilIdle()
            assertFalse(model.review.canSave)
            model.onSave()
            assertNull(destinations.lastRequest)
        }
        assertEquals(0, drafts.writes)
    }

    @Test
    fun cancelledPendingRetentionDoesNotPersistObsoleteInk() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        drafts.beforeRetain = { gate.await() }
        val model = model()
        model.onImageSelected("photo")
        model.onCropDone()
        assertTrue(model.review.rendering)
        model.onBack()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, drafts.writes)
        assertTrue(model.uiState.value is InkUiState.Crop)
    }

    @Test
    fun pickerCancellationAndSaveFailureKeepRestoredDraft() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.SIGNATURE, bytes)
        preferences.confirmHandwriting()
        val destination = FakeExportDestinations(requiresPicker = true)
        val model = model(destination)
        advanceUntilIdle()
        model.onSave()
        model.refreshDraft()
        assertEquals(SaveState.SAVING, model.review.save.state)
        model.onSaveDestination(null)
        assertEquals(SaveState.IDLE, model.review.save.state)
        destination.operation = "write_failed"
        model.onSave()
        model.onSaveDestination("content://test/destination")
        advanceUntilIdle()
        assertEquals(SaveState.SAVE_FAILED, model.review.save.state)
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.SIGNATURE)).bytes)
    }

    @Test
    fun foregroundRevalidationRejectsTamperedRestoredDraft() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.SIGNATURE, bytes)
        val model = model()
        advanceUntilIdle()
        drafts.records["ibps_po" to spec.type] = app.scanfit.core.data.draft.RetainedDraft(byteArrayOf(1), drafts.now)
        model.refreshDraft()
        advanceUntilIdle()
        assertTrue(model.uiState.value is InkUiState.PickSource)
        assertTrue(drafts.records.isEmpty())
    }

    @Test
    fun aLateForegroundLoadCannotUndoReplace() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.SIGNATURE, bytes)
        val model = model()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        drafts.beforeRead = { gate.await() }
        model.refreshDraft()
        model.onReplace()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.uiState.value is InkUiState.PickSource)
        assertEquals(1, drafts.records.size)
    }

    @Test
    fun supersededRetentionCommitsOnlyTheNewerGeneration() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        drafts.beforeRetain = { gate.await() }
        val model = model()
        model.onImageSelected("photo")
        model.onCropDone()
        val newer = TestJpeg.make(140, 60, 18 * 1024)
        tools.fit = { tools.success(it, newer) }
        model.onCrispBlack(false)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, drafts.writes)
        assertArrayEquals(newer, requireNotNull(drafts.read("ibps_po", spec, DocKind.SIGNATURE)).bytes)
        assertArrayEquals(newer, (model.review.result as InkReviewResult.Ready).bytes)
    }
}
