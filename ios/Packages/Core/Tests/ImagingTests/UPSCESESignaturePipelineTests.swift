import Imaging
import Inspect
import Match
import ScanModel
import TestSupport
import Testing

@Suite("UPSC ESE signature pipeline")
struct UPSCESESignaturePipelineTests {
    @Test("all three thin signature groups and detached marks survive real fit and JPEG encoding")
    func tripleSignature() throws {
        let exam = try #require(SpecPresets.bundle().exams.first { $0.id == "upsc_ese" })
        let spec = try #require(exam.documents.first { $0.type == .tripleSignature })
        let lines = [(20...200, 40), (30...190, 90), (25...210, 140)]
        let dots = [(205, 25), (195, 75), (215, 125)]
        #expect(lines.count == 3 && dots.count == 3)
        let paper = Raster.make(240, 180) { column, row in
            let stroke = lines.contains { $0.0.contains(column) && $0.1 == row }
            let dot = dots.contains { $0.0 == column && $0.1 == row }
            return stroke || dot ? 0x101010 : 0xF0F0F0
        }
        let pipeline = FitPipeline(encoder: ImageIOJpegEncoder())
        let result = try pipeline.run(paper, spec: spec, pipeline: .signatureCleanup).get()
        let cleaned = try #require(result.ink)
        #expect(cleaned.raster.luma().filter { $0 == 0 }.count == 531)
        #expect(cleaned.coverage > 0)
        let bytes = result.fit.bytes
        let inspected = Inspector.inspect(bytes)
        #expect(inspected.format == .jpeg && inspected.sof == "SOF0" && inspected.color == .rgb)
        #expect(!inspected.hasExif && !inspected.hasXmp && !inspected.hasIcc)
        let facts = FileFacts(inspected, docKind: .signature)
        let verdict = MatchEngine.evaluate(spec, facts).verdict
        #expect(verdict == .exact || verdict == .accepted)
        let entry = try #require(MatchEngine.match(facts, exams: [exam]).entries.first {
            $0.examId == exam.id && $0.docType == .tripleSignature
        })
        #expect(entry.unverified && entry.verdict == .accepted)
        let written = try #require(ImageIODecoder.decode(bytes, maxLongSide: 4000))
        #expect(written.width == inspected.width && written.height == inspected.height)
        let scaleX = Double(written.width) / Double(result.prepared.width)
        let scaleY = Double(written.height) / Double(result.prepared.height)
        #expect(scaleX >= 1 && scaleY >= 1)
        let padding = 16
        for (span, sourceRow) in lines {
            let center = Int(((Double(sourceRow - 25 + padding) + 0.5) * scaleY).rounded(.down))
            let radius = max(2, Int(scaleY.rounded(.up)))
            let band = max(0, center - radius)...min(written.height - 1, center + radius)
            let left = Int(Double(span.lowerBound - 20 + padding) * scaleX)
            let right = min(written.width - 1, Int(Double(span.upperBound - 20 + padding) * scaleX))
            let columns = (left...right).filter { column in
                band.contains { row in written.r(column, row) < 128 }
            }
            #expect(columns.count >= Int(Double(span.count) * scaleX * 0.8), "group at row \(sourceRow)")
        }
        for (sourceColumn, sourceRow) in dots {
            let column = Int(Double(sourceColumn - 20 + padding) * scaleX)
            let row = Int(Double(sourceRow - 25 + padding) * scaleY)
            let radius = max(2, Int(max(scaleX, scaleY).rounded(.up)))
            let columns = max(0, column - radius)...min(written.width - 1, column + radius)
            let rows = max(0, row - radius)...min(written.height - 1, row + radius)
            #expect(rows.contains { row in columns.contains { column in written.r(column, row) < 128 } },
                    "detached mark at \(sourceColumn),\(sourceRow)")
        }
    }
}
