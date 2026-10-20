import Billing
import Observation
import ScanData

/// Scriptable `Billing`: set what the store answers; a purchase or restore that succeeds grants Pro.
@MainActor
@Observable
public final class FakeBilling: Billing {
    public var isPro = false
    public var productsState: ProductsState
    public var purchaseOutcome: PurchaseOutcome
    public var restoreOutcome: RestoreOutcome
    public private(set) var purchases: [ProProduct] = []

    public init(
        products: ProductsState = .ready(LocalBilling.debugProducts),
        purchaseOutcome: PurchaseOutcome = .purchased,
        restoreOutcome: RestoreOutcome = .nothing
    ) {
        productsState = products
        self.purchaseOutcome = purchaseOutcome
        self.restoreOutcome = restoreOutcome
    }

    public func products() async -> ProductsState { productsState }

    public func purchase(_ product: ProProduct) async -> PurchaseOutcome {
        purchases.append(product)
        if purchaseOutcome == .purchased { isPro = true }
        return purchaseOutcome
    }

    public func restore() async -> RestoreOutcome {
        if restoreOutcome == .restored { isPro = true }
        return restoreOutcome
    }
}

/// A real, loaded `SaveGate` over memory with `used` of `total` free saves spent.
@MainActor
public func testSaveGate(used: Int = 0, total: Int = 20, billing: any Billing = FakeBilling()) async -> SaveGate {
    let gate = SaveGate(store: MemoryQuotaStore(QuotaState(used: used, allowance: total)), billing: billing) { total }
    await gate.load()
    return gate
}
