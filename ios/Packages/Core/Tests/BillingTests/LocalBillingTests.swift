@testable import Billing
import Testing

@MainActor
@Suite("Local billing stand-in")
struct LocalBillingTests {
    @Test("release builds sell nothing")
    func releaseSellsNothing() async {
        let billing = LocalBilling()
        #expect(await billing.products() == .unavailable)
        #expect(await billing.purchase(.yearly) == .failed)
        #expect(await billing.restore() == .failed)
        #expect(!billing.isPro)
    }

    @Test("debug products grant Pro in memory")
    func debugGrantsPro() async {
        let billing = LocalBilling(fakeProducts: LocalBilling.debugProducts)
        #expect(await billing.products() == .ready(LocalBilling.debugProducts))
        #expect(await billing.restore() == .nothing)
        #expect(await billing.purchase(.lifetime) == .purchased)
        #expect(billing.isPro)
        #expect(await billing.restore() == .restored)
    }

    @Test("product ids match the stores")
    func ids() {
        #expect(ProProduct.allCases.map(\.rawValue) == ["pro_yearly", "pro_lifetime"])
    }
}
