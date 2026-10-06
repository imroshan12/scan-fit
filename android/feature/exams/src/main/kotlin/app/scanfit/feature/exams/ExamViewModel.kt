package app.scanfit.feature.exams

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.draft.DraftStore
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The exam checklist (UI_UX §3). Immutable: the single source of truth for the screen. */
sealed interface ExamUiState {
    data object Loading : ExamUiState

    /** The id is not in the trusted presets (a stale link, or a retired exam). */
    data object NotFound : ExamUiState

    data class Ready(
        val exam: Exam,
        val pinned: Boolean,
        val savedDocuments: Set<DocType> = emptySet(),
        val readyDocuments: Map<DocType, Int> = emptyMap(),
    ) : ExamUiState
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExamViewModel
@Inject
constructor(
    savedState: SavedStateHandle,
    presets: PresetsRepository,
    private val preferences: UserPreferences,
    private val drafts: DraftStore,
) : ViewModel() {
    private val examId: String = savedState.get<String>(EXAM_ID).orEmpty()
    private val refreshes = MutableStateFlow(0L)

    val uiState: StateFlow<ExamUiState> =
        combine(
            presets.outcome,
            presets.bundle,
            preferences.pinnedExamIds,
            preferences.savedDocuments,
        ) { outcome, bundle, pinned, saved ->
            when {
                bundle == null -> if (outcome == null) ExamUiState.Loading else ExamUiState.NotFound

                else -> {
                    val exam = bundle.exams.firstOrNull { it.id == examId && it.status == ExamStatus.ACTIVE }
                    if (exam == null) {
                        ExamUiState.NotFound
                    } else {
                        ExamUiState.Ready(
                            exam,
                            exam.id in pinned,
                            exam.documents.map { it.type }.filter { type ->
                                saved.any { it.examId == exam.id && it.docType == type.name }
                            }.toSet(),
                        )
                    }
                }
            }
        }.combine(combine(drafts.revisions, refreshes) { revision, refresh -> revision to refresh }) { state, _ ->
            state
        }.transformLatest { state ->
            if (state !is ExamUiState.Ready) {
                emit(state)
            } else {
                emit(ExamUiState.Loading)
                val available = withContext(Dispatchers.IO) {
                    buildMap {
                        for (spec in state.exam.documents) {
                            val kind = DocKind.of(spec.type)
                            if (kind == DocKind.PDF_DOCUMENT) continue
                            val draft = drafts.read(state.exam.id, spec, kind) ?: continue
                            put(spec.type, (draft.bytes.size + KB / 2) / KB)
                        }
                    }
                }
                emit(state.copy(readyDocuments = available))
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
            ExamUiState.Loading,
        )

    fun refreshDrafts() {
        refreshes.value += 1
    }

    fun onTogglePin() {
        val ready = uiState.value as? ExamUiState.Ready ?: return
        viewModelScope.launch { preferences.setPinned(ready.exam.id, !ready.pinned) }
    }

    companion object {
        /** Navigation argument: `exam/{examId}`. */
        const val EXAM_ID = "examId"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val KB = 1024
    }
}
