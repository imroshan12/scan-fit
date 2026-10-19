import Foundation
import Imaging
import Inspect
import ScanModel

public enum PdfOutcome: Sendable {
    case made(PdfMade)
    case failed(PdfError)
}

/// Makes every PDF the app writes (ALGORITHMS 6): prepares pictures once, measures the PDF at each DPI of the ladder
/// and each grid quality with one page raster in memory at a time, then writes the chosen DPI and quality (padded up to
/// a minimum when needed). Platform pieces are injected; cancellation throws `CancellationError`.
public struct PdfEngine: Sendable {
    public static let previewLongSide = 800.0
    private static let pictureCap = 2400, cleanupCap = 1600, cacheQuality = 95

    private let opener: PdfOpener
    private let decoder: PictureDecoder
    private let encoder: JpegEncoder

    public init(opener: PdfOpener, decoder: PictureDecoder, encoder: JpegEncoder) {
        self.opener = opener
        self.decoder = decoder
        self.encoder = encoder
    }

    private struct Prepared {
        let jpeg: [UInt8]
        let width: Int
        let height: Int
    }

    private enum Page {
        case pictures(PdfSize, [Placement], [Prepared])
        case rendered(PdfSize, PdfPages, Int)

        var size: PdfSize {
            switch self {
            case let .pictures(size, _, _), let .rendered(size, _, _): size
            }
        }
    }

    private struct Failure: Error { let error: PdfError }

    public func make(_ job: PdfJob, progress: @Sendable (PdfProgress) -> Void = { _ in }) async throws -> PdfOutcome {
        let idCardSides = job.sources.count == 2 && job.sources.allSatisfy(\.isImage)
        if job.sources.isEmpty || (job.tool == .idCard && !idCardSides) { return .failed(.noPages) }
        var opened: [Int: PdfPages] = [:]
        for (i, source) in job.sources.enumerated() {
            guard case let .document(bytes) = source else { continue }
            if Inspector.inspect(bytes).pdfEncrypted { return .failed(.encrypted) }
            opened[i] = opener.open(bytes)
        }
        let documents = job.sources.filter { !$0.isImage }.count
        if opened.count != documents || opened.values.contains(where: { $0.pageCount < 1 }) {
            return .failed(.unreadable)
        }
        do {
            return try await make(job, opened: opened, progress: progress)
        } catch let failure as Failure {
            return .failed(failure.error)
        }
    }

    private func make(
        _ job: PdfJob, opened: [Int: PdfPages], progress: @Sendable (PdfProgress) -> Void
    ) async throws -> PdfOutcome {
        let pictureCount = job.tool == .idCard ? 1 : job.sources.filter(\.isImage).count
        let pageCount = pictureCount + opened.values.reduce(0) { $0 + $1.pageCount }
        if pageCount > PdfFit.maxPages { return .failed(.tooManyPages) }
        guard let window = PdfWindow(job.sizeKb) else { return .failed(.unknownLimit) }
        if let made = unchanged(job, opened: opened, window: window) { return .made(made) }

        var prepared: [Int: Prepared] = [:]
        let pictures = job.sources.indices.filter { job.sources[$0].isImage }
        for (n, i) in pictures.enumerated() {
            progress(.preparing(done: n, total: pictures.count))
            try Task.checkCancellation()
            prepared[i] = try prepare(job.sources[i], job.options)
        }
        let pages = self.pages(job, opened: opened, prepared: prepared)
        for dpi in PdfLayout.dpiLadder {
            guard let sizes = try measure(pages, dpi: dpi, window: window, options: job.options, progress: progress),
                  let choice = PdfFit.choose(window, dpi: dpi, sizes: sizes) else { continue }
            return .made(try write(pages, choice: choice, window: window, options: job.options, progress: progress))
        }
        return .failed(.cannotReach)
    }

