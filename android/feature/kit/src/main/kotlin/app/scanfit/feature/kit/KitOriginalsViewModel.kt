package app.scanfit.feature.kit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.kit.KitOriginal
import app.scanfit.core.data.kit.KitStore
import app.scanfit.core.imaging.Raster
import app.scanfit.core.match.DocKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KitOriginalGroup(
    val kind: DocKind,
    val originals: List<KitOriginal>,
)

sealed interface KitOriginalsUiState {
    data object Loading : KitOriginalsUiState

    data object Empty : KitOriginalsUiState

    data object Error : KitOriginalsUiState

    data class Ready(
        val groups: List<KitOriginalGroup>,
    ) : KitOriginalsUiState
}

/** My Kit's Originals view (ALGORITHMS 13.1): grouped by kind, newest first; thumbnails load per row. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class KitOriginalsViewModel internal constructor(
    private val kit: KitStore,
    private val images: KitImages,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(kit: KitStore, images: KitImages) : this(kit, images, Dispatchers.IO)

    private val refreshes = MutableStateFlow(0L)

    val uiState: StateFlow<KitOriginalsUiState> = combine(kit.revisions, refreshes) { _, _ -> }
        .transformLatest {
            emit(
                try {
                    val groups = group(withContext(io) { kit.originals() })
                    if (groups.isEmpty()) KitOriginalsUiState.Empty else KitOriginalsUiState.Ready(groups)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    KitOriginalsUiState.Error
                },
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
            KitOriginalsUiState.Loading,
        )

    fun refresh() {
        refreshes.value += 1
    }

    /** A small upright copy for a row, decoded off the main thread; `null` when the original cannot be read. */
    suspend fun thumbnail(id: String): Raster? = withContext(io) {
        kit.original(id)?.let { images.decode(it, THUMBNAIL_LONG_SIDE) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Twice the 56 dp row thumbnail at the densest common screens (ALGORITHMS 13.1). */
        const val THUMBNAIL_LONG_SIDE = 192

        /** The order of ALGORITHMS 13.1: Photo, Signature, Thumb, Fingers, Declaration. */
        private val KIND_ORDER =
            listOf(DocKind.PHOTO, DocKind.SIGNATURE, DocKind.THUMB, DocKind.FINGERS, DocKind.DECLARATION)

        internal fun group(originals: List<KitOriginal>): List<KitOriginalGroup> = KIND_ORDER.mapNotNull { kind ->
            originals.filter { it.kind == kind }
                .sortedByDescending { it.createdAtMillis }
                .takeIf { it.isNotEmpty() }
                ?.let { KitOriginalGroup(kind, it) }
        }
    }
}
