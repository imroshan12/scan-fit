package app.scanfit.core.testing

import app.scanfit.core.billing.Billing
import app.scanfit.core.billing.LocalBilling
import app.scanfit.core.data.quota.PaywallRequests
import app.scanfit.core.data.quota.QuotaConfig
import app.scanfit.core.data.quota.QuotaState
import app.scanfit.core.data.quota.QuotaStore
import app.scanfit.core.data.quota.SaveGate

/** [QuotaStore] in memory. */
class FakeQuotaStore(
    var state: QuotaState = QuotaState(),
) : QuotaStore {
    override suspend fun update(transform: (QuotaState) -> QuotaState): QuotaState {
        state = transform(state)
        return state
    }
}

/** A real [SaveGate] over a [FakeQuotaStore] with [used] of 20 free saves spent, and the paywall requests it opens. */
class TestSaveGate(
    used: Int = 0,
    total: Int = QuotaConfig.DEFAULT_FREE_SAVES_TOTAL,
    val billing: Billing = LocalBilling(LocalBilling.DEBUG_PRODUCTS),
) {
    val store = FakeQuotaStore(QuotaState(used = used, allowance = total))
    val paywall = PaywallRequests()
    val gate = SaveGate(store, billing, paywall) { total }
}
