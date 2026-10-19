import Foundation
@testable import Imaging
import TestSupport
import Testing

/// Runs every `page_cleanup_cases` entry (ALGORITHMS 9.10 page cleanup): a parametric photographed page (shaded paper,
/// text bars, a dark photo block, a red stamp) must come out with white paper, dark text, the block not washed out and
/// the stamp grey or red as asked.
@Suite("Page cleanup conformance")
struct PageCleanupConformanceTests {
    private struct Box {
        let x: Int, y: Int, w: Int, h: Int

        func contains(_ px: Int, _ py: Int, grow: Int = 0) -> Bool {
            px >= x - grow && px < x + w + grow && py >= y - grow && py < y + h + grow
        }
    }

    private static func luma(_ r: Raster, _ x: Int, _ y: Int) -> Int {
        (299 * r.r(x, y) + 587 * r.g(x, y) + 114 * r.b(x, y) + 500) / 1000
    }

    private static func grey(_ v: Int) -> Int { (v << 16) | (v << 8) | v }

    @Test func everyPageCleanupCaseHolds() throws {
        let cases = try CasesFile.section("page_cleanup_cases")
        #expect(cases.count >= 2)
        for c in cases {
            let id = c.string("id") ?? "?"
            let w = try #require(c.int("width")), h = try #require(c.int("height"))
            let paper = try #require(c["paper"] as? [Int])
            let texts = try #require(c["text"] as? [[Int]])
            let block = try #require(c["block"] as? [Int])
            let stamp = try #require(c["stamp"] as? [Any])
            let cx = try #require(stamp[0] as? Int), cy = try #require(stamp[1] as? Int)
            let radius = try #require(stamp[2] as? Int)
            let colour = try #require(stamp[3] as? [Int])
            let textBoxes = texts.map { Box(x: $0[0], y: $0[1], w: $0[2], h: $0[3]) }
            let blockBox = Box(x: block[0], y: block[1], w: block[2], h: block[3])
            func inStamp(_ x: Int, _ y: Int, _ r: Int) -> Bool { (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r }
            let source = Raster.make(w, h) { x, y in
                if inStamp(x, y, radius) { return (colour[0] << 16) | (colour[1] << 8) | colour[2] }
                if blockBox.contains(x, y) { return Self.grey(block[4]) }
                if let text = texts.first(where: { Box(x: $0[0], y: $0[1], w: $0[2], h: $0[3]).contains(x, y) }) {
                    return Self.grey(text[4])
                }
                return Self.grey(roundHalfUp(Double(paper[0]) + Double(paper[1] - paper[0]) * Double(x) / Double(w - 1)))
            }
            let out = PageCleanup.clean(source, grayscale: c.bool("grayscale") ?? true)
            let expect = try #require(c.dict("expect"))
            let margin = 4, gap = 6
            let inner = Box(x: blockBox.x + margin, y: blockBox.y + margin, w: blockBox.w - 2 * margin,
                            h: blockBox.h - 2 * margin)
            let stampBox = Box(x: cx - radius, y: cy - radius, w: 2 * radius + 1, h: 2 * radius + 1)
            var paperCount = 0, white = 0, textSum = 0, textCount = 0, blockSum = 0, blockCount = 0
            var redSum = 0, greenSum = 0, stampCount = 0, spread = 0
            for y in 0..<h {
                for x in 0..<w {
                    let l = Self.luma(out, x, y)
                    if textBoxes.contains(where: { $0.contains(x, y) }) {
                        textSum += l; textCount += 1
                    } else if inner.contains(x, y) {
                        blockSum += l; blockCount += 1
                    } else if inStamp(x, y, radius - margin) {
                        redSum += out.r(x, y); greenSum += out.g(x, y); stampCount += 1
                        let channels = [out.r(x, y), out.g(x, y), out.b(x, y)]
                        spread = max(spread, (channels.max() ?? 0) - (channels.min() ?? 0))
                    } else if !textBoxes.contains(where: { $0.contains(x, y, grow: gap) }),
                              !blockBox.contains(x, y, grow: gap), !stampBox.contains(x, y, grow: gap) {
                        paperCount += 1
                        if l >= 250 { white += 1 }
                    }
                }
            }
            let whiteFraction = Double(white) / Double(paperCount)
            #expect(whiteFraction >= (expect.double("paper_white_min_fraction") ?? 1), "\(id): paper \(whiteFraction)")
            let textMean = Double(textSum) / Double(textCount)
            #expect(textMean <= (expect.double("text_max_mean") ?? 0), "\(id): text \(textMean)")
            let blockMean = Double(blockSum) / Double(blockCount)
            #expect(blockMean <= (expect.double("block_max_mean") ?? 0), "\(id): block \(blockMean)")
            #expect(blockMean >= (expect.double("block_min_mean") ?? 255), "\(id): block \(blockMean)")
            if expect.bool("stamp_gray") == true { #expect(spread <= 2, "\(id): stamp spread \(spread)") }
            if let red = expect.double("stamp_red_min") {
                let got = Double(redSum - greenSum) / Double(stampCount)
                #expect(got >= red, "\(id): stamp red \(got)")
            }
        }
    }
}
