import Foundation
import Imaging
import Match
@testable import PhotoFlow
import ScanData
import ScanModel
import ScanVision
import Testing
import TestSupport

/// Save to My Kit on the photo review and "Use for exam…" from an original (ALGORITHMS 13.1, 13.2).
@MainActor
@Suite("Photo flow and My Kit")
struct PhotoKitTests {
    private let tools = FakePhotoTools()
    private let faces = FakeFaceDetector(faces: [Face(x: 400, y: 500, w: 200, h: 240)])
    private let kit = FakeKitStore()

    private func model(original: String? = nil, drafts: any DraftStore = NoDraftStore()) async throws
        -> PhotoFlowViewModel {
        let bundle = try SpecPresets.bundle()
        let m = PhotoFlowViewModel(
            target: .exam(examId: "ibps_po", docType: .photo), tools: tools, faces: faces,
            segmenter: FakePersonSegmenter(), drafts: drafts, kit: kit, originalID: original
        ) { bundle }
        await m.onAppear()
        return m
    }

    private func reviewed(_ m: PhotoFlowViewModel) async {
        await m.imageSelected([1, 2, 3])
        await m.cropDone()
        await m.renderTask?.value
    }

    @Test("keeps the upright working picture, not the fitted file, once")
    func saves() async throws {
        let m = try await model()
        await reviewed(m)
        let before = m.state
        #expect(m.kitSave == .idle)
        await m.saveOriginal()
        #expect(m.kitSave == .saved)
        let saved = try #require(await kit.originalsList.first)
        #expect(saved.kind == .photo && saved.width == 1000 && saved.height == 1400)
        #expect(m.state == before)
        await m.saveOriginal()
        #expect(await kit.originalsList.count == 1)
    }

    @Test("a full kit and a failure are shown and can be retried")
    func fullAndRetry() async throws {
        for _ in 0..<KitLimits.maxOriginals {
            _ = await kit.saveOriginal(kind: .signature, jpeg: TestJpeg.make(width: 10, height: 10, size: 4096))
        }
        let m = try await model()
        await reviewed(m)
        await m.saveOriginal()
        #expect(m.kitSave == .full)
        await kit.removeAllOriginals()
        await kit.setFailures(writes: true)
        await m.saveOriginal()
        #expect(m.kitSave == .failed)
        await kit.setFailures()
        await m.saveOriginal()
        #expect(m.kitSave == .saved)
    }

    @Test("a new picture starts again from Save to My Kit")
    func reset() async throws {
        let m = try await model()
        await reviewed(m)
        await m.saveOriginal()
        _ = m.back()
        _ = m.back()
        await reviewed(m)
        #expect(m.kitSave == .idle)
    }

    @Test("an original opens straight at the crop, skips the draft and counts as kept")
    func fromOriginal() async throws {
        guard case let .saved(id) = await kit.saveOriginal(
            kind: .photo, jpeg: TestJpeg.make(width: 1000, height: 1400, size: 4096)
        ) else { Issue.record("not saved"); return }
        let m = try await model(original: id)
        guard case .crop = m.state else { Issue.record("not cropping: \(m.state)"); return }
        await m.cropDone()
        await m.renderTask?.value
        guard case let .review(review) = m.state else { Issue.record("no review"); return }
        #expect(!review.restored)
        #expect(m.kitSave == .saved)
    }

    @Test("a missing original says so on the source step; a malformed id is ignored")
    func missing() async throws {
        let missing = try await model(original: await kit.newID())
        #expect(missing.state.slot != nil)
        guard case let .pickSource(_, problem) = missing.state else { Issue.record("not picking"); return }
        #expect(problem == .openFailed)
        guard case let .pickSource(_, none) = try await model(original: "../index").state else { return }
        #expect(none == nil)
    }
}
