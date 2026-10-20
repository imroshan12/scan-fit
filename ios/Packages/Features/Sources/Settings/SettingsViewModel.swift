import Billing
import Config
import Foundation
import Observation
import ScanData

/// What "Restore purchases" in Settings found (ALGORITHMS 14.3).
public enum RestoreState: Sendable, Equatable {
    case idle, restoring, restored, nothing, failed
}

/// Settings (ROADMAP Phase 3): the app, Pro and free saves, privacy, help, about.
@MainActor
@Observable
public final class SettingsViewModel {
    public let preferences: UserPreferences
    public private(set) var restore: RestoreState = .idle
    @ObservationIgnored private let gate: SaveGate
    @ObservationIgnored private let billing: any Billing

    public init(preferences: UserPreferences, gate: SaveGate, billing: any Billing) {
        self.preferences = preferences
        self.gate = gate
        self.billing = billing
    }

    /// `nil` until the free-saves state has loaded.
    public var quota: QuotaStatus? { gate.status }

    public func getPro() {
        gate.paywall.open(.settings)
    }

    public func restorePurchases() async {
        guard restore != .restoring else { return }
        restore = .restoring
        restore = switch await billing.restore() {
        case .restored: billing.isPro ? .restored : .nothing
        case .nothing: .nothing
        case .failed: .failed
        }
    }

    /// "Report a problem" (ROADMAP Phase 3): an email with the app, system and specs versions, never a file.
    public static func reportURL(subject: String, body: String) -> URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = AppLinks.supportEmail
        components.queryItems = [URLQueryItem(name: "subject", value: subject), URLQueryItem(name: "body", value: body)]
        return components.url
    }
}
