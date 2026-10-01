package app.scanfit.core.presets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VerifiedDigestStoreTest {
    private val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")), "fixtures/signing")

    private fun files() = PresetFiles(
        bundle = File(spec, "sample_presets.json").readBytes(),
        signatureText = File(spec, "sample_presets.json.sig").readText(),
        publicKeyBase64 = File(spec, "dev_public_key.b64").readText(),
    )

    private class RecordingStore : VerifiedDigestStore {
        val digests = mutableSetOf<String>()
        var adds = 0

        override fun contains(digest: String) = digest in digests

        override fun add(digest: String) {
            adds++
            digests += digest
        }
    }

    private fun repo(
        files: PresetFiles,
        store: VerifiedDigestStore,
    ) = DefaultPresetsRepository({ files }, Dispatchers.Unconfined, store)

    @Test
    fun aSuccessfulFullVerificationIsRemembered() = runBlocking {
        val store = RecordingStore()
        assertTrue(repo(files(), store).loadEmbedded() is PresetsLoadOutcome.Ready)
        assertEquals(1, store.adds)
        // A fresh process with the same store skips the slow verify but still loads, and does not re-add.
        assertTrue(repo(files(), store).loadEmbedded() is PresetsLoadOutcome.Ready)
        assertEquals(1, store.adds)
    }

    @Test
    fun aFailedVerificationIsNeverRemembered() = runBlocking {
        val store = RecordingStore()
        val bad =
            files().also {
                it.bundle[it.bundle.size / 2] = (it.bundle[it.bundle.size / 2].toInt() xor 1).toByte()
            }
        assertEquals(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE), repo(bad, store).loadEmbedded())
        assertEquals(0, store.adds)
    }

    @Test
    fun changingOneByteMissesTheCacheAndIsFullyReverified() = runBlocking {
        val store = RecordingStore()
        repo(files(), store).loadEmbedded() // remembers the good digest
        val tampered =
            files().also {
                it.bundle[it.bundle.size / 2] =
                    (it.bundle[it.bundle.size / 2].toInt() xor 1).toByte()
            }
        assertFalse(
            store.contains(
                VerifiedDigestStore.digestOf(tampered.bundle, tampered.signatureText, tampered.publicKeyBase64),
            ),
        )
        assertEquals(PresetsLoadOutcome.Failed(PresetLoadError.BAD_SIGNATURE), repo(tampered, store).loadEmbedded())
    }

    @Test
    fun theDigestBindsBundleSignatureAndKey() {
        val f = files()
        val base = VerifiedDigestStore.digestOf(f.bundle, f.signatureText, f.publicKeyBase64)
        val otherKey = File(spec, "other_public_key.b64").readText()
        assertNotEquals(base, VerifiedDigestStore.digestOf(f.bundle, f.signatureText, otherKey))
        assertNotEquals(base, VerifiedDigestStore.digestOf(f.bundle, "AAAA" + f.signatureText, f.publicKeyBase64))
        assertNotEquals(base, VerifiedDigestStore.digestOf(f.bundle + 0, f.signatureText, f.publicKeyBase64))
        assertEquals(
            "surrounding whitespace is ignored like the verifier does",
            base,
            VerifiedDigestStore.digestOf(f.bundle, f.signatureText + "\n", " " + f.publicKeyBase64 + "\n"),
        )
    }

    @Test
    fun theFileStoreKeepsOnlyTheLatestDigestAndSurvivesMissingFiles() {
        val dir =
            kotlin.io.path
                .createTempDirectory()
                .toFile()
        val store = FileVerifiedDigestStore(File(dir, "nested/verified"))
        assertFalse(store.contains("abc"))
        store.add("abc")
        assertTrue(FileVerifiedDigestStore(File(dir, "nested/verified")).contains("abc"))
        store.add("def")
        assertFalse(store.contains("abc"))
        assertTrue(store.contains("def"))
        dir.deleteRecursively()
    }
}
