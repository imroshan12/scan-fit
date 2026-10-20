package app.scanfit.feature.settings

import app.cash.turbine.test
import app.scanfit.core.billing.RestoreOutcome
import app.scanfit.core.data.quota.PaywallTrigger
import app.scanfit.core.data.quota.QuotaStatus
import app.scanfit.core.presets.PresetLoadError
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeBilling
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.TestSaveGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val billing = FakeBilling()

    private fun model(
        presets: FakePresetsRepository = FakePresetsRepository(null),
        prefs: FakeUserPreferences = FakeUserPreferences(),
        quota: TestSaveGate = TestSaveGate(billing = billing),
    ) = SettingsViewModel(presets, prefs, quota.gate, billing, quota.paywall)

    @Test
    fun showsTheInstalledPresetsVersionOnceVerified() = runTest {
        val presets = FakePresetsRepository(initial = null)
        model(presets).uiState.test {
            assertNull(awaitItem().presetsVersion)
            presets.outcome.value = PresetsLoadOutcome.Ready(PresetsSummary(examCount = 55, version = 4))
            assertEquals(4, expectMostRecentItem().presetsVersion)
        }
    }

    @Test
    fun aRejectedBundleShowsNoVersion() = runTest {
        val presets = FakePresetsRepository(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE))
        model(presets).uiState.test { assertNull(expectMostRecentItem().presetsVersion) }
    }

    @Test
    fun theUnverifiedToggleIsReadAndWrittenThroughPreferences() = runTest {
        val prefs = FakeUserPreferences()
        val model = model(prefs = prefs)
        model.uiState.test {
            assertEquals("on by default", true, expectMostRecentItem().showUnverified)
            model.onShowUnverifiedChange(false)
            assertEquals(false, awaitItem().showUnverified)
            assertEquals(false, prefs.showUnverified.value)
        }
    }

    @Test
    fun theAnalyticsOptOutIsStored() = runTest {
        val prefs = FakeUserPreferences()
        val model = model(prefs = prefs)
        model.uiState.test {
            assertEquals(true, expectMostRecentItem().analyticsEnabled)
            model.onAnalyticsChange(false)
            assertEquals(false, awaitItem().analyticsEnabled)
            assertEquals(false, prefs.analyticsEnabled.value)
        }
    }

    @Test
    fun showsFreeSavesLeftThenProAfterAPurchase() = runTest {
        val model = model(quota = TestSaveGate(used = 7, billing = billing))
        model.uiState.test {
            assertEquals(QuotaStatus(pro = false, remaining = 13, showsLeft = false), expectMostRecentItem().quota)
            billing.isPro.value = true
            assertEquals(QuotaStatus(pro = true, remaining = 13, showsLeft = false), awaitItem().quota)
        }
    }

    @Test
    fun getProOpensThePaywallFromSettings() = runTest {
        val quota = TestSaveGate(billing = billing)
        model(quota = quota).onGetPro()
        assertEquals(PaywallTrigger.SETTINGS, quota.paywall.current.value)
    }

    @Test
    fun restoreReportsWhatItFound() = runTest {
        val model = model()
        model.uiState.test {
            expectMostRecentItem()
            model.onRestore()
            assertEquals(RestoreState.NOTHING, expectMostRecentItem().restore)
            billing.restoreOutcome = RestoreOutcome.FAILED
            model.onRestore()
            assertEquals(RestoreState.FAILED, expectMostRecentItem().restore)
            billing.restoreOutcome = RestoreOutcome.RESTORED
            model.onRestore()
            assertEquals(RestoreState.RESTORED, expectMostRecentItem().restore)
        }
    }
}
