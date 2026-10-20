import Foundation

/// The free-saves state (ALGORITHMS 14.2). `hashes` are the SHA-256 hex identities of files that left the app, oldest
/// first.
public struct QuotaState: Sendable, Equatable {
    public var used: Int
    public var allowance: Int
    public var hashes: [String]

    public init(used: Int = 0, allowance: Int = 0, hashes: [String] = []) {
        self.used = used
        self.allowance = allowance
        self.hashes = hashes
    }

    public var remaining: Int { max(0, allowance - used) }
}

/// Whether a Save, Share or Save all may go ahead, and how many of its files are new to the app.
public struct QuotaDecision: Sendable, Equatable {
    public let allowed: Bool
    public let newFiles: Int
}

/// ALGORITHMS 14.2, pure. Conformance: `save_quota_cases`; reference `spec/tools/quota.py`.
public enum SaveQuotaRules {
    public static let maxHashes = 1000
    public static let showsLeftAt = 5

    /// Raises the allowance to `total`; never lowers it (saves a user has been shown are not taken back).
    public static func total(_ state: QuotaState, _ total: Int) -> QuotaState {
        guard total >= 0 else { return state }
        var next = state
        next.allowance = max(state.allowance, total)
        return next
    }

    public static func decide(_ state: QuotaState, files: [String], pro: Bool) -> QuotaDecision {
        let known = Set(state.hashes)
        var seen = Set<String>()
        let new = files.filter { !known.contains($0) && seen.insert($0).inserted }.count
        return QuotaDecision(allowed: pro || new == 0 || new <= state.remaining, newFiles: new)
    }

    /// Records one file that left the app. Never refuses: a race past the last free save may count twice.
    public static func record(_ state: QuotaState, file: String, pro: Bool, maxHashes: Int = maxHashes) -> QuotaState {
        var next = state
        if let index = next.hashes.firstIndex(of: file) {
            next.hashes.remove(at: index)
            next.hashes.append(file)
            return next
        }
        next.hashes.append(file)
        if next.hashes.count > maxHashes { next.hashes.removeFirst(next.hashes.count - maxHashes) }
        if !pro { next.used += 1 }
        return next
    }

    /// The account's copy and the phone's keep the higher count.
    public static func merge(_ state: QuotaState, remoteUsed: Int) -> QuotaState {
        guard remoteUsed >= 0 else { return state }
        var next = state
        next.used = max(state.used, remoteUsed)
        return next
    }

    public static func showsLeft(_ state: QuotaState, pro: Bool) -> Bool {
        !pro && state.remaining <= showsLeftAt
    }
}

/// Where the free-saves state lives (ALGORITHMS 14.4).
public protocol QuotaStore: Sendable {
    /// Applies `transform` to the stored state under one lock and returns the new state.
    func update(_ transform: @Sendable (QuotaState) -> QuotaState) async -> QuotaState
}

/// One versioned JSON record, `{version: 1, used, allowance, hashes}`, replaced atomically in Application Support and
/// excluded from backup (file identities never leave the phone). A missing, corrupt or unknown record reads as the
/// new-install state; the account copy restores the count after a reinstall.
public actor FileQuotaStore: QuotaStore {
    private let root: URL
    private var cached: QuotaState?
    private static let version = 1

    private struct Record: Codable {
        let version: Int
        let used: Int
        let allowance: Int
        let hashes: [String]
    }

    public init(root: URL) {
        self.root = root
    }

    public func update(_ transform: @Sendable (QuotaState) -> QuotaState) async -> QuotaState {
        let current = cached ?? read()
        let next = transform(current)
        if next != current {
            // A failed write keeps the new state for this process; the next launch reads the last good record.
            try? write(next)
        }
        cached = next
        return next
    }

    private var file: URL { root.appendingPathComponent("quota.json") }

    private func read() -> QuotaState {
        guard let data = try? Data(contentsOf: file),
              let record = try? JSONDecoder().decode(Record.self, from: data),
              record.version == Self.version, record.used >= 0, record.allowance >= 0,
              record.hashes.allSatisfy(Self.isHash) else { return QuotaState() }
        let hashes = Array(record.hashes.suffix(SaveQuotaRules.maxHashes))
        return QuotaState(used: record.used, allowance: record.allowance, hashes: hashes)
    }

    private func write(_ state: QuotaState) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var folder = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
        let record = Record(version: Self.version, used: state.used, allowance: state.allowance, hashes: state.hashes)
        try JSONEncoder().encode(record).write(to: file, options: .atomic)
    }

    private static func isHash(_ text: String) -> Bool {
        text.utf8.count == 64 && text.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
    }
}

/// `QuotaStore` in memory, for tests and previews.
public actor MemoryQuotaStore: QuotaStore {
    public private(set) var state: QuotaState

    public init(_ state: QuotaState = QuotaState()) {
        self.state = state
    }

    public func update(_ transform: @Sendable (QuotaState) -> QuotaState) async -> QuotaState {
        state = transform(state)
        return state
    }
}
