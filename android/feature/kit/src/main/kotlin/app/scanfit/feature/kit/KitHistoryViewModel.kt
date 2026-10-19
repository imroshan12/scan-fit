package app.scanfit.feature.kit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.export.ShareStager
import app.scanfit.core.data.kit.HistoryEntry
import app.scanfit.core.data.kit.KitStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface KitHistoryUiState {
    data object Loading : KitHistoryUiState

    data object Empty : KitHistoryUiState

    data object Error : KitHistoryUiState

    data class Ready(
        val entries: List<HistoryEntry>,
        /** A staged copy for the share sheet (ALGORITHMS 1.6.2): the screen opens it once, then `onShareHandled`. */
        val shareUri: String? = null,
        val shareFailed: Boolean = false,
    ) : KitHistoryUiState
}

/** My Kit's History view (ALGORITHMS 13.3): every verified save, newest first, shareable for 30 days. */
@HiltViewModel
class KitHistoryViewModel internal constructor(
    private val kit: KitStore,
    private val share: ShareStager,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(kit: KitStore, share: ShareStager) : this(kit, share, Dispatchers.IO)

    private val refreshes = MutableStateFlow(0L)
    private val _uiState = MutableStateFlow<KitHistoryUiState>(KitHistoryUiState.Loading)
    val uiState: StateFlow<KitHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(kit.revisions, refreshes) { _, _ -> }.collectLatest {
                val next = try {
                    val entries = withContext(io) { kit.history() }
                    if (entries.isEmpty()) KitHistoryUiState.Empty else KitHistoryUiState.Ready(entries)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    KitHistoryUiState.Error
                }
                // A reload keeps a share that is on its way to the sheet.
                val current = _uiState.value as? KitHistoryUiState.Ready
                _uiState.value = if (next is KitHistoryUiState.Ready && current != null) {
                    next.copy(shareUri = current.shareUri, shareFailed = current.shareFailed)
                } else {
                    next
                }
            }
        }
    }

    fun refresh() {
        refreshes.value += 1
    }

    /** Shares the entry's private copy under its recorded name, exactly as it was saved; never records Saved. */
    fun onShare(id: String) {
        val ready = _uiState.value as? KitHistoryUiState.Ready ?: return
        val entry = ready.entries.firstOrNull { it.record.id == id && it.shareable } ?: return
        viewModelScope.launch {
            val uri = withContext(io) { kit.exportCopy(id)?.let { share.stage(entry.record.fileName, it) } }
            val current = _uiState.value as? KitHistoryUiState.Ready ?: return@launch
            _uiState.value = current.copy(shareUri = uri, shareFailed = uri == null)
            // A copy that failed its check was deleted: the row now says it is no longer kept.
            if (uri == null) refresh()
        }
    }

    fun onShareHandled() {
        val ready = _uiState.value as? KitHistoryUiState.Ready ?: return
        _uiState.value = ready.copy(shareUri = null)
    }

    fun onClear() {
        viewModelScope.launch { withContext(io) { kit.clearHistory() } }
    }
}
