package app.scanfit.feature.home

import app.cash.turbine.test
import app.scanfit.core.model.ExamCategory
import app.scanfit.core.presets.PresetLoadError
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val ready = PresetsLoadOutcome.Ready(PresetsSummary(examCount = 55, version = 2))

    private fun model(
        prefs: FakeUserPreferences = FakeUserPreferences(),
        presets: FakePresetsRepository = FakePresetsRepository(ready, SpecPresets.bundle),
    ) = HomeViewModel(presets, prefs)

    /** The state once the combine has produced a value for the current inputs (skips the initial placeholder). */
    private suspend fun HomeViewModel.settled(predicate: (HomeUiState) -> Boolean = { true }) = uiState.first { it.presets != PresetsStatus.Loading && predicate(it) }

    @Test
    fun startsLoadingThenShowsTheVerifiedPresetsSummary() = runTest {
        val presets = FakePresetsRepository(initial = null)
        model(presets = presets).uiState.test {
            assertEquals(PresetsStatus.Loading, awaitItem().presets)
            presets.outcome.value = ready
            assertEquals(PresetsStatus.Ready(55, 2), awaitItem().presets)
        }
    }

    @Test
    fun aBadSignatureIsAPlainFailedStateNeverACrashOrAHalfTrustedBundle() = runTest {
        val state = model(presets = FakePresetsRepository(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE))).settled()
        assertEquals(PresetsStatus.Failed, state.presets)
        assertTrue(state.popular.isEmpty() && state.results.isEmpty())
    }

    @Test
    fun aMissingEmbeddedSnapshotIsAFailedState() = runTest {
        assertEquals(PresetsStatus.Failed, model(presets = FakePresetsRepository(PresetsLoadOutcome.MissingEmbedded)).settled().presets)
    }

    @Test
    fun anEmptyQueryShowsSectionsPopularFirstAndPinnedInPinOrder() = runTest {
        val state = model(FakeUserPreferences(pinned = listOf("ssc_cgl", "ibps_po"))).settled { it.popular.isNotEmpty() }
        assertTrue(state.showsSections)
        assertEquals(listOf("ssc_cgl", "ibps_po"), state.pinned.map { it.id })
        assertEquals(listOf("ibps_po", "sbi_po", "ssc_cgl"), state.popular.take(3).map { it.id })
    }

    @Test
    fun typingSearchesWithTheSharedEngine() = runTest {
        val model = model()
        model.onQueryChange("ssc cgl")
        val state = model.settled { it.query == "ssc cgl" }
        assertFalse(state.showsSections)
        assertEquals("ssc_cgl", state.results.first().id)
    }

    @Test
    fun hindiFindsTheSameExams() = runTest {
        val model = model()
        model.onQueryChange("बैंक")
        val ids = model.settled { it.query == "बैंक" }.results.map { it.id }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { id -> SpecPresets.bundle.exams.first { it.id == id }.category == ExamCategory.BANKING })
    }

    @Test
    fun aCategoryChipBrowsesThatCategoryAndTappingItAgainClearsIt() = runTest {
        val model = model()
        model.onCategorySelected(ExamCategory.SSC)
        val browsed = model.settled { it.category == ExamCategory.SSC }
        assertTrue(browsed.results.isNotEmpty() && browsed.results.all { it.category == ExamCategory.SSC })
        model.onCategorySelected(ExamCategory.SSC)
        assertTrue(model.settled { it.category == null }.showsSections)
    }

    @Test
    fun unverifiedExamsAppearOnlyWhenTheSettingIsOn() = runTest {
        val prefs = FakeUserPreferences(showUnverified = false)
        val model = model(prefs)
        model.onQueryChange("rrb alp")
        assertTrue(model.settled { it.query == "rrb alp" }.results.none { it.id == "rrb_alp" })
        prefs.showUnverified.value = true
        assertTrue(model.settled { s -> s.results.any { it.id == "rrb_alp" } }.results.isNotEmpty())
    }

    @Test
    fun aPinnedUnverifiedExamStaysInMyExamsEvenWithTheSettingOff() = runTest {
        val prefs = FakeUserPreferences(pinned = listOf("rrb_alp"), showUnverified = false)
        val state = model(prefs).settled { it.pinned.isNotEmpty() }
        assertEquals(listOf("rrb_alp"), state.pinned.map { it.id })
    }
}