    /// ALGORITHMS 6.1: one PDF already inside the window is returned byte for byte.
    private func unchanged(_ job: PdfJob, opened: [Int: PdfPages], window: PdfWindow) -> PdfMade? {
        guard job.sources.count == 1, case let .document(bytes) = job.sources[0], let pages = opened[0] else {
            return nil
        }
        let kb = Double(bytes.count) / 1024
        guard kb >= window.min, kb <= window.max else { return nil }
        let size = pages.pageSize(0)
        let scale = Self.previewLongSide / max(size.width, size.height)
        let preview = pages.render(
            0, width: max(1, roundHalfUp(size.width * scale)), height: max(1, roundHalfUp(size.height * scale))
        )
        return PdfMade(
            bytes: bytes, pages: pages.pageCount, dpi: nil, quality: nil, strategy: .unchanged, preview: preview
        )
    }

    /// ALGORITHMS 6.2: decode, turn, crop, then cleanup or grayscale; kept as a high-quality JPEG for every DPI.
    private func prepare(_ source: PdfSource, _ options: PdfOptions) throws -> Prepared {
        guard case let .image(bytes, turns, crop, cropBase) = source,
              var raster = decoder.decode(bytes, maxLongSide: options.cleanup ? Self.cleanupCap : Self.pictureCap)
        else { throw Failure(error: .unreadable) }
        for _ in 0..<(((turns % 4) + 4) % 4) { raster = CropAdjust.rotateClockwise(raster) }
        if let crop {
            let base = cropBase ?? PixelSize(width: raster.width, height: raster.height)
            let scaled = crop.scaled(srcW: base.width, srcH: base.height, to: raster)
            let x = min(max(scaled.x, 0), raster.width - 1), y = min(max(scaled.y, 0), raster.height - 1)
            raster = raster.crop(
                x: x, y: y, width: min(scaled.w, raster.width - x), height: min(scaled.h, raster.height - y)
            )
        }
        if options.cleanup {
            raster = PageCleanup.clean(raster, grayscale: options.grayscale)
        } else if options.grayscale {
            raster = PageCleanup.grayscale(raster)
        }
        let jpeg = encoder.encode(raster, quality: Self.cacheQuality)
        return Prepared(jpeg: jpeg, width: raster.width, height: raster.height)
    }

    private func pages(_ job: PdfJob, opened: [Int: PdfPages], prepared: [Int: Prepared]) -> [Page] {
        if job.tool == .idCard, let front = prepared[0], let back = prepared[1] {
            let (size, placements) = PdfLayout.idCardPage(
                front: PixelSize(width: front.width, height: front.height),
                back: PixelSize(width: back.width, height: back.height)
            )
            return [.pictures(size, placements, [front, back])]
        }
        return job.sources.indices.flatMap { i -> [Page] in
            if let picture = prepared[i] {
                let (size, placement) = PdfLayout.imagePage(picture.width, picture.height)
                return [.pictures(size, [placement], [picture])]
            }
            guard let document = opened[i] else { return [] }
            return (0..<document.pageCount).map { .rendered(document.pageSize($0), document, $0) }
        }
    }

    /// The page's images at `dpi`, with where each goes.
    private func rasters(_ page: Page, dpi: Int, options: PdfOptions) throws -> [(Raster, PlacedPixels)] {
        switch page {
        case let .pictures(_, placements, pictures):
            return try zip(pictures, placements).map { picture, placement in
                let px = PdfLayout.pixels(picture.width, picture.height, scale: placement.scale, dpi: dpi)
                guard let decoded = decoder.decode(picture.jpeg, maxLongSide: max(picture.width, picture.height))
                else { throw Failure(error: .failed) }
                let raster = Resampler.resize(decoded, width: px.width, height: px.height)
                return (raster, PlacedPixels(width: px.width, height: px.height, rect: placement.rect))
            }
        case let .rendered(size, document, index):
            let px = PdfLayout.pdfPagePixels(size, dpi: dpi)
            guard let rendered = document.render(index, width: px.width, height: px.height)
            else { throw Failure(error: .unreadable) }
            let raster = options.grayscale ? PageCleanup.grayscale(rendered) : rendered
            let rect = PdfRect(x: 0, y: 0, width: size.width, height: size.height)
            return [(raster, PlacedPixels(width: px.width, height: px.height, rect: rect))]
        }
    }

    private func jpeg(_ raster: Raster, quality: Int, dpi: Int) throws -> [UInt8] {
        guard case let .success(bytes) = JpegPatcher.patch(encoder.encode(raster, quality: quality), dpi: dpi)
        else { throw Failure(error: .failed) }
        return bytes
    }

