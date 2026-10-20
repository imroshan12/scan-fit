package app.scanfit.core.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The stand-in [Billing] until RevenueCat is wired (LAUNCH_PLAN week 1). With no [fakeProducts] (release builds) the
 * store is unavailable and nothing can be bought. Debug builds pass fake products whose purchase grants Pro for the
 * process only, so the paywall and the free-saves gate can be walked by hand. Nothing is charged or stored.
 */
class LocalBilling(
    private val fakeProducts: List<StoreProduct> = emptyList(),
) : Billing {
    private val pro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> = pro.asStateFlow()

    override suspend fun products(): ProductsState = when {
        fakeProducts.isEmpty() -> ProductsState.Unavailable
        else -> ProductsState.Ready(fakeProducts)
    }

    override suspend fun purchase(product: ProProduct): PurchaseOutcome {
        if (fakeProducts.none { it.product == product }) return PurchaseOutcome.FAILED
        pro.value = true
        return PurchaseOutcome.PURCHASED
    }

    override suspend fun restore(): RestoreOutcome = when {
        fakeProducts.isEmpty() -> RestoreOutcome.FAILED
        pro.value -> RestoreOutcome.RESTORED
        else -> RestoreOutcome.NOTHING
    }

    companion object {
        /** The launch prices (PRD §6), for debug builds only. */
        val DEBUG_PRODUCTS = listOf(
            StoreProduct(ProProduct.YEARLY, "₹99"),
            StoreProduct(ProProduct.LIFETIME, "₹199"),
        )
    }
}
