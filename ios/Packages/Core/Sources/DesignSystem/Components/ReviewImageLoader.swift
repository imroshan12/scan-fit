import CoreGraphics
import Foundation
import Imaging
import ImageIO
import Observation

struct ReviewImageInput: Sendable, Equatable {
    let before: Raster?
    let after: [UInt8]
}

struct ReviewImages: Sendable {
    let before: CGImage?
    let after: CGImage?
}

@MainActor
@Observable
final class ReviewImageLoader {
    private var input: ReviewImageInput?
    private var decoded: ReviewImages?
    private var generation = 0

    func images(for current: ReviewImageInput) -> ReviewImages? {
        current == input && !current.after.isEmpty ? decoded : nil
    }

    func load(
        _ current: ReviewImageInput,
        decode: @escaping @Sendable (ReviewImageInput) async -> ReviewImages = ReviewImageLoader.decode
    ) async {
        generation += 1
        let gen = generation
        input = current
        decoded = nil
        guard !current.after.isEmpty, !Task.isCancelled else { return }
        let worker = Task.detached(priority: .userInitiated) { await decode(current) }
        let images = await withTaskCancellationHandler {
            await worker.value
        } onCancel: {
            worker.cancel()
        }
        guard !Task.isCancelled, gen == generation else { return }
        decoded = images
    }

    private nonisolated static func decode(_ input: ReviewImageInput) async -> ReviewImages {
        guard !Task.isCancelled else { return ReviewImages(before: nil, after: nil) }
        let before = input.before?.cgImage
        guard !Task.isCancelled,
              let source = CGImageSourceCreateWithData(Data(input.after) as CFData, nil) else {
            return ReviewImages(before: before, after: nil)
        }
        let options = [kCGImageSourceShouldCacheImmediately: true] as CFDictionary
        return ReviewImages(before: before, after: CGImageSourceCreateImageAtIndex(source, 0, options))
    }
}
