import Billing
import CryptoKit
import Foundation
import Observation

/// Why the paywall opened (ALGORITHMS 14.3); also the analytics `paywall_shown(trigger)` value.
public enum PaywallTrigger: String, Sendable, Identifiable {
    case save, share, saveAll = "save_all", settings
    public var id: String { rawValue }
}

/// One paywall for the whole app: a blocked save asks for it, the app's root shows it over the current screen.
@MainActor
@Observable
public final class PaywallRequests {
    public private(set) var current: PaywallTrigger?

    public init() {}

    public func open(_ trigger: PaywallTrigger) { current = trigger }
    public func close() { current = nil }
}

/// What screens show (ALGORITHMS 14.3): Pro, or how many free saves are left and whether a review shows it.
public struct QuotaStatus: Sendable, Equatable {
    public let pro: Bool
    public let remaining: Int
    public let showsLeft: Bool

    public init(pro: Bool, remaining: Int, showsLeft: Bool) {
        self.pro = pro
        self.remaining = remaining
        self.showsLeft = showsLeft
    }
}

/// The free-saves gate (ALGORITHMS 14). Flows call `allows` before a Save, Share or Save all (it opens the paywall when
/// the free saves are spent) and `record` once a file has left the app. The app's root calls `load()` at launch.
/// Identities are SHA-256 of the exact bytes and never leave the phone (CLAUDE.md rule 2).
@MainActor
@Observable
public final class SaveGate {
    /// `nil` until loaded.
    public private(set) var state: QuotaState?
    @ObservationIgnored private let store: any QuotaStore
    @ObservationIgnored private let billing: any Billing
    @ObservationIgnored public let paywall: PaywallRequests
    @ObservationIgnored private let total: @MainActor () -> Int

    /// The configured `free_saves_total` (the build's value now, Remote Config later, ARCHITECTURE §9).
    public nonisolated static let defaultFreeSavesTotal = 20

    /// The total a build carries (ALGORITHMS 14.2 "Where t comes from"): Info.plist `MRDFreeSavesTotal`, 200 in CI test
    /// builds. A missing, non-numeric or < 1 value means `defaultFreeSavesTotal`.
    public nonisolated static func buildTotal(_ raw: Any?) -> Int {
        let text: String? = switch raw {
        case let number as Int: String(number)
        case let string as String: string
        default: nil
        }
        guard let value = text.flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), value >= 1 else {
            return defaultFreeSavesTotal
        }
        return value
    }

    public init(
        store: any QuotaStore,
        billing: any Billing,
        paywall: PaywallRequests = PaywallRequests(),
        total: @escaping @MainActor () -> Int = { SaveGate.defaultFreeSavesTotal }
    ) {
        self.store = store
        self.billing = billing
        self.paywall = paywall
        self.total = total
    }

    /// `nil` until loaded. Observable: it follows the count and `Billing.isPro`.
    public var status: QuotaStatus? {
        guard let state else { return nil }
        let pro = billing.isPro
        return QuotaStatus(pro: pro, remaining: state.remaining, showsLeft: SaveQuotaRules.showsLeft(state, pro: pro))
    }

    /// Reads the stored state and applies the configured total (every launch, ALGORITHMS 14.2).
    public func load() async {
        let total = total()
        state = await store.update { SaveQuotaRules.total($0, total) }
    }

    /// True when `files` may leave the app; otherwise opens the paywall for `trigger` and returns false. It hashes only
    /// when the batch is larger than what is left. Before the first load it allows; `record` still counts the save.
    public func allows(_ files: [[UInt8]], trigger: PaywallTrigger) -> Bool {
        guard let state else { return true }
        let allowed = billing.isPro || files.count <= state.remaining
            || SaveQuotaRules.decide(state, files: files.map(Self.identity), pro: false).allowed
        if !allowed { paywall.open(trigger) }
        return allowed
    }

    /// Records one verified file that left the app (after §1.6 verification, or once the share sheet has it).
    public func record(_ bytes: [UInt8]) async {
        let hash = await Task.detached(priority: .userInitiated) { Self.identity(bytes) }.value
        let pro = billing.isPro
        let total = total()
        state = await store.update { SaveQuotaRules.record(SaveQuotaRules.total($0, total), file: hash, pro: pro) }
    }

    /// Lowercase hex SHA-256 of the exact bytes (ALGORITHMS 14.1).
    public nonisolated static func identity(_ bytes: [UInt8]) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
}
