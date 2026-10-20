package app.scanfit.feature.flowphoto

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.kit.KitSave
import app.scanfit.core.data.kit.KitStore
import app.scanfit.core.data.kit.SaveOriginalResult
import app.scanfit.core.match.DocKind
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
import app.scanfit.core.testing.TestJpeg
import app.scanfit.core.testing.TestSaveGate
import app.scanfit.core.vision.FaceBox
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Save to My Kit on the photo review and "Use for exam…" from an original (ALGORITHMS 13.1, 13.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoKitViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tools = FakePhotoTools().apply { files["photo"] = byteArrayOf(1) }
    private val faces = FakeFaceDetector(listOf(FaceBox(400.0, 500.0, 200.0, 240.0)))
    private val drafts = FakeDraftStore()
    private val kit = FakeKitStore()
    private val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(original: String? = null) = PhotoFlowViewModel(
        SavedStateHandle(
            buildMap {
                put(PhotoFlowViewModel.EXAM_ID, "ibps_po")
                put(PhotoFlowViewModel.DOC_TYPE, "photo")
                original?.let { put(PhotoFlowViewModel.ORIGINAL, it) }
            },
        ),
        FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
        tools,
        faces,
        FakePersonSegmenter(),
        dispatcher,
        DocumentExporter(FakeExportDestinations(), FakeUserPreferences(), kit, TestSaveGate().gate),
        drafts,
        FakeShareStager(),
        kit,
    )

    private val PhotoFlowViewModel.review get() = uiState.value as PhotoUiState.Review

    private fun TestScope.reviewed(m: PhotoFlowViewModel = model()): PhotoFlowViewModel {
        advanceUntilIdle()
        m.onImageSelected("photo")
        m.onCropDone()
        advanceUntilIdle()
        return m
    }

    @Test
    fun savesTheUprightWorkingPictureNotTheFittedFile() = runTest(dispatcher) {
        val m = reviewed()
        val before = m.review.result
        assertEquals(KitSave.IDLE, m.review.kit)
        m.onSaveOriginal()
        advanceUntilIdle()
        assertEquals(KitSave.SAVED, m.review.kit)
        assertSame(tools.decoded, tools.originalInput)
        assertEquals(DocKind.PHOTO, kit.originals.single().kind)
        assertSame(before, m.review.result)
        m.onSaveOriginal()
        advanceUntilIdle()
        assertEquals(1, kit.originals.size)
    }

    @Test
    fun thePreviewIsTheWorkingPictureAtDisplaySizeAndSavesNothing() = runTest(dispatcher) {
        val m = reviewed()
        val preview = requireNotNull(m.originalPreview())
        val source = requireNotNull(tools.decoded)
        assertTrue(maxOf(preview.width, preview.height) <= 720)
        assertEquals(source.width.toDouble() / source.height, preview.width.toDouble() / preview.height, 0.02)
        assertTrue("a preview writes nothing", kit.originals.isEmpty())
        assertEquals(KitSave.IDLE, m.review.kit)
    }

    @Test
    fun aFullKitAndAFailureAreShownAndCanBeRetried() = runTest(dispatcher) {
        repeat(KitStore.MAX_ORIGINALS) { kit.saveOriginal(DocKind.SIGNATURE, TestJpeg.make(10, 10, 4096)) }
        val m = reviewed()
        m.onSaveOriginal()
        advanceUntilIdle()
        assertEquals(KitSave.FULL, m.review.kit)
        kit.originals.clear()
        tools.failOriginal = true
        m.onSaveOriginal()
        advanceUntilIdle()
        assertEquals(KitSave.FAILED, m.review.kit)
        tools.failOriginal = false
        m.onSaveOriginal()
        advanceUntilIdle()
        assertEquals(KitSave.SAVED, m.review.kit)
    }

    @Test
    fun theStateSurvivesReRendersAndResetsForANewPicture() = runTest(dispatcher) {
        val m = reviewed()
        m.onSaveOriginal()
        advanceUntilIdle()
        m.onNameDate(true)
        advanceUntilIdle()
        assertEquals(KitSave.SAVED, m.review.kit)
        m.onBack()
        m.onBack()
        reviewed(m)
        assertEquals(KitSave.IDLE, m.review.kit)
    }

    @Test
    fun aRestoredReviewHasNoPictureToKeep() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.PHOTO, TestJpeg.make(200, 230, 34 * 1024))
        val m = model()
        advanceUntilIdle()
        assertTrue(m.review.restored)
        m.onSaveOriginal()
        advanceUntilIdle()
        assertTrue(kit.originals.isEmpty())
    }

    @Test
    fun anOriginalOpensStraightAtTheCropAndSkipsTheDraft() = runTest(dispatcher) {
        drafts.retain("ibps_po", spec, DocKind.PHOTO, TestJpeg.make(200, 230, 34 * 1024))
        val saved = kit.saveOriginal(DocKind.PHOTO, TestJpeg.make(1000, 1400, 4096)) as SaveOriginalResult.Saved
        val m = model(original = saved.id)
        advanceUntilIdle()
        assertTrue(m.uiState.value is PhotoUiState.Crop)
        assertEquals(1, faces.calls)
        m.onCropDone()
        advanceUntilIdle()
        assertEquals(KitSave.SAVED, m.review.kit)
        assertTrue(!m.review.restored)
        // Back from the crop goes to the source step, where another picture may be picked.
        m.onBack()
        m.onBack()
        assertTrue(m.uiState.value is PhotoUiState.PickSource)
    }

    @Test
    fun aMissingOriginalSaysSoOnTheSourceStep() = runTest(dispatcher) {
        val m = model(original = kit.newId())
        advanceUntilIdle()
        assertEquals(SourceProblem.OPEN_FAILED, (m.uiState.value as PhotoUiState.PickSource).problem)
    }

    @Test
    fun aMalformedOriginalArgumentIsIgnored() = runTest(dispatcher) {
        val m = model(original = "../index")
        advanceUntilIdle()
        assertEquals(null, (m.uiState.value as PhotoUiState.PickSource).problem)
    }
}
