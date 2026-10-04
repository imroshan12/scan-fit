package app.scanfit.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    fun defaultsAreEmptyAndUnverifiedExamsShown() = runTest {
        val prefs = prefs()
        assertEquals(emptyList<String>(), prefs.pinnedExamIds.first())
        assertTrue(prefs.showUnverified.first())
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
            assertTrue("on by default", awaitItem())
            prefs.setShowUnverified(false)
            assertFalse("turning it off is stored, not overridden by the default", awaitItem())
        }
    }

    @Test
    fun theHandwritingConfirmationIsAskedOnceAndRemembered() = runTest {
        val prefs = prefs()
        prefs.handwritingConfirmed.test {
            assertFalse("not confirmed until the user ticks it", awaitItem())
            prefs.confirmHandwriting()
            assertTrue(awaitItem())
        }
    }

    @Test
    fun savedDocumentsSurviveStoreRestartAndDoNotDuplicate() = runTest {
        val owner = Job()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + owner),
        ) { folder.root.resolve("saved.preferences_pb") }
        val first = DataStoreUserPreferences(store)
        assertTrue(first.savedDocuments.first().isEmpty())
        first.recordSaved("ibps_po", "PHOTO")
        first.recordSaved("ibps_po", "PHOTO")
        first.recordSaved("ibps_po", "SIGNATURE")
        first.recordSaved("jee_main", "PHOTO")
        val expected = setOf(
            SavedDocument("ibps_po", "PHOTO"),
            SavedDocument("ibps_po", "SIGNATURE"),
            SavedDocument("jee_main", "PHOTO"),
        )
        assertEquals(expected, first.savedDocuments.first())
        owner.cancelAndJoin()
        assertEquals(expected, prefs("saved").savedDocuments.first())
    }
}
