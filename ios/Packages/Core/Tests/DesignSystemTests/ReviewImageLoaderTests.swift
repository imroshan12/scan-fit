@testable import DesignSystem
import Foundation
import Imaging
import Testing

private actor ReviewDecodeGate {
    private var entered = false
    private var started: CheckedContinuation<Void, Never>?
    private var release: CheckedContinuation<Void, Never>?

    func decode(_ input: ReviewImageInput) async -> ReviewImages {
        entered = true
        started?.resume()
        started = nil
        await withCheckedContinuation { release = $0 }
        return ReviewImages(before: input.before?.cgImage, after: input.before?.cgImage)
    }

    func waitForStart() async {
        if entered { return }
        await withCheckedContinuation { started = $0 }
    }

    func finish() {
        release?.resume()
        release = nil
    }
}

@MainActor
@Suite("Review image loading")
struct ReviewImageLoaderTests {
    private nonisolated static func checkBackgroundThread() {
        #expect(!Thread.isMainThread)
    }

    @Test("the bounded crop and final output are independently decoded off-main")
    func realDecode() async throws {
        let before = Raster.make(80, 60) { _, _ in 0xE0D0C0 }
        let bytes = ImageIOJpegEncoder().encode(.white(40, 30), quality: 90)
        let input = ReviewImageInput(before: before, after: bytes)
        let loader = ReviewImageLoader()
        await loader.load(input)
        let images = try #require(loader.images(for: input))
        #expect(images.before?.width == 80 && images.before?.height == 60)
        #expect(images.after?.width == 40 && images.after?.height == 30)
        let unchecked = ReviewImageInput(before: before, after: [1])
        await loader.load(unchecked) { input in
            Self.checkBackgroundThread()
            return ReviewImages(before: input.before?.cgImage, after: nil)
        }
    }

    @Test("a late superseded decode cannot replace current images")
    func replacement() async throws {
        let loader = ReviewImageLoader()
        let gate = ReviewDecodeGate()
        let old = ReviewImageInput(before: .white(80, 60), after: [1])
        let current = ReviewImageInput(before: .white(40, 30), after: [2])
        let loading = Task { await loader.load(old) { await gate.decode($0) } }
        await gate.waitForStart()
        #expect(loader.images(for: old) == nil)
        await loader.load(current) { input in
            ReviewImages(before: input.before?.cgImage, after: input.before?.cgImage)
        }
        #expect(loader.images(for: old) == nil)
        await gate.finish()
        await loading.value
        #expect(loader.images(for: current)?.after?.width == 40)
        let changedBytes = ReviewImageInput(before: current.before, after: [3])
        #expect(loader.images(for: changedBytes) == nil, "hide old output even before the replacement task starts")
    }

    @Test("cancellation cannot publish a decoded result")
    func cancelled() async {
        let loader = ReviewImageLoader()
        let gate = ReviewDecodeGate()
        let input = ReviewImageInput(before: .white(80, 60), after: [1])
        let loading = Task { await loader.load(input) { await gate.decode($0) } }
        await gate.waitForStart()
        loading.cancel()
        await gate.finish()
        await loading.value
        #expect(loader.images(for: input) == nil)
    }

    @Test("working and failed inputs hide both images and clear decoded output")
    func placeholder() async {
        let loader = ReviewImageLoader()
        let ready = ReviewImageInput(before: .white(80, 60), after: [1])
        await loader.load(ready) { input in
            ReviewImages(before: input.before?.cgImage, after: input.before?.cgImage)
        }
        #expect(loader.images(for: ready)?.after != nil)
        let empty = ReviewImageInput(before: ready.before, after: [])
        #expect(loader.images(for: empty) == nil)
        await loader.load(empty)
        #expect(loader.images(for: ready) == nil && loader.images(for: empty) == nil)
    }
}
