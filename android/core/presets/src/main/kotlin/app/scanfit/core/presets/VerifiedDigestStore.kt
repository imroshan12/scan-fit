package app.scanfit.core.presets

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Remembers which exact (bundle, signature, public key) triple has already passed full Ed25519 verification.
 *
 * Why: Tink's pure-Java Ed25519 costs 0.7-1.4 s per cold start on a 2 GB device (measured on a release build),
 * and the exam list will wait on it. iOS has native Ed25519 and needs no such cache.
 *
 * Safe because the digest binds all three inputs: changing a single byte of the bundle, signature or key
 * misses the cache and triggers a full verification. An entry is only written after a successful one.
 */
interface VerifiedDigestStore {
    fun contains(digest: String): Boolean

    fun add(digest: String)

    companion object {
        /** Never remembers anything: every load verifies in full. */
        val None: VerifiedDigestStore =
            object : VerifiedDigestStore {
                override fun contains(digest: String) = false

                override fun add(digest: String) = Unit
            }

        /** SHA-256 over length-prefixed inputs, so (a, bc) can never collide with (ab, c). */
        fun digestOf(
            bundle: ByteArray,
            signatureText: String,
            publicKeyBase64: String,
        ): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (part in listOf(bundle, signatureText.trim().toByteArray(), publicKeyBase64.trim().toByteArray())) {
                md.update(part.size.toString().toByteArray())
                md.update(SEPARATOR)
                md.update(part)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private val SEPARATOR = byteArrayOf(':'.code.toByte())
    }
}

/** Keeps only the most recent verified digest in one small file (in no-backup storage on Android). */
class FileVerifiedDigestStore(
    private val file: File,
) : VerifiedDigestStore {
    override fun contains(digest: String): Boolean =
        try {
            file.isFile && file.readText().trim() == digest
        } catch (_: IOException) {
            false
        }

    override fun add(digest: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(digest)
        } catch (_: IOException) {
            // Best effort: the next launch simply verifies in full again.
        }
    }
}
