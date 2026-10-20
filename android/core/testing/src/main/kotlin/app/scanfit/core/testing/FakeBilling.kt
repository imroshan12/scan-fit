package app.scanfit.core.testing

import app.scanfit.core.billing.Billing
import app.scanfit.core.billing.LocalBilling
import app.scanfit.core.billing.ProProduct
import app.scanfit.core.billing.ProductsState
import app.scanfit.core.billing.PurchaseOutcome
import app.scanfit.core.billing.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow

/** Scriptable [Billing]: set what the store answers; a purchase or restore that returns success grants Pro. */
class FakeBilling(
    var products: ProductsState = ProductsState.Ready(LocalBilling.DEBUG_PRODUCTS),
    var purchaseOutcome: PurchaseOutcome = PurchaseOutcome.PURCHASED,
    var restoreOutcome: RestoreOutcome = RestoreOutcome.NOTHING,
) : Billing {
    override val isPro = MutableStateFlow(false)
    val purchases = mutableListOf<ProProduct>()

    override suspend fun products(): ProductsState = products

    override suspend fun purchase(product: ProProduct): PurchaseOutcome {
        purchases += product
        if (purchaseOutcome == PurchaseOutcome.PURCHASED) isPro.value = true
        return purchaseOutcome
    }

    override suspend fun restore(): RestoreOutcome {
        if (restoreOutcome == RestoreOutcome.RESTORED) isPro.value = true
        return restoreOutcome
    }
}
