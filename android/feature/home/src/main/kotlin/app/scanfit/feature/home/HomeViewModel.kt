package app.scanfit.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Home screen state. Phase 0 shows the embedded-presets status, which is the exit-gate proof that the signed
 * snapshot loaded; search, categories and pinned exams arrive in Phase 2. Immutable: the single source of truth.
 */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Ready(
        val examCount: Int,
        val version: Int,
    ) : HomeUiState

    /** Verification failed or the snapshot is missing. The user sees a plain message, never a raw error. */
    data object Failed : HomeUiState
}

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        presets: PresetsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<HomeUiState> =
            presets.outcome
                .map { outcome ->
                    when (outcome) {
                        null -> {
                            HomeUiState.Loading
                        }

                        is PresetsLoadOutcome.Ready -> {
                            HomeUiState.Ready(
                                outcome.summary.examCount,
                                outcome.summary.version,
                            )
                        }

                        is PresetsLoadOutcome.Failed, PresetsLoadOutcome.MissingEmbedded -> {
                            HomeUiState.Failed
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState.Loading)

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
