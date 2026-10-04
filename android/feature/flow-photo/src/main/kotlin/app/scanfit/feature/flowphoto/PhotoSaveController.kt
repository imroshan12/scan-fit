package app.scanfit.feature.flowphoto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhotoSaveController(
    private val state: MutableStateFlow<PhotoUiState>,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val exporter: PhotoExporter,
) {
    private var pending: PhotoExportRequest? = null

    fun onSave(): String? {
        val review = state.value as? PhotoUiState.Review ?: return null
        val ready = review.result as? ReviewResult.Ready ?: return null
        if (review.rendering || !ready.meetsRules || review.save.state == PhotoSaveState.SAVING) return null
        val request = PhotoExportRequest(review.slot, ready.bytes)
        state.value = review.copy(save = PhotoSaveResult(PhotoSaveState.SAVING))
        if (exporter.destinations.requiresPicker) {
            pending = request
            return request.fileName
        }
        export(request, null)
        return null
    }

    fun onSaveDestination(uri: String?, launchFailed: Boolean) {
        val request = pending ?: return
        pending = null
        val review = state.value as? PhotoUiState.Review ?: return
        if (uri == null) {
            val next = if (launchFailed) PhotoSaveState.SAVE_FAILED else PhotoSaveState.IDLE
            state.value = review.copy(save = PhotoSaveResult(next))
        } else {
            export(request, uri)
        }
    }

    private fun export(request: PhotoExportRequest, uri: String?) {
        scope.launch {
            val result = withContext(work) { exporter.export(request, uri) }
            val review = state.value as? PhotoUiState.Review ?: return@launch
            state.value = review.copy(save = result)
        }
    }
}

fun PhotoFlowViewModel.onSave(): String? = saves.onSave()

fun PhotoFlowViewModel.onSaveDestination(uri: String?, launchFailed: Boolean = false) {
    saves.onSaveDestination(uri, launchFailed)
}
