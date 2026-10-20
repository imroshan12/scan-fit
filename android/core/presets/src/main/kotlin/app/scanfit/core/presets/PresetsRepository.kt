package app.scanfit.core.presets

import android.content.Context
import app.scanfit.core.model.PresetBundle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the UI needs to know about the presets that are currently trusted. */
data class PresetsSummary(
    val examCount: Int,
    val version: Int,
)

sealed interface PresetsLoadOutcome {
    data class Ready(
        val summary: PresetsSummary,
    ) : PresetsLoadOutcome

    data class Failed(
        val error: PresetLoadError,
    ) : PresetsLoadOutcome

    /** The build is missing its embedded snapshot (a packaging bug, surfaced instead of crashing). */
    data object MissingEmbedded : PresetsLoadOutcome
}

/** The presets snapshot: the exact bytes of `presets.json`. */
class PresetFiles(
    val bundle: ByteArray,
)

/** Where the embedded snapshot comes from: assets in the app, fixtures in tests. */
fun interface EmbeddedPresetSource {
    fun read(): PresetFiles?
}

/** Reads the snapshot that `:core:presets`' embed task put into the assets (see EmbedPresetsTask). */
class AssetEmbeddedPresetSource(
    private val context: Context,
) : EmbeddedPresetSource {
    override fun read(): PresetFiles? = try {
        PresetFiles(bundle = context.assets.open("presets/presets.json").use { it.readBytes() })
    } catch (_: IOException) {
        null
    }
}

/** Holds the loaded presets. UIs observe [outcome]: `null` while the snapshot is still being read. */
interface PresetsRepository {
    val outcome: StateFlow<PresetsLoadOutcome?>
    val bundle: StateFlow<PresetBundle?>

    /** Checks the embedded snapshot's format and installs it. A corrupt or unsupported snapshot is rejected. */
    suspend fun loadEmbedded(): PresetsLoadOutcome
}

/**
 * Phase 0 loads only the embedded snapshot. OTA sync (ETag, WorkManager, "Spec updated" badges) lands in Phase 4
 * on top of the same [PresetBundleLoader].
 */
class DefaultPresetsRepository(
    private val source: EmbeddedPresetSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PresetsRepository {
    private val lock = Mutex()
    private val _outcome = MutableStateFlow<PresetsLoadOutcome?>(null)
    private val _bundle = MutableStateFlow<PresetBundle?>(null)

    override val outcome: StateFlow<PresetsLoadOutcome?> = _outcome.asStateFlow()
    override val bundle: StateFlow<PresetBundle?> = _bundle.asStateFlow()

    override suspend fun loadEmbedded(): PresetsLoadOutcome = withContext(dispatcher) {
        lock.withLock {
            val result = attempt()
            // A rejected later load must not replace a good earlier outcome that the UI is already showing.
            if (_bundle.value == null || result is PresetsLoadOutcome.Ready) _outcome.value = result
            result
        }
    }

    private fun attempt(): PresetsLoadOutcome {
        val files = source.read() ?: return PresetsLoadOutcome.MissingEmbedded
        val installed = _bundle.value?.presetsVersion ?: 0
        return when (val result = PresetBundleLoader.load(files.bundle, installed)) {
            is PresetLoadResult.Success -> {
                _bundle.value = result.bundle
                PresetsLoadOutcome.Ready(PresetsSummary(result.bundle.exams.size, result.bundle.presetsVersion))
            }

            is PresetLoadResult.Failure -> {
                PresetsLoadOutcome.Failed(result.error)
            }
        }
    }
}
