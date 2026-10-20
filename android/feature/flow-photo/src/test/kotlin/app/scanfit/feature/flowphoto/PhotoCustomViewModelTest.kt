package app.scanfit.feature.flowphoto

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.model.CustomSize
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeFaceDetector
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakePersonSegmenter
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeShareStager
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestSaveGate
import app.scanfit.core.vision.FaceBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The photo flow for a Custom size (ALGORITHMS 11): the same face-aware pipeline, no draft, no Saved status. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoCustomViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val share = FakeShareStager()
    private val tools = FakePhotoTools().apply { files[PHOTO] = byteArrayOf(1, 2, 3) }
    private val destinations = FakeExportDestinations()
    private val drafts = FakeDraftStore()
    private val prefs = FakeUserPreferences()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(text: String = SIZE) = PhotoFlowViewModel(
        SavedStateHandle(mapOf(PhotoFlowViewModel.CUSTOM to text)),
        FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
        tools,
        FakeFaceDetector(listOf(FaceBox(400.0, 500.0, 200.0, 240.0))),
        FakePersonSegmenter(),
        dispatcher,
        DocumentExporter(destinations, prefs, FakeKitStore(), TestSaveGate().gate),
        drafts,
        share,
        FakeKitStore(),
    )

    @Test
    fun aCustomPhotoIsCroppedToItsOwnAspect() {
        val slot = (model().uiState.value as PhotoUiState.PickSource).slot
        assertTrue(slot.custom)
        assertEquals(200.0 / 230.0, slot.aspect)
        assertEquals("a signature size is not a photo flow", PhotoUiState.NotFound, model("signature;140x60;10-20;200").uiState.value)
    }

    @Test
    fun savingKeepsNoDraftAndRecordsNoSavedStatus() = runTest(dispatcher) {
        val m = model()
        m.onImageSelected(PHOTO)
        m.onCropDone()
        advanceUntilIdle()
        val review = m.uiState.value as PhotoUiState.Review
        assertTrue((review.result as ReviewResult.Ready).meetsRules)
        assertEquals(0, drafts.writes)
        m.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, (m.uiState.value as PhotoUiState.Review).save.state)
        assertTrue(prefs.savedDocuments.first().isEmpty())
        assertEquals(CustomSize.EXAM_NAME, destinations.lastRequest?.examName)
        assertTrue(destinations.lastRequest?.fileName.orEmpty().startsWith("photo_Custom_200x230_"))
    }

    private companion object {
        const val SIZE = "photo;200x230;20-50;200"
        const val PHOTO = "content://picker/photo.jpg"
    }
}
