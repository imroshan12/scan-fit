import Foundation
import Kit
import Match
@testable import ScanData
import ScanModel
import TestSupport
import Testing

@MainActor
@Suite("Kit persisted prepared files")
struct KitPersistenceTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func root() -> URL {
        FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent(UUID().uuidString)
    }

    @Test("native store relaunch lists actual fitted photo and signature bytes without changing retention")
    func relaunch() async throws {
        let ese = try KitFixtures.exam("upsc_ese")
        let ibps = try KitFixtures.exam("ibps_po")
        let bundle = KitFixtures.bundle([ese, ibps])
        let root = root(), now = now
        defer { try? FileManager.default.removeItem(at: root) }
        let initial = FileDraftStore(root: root, clock: { now })
        let slots: [(Exam, DocType)] = [(ese, .photo), (ese, .tripleSignature), (ibps, .photo), (ibps, .signature)]
        var originals: [[UInt8]] = []
        for (exam, type) in slots {
            let bytes = try await Task.detached { try KitFixtures.encoded(exam, type) }.value
            originals.append(bytes)
            try await initial.retain(bytes, examID: exam.id, spec: KitFixtures.spec(exam, type), kind: DocKind.of(type))
        }
        let relaunched = FileDraftStore(root: root, clock: { now })
        let model = KitViewModel(drafts: relaunched) { bundle }
        await model.refresh()
        let groups = try KitFixtures.groups(model)
        #expect(groups.map(\.id) == [ese.id, ibps.id])
        #expect(groups.first?.confidence == .low)
        let rows = groups.flatMap(\.rows)
        #expect(rows.count == slots.count && originals.count == 4)
        #expect(rows.map(\.roundedKB) == originals.map { ($0.count + 512) / 1024 })
        for (index, slot) in slots.enumerated() {
            let (exam, type) = slot
            let retained = try #require(await relaunched.load(
                examID: rows[index].route.examId, spec: KitFixtures.spec(exam, rows[index].route.docType),
                kind: DocKind.of(type)
            ))
            #expect(rows[index].route == FlowRoute(examId: exam.id, docType: type))
            #expect(retained.bytes == originals[index] && retained.createdAt == now)
        }
        await model.refresh()
        #expect(try KitFixtures.groups(model) == groups)
        let boundary = now.addingTimeInterval(FileDraftStore.lifetime)
        let expired = FileDraftStore(root: root, clock: { boundary })
        let expiredModel = KitViewModel(drafts: expired) { bundle }
        await expiredModel.refresh()
        #expect(expiredModel.state == .empty)
    }

    @Test("missing, corrupt, expired, future and current-preset invalid records are never listed")
    func invalidRecords() async throws {
        let exam = try KitFixtures.exam("ibps_po")
        let original = try KitFixtures.spec(exam, .photo)
        let bytes = TestJpeg.make(width: 200, height: 230, size: 34 * 1024)
        let now = now
        for operation in ["missing", "corrupt", "expired", "future", "changed", "retired", "hidden"] {
            let root = root()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = FileDraftStore(root: root, clock: { now })
            var current = exam
            if operation != "missing" {
                try await store.retain(bytes, examID: exam.id, spec: original, kind: .photo)
            }
            if ["corrupt", "expired", "future"].contains(operation) {
                let slot = await store.slotURL(examID: exam.id, type: .photo)
                if operation == "corrupt" {
                    try Data([1, 2, 3]).write(to: slot)
                } else {
                    let date = operation == "future" ? now.addingTimeInterval(1) :
                        now.addingTimeInterval(-FileDraftStore.lifetime)
                    let record = FileDraftStore.Record(version: 1, createdAt: date, jpeg: Data(bytes))
                    try JSONEncoder().encode(record).write(to: slot)
                }
            }
            if operation == "changed" { current = try KitFixtures.changed(exam, dpi: 300) }
            if operation == "retired" || operation == "hidden" {
                current = try KitFixtures.changed(exam, status: operation)
            }
            let bundle = KitFixtures.bundle([current])
            let relaunched = FileDraftStore(root: root, clock: { now })
            let model = KitViewModel(drafts: relaunched) { bundle }
            await model.refresh()
            #expect(model.state == .empty, "\(operation): no available draft")
        }
    }

    @Test("entry and foreground style refresh revalidate disk while keeping other valid rows")
    func reentry() async throws {
        let exam = try KitFixtures.exam("ibps_po")
        let bundle = KitFixtures.bundle([exam])
        let root = root(), now = now
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FileDraftStore(root: root, clock: { now })
        let model = KitViewModel(drafts: store) { bundle }
        await model.refresh()
        #expect(model.state == .empty)
        try await store.retain(TestJpeg.make(width: 200, height: 230, size: 34 * 1024),
                               examID: exam.id, spec: KitFixtures.spec(exam, .photo), kind: .photo)
        try await store.retain(TestJpeg.make(width: 140, height: 60, size: 16 * 1024),
                               examID: exam.id, spec: KitFixtures.spec(exam, .signature), kind: .signature)
        await model.refresh()
        #expect(try KitFixtures.groups(model).first?.rows.count == 2)
        try Data([1, 2, 3]).write(to: await store.slotURL(examID: exam.id, type: .photo))
        await model.refresh()
        #expect(try KitFixtures.groups(model).first?.rows.map(\.route.docType) == [.signature])
        try FileManager.default.removeItem(at: await store.slotURL(examID: exam.id, type: .signature))
        await model.refresh()
        #expect(model.state == .empty)
    }
}
