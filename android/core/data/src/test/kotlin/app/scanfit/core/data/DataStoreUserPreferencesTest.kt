package app.scanfit.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreUserPreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.prefs(name: String = "prefs") = DataStoreUserPreferences(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("$name.preferences_pb") },
    )

    @Test
    fun defaultsAreEmptyAndHidden() = runTest {
        val prefs = prefs()
        assertEquals(emptyList<String>(), prefs.pinnedExamIds.first())
        assertFalse(prefs.showUnverified.first())
    }

    @Test
    fun theMostRecentlyPinnedExamComesFirstAndRepinningDoesNotDuplicate() = runTest {
        val prefs = prefs()
        prefs.setPinned("ibps_po", true)
        prefs.setPinned("ssc_cgl", true)
        prefs.setPinned("ibps_po", true)
        assertEquals(listOf("ibps_po", "ssc_cgl"), prefs.pinnedExamIds.first())
        prefs.setPinned("ibps_po", false)
        assertEquals(listOf("ssc_cgl"), prefs.pinnedExamIds.first())
    }

    @Test
    fun showUnverifiedIsRemembered() = runTest {
        val prefs = prefs()
        prefs.showUnverified.test {
            assertFalse(awaitItem())
            prefs.setShowUnverified(true)
            assertTrue(awaitItem())
        }
    }
}
