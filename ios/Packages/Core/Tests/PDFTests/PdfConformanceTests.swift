import CryptoKit
import Foundation
import Imaging
@testable import PDF
import ScanModel
import TestSupport
import Testing

/// The pure parts of the PDF engine against spec/fixtures/cases.json (ALGORITHMS 9.10): layout, writer bytes, fit, names.
@Suite("PDF conformance")
struct PdfConformanceTests {
    private func texts(_ values: Double...) -> [String] { values.map(PdfWriter.num) }

    private func rect(_ r: PdfRect) -> [String] { texts(r.x, r.y, r.width, r.height) }

    private func size(_ c: [String: Any], _ key: String) throws -> PixelSize {
        let v = try #require(c[key] as? [Int])
        return PixelSize(width: v[0], height: v[1])
    }

    @Test func layoutCasesHold() throws {
        let cases = try CasesFile.section("pdf_layout_cases")
        #expect(cases.count >= 5)
        for c in cases {
            let id = c.string("id") ?? "?"
            let expect = try #require(c.dict("expect"))
            var page: PdfSize
            var rects: [PdfRect]
            var pixels: [Int: [PixelSize]] = [:]
            switch c.string("kind") {
            case "image":
                let w = try #require(c.int("w")), h = try #require(c.int("h"))
                let (s, placement) = PdfLayout.imagePage(w, h)
                page = s
                rects = [placement.rect]
                for dpi in PdfLayout.dpiLadder { pixels[dpi] = [PdfLayout.pixels(w, h, scale: placement.scale, dpi: dpi)] }
            case "id_card":
                let front = try size(c, "front"), back = try size(c, "back")
                let (s, placements) = PdfLayout.idCardPage(front: front, back: back)
                page = s
                rects = placements.map(\.rect)
                for dpi in PdfLayout.dpiLadder {
                    pixels[dpi] = zip([front, back], placements).map {
                        PdfLayout.pixels($0.width, $0.height, scale: $1.scale, dpi: dpi)
                    }
                }
            default:
                page = PdfSize(width: try #require(c.double("w_pt")), height: try #require(c.double("h_pt")))
                rects = [PdfRect(x: 0, y: 0, width: page.width, height: page.height)]
                for dpi in PdfLayout.dpiLadder { pixels[dpi] = [PdfLayout.pdfPagePixels(page, dpi: dpi)] }
            }
            #expect(expect["page"] as? [String] == texts(page.width, page.height), "\(id)")
            #expect(expect["rects"] as? [[String]] == rects.map(rect), "\(id)")
            let want = try #require(expect["pixels"] as? [String: [[Int]]])
            for dpi in PdfLayout.dpiLadder {
                #expect(want["\(dpi)"] == pixels[dpi]?.map { [$0.width, $0.height] }, "\(id)@\(dpi)")
            }
        }
    }

    @Test func writerCasesAreByteIdentical() throws {
        let cases = try CasesFile.section("pdf_writer_cases")
        #expect(cases.count >= 5)
        for c in cases {
            let id = c.string("id") ?? "?"
            let pages = try (try #require(c["pages"] as? [[String: Any]])).map(writerPage)
            let pad = c.int("pad_to")
            let bytes = pad.map { PdfWriter.writePadded(pages, target: $0) } ?? PdfWriter.write(pages)
            let expect = try #require(c.dict("expect"))
            #expect(bytes.count == expect.int("size"), "\(id)")
            let digest = SHA256.hash(data: Data(bytes)).map { String(format: "%02x", $0) }.joined()
            #expect(digest == expect.string("sha256"), "\(id)")
            if pad == nil {
                let geometry = pages.map { page in
                    PageGeometry(size: page.size, images: page.images.map {
                        PlacedPixels(width: $0.width, height: $0.height, rect: $0.rect)
                    })
                }
                #expect(PdfWriter.size(geometry, lengths: pages.map { $0.images.map(\.jpeg.count) }) == bytes.count)
            }
        }
    }

    private func writerPage(_ page: [String: Any]) throws -> WriterPage {
        let images = try #require(page["images"] as? [[String: Any]])
        func jpeg(_ o: [String: Any]) throws -> [UInt8] {
            [UInt8](try SpecFiles.data("fixtures/\(try #require(o.string("file")))"))
        }
        func px(_ o: [String: Any]) throws -> PixelSize {
            PixelSize(width: try #require(o.int("w")), height: try #require(o.int("h")))
        }
        switch page.string("kind") {
        case "image":
            let img = images[0], p = try px(img)
            let (size, placement) = PdfLayout.imagePage(p.width, p.height)
            return WriterPage(size: size, images: [
                WriterImage(jpeg: try jpeg(img), width: p.width, height: p.height, rect: placement.rect),
            ])
        case "id_card":
            let (size, placements) = PdfLayout.idCardPage(front: try px(images[0]), back: try px(images[1]))
            return WriterPage(size: size, images: try zip(images, placements).map { img, placement in
                let p = try px(img)
                return WriterImage(jpeg: try jpeg(img), width: p.width, height: p.height, rect: placement.rect)
            })
        default:
            let size = PdfSize(width: try #require(page.double("w_pt")), height: try #require(page.double("h_pt")))
            let img = images[0], p = try px(img)
            let rect = PdfRect(x: 0, y: 0, width: size.width, height: size.height)
            return WriterPage(size: size, images: [WriterImage(jpeg: try jpeg(img), width: p.width, height: p.height, rect: rect)])
        }
    }

    static func sizeKb(_ o: [String: Any]) -> SizeKB {
        SizeKB(min: o.double("min"), max: o.double("max"), target: o.double("target"))
    }

    @Test func fitCasesHold() throws {
        let cases = try CasesFile.section("pdf_fit_cases")
        #expect(cases.count >= 5)
        for c in cases {
            let id = c.string("id") ?? "?"
            let expect = try #require(c.dict("expect"))
            guard let window = PdfWindow(Self.sizeKb(try #require(c.dict("size_kb")))) else {
                #expect(expect.string("error") == "unknown_limit", "\(id)")
                continue
            }
            let raw = try #require(c["sizes"] as? [String: [Int]])
            let sizes = Dictionary(uniqueKeysWithValues: raw.map { (Int($0.key) ?? 0, $0.value) })
            let choice = PdfFit.choose(window, sizes: sizes)
            if let error = expect.string("error") {
                #expect(choice == nil && error == "cannot_reach", "\(id)")
                continue
            }
            let made = try #require(choice, "\(id)")
            #expect(made.dpi == expect.int("dpi"), "\(id)")
            #expect(made.quality == expect.int("quality"), "\(id)")
            #expect(made.padTo == expect.int("pad_to"), "\(id)")
            #expect(made.strategy.rawValue == expect.string("strategy"), "\(id)")
        }
    }

    @Test func namesFollowTheCases() throws {
        for c in try CasesFile.section("pdf_name_cases") {
            let type = try #require(DocType(rawValue: c.string("doc_type") ?? ""))
            let slot = DocSpec.pdf(type, sizeKb: SizeKB(max: 300), filename: c.string("filename"))
            let name = ExportNaming.pdfFileName(
                examName: try #require(c.string("exam_name")), slot: slot, bytes: try #require(c.int("bytes"))
            )
            #expect(name == c.string("expect"), "\(c.string("id") ?? "?")")
        }
    }

    @Test func numbersHaveAtMostTwoDecimals() {
        #expect(texts(29.75, 30.5, 595, 0.333, 0.001, 100.1) == ["29.75", "30.5", "595", "0.33", "0", "100.1"])
    }
}
