package app.scanfit.core.data.quota

/**
 * The free-saves state (ALGORITHMS 14.2). [hashes] are the SHA-256 hex identities of files that left the app, oldest
 * first.
 */
data class QuotaState(
    val used: Int = 0,
    val allowance: Int = 0,
    val hashes: List<String> = emptyList(),
) {
    val remaining: Int get() = maxOf(0, allowance - used)
}

/** Whether a Save, Share or Save all may go ahead, and how many of its files are new to the app. */
data class QuotaDecision(
    val allowed: Boolean,
    val newFiles: Int,
)

/** ALGORITHMS 14.2, pure. Conformance: `save_quota_cases`; reference `spec/tools/quota.py`. */
object SaveQuotaRules {
    const val MAX_HASHES = 1000
    const val SHOWS_LEFT_AT = 5

    /** Raises the allowance to [total]; never lowers it (saves a user has been shown are not taken back). */
    fun total(
        state: QuotaState,
        total: Int,
    ): QuotaState = if (total < 0) state else state.copy(allowance = maxOf(state.allowance, total))

    fun decide(
        state: QuotaState,
        files: List<String>,
        pro: Boolean,
    ): QuotaDecision {
        val known = state.hashes.toSet()
        val new = files.distinct().count { it !in known }
        return QuotaDecision(pro || new == 0 || new <= state.remaining, new)
    }

    /** Records one file that left the app. Never refuses: a race past the last free save may count twice. */
    fun record(
        state: QuotaState,
        file: String,
        pro: Boolean,
        maxHashes: Int = MAX_HASHES,
    ): QuotaState {
        if (file in state.hashes) return state.copy(hashes = state.hashes - file + file)
        return QuotaState(
            used = if (pro) state.used else state.used + 1,
            allowance = state.allowance,
            hashes = (state.hashes + file).takeLast(maxHashes),
        )
    }

    /** The account's copy and the phone's keep the higher count. */
    fun merge(
        state: QuotaState,
        remoteUsed: Int,
    ): QuotaState = if (remoteUsed < 0) state else state.copy(used = maxOf(state.used, remoteUsed))

    fun showsLeft(
        state: QuotaState,
        pro: Boolean,
    ): Boolean = !pro && state.remaining <= SHOWS_LEFT_AT
}
