package app.scanfit.feature.flowphoto

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.SavedDocument
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.imaging.FitError
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeFaceDetector
import app.scanfit.core.testing.FakePersonSegmenter
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.vision.FaceBox
import app.scanfit.core.vision.PersonSegmenter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoSaveViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tools = FakePhotoTools().apply { files["photo"] = byteArrayOf(1) }
    private val preferences = FakeUserPreferences()
    private val destinations = FakeExportDestinations()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(
        destination: FakeExportDestinations = destinations,
        segmenter: PersonSegmenter = FakePersonSegmenter(),
    ): PhotoFlowViewModel {
        val model = PhotoFlowViewModel(
            SavedStateHandle(mapOf("examId" to "ibps_po", "docType" to "photo")),
            FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
            tools,
            FakeFaceDetector(listOf(FaceBox(400.0, 500.0, 200.0, 240.0))),
            segmenter,
            dispatcher,
            DocumentExporter(destination, preferences),
            FakeDraftStore(),
        )
        model.onImageSelected("photo")
        model.onCropDone()
        advanceUntilIdle()
        return model
    }

    private val PhotoFlowViewModel.review get() = uiState.value as PhotoUiState.Review

    @Test
    fun duplicateSaveAndEditsAreRejectedWhileSaving() = runTest(dispatcher) {
        destinations.gate = CompletableDeferred()
        val model = model()
        val before = model.review
        model.onSave()
        assertEquals(SaveState.SAVING, model.review.save.state)
        model.onSave()
        model.onWhiteBackground(true)
        model.onNameDate(true)
        model.onName("changed")
        model.onDate("01/01/2026")
        model.onImageSelected("photo")
        assertTrue(model.onBack())
        assertEquals(before.options, model.review.options)
        assertEquals(1, destinations.events.count { it == "create" })
        destinations.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, model.review.save.state)
        assertArrayEquals((before.result as ReviewResult.Ready).bytes, destinations.lastRequest?.bytes)
        assertEquals(setOf(SavedDocument("ibps_po", "PHOTO")), preferences.savedDocuments.value)
    }

    @Test
    fun failedSaveCanRetryAndAFailedLaterAttemptKeepsSavedChecklistStatus() = runTest(dispatcher) {
        destinations.operation = "write_failed"
        val model = model()
        model.onSave()
        assertEquals(SaveState.SAVE_FAILED, model.review.save.state)
        assertTrue(preferences.savedDocuments.value.isEmpty())
        destinations.operation = "success"
        model.onSave()
        assertEquals(SaveState.SAVED, model.review.save.state)
        destinations.operation = "read_failed"
        model.onSave()
        assertEquals(SaveState.SAVE_FAILED, model.review.save.state)
        assertEquals(setOf(SavedDocument("ibps_po", "PHOTO")), preferences.savedDocuments.value)
    }

    @Test
    fun verificationFailureRetriesAndFailedCleanupIsNotReportedAsDeletion() = runTest(dispatcher) {
        destinations.operation = "corrupt"
        destinations.deleteSucceeds = false
        val model = model()
        model.onSave()
        assertEquals(SaveState.VERIFY_FAILED, model.review.save.state)
        assertEquals(false, model.review.save.cleanupSucceeded)
        assertFalse("publish" in destinations.events)
        destinations.operation = "success"
        model.onSave()
        assertEquals(SaveState.SAVED, model.review.save.state)
    }

    @Test
    fun pickerCancellationIsSilentAndRetryUsesThePortalFilename() = runTest(dispatcher) {
        val destination = FakeExportDestinations(requiresPicker = true)
        val model = model(destination)
        assertEquals("photo_IBPS-PO_200x230_34kb.jpg", model.onSave())
        assertNull(model.onSave())
        model.onSaveDestination(null)
        assertEquals(SaveState.IDLE, model.review.save.state)
        assertTrue(destination.events.isEmpty())
        assertTrue(preferences.savedDocuments.value.isEmpty())
        model.onSave()
        model.onSaveDestination(null, launchFailed = true)
        assertEquals(SaveState.SAVE_FAILED, model.review.save.state)
        model.onSave()
        model.onSaveDestination("content://document/new")
        assertEquals(SaveState.SAVED, model.review.save.state)
        model.onSaveDestination(null)
        assertEquals(SaveState.SAVED, model.review.save.state)
    }

    @Test
    fun aDebouncedEditCannotSaveThePreviousReadyBytes() = runTest(dispatcher) {
        val model = model()
        val updated = FakePhotoTools.jpeg(200, 230, 36 * 1024)
        tools.fit = { FakePhotoTools.success(it, updated) }
        model.onName("Asha")
        assertTrue(model.review.result is ReviewResult.Ready)
        assertTrue(model.review.rendering)
        assertNull(model.onSave())
        assertTrue(destinations.events.isEmpty())
        advanceUntilIdle()
        assertFalse(model.review.rendering)
        model.onSave()
        assertArrayEquals(updated, destinations.lastRequest?.bytes)
    }

    @Test
    fun anOlderRenderCannotOverwriteTheNewestReviewOrSavedResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val segmenter = PersonSegmenter { raster ->
            withContext(NonCancellable) { gate.await() }
            ByteArray(raster.width * raster.height) { 0xff.toByte() }
        }
        val model = model(segmenter = segmenter)
        model.onWhiteBackground(true)
        assertTrue(model.review.rendering)
        model.onWhiteBackground(false)
        assertFalse(model.review.rendering)
        model.onSave()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.review.options.whiteBackground)
        assertEquals(SaveState.SAVED, model.review.save.state)
    }

    @Test
    fun failedOrNonmatchingReviewIsNotSaved() = runTest(dispatcher) {
        val model = model()
        tools.fit = { FakePhotoTools.failure(FitError.TOO_DETAILED) }
        model.onWhiteBackground(true)
        model.onSave()
        assertTrue(destinations.events.isEmpty())
        tools.fit = { FakePhotoTools.success(it, FakePhotoTools.jpeg(400, 230, 35 * 1024)) }
        model.onWhiteBackground(false)
        assertFalse((model.review.result as ReviewResult.Ready).meetsRules)
        model.onSave()
        assertTrue(destinations.events.isEmpty())
    }
}
