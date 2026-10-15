package app.scanfit.feature.flowink

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.imaging.Pipeline
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.CustomSize
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeShareStager
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The ink flow for a Custom size (ALGORITHMS 11): the same pipeline, no draft, no Saved status, no handwriting tick. */
@OptIn(ExperimentalCoroutinesApi::class)
class InkCustomViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val share = FakeShareStager()
    private val tools = FakeInkTools().apply { files[PHOTO] = byteArrayOf(1, 2, 3) }
    private val destinations = FakeExportDestinations()
    private val drafts = FakeDraftStore()
    private val prefs = FakeUserPreferences()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(
        text: String = SIGNATURE,
        presets: FakePresetsRepository = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
    ) = InkFlowViewModel(
        SavedStateHandle(mapOf(InkFlowViewModel.CUSTOM to text)),
        presets,
        tools,
        prefs,
        DocumentExporter(destinations, prefs),
        dispatcher,
        drafts,
        share,
    )

    @Test
    fun aCustomSignatureUsesTheSignatureCleanupAndItsOwnLimits() {
        val slot = (model().uiState.value as InkUiState.PickSource).slot
        assertTrue(slot.custom)
        assertEquals(Pipeline.SIGNATURE_CLEANUP to DocKind.SIGNATURE, slot.pipeline to slot.kind)
        assertEquals(CustomSize.parse(SIGNATURE)?.spec, slot.spec)
        assertFalse("the CAPITAL-letters tick is an exam rule", slot.needsHandwritingConfirmation)
    }

    @Test
    fun aCustomSizeOpensEvenWhenThePresetsCannotLoad() {
        val failed = FakePresetsRepository(PresetsLoadOutcome.MissingEmbedded, null)
        assertTrue(model(presets = failed).uiState.value is InkUiState.PickSource)
    }

    @Test
    fun anInvalidCustomTextIsNotFound() {
        assertEquals(InkUiState.NotFound, model(text = "signature;140x60;20-10;200").uiState.value)
        assertEquals("a photo size is not an ink flow", InkUiState.NotFound, model(text = "photo;-;0-50;200").uiState.value)
    }

    @Test
    fun savingKeepsNoDraftRecordsNoSavedStatusAndNamesTheFileCustom() = runTest(dispatcher) {
        val m = model()
        m.onImageSelected(PHOTO)
        m.onCropDone()
        advanceUntilIdle()
        val review = m.uiState.value as InkUiState.Review
        val ready = review.result as InkReviewResult.Ready
        assertTrue("16 KB at 140x60 meets the custom 10–20 KB", ready.meetsRules && review.canSave)
        assertTrue("the catalogue exams that accept it are still listed", ready.note.accepted > 0)
        assertEquals("no draft for a custom size", 0, drafts.writes)
        m.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, (m.uiState.value as InkUiState.Review).save.state)
        assertTrue("no checklist, so no Saved record", prefs.savedDocuments.first().isEmpty())
        assertEquals("signature_Custom_140x60_16kb.jpg", destinations.lastRequest?.fileName)
        assertEquals(CustomSize.EXAM_NAME, destinations.lastRequest?.examName)
    }

    private companion object {
        const val SIGNATURE = "signature;140x60;10-20;200"
        const val PHOTO = "content://picker/signature.jpg"
    }
}
