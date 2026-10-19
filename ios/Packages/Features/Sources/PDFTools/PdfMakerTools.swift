import Foundation
import Imaging
import Inspect
import PDF

/// What the list shows for an added file: a picture or a PDF (with its pages), and a small thumbnail.
public struct PdfFileInfo: Sendable, Equatable {
    public let document: Bool
    public let pages: Int
    public let thumbnail: Raster?
    /// A PDF with a password: it cannot be added (ALGORITHMS 6.1).
    public let encrypted: Bool

    public init(document: Bool, pages: Int, thumbnail: Raster?, encrypted: Bool = false) {
        self.document = document
        self.pages = pages
        self.thumbnail = thumbnail
        self.encrypted = encrypted
    }
}

/// The platform side of the PDF maker, behind a protocol so `PdfMakerViewModel` is testable with a fake.
public protocol PdfMakerTools: Sendable {
    /// nil when the file is neither a picture we can decode nor a PDF we can open.
    func describe(_ bytes: [UInt8]) -> PdfFileInfo?
    /// ALGORITHMS 1.1: upright, downsampled on decode.
    func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster?
    func make(_ job: PdfJob, progress: @escaping @Sendable (PdfProgress) -> Void) async throws -> PdfOutcome
}

public struct LivePdfMakerTools: PdfMakerTools {
    private static let thumbnail = 192.0
    private let opener = CGPdfOpener()

    public init() {}

    public func describe(_ bytes: [UInt8]) -> PdfFileInfo? {
        let file = Inspector.inspect(bytes)
        guard file.format == .pdf else {
            return ImageIODecoder.decode(bytes, maxLongSide: Int(Self.thumbnail)).map {
                PdfFileInfo(document: false, pages: 1, thumbnail: $0)
            }
        }
        if file.pdfEncrypted { return PdfFileInfo(document: true, pages: 0, thumbnail: nil, encrypted: true) }
        guard let pages = opener.open(bytes), pages.pageCount > 0 else { return nil }
        let size = pages.pageSize(0)
        let scale = Self.thumbnail / max(size.width, size.height)
        let width = max(1, Int(size.width * scale)), height = max(1, Int(size.height * scale))
        let thumbnail = pages.render(0, width: width, height: height)
        return PdfFileInfo(document: true, pages: pages.pageCount, thumbnail: thumbnail)
    }

    public func decode(_ bytes: [UInt8], maxLongSide: Int) -> Raster? {
        ImageIODecoder.decode(bytes, maxLongSide: maxLongSide)
    }

    public func make(_ job: PdfJob, progress: @escaping @Sendable (PdfProgress) -> Void) async throws -> PdfOutcome {
        let engine = PdfEngine(opener: opener, decoder: ImageIOPictureDecoder(), encoder: ImageIOJpegEncoder())
        return try await engine.make(job, progress: progress)
    }
}
