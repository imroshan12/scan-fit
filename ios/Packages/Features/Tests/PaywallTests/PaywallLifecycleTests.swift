import Billing
import Foundation
@testable import Paywall
import ScanData
import Testing
import TestSupport

@MainActor
@Suite("Paywall store-request lifecycle")
struct PaywallLifecycleTests {
    @Test("every shared lifecycle case holds")
    func sharedCases() async throws {
        let cases = try CasesFile.section("paywall_lifecycle_cases")
        #expect(cases.count == 3)
        for c in cases {
            let operation = try #require(c.string("operation"))
            let billing = DelayedBilling(held: operation)
            let requests = PaywallRequests()
            let model = PaywallViewModel(billing: billing, requests: requests)
            requests.open(.save)
            let work = Task {
                await model.appeared()
                if operation == "purchase" { await model.purchase(.yearly) }
                if operation == "restore" { await model.restore() }
            }
            await billing.started()
            model.close()
            requests.open(.settings)
            await model.appeared()
            let expect = try #require(c.dict("expect"))
            let busy = model.busy
            billing.finish(try #require(c.string("late_result")))
            await work.value
            #expect(busy == expect.bool("busy_reopened"), "\(c.string("id") ?? "")")
            #expect(model.message == nil)
            #expect(!model.busy)
            #expect(model.products == .ready(LocalBilling.debugProducts))
            #expect(billing.isPro == expect.bool("pro_after"))
        }
    }

    @Test("retry and duplicate actions do nothing while buying")
    func duplicateActions() async {
        let billing = DelayedBilling(held: "purchase")
        let model = PaywallViewModel(billing: billing, requests: PaywallRequests())
        await model.appeared()
        let work = Task { await model.purchase(.yearly) }
        await billing.started()
        await model.retry()
        await model.restore()
        await model.purchase(.lifetime)
        let counts = (billing.productLoads, billing.purchaseCalls, billing.restoreCalls)
        billing.finish("purchased")
        await work.value
        #expect(counts.0 == 1)
        #expect(counts.1 == 1)
        #expect(counts.2 == 0)
        #expect(model.message == .thanks)
    }

    @Test("a product outside the loaded offer cannot be purchased")
    func unavailableProduct() async {
        let billing = FakeBilling(products: .ready([]))
        let model = PaywallViewModel(billing: billing, requests: PaywallRequests())
        await model.appeared()
        await model.purchase(.yearly)
        #expect(billing.purchases.isEmpty)
    }
}

@MainActor
private final class DelayedBilling: Billing {
    private let base = FakeBilling()
    private let held: String
    private var result: CheckedContinuation<String, Never>?
    private var didStart: CheckedContinuation<Void, Never>?
    private(set) var productLoads = 0
    private(set) var purchaseCalls = 0
    private(set) var restoreCalls = 0
    var isPro: Bool { base.isPro }

    init(held: String) { self.held = held }

    func started() async {
        if result != nil { return }
        await withCheckedContinuation { didStart = $0 }
    }

    func finish(_ value: String) {
        result?.resume(returning: value)
        result = nil
    }

    private func wait() async -> String {
        await withCheckedContinuation {
            result = $0
            didStart?.resume()
            didStart = nil
        }
    }

    func products() async -> ProductsState {
        productLoads += 1
        if held == "products", productLoads == 1 {
            let value = await wait()
            precondition(value == "unavailable")
            return .unavailable
        }
        return await base.products()
    }

    func purchase(_ product: ProProduct) async -> PurchaseOutcome {
        purchaseCalls += 1
        if held == "purchase" {
            let value = await wait()
            precondition(value == "purchased")
        }
        return await base.purchase(product)
    }

    func restore() async -> RestoreOutcome {
        restoreCalls += 1
        if held == "restore" {
            let value = await wait()
            precondition(value == "nothing")
        }
        return await base.restore()
    }
}
