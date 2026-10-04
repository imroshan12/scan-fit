package app.scanfit.feature.flowphoto

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.imaging.AutoFraming
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.Raster
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeFaceDetector
import app.scanfit.core.testing.FakePersonSegmenter
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.vision.FaceBox
import app.scanfit.core.vision.FaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoFlowViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tools = FakePhotoTools().apply { files[PHOTO] = byteArrayOf(1, 2, 3) }
    private val face = FaceBox(400.0, 500.0, 200.0, 240.0)
    private val faces = FakeFaceDetector(listOf(face))
    private val segmenter = FakePersonSegmenter()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(
        exam: String = "ibps_po",
        doc: String = "photo",
        detector: FaceDetector = faces,
    ) = PhotoFlowViewModel(
        SavedStateHandle(mapOf(PhotoFlowViewModel.EXAM_ID to exam, PhotoFlowViewModel.DOC_TYPE to doc)),
        FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
        tools,
        detector,
        segmenter,
        dispatcher,
        PhotoExporter(FakeExportDestinations(), FakeUserPreferences()),
    )

    private val PhotoFlowViewModel.state get() = uiState.value

    /** Picks the photo and continues past the crop, landing on Review. */
    private fun TestScope.reviewed(m: PhotoFlowViewModel = model()): PhotoUiState.Review {
        m.onImageSelected(PHOTO)
        m.onCropDone()
        advanceUntilIdle()
        return m.state as PhotoUiState.Review
    }

    @Test
    fun startsAtThePickerForTheExamsPhotoSlot() {
        val s = model().state as PhotoUiState.PickSource
        assertEquals("IBPS PO / MT", s.slot.examName)
        assertEquals(38, s.slot.targetKb)
        assertNull(s.problem)
    }

    @Test
    fun anUnknownExamOrSlotIsNotFound() {
        assertEquals(PhotoUiState.NotFound, model(exam = "no_such_exam").state)
        assertEquals(PhotoUiState.NotFound, model(doc = "postcard_photo").state)
    }

    @Test
    fun oneFaceIsFramedAutomaticallyAndTheCaptureIsDeleted() {
        val m = model()
        m.onImageSelected(PHOTO)
        val crop = m.state as PhotoUiState.Crop
        val expected = AutoFraming.frame(face, 1000, 1400, 200.0 / 230.0).crop
        assertEquals(expected, crop.rect)
        assertNull(crop.problem)
        assertEquals(1, tools.discarded)
    }

    @Test
    fun anUnreadableFileGoesBackToThePickerWithAMessage() {
        val m = model()
        m.onImageSelected("content://missing")
        assertEquals(SourceProblem.OPEN_FAILED, (m.state as PhotoUiState.PickSource).problem)
        tools.decoded = null
        m.onImageSelected(PHOTO)
        assertEquals(SourceProblem.OPEN_FAILED, (m.state as PhotoUiState.PickSource).problem)
    }

    @Test
    fun noFaceBlocksAndSeveralFacesAskForARecrop() = runTest(dispatcher) {
        faces.faces = emptyList()
        val m = model()
        m.onImageSelected(PHOTO)
        assertEquals(SourceProblem.NO_FACE, (m.state as PhotoUiState.PickSource).problem)

        faces.faces = listOf(face, FaceBox(700.0, 500.0, 150.0, 180.0))
        m.onImageSelected(PHOTO)
        val crop = m.state as PhotoUiState.Crop
        assertEquals(CropProblem.SEVERAL_FACES, crop.problem)
        m.onCropDone()
        assertEquals("still two faces in the crop", CropProblem.SEVERAL_FACES, (m.state as PhotoUiState.Crop).problem)
        faces.faces = listOf(face)
        m.onCropDone()
        advanceUntilIdle()
        assertTrue(m.state is PhotoUiState.Review)
    }

    @Test
    fun aDetectorFailureOffersARetryInsteadOfCrashing() {
        val m = model(detector = FaceDetector { error("model failed to load") })
        m.onImageSelected(PHOTO)
        assertEquals(SourceProblem.FAILED, (m.state as PhotoUiState.PickSource).problem)
    }

    @Test
    fun moveAndZoomStayAspectLockedAndInsideTheImage() {
        val m = model()
        m.onImageSelected(PHOTO)
        val before = (m.state as PhotoUiState.Crop).rect
        m.onMove(-5000.0, 0.0)
        assertEquals(0, (m.state as PhotoUiState.Crop).rect.x)
        m.onZoom(2.0)
        val zoomed = (m.state as PhotoUiState.Crop).rect
        assertTrue("zoom in = smaller crop", zoomed.h < before.h)
        assertEquals(200.0 / 230.0, zoomed.w.toDouble() / zoomed.h, 0.02)
    }

    @Test
    fun rotateTurnsTheImageAndReframes() {
        val m = model()
        m.onImageSelected(PHOTO)
        m.onRotate()
        val crop = m.state as PhotoUiState.Crop
        assertEquals(1400, crop.image.width)
        assertEquals(1000, crop.image.height)
        assertEquals("detected again", 2, faces.calls)
    }

    @Test
    fun resetGoesBackToTheAutomaticFraming() {
        val m = model()
        m.onImageSelected(PHOTO)
        val auto = (m.state as PhotoUiState.Crop).rect
        m.onMove(100.0, 100.0)
        m.onResetCrop()
        assertEquals(auto, (m.state as PhotoUiState.Crop).rect)
    }

    @Test
    fun reviewFitsExactlyTheCropAndChecksTheBytes() = runTest(dispatcher) {
        val review = reviewed()
        val ready = review.result as ReviewResult.Ready
        assertEquals(34, ready.kb)
        assertEquals(200, ready.width)
        assertEquals(230, ready.height)
        assertTrue(ready.meetsRules)
        val expected = AutoFraming.frame(face, 1000, 1400, 200.0 / 230.0).crop
        assertEquals(expected.w, tools.fitInput?.width)
        assertEquals(expected.h, tools.fitInput?.height)
        assertFalse("IBPS does not require a strip", review.options.nameDate)
    }

    @Test
    fun aFileOutsideTheWindowDoesNotMeetTheRules() = runTest(dispatcher) {
        tools.fit = { r -> FakePhotoTools.success(r, FakePhotoTools.jpeg(200, 230, 60 * 1024)) }
        assertFalse((reviewed().result as ReviewResult.Ready).meetsRules)
    }

    @Test
    fun aFitErrorIsShownNotThrown() = runTest(dispatcher) {
        tools.fit = { FakePhotoTools.failure(FitError.TOO_DETAILED) }
        assertEquals(ReviewResult.Failed(FitError.TOO_DETAILED), reviewed().result)
    }

    @Test
    fun anUnverifiedExamIsFlaggedForTheLikelyOkWording() {
        assertTrue((model(exam = "niacl_ao").state as PhotoUiState.PickSource).slot.unverified)
        assertFalse((model().state as PhotoUiState.PickSource).slot.unverified)
    }

    @Test
    fun whiteBackgroundWhitensOnlyThePersonsSurroundings() = runTest(dispatcher) {
        val m = model()
        reviewed(m)
        val crop = AutoFraming.frame(face, 1000, 1400, 200.0 / 230.0).crop
        // person = left half of the crop
        segmenter.mask = ByteArray(crop.w * crop.h) { if (it % crop.w < crop.w / 2) -1 else 0 }
        m.onWhiteBackground(true)
        advanceUntilIdle()
        val input = checkNotNull(tools.fitInput)
        assertEquals("far background is white", 255, input.g(crop.w - 2, crop.h / 2))
        val review = m.state as PhotoUiState.Review
        assertTrue(review.options.whiteBackground)
    }

    @Test
    fun whiteBackgroundTurnsItselfOffWhenTheModelIsUnavailable() = runTest(dispatcher) {
        val m = model()
        reviewed(m)
        segmenter.mask = null
        m.onWhiteBackground(true)
        advanceUntilIdle()
        val options = (m.state as PhotoUiState.Review).options
        assertFalse(options.whiteBackground)
        assertFalse(options.whiteBackgroundAvailable)
    }

    @Test
    fun theStripIsOffByDefaultEvenWhenThePresetAsksForIt() = runTest(dispatcher) {
        val m = model(exam = "upsc_cse")
        val review = reviewed(m)
        assertTrue("the preset asks for it (the screen shows a hint)", review.slot.spec.nameDateStrip?.required == true)
        assertFalse(review.options.nameDate)
        assertTrue("nothing drawn", tools.strips.isEmpty())
        assertEquals("today's date is ready", "03/10/2026", review.options.date)
        m.onNameDate(true)
        advanceUntilIdle()
        m.onName("  asha rao ")
        assertEquals("the field shows what was typed at once", "  asha rao ", (m.state as PhotoUiState.Review).options.name)
        assertTrue("typing is debounced, not fitted per key", tools.strips.none { it.first == "asha rao" })
        advanceUntilIdle()
        assertEquals("asha rao" to "03/10/2026", tools.strips.last())
        m.onDate("")
        advanceUntilIdle()
        assertEquals("an empty date falls back to today", "03/10/2026", tools.strips.last().second)
    }

    @Test
    fun backStepsThroughTheFlowThenLeaves() = runTest(dispatcher) {
        val m = model()
        assertFalse("the picker is the first step", m.onBack())
        reviewed(m)
        m.onMove(-50.0, 0.0) // no effect on Review
        assertTrue(m.onBack())
        val crop = m.state as PhotoUiState.Crop
        assertEquals(AutoFraming.frame(face, 1000, 1400, 200.0 / 230.0).crop, crop.rect)
        assertTrue(m.onBack())
        assertTrue(m.state is PhotoUiState.PickSource)
        assertFalse(m.onBack())
    }

    @Test
    fun aFreeRatioSlotKeepsThePhotosShape() {
        val m = model(exam = "upsc_cse")
        tools.decoded = Raster.of(900, 1200) { _, _ -> 0x808080 }
        faces.faces = emptyList<FaceBox>() + FaceBox(300.0, 300.0, 300.0, 360.0)
        m.onImageSelected(PHOTO)
        val rect: CropRect = (m.state as PhotoUiState.Crop).rect
        assertEquals(900.0 / 1200.0, rect.w.toDouble() / rect.h, 0.01)
    }

    private companion object {
        const val PHOTO = "content://picker/photo.jpg"
    }
}
