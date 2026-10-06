package app.scanfit.feature.exams

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExamViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val ready = PresetsLoadOutcome.Ready(PresetsSummary(55, 2))

    private fun model(
        id: String,
        prefs: FakeUserPreferences = FakeUserPreferences(),
        presets: FakePresetsRepository = FakePresetsRepository(ready, SpecPresets.bundle),
    ) = ExamViewModel(SavedStateHandle(mapOf(ExamViewModel.EXAM_ID to id)), presets, prefs, FakeDraftStore())

    @Test
    fun showsTheExamFromTheTrustedPresets() = runTest {
        val state = model("ibps_po").uiState.first { it is ExamUiState.Ready } as ExamUiState.Ready
        assertEquals("IBPS PO / MT", state.exam.name)
        assertTrue(state.exam.documents.isNotEmpty())
    }

    @Test
    fun anUnknownIdIsNotFoundNotACrash() = runTest {
        assertEquals(ExamUiState.NotFound, model("no_such_exam").uiState.first { it != ExamUiState.Loading })
    }

    @Test
    fun staysLoadingUntilThePresetsAreVerified() = runTest {
        val presets = FakePresetsRepository(initial = null)
        val model = model("ibps_po", presets = presets)
        assertEquals(ExamUiState.Loading, model.uiState.first())
        presets.outcome.value = ready
        presets.bundle.value = SpecPresets.bundle
        assertTrue(model.uiState.first { it != ExamUiState.Loading } is ExamUiState.Ready)
    }

    @Test
    fun pinningIsSavedAndReflected() = runTest {
        val prefs = FakeUserPreferences()
        val model = model("ssc_cgl", prefs)
        model.uiState.first { it is ExamUiState.Ready }
        model.onTogglePin()
        assertEquals(listOf("ssc_cgl"), prefs.pinnedExamIds.value)
        assertTrue((model.uiState.first { (it as? ExamUiState.Ready)?.pinned == true } as ExamUiState.Ready).pinned)
        model.onTogglePin()
        assertTrue(prefs.pinnedExamIds.value.isEmpty())
    }

    @Test
    fun verifiedSavedDocumentsAreObservedAndShownOnReopeningOnlyForTheirExam() = runTest {
        val prefs = FakeUserPreferences()
        val model = model("ibps_po", prefs)
        model.uiState.first { it is ExamUiState.Ready }
        prefs.recordSaved("ibps_po", "PHOTO")
        prefs.recordSaved("jee_main", "SIGNATURE")
        val observed = model.uiState.first { (it as? ExamUiState.Ready)?.savedDocuments?.isNotEmpty() == true }
        assertEquals(setOf(DocType.PHOTO), (observed as ExamUiState.Ready).savedDocuments)
        val reopened = model("ibps_po", prefs).uiState.first { it is ExamUiState.Ready } as ExamUiState.Ready
        assertEquals(setOf(DocType.PHOTO), reopened.savedDocuments)
    }
}
