package app.scanfit.core.presets

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.util.Base64

/** Why a preset bundle was refused. `reason` matches the `sync_failure_reason` analytics enum and vector.json. */
enum class PresetLoadError(
    val reason: String,
) {
    BAD_SIGNATURE("bad_signature"),
    BAD_SCHEMA("bad_schema"),
    STALE_VERSION("stale_version"),
    PARSE("parse"),
}

/** An Ed25519 public key for verifying preset bundles. */
class PresetPublicKey private constructor(
    internal val raw: ByteArray,
) {
    companion object {
        private const val KEY_BYTES = 32

        /** Null unless [base64] (surrounding whitespace ignored) is exactly 32 bytes. */
        fun fromBase64(base64: String): PresetPublicKey? {
            val bytes = decodeStrict(base64.trim()) ?: return null
            return if (bytes.size == KEY_BYTES) PresetPublicKey(bytes) else null
        }
    }
}

internal fun decodeStrict(base64: String): ByteArray? =
    try {
        Base64.getDecoder().decode(base64)
    } catch (_: IllegalArgumentException) {
        null
    }

object PresetVerifier {
    private const val SIGNATURE_BYTES = 64

    /**
     * True only if [signatureText] is base64 (surrounding whitespace ignored) of a 64-byte Ed25519 signature over
     * exactly [bundle]. Anything malformed is simply false.
     */
    fun isValid(
        bundle: ByteArray,
        signatureText: String,
        publicKey: PresetPublicKey,
    ): Boolean {
        val signature = decodeStrict(signatureText.trim())?.takeIf { it.size == SIGNATURE_BYTES } ?: return false
        return try {
            Ed25519Verify(publicKey.raw).verify(signature, bundle)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }
}
