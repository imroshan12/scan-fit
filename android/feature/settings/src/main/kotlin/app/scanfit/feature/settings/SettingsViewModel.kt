package app.scanfit.feature.settings

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

/** Settings state. Pro, privacy, help and "Show unverified" arrive with their phases. */
data class SettingsUiState(
    val presetsVersion: Int?,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        presets: PresetsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<SettingsUiState> =
            presets.outcome
                .map { SettingsUiState((it as? PresetsLoadOutcome.Ready)?.summary?.version) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(null))

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
