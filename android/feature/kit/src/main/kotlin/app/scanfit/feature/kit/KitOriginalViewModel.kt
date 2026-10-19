package app.scanfit.feature.kit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.kit.KitIds
import app.scanfit.core.data.kit.KitOriginal
import app.scanfit.core.data.kit.KitStore
import app.scanfit.core.imaging.Raster
import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.match.CheckerTargets
import app.scanfit.core.model.PresetBundle
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface KitOriginalUiState {
    data object Loading : KitOriginalUiState

    /** The original is gone or failed its read check (it was removed): `kit.original_missing`. */
    data object Missing : KitOriginalUiState

    /** Deleted on the user's request: the screen leaves. */
    data object Deleted : KitOriginalUiState

    data class Ready(
        val original: KitOriginal,
        val preview: Raster?,
        val query: String = "",
        /** "Use for exam…": the Checker's list for the original's kind (ALGORITHMS 13.2). */
        val targets: List<CheckerTarget> = emptyList(),
    ) : KitOriginalUiState
}

/** One original's screen: preview, "Use for exam…" and Delete (ALGORITHMS 13.1, 13.2). */
@HiltViewModel
class KitOriginalViewModel internal constructor(
    savedState: SavedStateHandle,
    private val kit: KitStore,
    private val presets: PresetsRepository,
    private val images: KitImages,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle,
        kit: KitStore,
        presets: PresetsRepository,
        images: KitImages,
    ) : this(savedState, kit, presets, images, Dispatchers.IO)

    private val id: String? = savedState.get<String>(ORIGINAL_ID)?.takeIf(KitIds::isValid)
    private val _uiState = MutableStateFlow<KitOriginalUiState>(KitOriginalUiState.Loading)
    val uiState: StateFlow<KitOriginalUiState> = _uiState.asStateFlow()
    private var bundle: PresetBundle? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            presets.bundle.collectLatest { latest ->
                bundle = latest
                val ready = _uiState.value as? KitOriginalUiState.Ready ?: return@collectLatest
                _uiState.value = ready.copy(targets = targets(ready.original, ready.query))
            }
        }
    }

    private suspend fun load() {
        val id = id ?: run {
            _uiState.value = KitOriginalUiState.Missing
            return
        }
        val loaded = withContext(io) {
            val entry = try {
                kit.originals().firstOrNull { it.id == id }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val bytes = entry?.let { kit.original(id) }
            if (entry == null || bytes == null) null else entry to images.decode(bytes, PREVIEW_LONG_SIDE)
        }
        _uiState.value = if (loaded == null) {
            KitOriginalUiState.Missing
        } else {
            KitOriginalUiState.Ready(loaded.first, loaded.second, targets = targets(loaded.first, ""))
        }
    }

    fun onQuery(query: String) {
        val ready = _uiState.value as? KitOriginalUiState.Ready ?: return
        _uiState.value = ready.copy(query = query, targets = targets(ready.original, query))
    }

    fun onDelete() {
        val ready = _uiState.value as? KitOriginalUiState.Ready ?: return
        viewModelScope.launch {
            withContext(io) { kit.deleteOriginal(ready.original.id) }
            _uiState.value = KitOriginalUiState.Deleted
        }
    }

    private fun targets(
        original: KitOriginal,
        query: String,
    ): List<CheckerTarget> = bundle?.let { CheckerTargets.of(it, original.kind, query) }.orEmpty()

    companion object {
        /** Navigation argument: `kit/original/{originalId}`. */
        const val ORIGINAL_ID = "originalId"

        /** Twice a 280 dp preview at common densities, never the stored size (CLAUDE.md rule 4). */
        private const val PREVIEW_LONG_SIDE = 800
    }
}
