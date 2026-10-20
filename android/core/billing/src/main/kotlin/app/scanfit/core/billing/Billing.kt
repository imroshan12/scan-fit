package app.scanfit.core.billing

import kotlinx.coroutines.flow.StateFlow

/** The two Pro products (PRD §6). The ids are the same in Play Console, App Store Connect and RevenueCat. */
enum class ProProduct(val id: String) {
    YEARLY("pro_yearly"),
    LIFETIME("pro_lifetime"),
}

/** A product as the store offers it: [price] is the store's own localised text, e.g. "₹99.00". */
data class StoreProduct(
    val product: ProProduct,
    val price: String,
)

sealed interface ProductsState {
    data object Loading : ProductsState

    /** No connection, no store account, or billing not set up: the paywall offers Retry. */
    data object Unavailable : ProductsState

    data class Ready(val products: List<StoreProduct>) : ProductsState
}

enum class PurchaseOutcome {
    PURCHASED,

    /** The user closed the store sheet: no message. */
    CANCELLED,

    /** Accepted but not paid yet (e.g. a UPI payment waiting for approval): Pro turns on when it completes. */
    PENDING,
    FAILED,
}

enum class RestoreOutcome { RESTORED, NOTHING, FAILED }

/** Pro purchases (ARCHITECTURE §8, ALGORITHMS §14). The `pro` entitlement is cached so it holds offline. */
interface Billing {
    /** True while the account holds the `pro` entitlement. */
    val isPro: StateFlow<Boolean>

    /** Fetches the products with their store prices. */
    suspend fun products(): ProductsState

    suspend fun purchase(product: ProProduct): PurchaseOutcome

    suspend fun restore(): RestoreOutcome
}
