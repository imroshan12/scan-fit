package app.scanfit.feature.flowphoto

import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.match.DocKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhotoSaveController(
    private val state: MutableStateFlow<PhotoUiState>,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher,
    private val exporter: DocumentExporter,
) {
    private var pending: ExportRequest? = null

    fun onSave(): String? {
        val review = state.value as? PhotoUiState.Review ?: return null
        val ready = review.result as? ReviewResult.Ready ?: return null
        if (review.rendering || !ready.meetsRules || review.save.state == SaveState.SAVING) return null
        val slot = review.slot
        val request = ExportRequest(slot.examId, slot.examName, slot.spec, DocKind.PHOTO, ready.bytes)
        state.value = review.copy(save = SaveResult(SaveState.SAVING))
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
            val next = if (launchFailed) SaveState.SAVE_FAILED else SaveState.IDLE
            state.value = review.copy(save = SaveResult(next))
        } else {
            export(request, uri)
        }
    }

    private fun export(request: ExportRequest, uri: String?) {
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
