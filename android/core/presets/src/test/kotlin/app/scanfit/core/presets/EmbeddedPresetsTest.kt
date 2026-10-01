package app.scanfit.core.presets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Phase 0 exit gate for Android, minus the device: the dist bundle verifies against the dev key and loads. */
class EmbeddedPresetsTest {
    private val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))

    private fun dist(): PresetFiles =
        PresetFiles(
            bundle =
                File(spec, "dist/presets.json")
                    .also {
                        check(it.exists()) { "run spec/tools/build_all.sh first" }
                    }.readBytes(),
            signatureText = File(spec, "dist/presets.json.sig").readText(),
            publicKeyBase64 = File(spec, "signing/dev_public_key.b64").readText(),
        )

    private fun repo(files: PresetFiles?) = DefaultPresetsRepository({ files }, Dispatchers.Unconfined)

    @Test
    fun theRealBundleVerifiesAgainstTheDevKeyAndDecodes() =
        runBlocking {
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
    fun aBundleVerifiedWithAnotherKeyIsRejectedAndNothingIsInstalled() =
        runBlocking {
            val files = dist()
            val other = File(spec, "fixtures/signing/other_public_key.b64").readText()
            val repo = repo(PresetFiles(files.bundle, files.signatureText, other))
            assertEquals(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE), repo.loadEmbedded())
            assertNull(repo.bundle.value)
        }

    @Test
    fun aOneByteChangeToTheRealBundleIsRejected() =
        runBlocking {
            val files = dist()
            files.bundle[files.bundle.size / 3] = (files.bundle[files.bundle.size / 3].toInt() xor 0x20).toByte()
            assertEquals(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE), repo(files).loadEmbedded())
        }

    @Test
    fun aBuildWithNoEmbeddedSnapshotReportsItInsteadOfCrashing() =
        runBlocking {
            assertEquals(PresetsLoadOutcome.MissingEmbedded, repo(null).loadEmbedded())
        }

    @Test
    fun installingTheSameBundleTwiceIsAStaleRefusalAndKeepsTheFirst() =
        runBlocking {
            val repo = repo(dist())
            val first = repo.loadEmbedded()
            assertEquals(PresetsLoadOutcome.Failed(PresetLoadError.STALE_VERSION), repo.loadEmbedded())
            assertNotNull("the earlier good bundle stays installed", repo.bundle.value)
            assertEquals("the UI keeps showing the good outcome", first, repo.outcome.value)
        }
}
