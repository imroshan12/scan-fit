import Foundation
import Imaging
import Inspect
@testable import PDF
import ScanModel
import TestSupport
import Testing

/// Every `pdf_job_cases` entry (ALGORITHMS 9.10) with the real ImageIO encoder/decoder and CoreGraphics PDF renderer,
/// including the Phase 3 exit gate: the sample PDFs compress into each target.
@Suite("PDF job conformance")
struct PdfJobConformanceTests {
    static let engine = PdfEngine(opener: CGPdfOpener(), decoder: ImageIOPictureDecoder(), encoder: ImageIOJpegEncoder())

    @Test func everyJobCaseHolds() async throws {
        let cases = try CasesFile.section("pdf_job_cases")
        #expect(cases.count >= 8)
        for c in cases {
            let id = c.string("id") ?? "?"
            let sources: [PdfSource] = try (try #require(c["sources"] as? [[String: String]])).map { s in
                if let pdf = s["pdf"] { return .document([UInt8](try SpecFiles.data("fixtures/\(pdf)"))) }
                return .image(bytes: [UInt8](try SpecFiles.data("fixtures/\(try #require(s["image"]))")))
            }
            let options = try #require(c.dict("options"))
            let tool = try #require(PdfTool(rawValue: c.string("tool") ?? ""))
            let job = PdfJob(
                tool: tool,
                sources: sources,
                sizeKb: PdfConformanceTests.sizeKb(try #require(c.dict("size_kb"))),
                options: PdfOptions(grayscale: options.bool("grayscale") ?? true, cleanup: options.bool("cleanup") ?? true)
            )
            let expect = try #require(c.dict("expect"))
            let outcome = try await Self.engine.make(job)
            if let error = expect.string("error") {
                guard case let .failed(got) = outcome else { Issue.record("\(id): made a PDF"); continue }
                #expect(got.rawValue == error, "\(id)")
                continue
            }
            guard case let .made(pdf) = outcome else { Issue.record("\(id): \(outcome)"); continue }
            let file = Inspector.inspect(pdf.bytes)
            let kb = Double(pdf.bytes.count) / 1024
            #expect(file.format == .pdf, "\(id)")
            #expect(!file.pdfEncrypted, "\(id)")
            #expect(pdf.pages == expect.int("pages"), "\(id)")
            #expect(file.pdfPages == pdf.pages, "\(id)")
            #expect(kb >= (expect.double("kb_min") ?? 0) && kb <= (expect.double("kb_max") ?? 0), "\(id): \(kb) KB")
            if let imageOnly = expect.bool("image_only") { #expect(file.pdfImageOnly == imageOnly, "\(id)") }
            if let strategy = expect.string("strategy") { #expect(pdf.strategy.rawValue == strategy, "\(id)") }
            if expect.bool("same_bytes") == true, case let .document(bytes) = sources[0] {
                #expect(bytes == pdf.bytes, "\(id)")
            }
            #expect(pdf.preview != nil, "\(id)")
        }
    }
}
