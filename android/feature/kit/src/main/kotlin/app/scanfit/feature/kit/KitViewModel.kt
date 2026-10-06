package app.scanfit.feature.kit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.draft.DraftStore
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.DocType
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.model.PresetBundle
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KitDocument(val type: DocType, val roundedKb: Int)

data class KitExam(val id: String, val name: String, val confidence: Confidence, val documents: List<KitDocument>)

sealed interface KitUiState {
    data object Loading : KitUiState

    data object Empty : KitUiState

    data object Error : KitUiState

    data class Ready(val exams: List<KitExam>) : KitUiState
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class KitViewModel @Inject constructor(
    private val presets: PresetsRepository,
    private val drafts: DraftStore,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0L)

    val uiState: StateFlow<KitUiState> = combine(
        presets.bundle,
        presets.outcome,
        drafts.revisions,
        refreshes,
    ) { bundle, outcome, _, _ -> bundle to outcome }.transformLatest { (bundle, outcome) ->
        if (bundle == null) {
            emit(if (outcome == null) KitUiState.Loading else KitUiState.Error)
        } else {
            emit(KitUiState.Loading)
            val state = try {
                val exams = withContext(Dispatchers.IO) { availableExams(bundle) }
                if (exams.isEmpty()) KitUiState.Empty else KitUiState.Ready(exams)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                KitUiState.Error
            }
            currentCoroutineContext().ensureActive()
            emit(state)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
        KitUiState.Loading,
    )

    fun refreshDrafts() {
        refreshes.value += 1
    }

    fun retry() {
        viewModelScope.launch {
            try {
                presets.loadEmbedded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            } finally {
                refreshDrafts()
            }
        }
    }

    private suspend fun availableExams(bundle: PresetBundle): List<KitExam> = buildList {
        for (exam in bundle.exams) {
            currentCoroutineContext().ensureActive()
            if (exam.status != ExamStatus.ACTIVE) continue
            val documents = buildList {
                for (spec in exam.documents) {
                    currentCoroutineContext().ensureActive()
                    val kind = DocKind.of(spec.type)
                    if (kind == DocKind.PDF_DOCUMENT) continue
                    val draft = drafts.read(exam.id, spec, kind) ?: continue
                    add(KitDocument(spec.type, (draft.bytes.size + KB / 2) / KB))
                }
            }
            if (documents.isNotEmpty()) add(KitExam(exam.id, exam.name, exam.confidence, documents))
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val KB = 1024
    }
}
