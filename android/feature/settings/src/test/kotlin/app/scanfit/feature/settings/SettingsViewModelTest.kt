package app.scanfit.feature.settings

import app.cash.turbine.test
import app.scanfit.core.presets.PresetLoadError
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
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
class SettingsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun showsTheInstalledPresetsVersionOnceVerified() = runTest {
        val presets = FakePresetsRepository(initial = null)
        SettingsViewModel(presets, FakeUserPreferences()).uiState.test {
            assertEquals(SettingsUiState(presetsVersion = null), awaitItem())
            presets.outcome.value = PresetsLoadOutcome.Ready(PresetsSummary(examCount = 55, version = 4))
            assertEquals(SettingsUiState(presetsVersion = 4), awaitItem())
        }
    }

    @Test
    fun aRejectedBundleShowsNoVersion() = runTest {
        val presets = FakePresetsRepository(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE))
        SettingsViewModel(presets, FakeUserPreferences()).uiState.test { assertEquals(SettingsUiState(presetsVersion = null), awaitItem()) }
    }

    @Test
    fun theUnverifiedToggleIsReadAndWrittenThroughPreferences() = runTest {
        val prefs = FakeUserPreferences()
        val model = SettingsViewModel(FakePresetsRepository(null), prefs)
        model.uiState.test {
            assertEquals(false, awaitItem().showUnverified)
            model.onShowUnverifiedChange(true)
            assertEquals(true, awaitItem().showUnverified)
            assertEquals(true, prefs.showUnverified.value)
        }
    }
}
