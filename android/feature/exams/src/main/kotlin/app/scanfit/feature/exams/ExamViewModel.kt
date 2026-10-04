package app.scanfit.feature.exams

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.model.Exam
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The exam checklist (UI_UX §3). Immutable: the single source of truth for the screen. */
sealed interface ExamUiState {
    data object Loading : ExamUiState

    /** The id is not in the trusted presets (a stale link, or a retired exam). */
    data object NotFound : ExamUiState

    data class Ready(
        val exam: Exam,
        val pinned: Boolean,
    ) : ExamUiState
}

@HiltViewModel
class ExamViewModel
@Inject
constructor(
    savedState: SavedStateHandle,
    presets: PresetsRepository,
    private val preferences: UserPreferences,
) : ViewModel() {
    private val examId: String = savedState.get<String>(EXAM_ID).orEmpty()

    val uiState: StateFlow<ExamUiState> =
        combine(presets.outcome, presets.bundle, preferences.pinnedExamIds) { outcome, bundle, pinned ->
            when {
                bundle == null -> if (outcome == null) ExamUiState.Loading else ExamUiState.NotFound

                else -> {
                    val exam = bundle.exams.firstOrNull { it.id == examId }
                    if (exam == null) ExamUiState.NotFound else ExamUiState.Ready(exam, exam.id in pinned)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExamUiState.Loading)

    fun onTogglePin() {
        val ready = uiState.value as? ExamUiState.Ready ?: return
        viewModelScope.launch { preferences.setPinned(ready.exam.id, !ready.pinned) }
    }

    companion object {
        /** Navigation argument: `exam/{examId}`. */
        const val EXAM_ID = "examId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
