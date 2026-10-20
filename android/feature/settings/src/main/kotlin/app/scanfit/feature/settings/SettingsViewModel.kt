package app.scanfit.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.billing.Billing
import app.scanfit.core.billing.RestoreOutcome
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.quota.PaywallRequests
import app.scanfit.core.data.quota.PaywallTrigger
import app.scanfit.core.data.quota.QuotaStatus
import app.scanfit.core.data.quota.SaveGate
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What "Restore purchases" in Settings found (ALGORITHMS 14.3). */
enum class RestoreState { IDLE, RESTORING, RESTORED, NOTHING, FAILED }

/** Settings (ROADMAP Phase 3): the app, Pro and free saves, privacy, help, about. */
data class SettingsUiState(
    val presetsVersion: Int?,
    val showUnverified: Boolean = true,
    /** `null` until the free-saves state has loaded. */
    val quota: QuotaStatus? = null,
    val analyticsEnabled: Boolean = true,
    val restore: RestoreState = RestoreState.IDLE,
)

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    presets: PresetsRepository,
    private val preferences: UserPreferences,
    gate: SaveGate,
    private val billing: Billing,
    private val paywall: PaywallRequests,
) : ViewModel() {
    private val restore = MutableStateFlow(RestoreState.IDLE)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            presets.outcome,
            preferences.showUnverified,
            gate.status.map<QuotaStatus, QuotaStatus?> { it }.onStart { emit(null) },
            preferences.analyticsEnabled,
            restore,
        ) { outcome, showUnverified, quota, analytics, restoring ->
            SettingsUiState(
                presetsVersion = (outcome as? PresetsLoadOutcome.Ready)?.summary?.version,
                showUnverified = showUnverified,
                quota = quota,
                analyticsEnabled = analytics,
                restore = restoring,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(null))

    fun onShowUnverifiedChange(show: Boolean) {
        viewModelScope.launch { preferences.setShowUnverified(show) }
    }

    fun onAnalyticsChange(enabled: Boolean) {
        viewModelScope.launch { preferences.setAnalyticsEnabled(enabled) }
    }

    fun onGetPro() = paywall.open(PaywallTrigger.SETTINGS)

    fun onRestore() {
        if (restore.value == RestoreState.RESTORING) return
        restore.value = RestoreState.RESTORING
        viewModelScope.launch {
            restore.value = when (billing.restore()) {
                RestoreOutcome.RESTORED -> if (billing.isPro.value) RestoreState.RESTORED else RestoreState.NOTHING
                RestoreOutcome.NOTHING -> RestoreState.NOTHING
                RestoreOutcome.FAILED -> RestoreState.FAILED
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
