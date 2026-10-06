package app.scanfit.feature.exams

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.draft.DraftStatus
import app.scanfit.core.data.draft.FileDraftStore
import app.scanfit.core.data.draft.RetainedDraft
import app.scanfit.core.data.draft.draftStatus
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class ExamDraftViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val drafts = FakeDraftStore()
    private val preferences = FakeUserPreferences()
    private val presets = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle)
    private val exam = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
    private val spec = exam.documents.first()
    private val bytes = TestJpeg.make(200, 230, 35 * 1024 + 600)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model() = ExamViewModel(SavedStateHandle(mapOf("examId" to exam.id)), presets, preferences, drafts)

    @Test
    fun validDraftShowsRoundedKbOnEntryAndRelaunchWithoutMutatingSaved() = runTest(dispatcher) {
        drafts.retain(exam.id, spec, DocKind.PHOTO, bytes)
        repeat(2) {
            val state = model().uiState.first { it is ExamUiState.Ready } as ExamUiState.Ready
            assertEquals(mapOf(DocType.PHOTO to 36), state.readyDocuments)
            assertTrue(state.savedDocuments.isEmpty())
            assertEquals(DraftStatus.READY, draftStatus(false, DocType.PHOTO in state.readyDocuments))
        }
        assertTrue(preferences.savedDocuments.value.isEmpty())
    }

    @Test
    fun historicalSavedWinsWithAndWithoutAnAvailableDraft() = runTest(dispatcher) {
        preferences.recordSaved(exam.id, spec.type.name)
        drafts.retain(exam.id, spec, DocKind.PHOTO, bytes)
        val available = model().uiState.first { it is ExamUiState.Ready } as ExamUiState.Ready
        assertEquals(DraftStatus.SAVED, draftStatus(spec.type in available.savedDocuments, true))
        drafts.delete(exam.id, spec)
        val absent = model().uiState.first { it is ExamUiState.Ready } as ExamUiState.Ready
        assertTrue(absent.readyDocuments.isEmpty())
        assertEquals(DraftStatus.SAVED, draftStatus(spec.type in absent.savedDocuments, false))
    }

    @Test
    fun revisionsRefreshTheSameChecklistAfterRetainAndDelete() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch(dispatcher) { model.uiState.collect { } }
        model.uiState.first { it is ExamUiState.Ready }
        drafts.retain(exam.id, spec, DocKind.PHOTO, bytes)
        val ready = model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isNotEmpty() == true }
        assertEquals(36, (ready as ExamUiState.Ready).readyDocuments[DocType.PHOTO])
        drafts.delete(exam.id, spec)
        val absent = model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isEmpty() == true }
        assertTrue((absent as ExamUiState.Ready).savedDocuments.isEmpty())
    }

    @Test
    fun foregroundRevalidatesExpiryCorruptionAndMissingFilesWithoutRevisionLoops() = runTest(dispatcher) {
        for (operation in listOf("expired", "corrupt", "missing")) {
            drafts.retain(exam.id, spec, DocKind.PHOTO, bytes)
            val model = model()
            val collector = backgroundScope.launch(dispatcher) { model.uiState.collect { } }
            model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isNotEmpty() == true }
            when (operation) {
                "expired" -> drafts.now += FileDraftStore.TTL_MILLIS
                "corrupt" -> drafts.records[exam.id to spec.type] = RetainedDraft(byteArrayOf(1), drafts.now)
                "missing" -> drafts.records.clear()
            }
            model.refreshDrafts()
            model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isEmpty() == true }
            val revision = drafts.revisions.value
            model.refreshDrafts()
            advanceUntilIdle()
            assertEquals(revision, drafts.revisions.value)
            collector.cancel()
        }
    }

    @Test
    fun trustedSpecChangesInvalidateReadyAndRetiredExamsDisappear() = runTest(dispatcher) {
        drafts.retain(exam.id, spec, DocKind.PHOTO, bytes)
        val model = model()
        backgroundScope.launch(dispatcher) { model.uiState.collect { } }
        model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isNotEmpty() == true }
        val changed = exam.copy(documents = exam.documents.map { if (it.type == spec.type) it.copy(dpi = 300) else it })
        presets.bundle.value = SpecPresets.bundle.copy(exams = listOf(changed))
        model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isEmpty() == true }
        assertTrue(drafts.records.isEmpty())
        presets.bundle.value = SpecPresets.bundle.copy(exams = listOf(changed.copy(status = ExamStatus.RETIRED)))
        assertEquals(ExamUiState.NotFound, model.uiState.first { it == ExamUiState.NotFound })
    }

    @Test
    fun screenReentryChecksTheFilesystemEvenWhenTheViewModelSurvives() = runTest(dispatcher) {
        drafts.retain(exam.id, spec, DocKind.PHOTO, bytes)
        val model = model()
        model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isNotEmpty() == true }
        drafts.records.clear()
        model.refreshDrafts()
        val reopened = model.uiState.first { (it as? ExamUiState.Ready)?.readyDocuments?.isEmpty() == true }
        assertTrue((reopened as ExamUiState.Ready).readyDocuments.isEmpty())
    }
}
