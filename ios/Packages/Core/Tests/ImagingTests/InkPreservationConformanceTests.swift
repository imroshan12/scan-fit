import Foundation
import Imaging
import TestSupport
import Testing

@Suite("Ink preservation conformance")
struct InkPreservationConformanceTests {
    private struct PreservationCase: Decodable {
        let id: String
        let width: Int
        let height: Int
        let lines: [[Int]]
        let dots: [[Int]]
        let expectInk: Int

        enum CodingKeys: String, CodingKey {
            case id, width, height, lines, dots
            case expectInk = "expect_ink"
        }
    }

    @Test("all three shared cases retain exact black pixels and bounds")
    func everyCase() throws {
        let entries = try CasesFile.section("ink_preservation_cases")
        #expect(entries.count == 3)
        var visited = Set<String>()
        for entry in entries {
            let fixture = try JSONDecoder().decode(
                PreservationCase.self, from: JSONSerialization.data(withJSONObject: entry)
            )
            #expect(visited.insert(fixture.id).inserted)
            #expect(fixture.expectInk > 0)
            #expect(fixture.lines.allSatisfy { $0.count == 3 })
            #expect(fixture.dots.allSatisfy { $0.count == 2 })
            let source = Raster.make(fixture.width, fixture.height) { column, row in
                let stroke = fixture.lines.contains {
                    $0.count == 3 && $0[2] == row && ($0[0]...$0[1]).contains(column)
                }
                let dot = fixture.dots.contains { $0.count == 2 && $0[0] == column && $0[1] == row }
                return stroke || dot ? 0x101010 : 0xF0F0F0
            }
            var columns: [Int] = []
            var rows: [Int] = []
            for row in 0..<source.height {
                for column in 0..<source.width where source.r(column, row) == 16 {
                    columns.append(column)
                    rows.append(row)
                }
            }
            #expect(columns.count == fixture.expectInk, "\(fixture.id): nonvacuous input")
            let minColumn = try #require(columns.min())
            let maxColumn = try #require(columns.max())
            let minRow = try #require(rows.min())
            let maxRow = try #require(rows.max())
            let width = maxColumn - minColumn + 1
            let height = maxRow - minRow + 1
            let padding = Int((Double(max(width, height)) * 0.08).rounded())
            let result = InkCleanup.clean(source, variant: .signature)
            let output = result.raster
            #expect(output.width == width + 2 * padding, "\(fixture.id): trim width")
            #expect(output.height == height + 2 * padding, "\(fixture.id): trim height")
            #expect(output.luma().filter { $0 == 0 }.count == fixture.expectInk, "\(fixture.id): exact ink")
            #expect(result.coverage == Double(fixture.expectInk) / Double(output.width * output.height))
            for index in columns.indices {
                let column = columns[index] - minColumn + padding
                let row = rows[index] - minRow + padding
                #expect(output.r(column, row) == 0 && output.g(column, row) == 0 && output.b(column, row) == 0,
                        "\(fixture.id): mark retained at \(columns[index]),\(rows[index])")
            }
        }
        #expect(visited == ["thin_stroke_and_dot", "three_thin_signatures", "disconnected_signature_marks"])
    }
}
