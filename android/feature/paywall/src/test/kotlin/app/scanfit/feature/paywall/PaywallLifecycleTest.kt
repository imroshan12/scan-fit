package app.scanfit.feature.paywall

import app.scanfit.core.billing.Billing
import app.scanfit.core.billing.LocalBilling
import app.scanfit.core.billing.ProProduct
import app.scanfit.core.billing.ProductsState
import app.scanfit.core.billing.PurchaseOutcome
import app.scanfit.core.billing.RestoreOutcome
import app.scanfit.core.data.quota.PaywallTrigger
import app.scanfit.core.testing.FakeBilling
import app.scanfit.core.testing.TestSaveGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallLifecycleTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun everySharedLifecycleCaseHolds() = runTest {
        val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
        val cases = Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject
            .getValue("paywall_lifecycle_cases").jsonArray
        assertEquals(3, cases.size)
        for (element in cases) {
            val c = element.jsonObject
            val id = c.getValue("id").jsonPrimitive.content
            val operation = c.getValue("operation").jsonPrimitive.content
            val billing = DelayedBilling(operation)
            val quota = TestSaveGate(billing = billing)
            val model = PaywallViewModel(billing, quota.paywall, quota.gate)
            quota.paywall.open(PaywallTrigger.SAVE)
            when (operation) {
                "purchase" -> model.onPurchase(ProProduct.YEARLY)
                "restore" -> model.onRestore()
            }
            model.onClose()
            quota.paywall.open(PaywallTrigger.SETTINGS)
            val expect = c.getValue("expect").jsonObject
            assertEquals(id, expect.getValue("busy_reopened").jsonPrimitive.boolean, model.uiState.value.busy)
            billing.finish.complete(c.getValue("late_result").jsonPrimitive.content)
            assertNull(id, model.uiState.value.message)
            assertFalse(id, model.uiState.value.busy)
            assertEquals(id, ProductsState.Ready(LocalBilling.DEBUG_PRODUCTS), model.uiState.value.products)
            assertEquals(id, expect.getValue("pro_after").jsonPrimitive.boolean, billing.isPro.value)
            model.onClose()
        }
    }

    @Test
    fun retryAndDuplicateActionsDoNothingWhileBuying() = runTest {
        val billing = DelayedBilling("purchase")
        val quota = TestSaveGate(billing = billing)
        val model = PaywallViewModel(billing, quota.paywall, quota.gate)
        quota.paywall.open(PaywallTrigger.SAVE)
        model.onPurchase(ProProduct.YEARLY)
        model.onRetry()
        model.onRestore()
        model.onPurchase(ProProduct.LIFETIME)
        assertEquals(1, billing.productLoads)
        assertEquals(1, billing.purchaseCalls)
        assertEquals(0, billing.restoreCalls)
        billing.finish.complete("purchased")
        assertEquals(PaywallMessage.THANKS, model.uiState.value.message)
    }

    @Test
    fun aProductOutsideTheOfferCannotBePurchased() = runTest {
        val billing = FakeBilling(products = ProductsState.Ready(emptyList()))
        val quota = TestSaveGate(billing = billing)
        val model = PaywallViewModel(billing, quota.paywall, quota.gate)
        quota.paywall.open(PaywallTrigger.SETTINGS)
        model.onPurchase(ProProduct.YEARLY)
        assertEquals(emptyList<ProProduct>(), billing.purchases)
    }
}

private class DelayedBilling(private val held: String) : Billing {
    private val base = FakeBilling()
    override val isPro = base.isPro
    val finish = CompletableDeferred<String>()
    var productLoads = 0
    var purchaseCalls = 0
    var restoreCalls = 0

    override suspend fun products(): ProductsState {
        productLoads++
        if (held == "products" && productLoads == 1) {
            check(withContext(NonCancellable) { finish.await() } == "unavailable")
            return ProductsState.Unavailable
        }
        return base.products()
    }

    override suspend fun purchase(product: ProProduct): PurchaseOutcome {
        purchaseCalls++
        if (held == "purchase") check(finish.await() == "purchased")
        return base.purchase(product)
    }

    override suspend fun restore(): RestoreOutcome {
        restoreCalls++
        if (held == "restore") check(finish.await() == "nothing")
        return base.restore()
    }
}
