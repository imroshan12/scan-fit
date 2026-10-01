import Testing
@testable import Imaging

@Suite("ImageOps")
struct ImageOpsTests {
    @Test("box widths follow the boxes-for-Gauss formula and are always odd")
    func boxWidths() {
        #expect(ImageOps.boxWidths(10.0) == [19, 19, 21])
        #expect(ImageOps.boxWidths(1.0) == [1, 1, 3])
        #expect((1...60).allSatisfy { s in ImageOps.boxWidths(Double(s) / 2).allSatisfy { $0 % 2 == 1 } })
    }

    @Test("blur keeps a flat plane flat and spreads an impulse symmetrically; tiny sigma is a no-op")
    func blur() {
        let flat = [Int](repeating: 77, count: 30 * 20)
        #expect(ImageOps.gaussianApprox(flat, 30, 20, sigma: 3.0) == flat)
        var impulse = [Int](repeating: 0, count: 31 * 31)
        impulse[15 * 31 + 15] = 255
        let blurred = ImageOps.gaussianApprox(impulse, 31, 31, sigma: 3.0)
        #expect(blurred[15 * 31 + 15] == blurred.max())
        for d in 1...6 {
            #expect(blurred[15 * 31 + 15 - d] == blurred[15 * 31 + 15 + d])
            #expect(blurred[(15 - d) * 31 + 15] == blurred[(15 + d) * 31 + 15])
        }
        let plane = (0..<9).map { $0 * 20 }
        #expect(ImageOps.gaussianApprox(plane, 3, 3, sigma: 0.2) == plane)
    }

    @Test("Sauvola marks a dark stroke on a bright background")
    func sauvola() {
        let w = 60, h = 40
        let n = (0..<(w * h)).map { (18...21).contains($0 / w) ? 20 : 250 }
        let m = ImageOps.sauvola(n, w, h, window: 15, k: 0.34, range: 128)
        #expect(m[20 * w + 30] == 1 && m[5 * w + 30] == 0 && m[35 * w + 10] == 0)
    }

    @Test("opening keeps blocks, removes specks and thin strokes, keeps thick border strokes")
    func opening() {
        let w = 12, h = 12
        var m = [Int](repeating: 0, count: w * h)
        for y in 2...5 { for x in 2...5 { m[y * w + x] = 1 } }
        m[9 * w + 9] = 1
        for x in 0..<w { m[x] = 1 }
        let o = ImageOps.open3x3(m, w, h)
        #expect(o[3 * w + 3] == 1 && o[9 * w + 9] == 0 && o[6] == 0)
        var thick = [Int](repeating: 0, count: w * h)
        for y in 0...2 { for x in 0..<w { thick[y * w + x] = 1 } }
        #expect(ImageOps.open3x3(thick, w, h)[0] == 1)
    }

    @Test("specks are removed by component size with 8-connectivity")
    func specks() {
        let w = 10, h = 10
        var m = [Int](repeating: 0, count: w * h)
        m[1 * w + 1] = 1; m[2 * w + 2] = 1; m[3 * w + 3] = 1
        for x in 5...9 { m[7 * w + x] = 1 }
        let out = ImageOps.removeSpecks(m, w, h, minSize: 4)
        #expect(out[2 * w + 2] == 0 && out[7 * w + 7] == 1)
        #expect(ImageOps.removeSpecks(m, w, h, minSize: 3)[2 * w + 2] == 1)
    }
}
