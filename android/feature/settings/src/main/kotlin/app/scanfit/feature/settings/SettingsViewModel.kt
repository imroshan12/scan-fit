package app.scanfit.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings state. Pro, privacy and help arrive with their phases. */
data class SettingsUiState(
    val presetsVersion: Int?,
    val showUnverified: Boolean = true,
)

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    presets: PresetsRepository,
    private val preferences: UserPreferences,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> =
        combine(presets.outcome, preferences.showUnverified) { outcome, showUnverified ->
            SettingsUiState((outcome as? PresetsLoadOutcome.Ready)?.summary?.version, showUnverified)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(null))

    fun onShowUnverifiedChange(show: Boolean) {
        viewModelScope.launch { preferences.setShowUnverified(show) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
