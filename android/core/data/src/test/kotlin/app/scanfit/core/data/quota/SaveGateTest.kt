package app.scanfit.core.data.quota

import app.scanfit.core.billing.LocalBilling
import app.scanfit.core.billing.ProProduct
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveGateTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val billing = LocalBilling(LocalBilling.DEBUG_PRODUCTS)
    private val paywall = PaywallRequests()

    private fun gate(
        root: File = temp.root,
        total: Int = 3,
    ) = SaveGate(FileQuotaStore(root), billing, paywall) { total }

    private val a = byteArrayOf(1, 2, 3)
    private val b = byteArrayOf(4, 5, 6)

    @Test
    fun identityIsLowercaseSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SaveGate.identity("abc".toByteArray()),
        )
    }

    @Test
    fun countsEachFileOnceAndOpensThePaywallWhenSpent() = runTest {
        val gate = gate(total = 2)
        assertEquals(QuotaStatus(pro = false, remaining = 2, showsLeft = true), gate.status.first())
        assertTrue(gate.allows(listOf(a), PaywallTrigger.SAVE))
        gate.record(a)
        gate.record(a) // Share after Save of the same bytes is free.
        assertEquals(1, gate.status.first().remaining)
        gate.record(b)
        assertEquals(0, gate.status.first().remaining)
        assertTrue("known bytes stay free", gate.allows(listOf(a, b), PaywallTrigger.SAVE_ALL))
        assertNull(paywall.current.value)
        assertFalse(gate.allows(listOf(byteArrayOf(9)), PaywallTrigger.SHARE))
        assertEquals(PaywallTrigger.SHARE, paywall.current.value)
    }

    @Test
    fun allowsBeforeTheFirstLoadButStillCounts() = runTest {
        val gate = gate(total = 0)
        assertTrue(gate.allows(listOf(a), PaywallTrigger.SAVE))
        gate.record(a)
        assertEquals(0, gate.status.first().remaining)
        assertFalse(gate.allows(listOf(b), PaywallTrigger.SAVE))
    }

    @Test
    fun proIsNeverBlockedAndNotCounted() = runTest {
        val gate = gate(total = 0)
        gate.status.first()
        billing.purchase(ProProduct.YEARLY)
        assertTrue(gate.allows(listOf(a), PaywallTrigger.SAVE))
        gate.record(a)
        assertEquals(QuotaStatus(pro = true, remaining = 0, showsLeft = false), gate.status.first())
        assertNull(paywall.current.value)
    }

    @Test
    fun theCountSurvivesARelaunch() = runTest {
        val first = gate()
        first.record(a)
        first.record(b)
        val relaunched = gate()
        assertEquals(1, relaunched.status.first().remaining)
        assertTrue(File(temp.root, "quota.json").readText().contains(SaveGate.identity(a)))
    }

    @Test
    fun aCorruptRecordStartsAgain() = runTest {
        File(temp.root, "quota.json").writeText("{not json")
        assertEquals(3, gate().status.first().remaining)
        File(temp.root, "quota.json").writeText("""{"version":2,"used":1,"allowance":3,"hashes":[]}""")
        assertEquals(3, gate().status.first().remaining)
        File(temp.root, "quota.json").writeText("""{"version":1,"used":1,"allowance":3,"hashes":["../x"]}""")
        assertEquals(3, gate().status.first().remaining)
    }

    @Test
    fun aRaisedTotalAppliesAtOnceAndALoweredOneTakesNothingBack() = runTest {
        val first = gate(total = 3)
        first.record(a)
        assertEquals(4, gate(total = 5).status.first().remaining)
        assertEquals(4, gate(total = 1).status.first().remaining)
    }
}
