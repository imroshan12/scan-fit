import CoreGraphics
import Foundation
@testable import Kit
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

/// Answers a blank image of the asked size and remembers the caps (no decoding in view-model tests).
final class FakeKitImages: KitImages, @unchecked Sendable {
    private let lock = NSLock()
    private var _caps: [Int] = []
    var caps: [Int] { lock.withLock { _caps } }

    func image(_: [UInt8], maxPixelSize: Int) -> CGImage? {
        lock.withLock { _caps.append(maxPixelSize) }
        let context = CGContext(
            data: nil, width: maxPixelSize, height: maxPixelSize, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )
        return context?.makeImage()
    }

    /// Every PDF opens as three A4 pages.
    func pdfPageCount(_: [UInt8]) -> Int? { 3 }

    func pdfPage(_: [UInt8], index: Int, width: Int) -> CGImage? {
        guard index >= 0, index < 3 else { return nil }
        let height = Int(Double(width) * 842 / 595)
        return CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )?.makeImage()
    }
}

/// My Kit's Originals, History and original screens (ALGORITHMS 13).
@MainActor
@Suite("My Kit originals and history")
struct KitOriginalsAndHistoryTests {
    private let kit = FakeKitStore()
    private let images = FakeKitImages()
    private let picture = TestJpeg.make(width: 1600, height: 1200, size: 4096)
    private let fitted = TestJpeg.make(width: 200, height: 230, size: 35 * 1024)

    private func save(_ kind: DocKind) async -> String {
        await kit.advance(by: 1)
        guard case let .saved(id) = await kit.saveOriginal(kind: kind, jpeg: picture) else { return "" }
        return id
    }

    private func request() throws -> ExportRequest {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "ibps_po" })
        let photo = try #require(exam.documents.first { $0.type == .photo })
        return ExportRequest(bytes: fitted, filename: "photo_IBPS-PO_200x230_35kb.jpg", spec: photo, kind: .photo)
    }

    // MARK: Originals

    @Test("an empty kit says so")
    func empty() async {
        let m = KitOriginalsViewModel(kit: kit, images: images)
        await m.refresh()
        #expect(m.state == .empty)
    }

    @Test("originals are grouped by kind in spec order, newest first")
    func grouped() async {
        let declaration = await save(.declaration)
        let oldPhoto = await save(.photo)
        let signature = await save(.signature)
        let newPhoto = await save(.photo)
        let m = KitOriginalsViewModel(kit: kit, images: images)
        await m.refresh()
        guard case let .ready(groups) = m.state else { Issue.record("not ready"); return }
        #expect(groups.map(\.kind) == [.photo, .signature, .declaration])
        #expect(groups[0].originals.map(\.id) == [newPhoto, oldPhoto])
        #expect(groups[1].originals.map(\.id) == [signature])
        #expect(groups[2].originals.map(\.id) == [declaration])
    }

    @Test("a storage failure is retryable, not empty")
    func failure() async {
        _ = await save(.photo)
        await kit.setFailures(reads: true)
        let m = KitOriginalsViewModel(kit: kit, images: images)
        await m.refresh()
        #expect(m.state == .failed)
        await kit.setFailures()
        await m.refresh()
        guard case .ready = m.state else { Issue.record("not ready"); return }
    }

    @Test("thumbnails are decoded small")
    func thumbnails() async {
        let id = await save(.photo)
        let m = KitOriginalsViewModel(kit: kit, images: images)
        #expect(await m.thumbnail(id)?.width == KitOriginalsViewModel.thumbnailPixels)
        #expect(images.caps == [KitOriginalsViewModel.thumbnailPixels])
        #expect(await m.thumbnail(await kit.newID()) == nil)
    }

    // MARK: History

    @Test("history is empty, then lists a recorded export")
    func historyList() async throws {
        let m = KitHistoryViewModel(kit: kit) { _, _ in nil }
        await m.refresh()
        #expect(m.state == .empty)
        await kit.recordExport(try request(), examId: "ibps_po", examName: "IBPS PO / MT")
        await m.refresh()
        guard case let .ready(entries) = m.state else { Issue.record("not ready"); return }
        #expect(entries.first?.record.fileName == "photo_IBPS-PO_200x230_35kb.jpg" && entries.first?.shareable == true)
    }

    @Test("share stages the exact copy under its recorded name; a failure says so")
    func share() async throws {
        await kit.recordExport(try request(), examId: "ibps_po", examName: "IBPS PO / MT")
        let staged = Staged()
        let m = KitHistoryViewModel(kit: kit) { bytes, name in
            await staged.set(bytes, name)
            return URL(fileURLWithPath: "/tmp/\(name)")
        }
        await m.refresh()
        guard case let .ready(entries) = m.state, let id = entries.first?.id else { return }
        await m.share(id)
        #expect(m.share.file?.url.lastPathComponent == "photo_IBPS-PO_200x230_35kb.jpg")
        #expect(await staged.bytes == fitted)
        m.shareHandled()
        #expect(m.share == .none)
        let failing = KitHistoryViewModel(kit: kit) { _, _ in nil }
        await failing.refresh()
        await failing.share(id)
        #expect(failing.share == .failed)
    }

    @Test("an expired entry cannot be shared; clearing empties the list")
    func expiredAndClear() async throws {
        await kit.recordExport(try request(), examId: "ibps_po", examName: "IBPS PO")
        await kit.advance(by: ExportHistory.shareWindow)
        let m = KitHistoryViewModel(kit: kit) { _, _ in URL(fileURLWithPath: "/tmp/x.jpg") }
        await m.refresh()
        guard case let .ready(entries) = m.state, let entry = entries.first else { return }
        #expect(!entry.shareable)
        await m.share(entry.id)
        #expect(m.share == .none)
        await m.clear()
        #expect(m.state == .empty)
    }

    // MARK: Original screen

    @Test("an original shows a small preview and the slots of its kind; search narrows them")
    func original() async throws {
        let id = await save(.signature)
        let bundle = try SpecPresets.bundle()
        let m = KitOriginalViewModel(id: id, kit: kit, images: images) { bundle }
        await m.onAppear()
        guard case let .ready(original, preview) = m.state else { Issue.record("not ready"); return }
        #expect(original.id == id && preview != nil)
        #expect((images.caps.first ?? .max) < 1600 * 2)
        #expect(!m.targets.isEmpty && m.targets.allSatisfy { DocKind.of($0.slot.type) == .signature })
        let all = m.targets.count
        m.query = "ibps po"
        #expect(!m.targets.isEmpty && m.targets.count < all && m.targets.first?.exam.id == "ibps_po")
    }

    @Test("a missing or malformed id is missing; delete removes it and leaves")
    func missingAndDelete() async throws {
        let missing = KitOriginalViewModel(id: await kit.newID(), kit: kit, images: images) { nil }
        await missing.onAppear()
        #expect(missing.state == .missing)
        let malformed = KitOriginalViewModel(id: "../index", kit: kit, images: images) { nil }
        await malformed.onAppear()
        #expect(malformed.state == .missing)
        let id = await save(.thumb)
        let m = KitOriginalViewModel(id: id, kit: kit, images: images) { nil }
        await m.onAppear()
        await m.delete()
        #expect(m.state == .deleted)
        #expect(await kit.originalsList.isEmpty)
    }
}

private actor Staged {
    var bytes: [UInt8] = []
    var name = ""

    func set(_ bytes: [UInt8], _ name: String) {
        self.bytes = bytes
        self.name = name
    }
}
