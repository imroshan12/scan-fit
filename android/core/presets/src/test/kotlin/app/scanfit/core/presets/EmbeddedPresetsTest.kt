package app.scanfit.core.presets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Phase 0 exit gate for Android, minus the device: the dist bundle passes the format checks and loads. */
class EmbeddedPresetsTest {
    private val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))

    private fun dist(): PresetFiles = PresetFiles(
        bundle =
        File(spec, "dist/presets.json")
            .also {
                check(it.exists()) { "run spec/tools/build_all.sh first" }
            }.readBytes(),
    )

    private fun repo(files: PresetFiles?) = DefaultPresetsRepository({ files }, Dispatchers.Unconfined)

    @Test
    fun theRealBundleLoads() = runBlocking {
        val repo = repo(dist())
        val outcome = repo.loadEmbedded()
        assertTrue("expected Ready, got $outcome", outcome is PresetsLoadOutcome.Ready)
        val summary = (outcome as PresetsLoadOutcome.Ready).summary
        assertTrue(summary.examCount >= 55)
        assertTrue(summary.version >= 1)
        assertNotNull(
            repo.bundle.value
                ?.exams
                ?.firstOrNull { it.id == "ibps_po" },
        )
        assertEquals(outcome, repo.outcome.value)
    }

    @Test
    fun aDamagedCopyOfTheRealBundleIsRejectedAndNothingIsInstalled() = runBlocking {
        val files = dist()
        val repo = repo(PresetFiles(files.bundle.copyOf(files.bundle.size / 3)))
        assertEquals(PresetsLoadOutcome.Failed(PresetLoadError.PARSE), repo.loadEmbedded())
        assertNull(repo.bundle.value)
    }

    @Test
    fun aBuildWithNoEmbeddedSnapshotReportsItInsteadOfCrashing() = runBlocking {
        assertEquals(PresetsLoadOutcome.MissingEmbedded, repo(null).loadEmbedded())
    }

    @Test
    fun installingTheSameBundleTwiceIsAStaleRefusalAndKeepsTheFirst() = runBlocking {
        val repo = repo(dist())
        val first = repo.loadEmbedded()
        assertEquals(PresetsLoadOutcome.Failed(PresetLoadError.STALE_VERSION), repo.loadEmbedded())
        assertNotNull("the earlier good bundle stays installed", repo.bundle.value)
        assertEquals("the UI keeps showing the good outcome", first, repo.outcome.value)
    }
}
