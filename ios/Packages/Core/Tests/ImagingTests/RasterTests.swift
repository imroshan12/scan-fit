import Imaging
import Testing

@Suite("Raster, Resampler, Orientation")
struct RasterTests {
    @Test("luma uses the integer formula")
    func luma() {
        let r = Raster.make(3, 1) { x, _ in [0xFFFFFF, 0x000000, 0xFF0000][x] }
        #expect(r.luma() == [255, 0, 76])
    }

    @Test("crop fills outside with white and handles negative origins")
    func crop() {
        let r = Raster.make(4, 4) { _, _ in 0x102030 }
        let c = r.crop(x: -1, y: -1, width: 3, height: 3)
        #expect(c.r(0, 0) == 0xFF && c.r(1, 1) == 0x10 && c.g(2, 2) == 0x20)
        let far = r.crop(x: 10, y: 10, width: 2, height: 2)
        #expect(far.rgb.allSatisfy { $0 == 255 })
        let edge = r.crop(x: 2, y: 2, width: 4, height: 4)
        #expect(edge.r(0, 0) == 0x10 && edge.r(3, 3) == 0xFF)
    }

    @Test("same-size resize is the identity; flat colour stays flat in every direction")
    func resizeBasics() {
        let r = noisyRaster(10, 8)
        #expect(Resampler.resize(r, width: 10, height: 8) == r)
        let flat = Raster.make(7, 5) { _, _ in 0x336699 }
        for (w, h) in [(3, 2), (14, 10), (7, 9), (11, 5), (1, 1)] {
            let out = Resampler.resize(flat, width: w, height: h)
            #expect(out.width == w && out.height == h)
            #expect((0..<h).allSatisfy { y in (0..<w).allSatisfy { x in out.r(x, y) == 0x33 && out.g(x, y) == 0x66 && out.b(x, y) == 0x99 } })
        }
    }

    @Test("halving averages 2x2 blocks; enlarging is smooth; partial pixels are weighted")
    func resizeMath() {
        let checker = Raster.make(2, 2) { x, y in (x + y) % 2 == 0 ? 0xFFFFFF : 0x000000 }
        #expect(Resampler.resize(checker, width: 1, height: 1).r(0, 0) == 128)
        let ramp = Raster.make(2, 1) { x, _ in x == 0 ? 0x000000 : 0xFFFFFF }
        let big = Resampler.resize(ramp, width: 8, height: 1)
        #expect(big.r(0, 0) == 0 && big.r(7, 0) == 255)
        #expect((1..<8).allSatisfy { big.r($0, 0) >= big.r($0 - 1, 0) })
        let three = Raster.make(3, 1) { x, _ in [0, 255, 0][x] * 0x010101 }
        let two = Resampler.resize(three, width: 2, height: 1)
        #expect(two.r(0, 0) == 85 && two.r(1, 0) == 85)
    }

    @Test("every EXIF orientation maps the corners like EXIF")
    func orientation() {
        let base = Raster.make(3, 2) { x, y in [10, 99, 20, 30, 99, 40][y * 3 + x] * 0x010101 }
        func corners(_ r: Raster) -> [Int] { [r.r(0, 0), r.r(r.width - 1, 0), r.r(0, r.height - 1), r.r(r.width - 1, r.height - 1)] }
        let expected: [Int: [Int]] = [1: [10, 20, 30, 40], 2: [20, 10, 40, 30], 3: [40, 30, 20, 10], 4: [30, 40, 10, 20],
                                      5: [10, 30, 20, 40], 6: [30, 10, 40, 20], 7: [40, 20, 30, 10], 8: [20, 40, 10, 30]]
        for (k, want) in expected {
            let out = Orientation.apply(base, k)
            #expect(corners(out) == want, "k=\(k)")
            #expect((out.width == 2 && out.height == 3) == (k >= 5), "k=\(k) swaps")
        }
        #expect(Orientation.apply(base, 0) == base && Orientation.apply(base, 9) == base)
        let inverse = [1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 6: 8, 7: 7, 8: 6]
        for (k, inv) in inverse { #expect(Orientation.apply(Orientation.apply(base, k), inv) == base, "k=\(k) round trip") }
    }
}
