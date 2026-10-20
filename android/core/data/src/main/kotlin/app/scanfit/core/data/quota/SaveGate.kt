package app.scanfit.core.data.quota

import app.scanfit.core.billing.Billing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Why the paywall opened (ALGORITHMS 14.3); also the analytics `paywall_shown(trigger)` value. */
enum class PaywallTrigger { SAVE, SHARE, SAVE_ALL, SETTINGS }

/** One paywall for the whole app: a blocked save asks for it, the app's root shows it over the current screen. */
@Singleton
class PaywallRequests
@Inject
constructor() {
    private val _current = MutableStateFlow<PaywallTrigger?>(null)
    val current: StateFlow<PaywallTrigger?> = _current.asStateFlow()

    fun open(trigger: PaywallTrigger) {
        _current.value = trigger
    }

    fun close() {
        _current.value = null
    }
}

/** The configured `free_saves_total` (Remote Config later, ARCHITECTURE §9). */
fun interface QuotaConfig {
    fun freeSavesTotal(): Int

    companion object {
        const val DEFAULT_FREE_SAVES_TOTAL = 20
    }
}

/** What screens show (ALGORITHMS 14.3): Pro, or how many free saves are left and whether a review shows it. */
data class QuotaStatus(
    val pro: Boolean,
    val remaining: Int,
    val showsLeft: Boolean,
)

/**
 * The free-saves gate (ALGORITHMS 14). Flows call [allows] before a Save, Share or Save all (it opens the paywall when
 * the free saves are spent) and [record] once a file has left the app; collecting [status] keeps the state loaded.
 * Identities are SHA-256 of the exact bytes and never leave the phone (CLAUDE.md rule 2).
 */
@Singleton
class SaveGate
@Inject
constructor(
    private val store: QuotaStore,
    private val billing: Billing,
    private val paywall: PaywallRequests,
    private val config: QuotaConfig,
) {
    private val state = MutableStateFlow<QuotaState?>(null)

    val status: Flow<QuotaStatus> =
        combine(state.filterNotNull(), billing.isPro) { quota, pro ->
            QuotaStatus(pro, quota.remaining, SaveQuotaRules.showsLeft(quota, pro))
        }.onStart { load() }.distinctUntilChanged()

    /**
     * True when [files] may leave the app; otherwise opens the paywall for [trigger] and returns false. Not suspending,
     * so a Save can still launch the system picker from the tap: it reads the state [status] keeps loaded, and hashes
     * only when the batch is larger than what is left (near or past the last free save). Before the first load it
     * allows; [record] still counts the save.
     */
    fun allows(
        files: List<ByteArray>,
        trigger: PaywallTrigger,
    ): Boolean {
        val quota = state.value ?: return true
        val allowed = billing.isPro.value || files.size <= quota.remaining ||
            SaveQuotaRules.decide(quota, files.map(::identity), pro = false).allowed
        if (!allowed) paywall.open(trigger)
        return allowed
    }

    /**
     * Records one verified file that left the app (after §1.6 verification, or once the share sheet has it). It hashes
     * [bytes]: call it off the main thread, on the flow's work dispatcher.
     */
    suspend fun record(bytes: ByteArray) {
        val hash = identity(bytes)
        val pro = billing.isPro.value
        val total = config.freeSavesTotal()
        state.value = store.update { SaveQuotaRules.record(SaveQuotaRules.total(it, total), hash, pro) }
    }

    private suspend fun load(): QuotaState {
        val total = config.freeSavesTotal()
        return store.update { SaveQuotaRules.total(it, total) }.also { state.value = it }
    }

    companion object {
        /** Lowercase hex SHA-256 of the exact bytes (ALGORITHMS 14.1). */
        fun identity(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