    /// The whole PDF's size at each grid quality, or `nil` as soon as the lowest-quality images alone exceed `goal.hi`.
    private func measure(
        _ pages: [Page], dpi: Int, window: PdfWindow, options: PdfOptions, progress: @Sendable (PdfProgress) -> Void
    ) throws -> [Int]? {
        let grid = PdfFit.grid(dpi)
        var lengths = [[[Int]]](repeating: [], count: grid.count)
        var geometry: [PageGeometry] = []
        var lowest = 0
        for (p, page) in pages.enumerated() {
            try Task.checkCancellation()
            progress(.fitting(page: p, total: pages.count))
            let images = try rasters(page, dpi: dpi, options: options)
            var perQuality = [[Int]](repeating: [Int](repeating: 0, count: images.count), count: grid.count)
            for (i, image) in images.enumerated() {
                perQuality[grid.count - 1][i] = try jpeg(image.0, quality: grid[grid.count - 1], dpi: dpi).count
            }
            lowest += perQuality[grid.count - 1].reduce(0, +)
            if Double(lowest) > window.hiBytes { return nil }
            for (i, image) in images.enumerated() {
                for q in 0..<(grid.count - 1) { perQuality[q][i] = try jpeg(image.0, quality: grid[q], dpi: dpi).count }
            }
            geometry.append(PageGeometry(size: page.size, images: images.map(\.1)))
            for q in grid.indices { lengths[q].append(perQuality[q]) }
        }
        return lengths.map { PdfWriter.size(geometry, lengths: $0) }
    }

    private func write(
        _ pages: [Page], choice: PdfChoice, window: PdfWindow, options: PdfOptions,
        progress: @Sendable (PdfProgress) -> Void
    ) throws -> PdfMade {
        var preview: Raster?
        var written: [WriterPage] = []
        for (p, page) in pages.enumerated() {
            try Task.checkCancellation()
            progress(.fitting(page: p, total: pages.count))
            let images = try rasters(page, dpi: choice.dpi, options: options)
            if p == 0 { preview = PdfPreview.compose(page.size, images: images, longSide: Self.previewLongSide) }
            written.append(WriterPage(size: page.size, images: try images.map { raster, at in
                WriterImage(jpeg: try jpeg(raster, quality: choice.quality, dpi: choice.dpi), width: at.width,
                            height: at.height, rect: at.rect)
            }))
        }
        let bytes = choice.padTo.map { PdfWriter.writePadded(written, target: $0) } ?? PdfWriter.write(written)
        let kb = Double(bytes.count) / 1024
        guard kb <= window.max, kb >= window.min else { throw Failure(error: .failed) }
        return PdfMade(
            bytes: bytes, pages: pages.count, dpi: choice.dpi, quality: choice.quality, strategy: choice.strategy,
            preview: preview
        )
    }
}

/// A small picture of a page as written: white, with each image drawn at its place (display only, never exported).
enum PdfPreview {
    static func compose(_ size: PdfSize, images: [(Raster, PlacedPixels)], longSide: Double) -> Raster {
        let scale = longSide / max(size.width, size.height)
        let cw = max(1, roundHalfUp(size.width * scale)), ch = max(1, roundHalfUp(size.height * scale))
        var canvas = [UInt8](repeating: 255, count: cw * ch * 3)
        for (raster, at) in images {
            let x = roundHalfUp(at.rect.x * scale), y = roundHalfUp(at.rect.y * scale)
            let w = max(1, roundHalfUp(at.rect.width * scale)), h = max(1, roundHalfUp(at.rect.height * scale))
            let image = Resampler.resize(raster, width: w, height: h)
            for row in 0..<h {
                let cy = y + row
                guard cy >= 0, cy < ch else { continue }
                let from = max(0, -x), to = min(w, cw - x)
                guard from < to else { continue }
                let src = (row * w + from) * 3, dst = (cy * cw + x + from) * 3
                canvas.replaceSubrange(dst..<(dst + (to - from) * 3), with: image.rgb[src..<(src + (to - from) * 3)])
            }
        }
        return Raster(width: cw, height: ch, rgb: canvas)
    }
}
