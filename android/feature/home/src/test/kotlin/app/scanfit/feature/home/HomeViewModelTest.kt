package app.scanfit.feature.home

import app.cash.turbine.test
import app.scanfit.core.presets.PresetLoadError
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakePresetsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsLoadingThenShowsTheVerifiedPresetsSummary() =
        runTest {
            val presets = FakePresetsRepository(initial = null)
            val model = HomeViewModel(presets)
            model.uiState.test {
                assertEquals(HomeUiState.Loading, awaitItem())
                presets.outcome.value = PresetsLoadOutcome.Ready(PresetsSummary(examCount = 55, version = 3))
                assertEquals(HomeUiState.Ready(examCount = 55, version = 3), awaitItem())
            }
        }

    @Test
    fun aBadSignatureIsAPlainFailedStateNeverACrashOrAHalfTrustedBundle() =
        runTest {
            val model = HomeViewModel(FakePresetsRepository(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE)))
            model.uiState.test { assertEquals(HomeUiState.Failed, awaitItem()) }
        }

    @Test
    fun aMissingEmbeddedSnapshotIsAFailedState() =
        runTest {
            val model = HomeViewModel(FakePresetsRepository(PresetsLoadOutcome.MissingEmbedded))
            model.uiState.test { assertEquals(HomeUiState.Failed, awaitItem()) }
        }
}
