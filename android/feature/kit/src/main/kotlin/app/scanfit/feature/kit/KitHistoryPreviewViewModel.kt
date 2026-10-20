package app.scanfit.feature.kit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.export.ShareStager
import app.scanfit.core.data.kit.ExportRecord
import app.scanfit.core.data.kit.KitIds
import app.scanfit.core.data.kit.KitStore
import app.scanfit.core.imaging.Raster
import app.scanfit.core.pdf.PdfPages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface KitHistoryPreviewUiState {
    data object Loading : KitHistoryPreviewUiState

    /** The copy is gone, too old or failed its check (ALGORITHMS 13.3). */
    data object Failed : KitHistoryPreviewUiState

    data class Ready(
        val record: ExportRecord,
        /** The saved image at screen size; `null` for a PDF, whose [pages] render one by one. */
        val image: Raster?,
        val pages: Int,
        val shareUri: String? = null,
        val shareFailed: Boolean = false,
    ) : KitHistoryPreviewUiState
}

/** A saved file from My Kit's History, previewed before it is shared again (ALGORITHMS 13.3 previews). */
@HiltViewModel
class KitHistoryPreviewViewModel internal constructor(
    savedState: SavedStateHandle,
    private val kit: KitStore,
    private val share: ShareStager,
    private val images: KitImages,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle,
        kit: KitStore,
        share: ShareStager,
        images: KitImages,
    ) : this(savedState, kit, share, images, Dispatchers.IO)

    private val id: String = savedState.get<String>(EXPORT_ID).orEmpty()
    private val _uiState = MutableStateFlow<KitHistoryPreviewUiState>(KitHistoryPreviewUiState.Loading)
    val uiState: StateFlow<KitHistoryPreviewUiState> = _uiState.asStateFlow()

    /** The open PDF; the platform renderer draws one page at a time. */
    private var pdf: PdfPages? = null
    private val rendering = Mutex()
    private var bytes: ByteArray? = null

    init {
        viewModelScope.launch {
            _uiState.value =
                try {
                    withContext(io) { load() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    KitHistoryPreviewUiState.Failed
                }
        }
    }

    private suspend fun load(): KitHistoryPreviewUiState {
        if (!KitIds.isValid(id)) return KitHistoryPreviewUiState.Failed
        val entry =
            kit.history().firstOrNull { it.record.id == id && it.shareable } ?: return KitHistoryPreviewUiState.Failed
        val copy = kit.exportCopy(id) ?: return KitHistoryPreviewUiState.Failed
        bytes = copy
        if (!entry.record.isPdf) {
            val image = images.decode(copy, IMAGE_PX) ?: return KitHistoryPreviewUiState.Failed
            return KitHistoryPreviewUiState.Ready(entry.record, image, pages = 1)
        }
        val pages = images.openPdf(copy) ?: return KitHistoryPreviewUiState.Failed
        pdf = pages
        return KitHistoryPreviewUiState.Ready(entry.record, null, minOf(pages.pageCount, MAX_PAGES))
    }

    /** Page [index] of a PDF at [widthPx] wide (aspect kept); `null` when it cannot be drawn. */
    suspend fun page(
        index: Int,
        widthPx: Int,
    ): Raster? = withContext(io) {
        rendering.withLock {
            val pages = pdf ?: return@withLock null
            if (index !in 0 until minOf(pages.pageCount, MAX_PAGES) || widthPx < 1) return@withLock null
            val size = pages.pageSize(index)
            val width = minOf(widthPx, MAX_PAGE_PX)
            pages.render(index, width, maxOf(1, (width * size.height / size.width).toInt()))
        }
    }

    /** Shares the copy under its recorded name, exactly as it was saved (ALGORITHMS 1.6.2); never records Saved. */
    fun onShare() {
        val ready = _uiState.value as? KitHistoryPreviewUiState.Ready ?: return
        val copy = bytes ?: return
        viewModelScope.launch {
            val uri = withContext(io) { share.stage(ready.record.fileName, copy) }
            _uiState.update {
                (it as? KitHistoryPreviewUiState.Ready)?.copy(shareUri = uri, shareFailed = uri == null) ?: it
            }
        }
    }

    fun onShareHandled() = _uiState.update { (it as? KitHistoryPreviewUiState.Ready)?.copy(shareUri = null) ?: it }

    override fun onCleared() {
        pdf?.close()
        pdf = null
    }

    companion object {
        /** Navigation argument: `kit/history/{exportId}`. */
        const val EXPORT_ID = "exportId"
        private const val IMAGE_PX = 1600
        private const val MAX_PAGE_PX = 1440
        private const val MAX_PAGES = 20
    }
}
