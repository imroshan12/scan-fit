import Billing
@testable import Paywall
import ScanData
import Testing
import TestSupport

@MainActor
@Suite("Paywall")
struct PaywallViewModelTests {
    @Test("loads the products each time it appears, and closes")
    func appearsAndCloses() async {
        let billing = FakeBilling()
        let requests = PaywallRequests()
        let model = PaywallViewModel(billing: billing, requests: requests)
        requests.open(.save)
        #expect(model.trigger == .save)
        #expect(model.products == .loading)
        await model.appeared()
        #expect(model.products == .ready(LocalBilling.debugProducts))
        model.close()
        #expect(requests.current == nil)
    }

    @Test("an unavailable store offers retry and sells nothing")
    func unavailable() async {
        let billing = FakeBilling(products: .unavailable)
        let model = PaywallViewModel(billing: billing, requests: PaywallRequests())
        await model.appeared()
        #expect(model.products == .unavailable)
        await model.purchase(.yearly)
        #expect(billing.purchases.isEmpty)
        billing.productsState = .ready([])
        await model.retry()
        #expect(model.products == .ready([]))
    }

    @Test("a purchase thanks and lets the blocked save through")
    func purchase() async {
        let billing = FakeBilling()
        let gate = await testSaveGate(used: 20, billing: billing)
        let model = PaywallViewModel(billing: billing, requests: gate.paywall)
        #expect(!gate.allows([[1]], trigger: .save))
        await model.appeared()
        await model.purchase(.lifetime)
        #expect(billing.purchases == [.lifetime])
        #expect(model.message == .thanks)
        #expect(!model.busy)
        #expect(gate.allows([[1]], trigger: .save))
    }

    @Test("cancelled, pending and failed purchases")
    func outcomes() async {
        let billing = FakeBilling()
        let model = PaywallViewModel(billing: billing, requests: PaywallRequests())
        await model.appeared()
        billing.purchaseOutcome = .cancelled
        await model.purchase(.yearly)
        #expect(model.message == nil, "a cancelled purchase says nothing")
        billing.purchaseOutcome = .pending
        await model.purchase(.yearly)
        #expect(model.message == .pending)
        billing.purchaseOutcome = .failed
        await model.purchase(.yearly)
        #expect(model.message == .failed)
    }

    @Test("restore outcomes")
    func restore() async {
        let billing = FakeBilling()
        let model = PaywallViewModel(billing: billing, requests: PaywallRequests())
        await model.restore()
        #expect(model.message == .restoreNone)
        billing.restoreOutcome = .failed
        await model.restore()
        #expect(model.message == .unavailable)
        billing.restoreOutcome = .restored
        await model.restore()
        #expect(model.message == .thanks)
    }
}
