package app.scanfit.feature.paywall

import app.scanfit.core.billing.ProProduct
import app.scanfit.core.billing.ProductsState
import app.scanfit.core.billing.PurchaseOutcome
import app.scanfit.core.billing.RestoreOutcome
import app.scanfit.core.data.quota.PaywallTrigger
import app.scanfit.core.testing.FakeBilling
import app.scanfit.core.testing.TestSaveGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val billing = FakeBilling()

    private fun model(used: Int = 0): Pair<PaywallViewModel, TestSaveGate> {
        val quota = TestSaveGate(used = used, billing = billing)
        return PaywallViewModel(billing, quota.paywall, quota.gate) to quota
    }

    @Test
    fun closedUntilAskedThenLoadsTheProducts() = runTest {
        val (model, quota) = model()
        assertNull(model.uiState.value.trigger)
        quota.paywall.open(PaywallTrigger.SAVE)
        assertEquals(PaywallTrigger.SAVE, model.uiState.value.trigger)
        assertEquals(billing.products, model.uiState.value.products)
        model.onClose()
        assertEquals(PaywallUiState(), model.uiState.value)
        assertNull(quota.paywall.current.value)
    }

    @Test
    fun anUnavailableStoreOffersRetry() = runTest {
        billing.products = ProductsState.Unavailable
        val (model, quota) = model()
        quota.paywall.open(PaywallTrigger.SETTINGS)
        assertEquals(ProductsState.Unavailable, model.uiState.value.products)
        model.onPurchase(ProProduct.YEARLY)
        assertTrue("nothing to buy", billing.purchases.isEmpty())
        billing.products = ProductsState.Ready(emptyList())
        model.onRetry()
        assertEquals(ProductsState.Ready(emptyList()), model.uiState.value.products)
    }

    @Test
    fun aPurchaseThanksAndTurnsProOn() = runTest {
        val (model, quota) = model(used = 20)
        quota.paywall.open(PaywallTrigger.SAVE)
        model.onPurchase(ProProduct.LIFETIME)
        assertEquals(listOf(ProProduct.LIFETIME), billing.purchases)
        assertEquals(PaywallMessage.THANKS, model.uiState.value.message)
        assertFalse(model.uiState.value.busy)
        assertTrue(quota.gate.allows(listOf(byteArrayOf(1)), PaywallTrigger.SAVE))
    }

    @Test
    fun cancelledPendingAndFailedPurchases() = runTest {
        val (model, quota) = model()
        quota.paywall.open(PaywallTrigger.SHARE)
        billing.purchaseOutcome = PurchaseOutcome.CANCELLED
        model.onPurchase(ProProduct.YEARLY)
        assertNull("a cancelled purchase says nothing", model.uiState.value.message)
        billing.purchaseOutcome = PurchaseOutcome.PENDING
        model.onPurchase(ProProduct.YEARLY)
        assertEquals(PaywallMessage.PENDING, model.uiState.value.message)
        billing.purchaseOutcome = PurchaseOutcome.FAILED
        model.onPurchase(ProProduct.YEARLY)
        assertEquals(PaywallMessage.FAILED, model.uiState.value.message)
        assertEquals(PaywallTrigger.SHARE, model.uiState.value.trigger)
    }

    @Test
    fun restoreOutcomes() = runTest {
        val (model, quota) = model()
        quota.paywall.open(PaywallTrigger.SETTINGS)
        model.onRestore()
        assertEquals(PaywallMessage.RESTORE_NONE, model.uiState.value.message)
        billing.restoreOutcome = RestoreOutcome.FAILED
        model.onRestore()
        assertEquals(PaywallMessage.UNAVAILABLE, model.uiState.value.message)
        billing.restoreOutcome = RestoreOutcome.RESTORED
        model.onRestore()
        assertEquals(PaywallMessage.THANKS, model.uiState.value.message)
    }

    @Test
    fun freeSavesLeftShowAtFiveOrFewerAndNeverForPro() = runTest {
        assertNull(model(used = 14).first.freeSavesLeft.value)
        val (model, _) = model(used = 17)
        assertEquals(3, model.freeSavesLeft.value)
        billing.isPro.value = true
        assertNull(model.freeSavesLeft.value)
    }
}
