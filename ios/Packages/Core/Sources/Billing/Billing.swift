import Foundation
import Observation

/// The two Pro products (PRD §6). The ids are the same in App Store Connect, Play Console and RevenueCat.
public enum ProProduct: String, CaseIterable, Sendable {
    case yearly = "pro_yearly"
    case lifetime = "pro_lifetime"
}

/// A product as the store offers it: `price` is the store's own localised text, e.g. "₹99.00".
public struct StoreProduct: Sendable, Equatable {
    public let product: ProProduct
    public let price: String

    public init(product: ProProduct, price: String) {
        self.product = product
        self.price = price
    }
}

public enum ProductsState: Sendable, Equatable {
    case loading
    /// No connection, no store account, or billing not set up: the paywall offers Retry.
    case unavailable
    case ready([StoreProduct])
}

public enum PurchaseOutcome: Sendable, Equatable {
    case purchased
    /// The user closed the store sheet: no message.
    case cancelled
    /// Accepted but not paid yet (e.g. Ask to Buy, or a payment waiting for approval): Pro turns on when it completes.
    case pending
    case failed
}

public enum RestoreOutcome: Sendable, Equatable {
    case restored, nothing, failed
}

/// Pro purchases (ARCHITECTURE §8, ALGORITHMS §14). The `pro` entitlement is cached so it holds offline.
/// Implementations are `@Observable`, so views and the free-saves gate follow `isPro`.
@MainActor
public protocol Billing: AnyObject, Sendable {
    /// True while the account holds the `pro` entitlement.
    var isPro: Bool { get }
    /// Fetches the products with their store prices.
    func products() async -> ProductsState
    func purchase(_ product: ProProduct) async -> PurchaseOutcome
    func restore() async -> RestoreOutcome
}

/// The stand-in `Billing` until RevenueCat is wired (LAUNCH_PLAN week 1). With no `fakeProducts` (release builds) the
/// store is unavailable and nothing can be bought. Debug builds pass fake products whose purchase grants Pro for the
/// process only, so the paywall and the free-saves gate can be walked by hand. Nothing is charged or stored.
@MainActor
@Observable
public final class LocalBilling: Billing {
    public private(set) var isPro = false
    @ObservationIgnored private let fakeProducts: [StoreProduct]

    /// The launch prices (PRD §6), for debug builds only.
    public static let debugProducts = [
        StoreProduct(product: .yearly, price: "₹99"),
        StoreProduct(product: .lifetime, price: "₹199"),
    ]

    public init(fakeProducts: [StoreProduct] = []) {
        self.fakeProducts = fakeProducts
    }

    public func products() async -> ProductsState {
        fakeProducts.isEmpty ? .unavailable : .ready(fakeProducts)
    }

    public func purchase(_ product: ProProduct) async -> PurchaseOutcome {
        guard fakeProducts.contains(where: { $0.product == product }) else { return .failed }
        isPro = true
        return .purchased
    }

    public func restore() async -> RestoreOutcome {
        if fakeProducts.isEmpty { return .failed }
        return isPro ? .restored : .nothing
    }
}
