import Billing
import Foundation
@testable import ScanData
import Testing
import TestSupport

/// Free saves (ALGORITHMS 14): the shared `save_quota_cases`, the file store and the gate.
@MainActor
@Suite("Free saves", .timeLimit(.minutes(1)))
struct SaveQuotaTests {
    @Test("every save quota case holds")
    func conformance() throws {
        let cases = try CasesFile.section("save_quota_cases")
        #expect(cases.count >= 8)
        for c in cases {
            let id = try #require(c.string("id"))
            let start = c.dict("start") ?? [:]
            let maxHashes = c.int("max_hashes") ?? SaveQuotaRules.maxHashes
            var state = QuotaState(
                used: start.int("used") ?? 0, allowance: start.int("allowance") ?? 0, hashes: start.strings("hashes") ?? []
            )
            let steps = try #require(c["steps"] as? [[String: Any]])
            for (index, step) in steps.enumerated() {
                let pro = step.bool("pro") ?? false
                let at = "\(id) step \(index)"
                switch step.string("op") {
                case "total": state = SaveQuotaRules.total(state, try #require(step.int("total")))
                case "record":
                    state = SaveQuotaRules.record(state, file: try #require(step.string("file")), pro: pro, maxHashes: maxHashes)
                case "merge": state = SaveQuotaRules.merge(state, remoteUsed: try #require(step.int("remote_used")))
                case "decide":
                    let decision = SaveQuotaRules.decide(state, files: step.strings("files") ?? [], pro: pro)
                    let expect = try #require(step.dict("expect"))
                    #expect(decision.allowed == expect.bool("allowed"), "\(at)")
                    #expect(decision.newFiles == expect.int("new"), "\(at)")
                case "state":
                    let expect = try #require(step.dict("expect"))
                    #expect(state.used == expect.int("used"), "\(at)")
                    #expect(state.allowance == expect.int("allowance"), "\(at)")
                    #expect(state.remaining == expect.int("remaining"), "\(at)")
                    #expect(SaveQuotaRules.showsLeft(state, pro: pro) == expect.bool("shows_left"), "\(at)")
                default: Issue.record("\(at): unknown op")
                }
            }
        }
    }

    @Test("identity is lowercase SHA-256")
    func identity() {
        #expect(SaveGate.identity(Array("abc".utf8)) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("quota-\(UUID().uuidString)")
    }

    private let a: [UInt8] = [1, 2, 3]
    private let b: [UInt8] = [4, 5, 6]

    @Test("each file counts once and spent saves open the paywall")
    func countsOnce() async {
        let gate = SaveGate(store: MemoryQuotaStore(), billing: FakeBilling()) { 2 }
        await gate.load()
        #expect(gate.status == QuotaStatus(pro: false, remaining: 2, showsLeft: true))
        #expect(gate.allows([a], trigger: .save))
        await gate.record(a)
        await gate.record(a)
        #expect(gate.status?.remaining == 1)
        await gate.record(b)
        #expect(gate.allows([a, b], trigger: .saveAll), "known bytes stay free")
        #expect(gate.paywall.current == nil)
        #expect(!gate.allows([[9]], trigger: .share))
        #expect(gate.paywall.current == .share)
    }

    @Test("before the first load it allows, but still counts")
    func beforeLoad() async {
        let gate = SaveGate(store: MemoryQuotaStore(), billing: FakeBilling()) { 0 }
        #expect(gate.allows([a], trigger: .save))
        await gate.record(a)
        #expect(gate.status?.remaining == 0)
        #expect(!gate.allows([b], trigger: .save))
    }

    @Test("Pro is never blocked and not counted")
    func pro() async {
        let billing = FakeBilling()
        let gate = await testSaveGate(used: 20, billing: billing)
        billing.isPro = true
        #expect(gate.allows([a], trigger: .save))
        await gate.record(a)
        #expect(gate.status == QuotaStatus(pro: true, remaining: 0, showsLeft: false))
        #expect(gate.state?.used == 20)
    }

    @Test("the count survives a relaunch, in a backup-excluded folder")
    func relaunch() async throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let first = SaveGate(store: FileQuotaStore(root: root), billing: FakeBilling()) { 3 }
        await first.load()
        await first.record(a)
        await first.record(b)
        let relaunched = SaveGate(store: FileQuotaStore(root: root), billing: FakeBilling()) { 3 }
        await relaunched.load()
        #expect(relaunched.status?.remaining == 1)
        let text = try String(contentsOf: root.appendingPathComponent("quota.json"), encoding: .utf8)
        #expect(text.contains(SaveGate.identity(a)))
        #expect(try root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    @Test("a corrupt or foreign record starts again")
    func corrupt() async throws {
        for text in [
            "{not json", #"{"version":2,"used":1,"allowance":3,"hashes":[]}"#,
            #"{"version":1,"used":1,"allowance":3,"hashes":["../x"]}"#,
        ] {
            let root = temporaryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try Data(text.utf8).write(to: root.appendingPathComponent("quota.json"))
            let gate = SaveGate(store: FileQuotaStore(root: root), billing: FakeBilling()) { 3 }
            await gate.load()
            #expect(gate.status?.remaining == 3, "\(text)")
        }
    }

    @Test("a raised total applies at once; a lowered one takes nothing back")
    func totals() async {
        let store = MemoryQuotaStore()
        let first = SaveGate(store: store, billing: FakeBilling()) { 3 }
        await first.load()
        await first.record(a)
        let raised = SaveGate(store: store, billing: FakeBilling()) { 5 }
        await raised.load()
        #expect(raised.status?.remaining == 4)
        let lowered = SaveGate(store: store, billing: FakeBilling()) { 1 }
        await lowered.load()
        #expect(lowered.status?.remaining == 4)
    }
}
