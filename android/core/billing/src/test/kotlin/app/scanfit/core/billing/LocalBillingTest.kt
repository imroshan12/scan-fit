package app.scanfit.core.billing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBillingTest {
    @Test
    fun releaseStandInSellsNothing() = runTest {
        val billing = LocalBilling()
        assertEquals(ProductsState.Unavailable, billing.products())
        assertEquals(PurchaseOutcome.FAILED, billing.purchase(ProProduct.YEARLY))
        assertEquals(RestoreOutcome.FAILED, billing.restore())
        assertFalse(billing.isPro.value)
    }

    @Test
    fun debugProductsGrantProInMemory() = runTest {
        val billing = LocalBilling(LocalBilling.DEBUG_PRODUCTS)
        assertEquals(ProductsState.Ready(LocalBilling.DEBUG_PRODUCTS), billing.products())
        assertEquals(RestoreOutcome.NOTHING, billing.restore())
        assertEquals(PurchaseOutcome.PURCHASED, billing.purchase(ProProduct.LIFETIME))
        assertTrue(billing.isPro.value)
        assertEquals(RestoreOutcome.RESTORED, billing.restore())
    }

    @Test
    fun productIdsMatchTheStores() {
        assertEquals(listOf("pro_yearly", "pro_lifetime"), ProProduct.entries.map { it.id })
    }
}
