package app.scanfit.core.presets

import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.SerializationException

sealed interface PresetLoadResult {
    data class Success(
        val bundle: PresetBundle,
    ) : PresetLoadResult

    data class Failure(
        val error: PresetLoadError,
    ) : PresetLoadResult
}

/**
 * The load order shared by both platforms (spec/tools/make_signing_vector.py documents it):
 * 1. signature  2. header parse  3. schema gate  4. version monotonic  5. full decode.
 * Every failure keeps the currently installed bundle; the caller never sees a half-trusted bundle.
 */
object PresetBundleLoader {
    fun load(
        bundle: ByteArray,
        signatureText: String,
        publicKey: PresetPublicKey,
        currentVersion: Int,
    ): PresetLoadResult = load(bundle, signatureText, publicKey, currentVersion, signatureAlreadyVerified = false)

    /**
     * [signatureAlreadyVerified] is internal on purpose: only [DefaultPresetsRepository] may set it, and only for
     * a digest that earlier passed full verification (see [VerifiedDigestStore]).
     */
    internal fun load(
        bundle: ByteArray,
        signatureText: String,
        publicKey: PresetPublicKey,
        currentVersion: Int,
        signatureAlreadyVerified: Boolean,
    ): PresetLoadResult {
        if (!signatureAlreadyVerified && !PresetVerifier.isValid(bundle, signatureText, publicKey)) {
            return fail(PresetLoadError.BAD_SIGNATURE)
        }
        val text = bundle.decodeToString()
        val header =
            try {
                PresetBundle.decodeHeader(text)
            } catch (_: SerializationException) {
                return fail(PresetLoadError.PARSE)
            }
        return when {
            header.schemaVersion > PresetBundle.SUPPORTED_SCHEMA_VERSION -> {
                fail(PresetLoadError.BAD_SCHEMA)
            }

            header.presetsVersion <= currentVersion -> {
                fail(PresetLoadError.STALE_VERSION)
            }

            else -> {
                try {
                    PresetLoadResult.Success(PresetBundle.decode(text))
                } catch (_: SerializationException) {
                    fail(PresetLoadError.PARSE)
                }
            }
        }
    }

    private fun fail(error: PresetLoadError) = PresetLoadResult.Failure(error)
}
