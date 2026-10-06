package app.scanfit.feature.flowphoto

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.SavedDocument
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeFaceDetector
import app.scanfit.core.testing.FakePersonSegmenter
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import app.scanfit.core.vision.FaceBox
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
class PhotoDraftViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val drafts = FakeDraftStore()
    private val tools = FakePhotoTools().apply { files["photo"] = byteArrayOf(1) }
    private val faces = FakeFaceDetector(listOf(FaceBox(400.0, 500.0, 200.0, 240.0)))
    private val segmenter = FakePersonSegmenter()
    private val preferences = FakeUserPreferences()
    private val destinations = FakeExportDestinations()
    private val presets = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle)
    private val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first()
    private val bytes = TestJpeg.make(200, 230, 34 * 1024)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(destination: FakeExportDestinations = destinations) = PhotoFlowViewModel(
        SavedStateHandle(mapOf("examId" to "ibps_po", "docType" to "photo")),
        presets,
        tools,
        faces,
        segmenter,
        dispatcher,
        DocumentExporter(destination, preferences),
        drafts,
    )

    private val PhotoFlowViewModel.review get() = uiState.value as PhotoUiState.Review

    @Test
    fun renderRetainsFinalBytesWithoutMarkingSavedAndKeepsBeforeCrop() = runTest(dispatcher) {
        val model = model()
        model.onImageSelected("photo")
        model.onCropDone()
        advanceUntilIdle()
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.PHOTO)).bytes)
        assertTrue(preferences.savedDocuments.value.isEmpty())
        assertFalse(model.review.restored)
        assertFalse(model.review.retainFailed)
        assertTrue(model.review.before != null)
    }

    @Test
    fun relaunchRestoresReadOnlyChecksAndMatchNoteWithoutProcessingThenSavesExactBytes() = runTest(dispatcher) {
        assertTrue(drafts.retain("ibps_po", spec, DocKind.PHOTO, bytes))
        preferences.recordSaved("ibps_po", "PHOTO")
        val model = model()
        advanceUntilIdle()
        val review = model.review
        assertTrue(review.restored)
        assertNull(review.before)
        assertEquals(SaveState.IDLE, review.save.state)
        assertTrue((review.result as ReviewResult.Ready).note.accepted > 0)
        assertNull(tools.fitInput)
        assertEquals(0, faces.calls)
        model.onWhiteBackground(true)
        model.onNameDate(true)
        model.onName("never rendered")
        assertEquals(review, model.review)
        model.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, model.review.save.state)
        assertArrayEquals(bytes, destinations.lastRequest?.bytes)
        assertEquals(setOf(SavedDocument("ibps_po", "PHOTO")), preferences.savedDocuments.value)
    }

    @Test
    fun replaceAndRestoredBackKeepPreviousDraftAndForegroundDoesNotUndoReplacement() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.PHOTO, bytes)
        val model = model()
        advanceUntilIdle()
        model.onReplace()
        assertTrue(model.uiState.value is PhotoUiState.PickSource)
        model.refreshDraft()
        advanceUntilIdle()
        assertTrue(model.uiState.value is PhotoUiState.PickSource)
        model.onPickCancelled()
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.PHOTO)).bytes)
        val reopened = model()
        advanceUntilIdle()
        assertTrue(reopened.onBack())
        assertTrue(reopened.uiState.value is PhotoUiState.PickSource)
    }

    @Test
    fun failedRetentionLeavesReviewSaveableAndPreviousGoodDraftIntact() = runTest(dispatcher) {
        val model = model()
        model.onImageSelected("photo")
        model.onCropDone()
        advanceUntilIdle()
        drafts.failWrites = true
        val newer = TestJpeg.make(200, 230, 36 * 1024)
        tools.fit = { FakePhotoTools.success(it, newer) }
        model.onNameDate(true)
        advanceUntilIdle()
        assertTrue(model.review.retainFailed)
        assertTrue((model.review.result as ReviewResult.Ready).meetsRules)
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.PHOTO)).bytes)
        model.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, model.review.save.state)
        assertArrayEquals(newer, destinations.lastRequest?.bytes)
    }

    @Test
    fun malformedAndWrongDpiOutputsNeverRetainOrBecomeSaveable() = runTest(dispatcher) {
        for (invalid in listOf(byteArrayOf(1, 2), bytes.copyOf().apply { this[15] = 72 })) {
            val model = model()
            tools.fit = { FakePhotoTools.success(it, invalid) }
            model.onImageSelected("photo")
            model.onCropDone()
            advanceUntilIdle()
            assertFalse((model.review.result as ReviewResult.Ready).meetsRules)
            model.onSave()
            assertNull(destinations.lastRequest)
        }
        assertEquals(0, drafts.writes)
    }

    @Test
    fun cancelledPendingRetentionCannotStoreObsoleteRender() = runTest(dispatcher) {
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
        assertTrue(model.uiState.value is PhotoUiState.Crop)
    }

    @Test
    fun restoredForegroundAndTrustedPresetChangesInvalidateUnavailableBytes() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.PHOTO, bytes)
        val model = model()
        advanceUntilIdle()
        drafts.now += app.scanfit.core.data.draft.FileDraftStore.TTL_MILLIS
        model.refreshDraft()
        advanceUntilIdle()
        assertTrue(model.uiState.value is PhotoUiState.PickSource)
        drafts.retain("ibps_po", spec, DocKind.PHOTO, bytes)
        val reopened = model()
        advanceUntilIdle()
        val exam = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
        presets.bundle.value = SpecPresets.bundle.copy(
            exams = listOf(
                exam.copy(
                    documents = exam.documents.map { if (it.type == DocType.PHOTO) it.copy(dpi = 300) else it },
                ),
            ),
        )
        advanceUntilIdle()
        assertTrue(reopened.uiState.value is PhotoUiState.PickSource)
        assertNull(drafts.read("ibps_po", spec, DocKind.PHOTO))
    }

    @Test
    fun cancelledPickerAndFailedSaveDoNotDeleteRestoredDraft() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.PHOTO, bytes)
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
        assertArrayEquals(bytes, requireNotNull(drafts.read("ibps_po", spec, DocKind.PHOTO)).bytes)
    }

    @Test
    fun aLateForegroundLoadCannotUndoReplace() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.PHOTO, bytes)
        val model = model()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        drafts.beforeRead = { gate.await() }
        model.refreshDraft()
        model.onReplace()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.uiState.value is PhotoUiState.PickSource)
        assertEquals(1, drafts.records.size)
    }

    @Test
    fun supersededRetentionCommitsOnlyTheNewerGeneration() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        drafts.beforeRetain = { gate.await() }
        val model = model()
        model.onImageSelected("photo")
        model.onCropDone()
        val newer = TestJpeg.make(200, 230, 36 * 1024)
        tools.fit = { FakePhotoTools.success(it, newer) }
        model.onNameDate(true)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, drafts.writes)
        assertArrayEquals(newer, requireNotNull(drafts.read("ibps_po", spec, DocKind.PHOTO)).bytes)
        assertArrayEquals(newer, (model.review.result as ReviewResult.Ready).bytes)
    }
}
