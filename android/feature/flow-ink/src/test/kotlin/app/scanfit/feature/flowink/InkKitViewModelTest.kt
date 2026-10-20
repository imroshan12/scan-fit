package app.scanfit.feature.flowink

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.kit.KitSave
import app.scanfit.core.data.kit.SaveOriginalResult
import app.scanfit.core.match.DocKind
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeShareStager
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Save to My Kit on the ink review and "Use for exam…" from an original (ALGORITHMS 13.1, 13.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class InkKitViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tools = FakeInkTools().apply { files["picture"] = byteArrayOf(1) }
    private val drafts = FakeDraftStore()
    private val kit = FakeKitStore()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(doc: String = "signature", original: String? = null) = InkFlowViewModel(
        SavedStateHandle(
            buildMap {
                put(InkFlowViewModel.EXAM_ID, "ibps_po")
                put(InkFlowViewModel.DOC_TYPE, doc)
                original?.let { put(InkFlowViewModel.ORIGINAL, it) }
            },
        ),
        FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
        tools,
        FakeUserPreferences(),
        DocumentExporter(FakeExportDestinations(), FakeUserPreferences(), kit),
        dispatcher,
        drafts,
        FakeShareStager(),
        kit,
    )

    private val InkFlowViewModel.review get() = uiState.value as InkUiState.Review

    @Test
    fun savesTheWorkingPictureAsTheSlotsKind() = runTest(dispatcher) {
        val m = model(doc = "left_thumb")
        advanceUntilIdle()
        m.onImageSelected("picture")
        m.onCropDone()
        advanceUntilIdle()
        val before = m.review.result
        m.onSaveOriginal()
        advanceUntilIdle()
        assertEquals(KitSave.SAVED, m.review.kit)
        assertEquals(DocKind.THUMB, kit.originals.single().kind)
        assertSame(tools.decoded, tools.originalInput)
        assertSame(before, m.review.result)
    }

    @Test
    fun thePreviewIsTheWorkingPictureAndSavesNothing() = runTest(dispatcher) {
        val m = model()
        advanceUntilIdle()
        assertNull("nothing to preview before a picture", m.originalPreview())
        m.onImageSelected("picture")
        m.onCropDone()
        advanceUntilIdle()
        val preview = requireNotNull(m.originalPreview())
        assertTrue(maxOf(preview.width, preview.height) <= 720)
        assertTrue("a preview writes nothing", kit.originals.isEmpty())
    }

    @Test
    fun aFailedEncodingCanBeRetried() = runTest(dispatcher) {
        val m = model()
        advanceUntilIdle()
        m.onImageSelected("picture")
        m.onCropDone()
        advanceUntilIdle()
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
    fun anOriginalOpensAtTheCropAndCountsAsKept() = runTest(dispatcher) {
        val saved = kit.saveOriginal(DocKind.SIGNATURE, TestJpeg.make(800, 400, 4096)) as SaveOriginalResult.Saved
        val m = model(original = saved.id)
        advanceUntilIdle()
        assertTrue(m.uiState.value is InkUiState.Crop)
        m.onCropDone()
        advanceUntilIdle()
        assertEquals(KitSave.SAVED, m.review.kit)
        assertFalse(m.review.restored)
    }

    @Test
    fun aMissingOriginalSaysSoOnTheSourceStep() = runTest(dispatcher) {
        val m = model(original = kit.newId())
        advanceUntilIdle()
        assertTrue((m.uiState.value as InkUiState.PickSource).openFailed)
    }
}
