import Billing
import Foundation
import Observation
import ScanData

/// What the paywall says after an action (ALGORITHMS 14.3). A cancelled purchase says nothing.
public enum PaywallMessage: Sendable, Equatable {
    case thanks, pending, failed, restoreNone, unavailable
}

/// The paywall (ALGORITHMS 14.3), shown by the app's root whenever `PaywallRequests` asks (a blocked save, or
/// Settings). It loads the products each time it appears.
@MainActor
@Observable
public final class PaywallViewModel {
    public private(set) var products: ProductsState = .loading
    /// A purchase or restore is running: both products and Restore are disabled.
    public private(set) var busy = false
    public private(set) var message: PaywallMessage?
    @ObservationIgnored private let billing: any Billing
    @ObservationIgnored private let requests: PaywallRequests
    @ObservationIgnored private var session = 0
    @ObservationIgnored private var loadVersion = 0
    @ObservationIgnored private var presented = false

    public init(billing: any Billing, requests: PaywallRequests) {
        self.billing = billing
        self.requests = requests
    }

    public var trigger: PaywallTrigger? { requests.current }

    /// Called when the paywall appears: a fresh offer every time.
    public func appeared() async {
        session += 1
        presented = true
        products = .loading
        message = nil
        await loadProducts()
    }

    public func retry() async {
        guard presented, !busy else { return }
        products = .loading
        message = nil
        await loadProducts()
    }

    public func purchase(_ product: ProProduct) async {
        guard presented, !busy, case let .ready(offer) = products,
              offer.contains(where: { $0.product == product }) else { return }
        let startedIn = session
        busy = true
        message = nil
        let result: PaywallMessage? = switch await billing.purchase(product) {
        case .purchased: .thanks
        case .pending: .pending
        case .failed: .failed
        case .cancelled: nil
        }
        busy = false
        if presented, startedIn == session { message = result }
    }

    public func restore() async {
        guard !busy else { return }
        let startedIn = session
        busy = true
        message = nil
        let result: PaywallMessage = switch await billing.restore() {
        case .restored: billing.isPro ? .thanks : .restoreNone
        case .nothing: .restoreNone
        case .failed: .unavailable
        }
        busy = false
        if startedIn == session { message = result }
    }

    /// Closing changes nothing else: the review, its draft and its save state stay as they were.
    public func close() {
        session += 1
        presented = false
        message = nil
        requests.close()
    }

    private func loadProducts() async {
        let startedIn = session
        loadVersion += 1
        let version = loadVersion
        let result = await billing.products()
        if presented, startedIn == session, version == loadVersion { products = result }
    }
}
