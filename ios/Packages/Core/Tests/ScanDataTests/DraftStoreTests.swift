import Foundation
import Imaging
import Inspect
import Match
@testable import ScanData
import ScanModel
import Testing
import TestSupport

@Suite("Retained draft filesystem")
struct DraftStoreTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let bytes = TestJpeg.make(width: 200, height: 230, size: 34 * 1024)

    private func spec() throws -> DocSpec {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        return try #require(exam.documents.first { $0.type == .photo })
    }

    private func root() -> URL {
        FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent(UUID().uuidString)
    }

    private func changedSpec(_ spec: DocSpec) throws -> DocSpec {
        var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(spec)) as? [String: Any])
        object["dpi"] = 300
        return try JSONDecoder().decode(DocSpec.self, from: JSONSerialization.data(withJSONObject: object))
    }

    @Test("all eleven shared cases verify actual files and relaunch bytes")
    func conformance() async throws {
        let cases = try CasesFile.section("draft_cases")
        #expect(cases.count == 11)
        #expect(Set(cases.compactMap { $0.string("id") }).count == 11)
        for test in cases {
            let root = root()
            defer { try? FileManager.default.removeItem(at: root) }
            let now = now
            let store = FileDraftStore(root: root, clock: { now })
            let original = try spec()
            let slot = await store.slotURL(examID: "ibps_po", type: .photo)
            let operation = try #require(test.string("operation"))
            var current = original
            if operation != "missing" {
                try await store.retain(bytes, examID: "ibps_po", spec: original, kind: .photo)
                var jpeg = bytes
                var version = 1
                var createdAt = now
                switch operation {
                case "valid": break
                case "corrupt": jpeg = [0xFF, 0xD8, 0xFF]
                case "expired": createdAt = now.addingTimeInterval(-FileDraftStore.lifetime)
                case "future": createdAt = now.addingTimeInterval(1)
                case "changed_spec": current = try changedSpec(original)
                case "wrong_dpi": jpeg[15] = 72
                case "exif":
                    jpeg = Array(bytes.prefix(2)) + [0xFF, 0xE1, 0, 8, 69, 120, 105, 102, 0, 0] + bytes.dropFirst(2)
                case "version": version = 2
                default: Issue.record("Unhandled draft operation: \(operation)")
                }
                let record = FileDraftStore.Record(version: version, createdAt: createdAt, jpeg: Data(jpeg))
                try JSONEncoder().encode(record).write(to: slot)
            }
            let relaunched = FileDraftStore(root: root, clock: { now })
            let draft = await relaunched.load(examID: "ibps_po", spec: current, kind: .photo)
            let expected = try #require(test.dict("expect"))
            #expect((draft != nil) == expected.bool("available"), "\(test.string("id") ?? "")")
            if let draft { #expect(draft.bytes == bytes && draft.createdAt == now) }
            let status = test.bool("saved") == true ? "saved" : (draft == nil ? "not_started" : "ready")
            #expect(status == expected.string("status"))
        }
    }

    @Test("age zero and just before expiry pass; at boundary and future fail")
    func ages() async throws {
        let root = root(), spec = try spec(), now = now
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FileDraftStore(root: root, clock: { now })
        let slot = await store.slotURL(examID: "ibps_po", type: .photo)
        for age in [0.0, FileDraftStore.lifetime - 0.001, FileDraftStore.lifetime, -0.001] {
            try await store.retain(bytes, examID: "ibps_po", spec: spec, kind: .photo)
            let record = FileDraftStore.Record(version: 1, createdAt: now.addingTimeInterval(-age), jpeg: Data(bytes))
            try JSONEncoder().encode(record).write(to: slot)
            #expect((await store.load(examID: "ibps_po", spec: spec, kind: .photo) != nil)
                    == (age >= 0 && age < FileDraftStore.lifetime))
        }
    }

    @Test("failed atomic replacement, invalid input and cancellation preserve old bytes")
    func replacementFailure() async throws {
        let root = root(), spec = try spec(), now = now
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FileDraftStore(root: root, clock: { now })
        try await store.retain(bytes, examID: "ibps_po", spec: spec, kind: .photo)
        let failing = FileDraftStore(root: root, clock: { now }, beforeCommit: { throw DraftError.storage })
        let replacement = TestJpeg.make(width: 200, height: 230, size: 35 * 1024)
        do {
            try await failing.retain(replacement, examID: "ibps_po", spec: spec, kind: .photo)
            Issue.record("commit succeeded")
        } catch {}
        do {
            try await store.retain([1, 2], examID: "ibps_po", spec: spec, kind: .photo)
            Issue.record("invalid bytes retained")
        } catch {}
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            try await store.retain(replacement, examID: "ibps_po", spec: spec, kind: .photo)
        }
        do { try await task.value; Issue.record("cancelled write succeeded") } catch {}
        #expect(await store.load(examID: "ibps_po", spec: spec, kind: .photo)?.bytes == bytes)
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.path).count == 1)
    }

    @Test("opaque names, symlink rejection and bounded reads do not touch external files")
    func hostileFiles() async throws {
        let root = root(), spec = try spec(), now = now
        let outside = self.root()
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: outside)
        }
        let store = FileDraftStore(root: root, clock: { now })
        try await store.retain(bytes, examID: "../../outside", spec: spec, kind: .photo)
        let slot = await store.slotURL(examID: "../../outside", type: .photo)
        #expect(slot.deletingLastPathComponent().path == root.path)
        #expect(slot.lastPathComponent.count == 69)
        try Data(bytes).write(to: outside)
        try FileManager.default.removeItem(at: slot)
        try FileManager.default.createSymbolicLink(at: slot, withDestinationURL: outside)
        do {
            try await store.retain(bytes, examID: "../../outside", spec: spec, kind: .photo)
            Issue.record("symlink write succeeded")
        } catch {}
        #expect(await store.load(examID: "../../outside", spec: spec, kind: .photo) == nil)
        #expect(try Data(contentsOf: outside) == Data(bytes))
        try Data().write(to: slot)
        let handle = try FileHandle(forWritingTo: slot)
        try handle.truncate(atOffset: UInt64(FileDraftStore.maximumRecord + 1))
        try handle.close()
        #expect(await store.load(examID: "../../outside", spec: spec, kind: .photo) == nil)
        #expect(!FileManager.default.fileExists(atPath: slot.path))
        let linkedRoot = root.appendingPathComponent("linked")
        try FileManager.default.createSymbolicLink(at: linkedRoot, withDestinationURL: outside)
        let linkedStore = FileDraftStore(root: linkedRoot, clock: { now })
        #expect(await linkedStore.load(examID: "ibps_po", spec: spec, kind: .photo) == nil)
    }

    @Test("payload limit, backup exclusion and revisions")
    func limitsAndBackup() async throws {
        let root = root(), spec = try spec(), now = now
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FileDraftStore(root: root, clock: { now })
        var iterator = await store.revisions().makeAsyncIterator()
        #expect(await iterator.next() == 0)
        #expect(await store.load(examID: "ibps_po", spec: spec, kind: .photo) == nil)
        try await store.retain(bytes, examID: "ibps_po", spec: spec, kind: .photo)
        #expect(await iterator.next() == 1)
        let values = try root.resourceValues(forKeys: [.isExcludedFromBackupKey])
        if let excluded = values.isExcludedFromBackup { #expect(excluded) }
        let slot = await store.slotURL(examID: "ibps_po", type: .photo)
        if let excluded = try slot.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup {
            #expect(excluded)
        }
        do {
            try await store.retain(Array(repeating: 0, count: FileDraftStore.maximumJPEG + 1),
                                   examID: "ibps_po", spec: spec, kind: .photo)
            Issue.record("oversized JPEG retained")
        } catch {}
        #expect(await store.load(examID: "ibps_po", spec: spec, kind: .photo)?.bytes == bytes)
    }
}
