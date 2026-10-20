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
 * The format checks shared by both platforms (spec/README.md "Loading presets", cases in
 * spec/fixtures/presets_load/cases.json): 1. header parse  2. schema gate  3. version only goes up  4. full decode.
 * Every failure keeps the currently installed bundle; the caller never sees a half-read bundle.
 */
object PresetBundleLoader {
    fun load(
        bundle: ByteArray,
        currentVersion: Int,
    ): PresetLoadResult {
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
