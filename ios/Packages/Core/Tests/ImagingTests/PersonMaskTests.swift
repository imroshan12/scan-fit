import Imaging
import Testing

/// ALGORITHMS 9.6: confidence -> hard 0/255 mask at 0.5, resampled bilinearly to the raster size first.
@Suite("PersonMask")
struct PersonMaskTests {
    @Test("same size is a hard threshold at one half")
    func threshold() {
        #expect(PersonMask.fromConfidence([0, 0.49, 0.5, 0.98], maskW: 4, maskH: 1, width: 4, height: 1) == [0, 0, 255, 255])
    }

    @Test("a smaller model mask is resampled to the raster")
    func resampled() {
        // left column person, right column background -> the left half of an 8x4 raster is person
        let out = PersonMask.fromConfidence([1, 0, 1, 0], maskW: 2, maskH: 2, width: 8, height: 4)
        #expect(out.count == 32)
        for y in 0..<4 {
            #expect(out[y * 8] == 255 && out[y * 8 + 3] == 255)
            #expect(out[y * 8 + 4] == 0 && out[y * 8 + 7] == 0)
        }
    }

    @Test("only 0 and 255 are produced")
    func binary() {
        let conf = (0..<(16 * 16)).map { Float($0 % 17) / 16 }
        let out = PersonMask.fromConfidence(conf, maskW: 16, maskH: 16, width: 37, height: 23)
        #expect(out.allSatisfy { $0 == 0 || $0 == 255 })
    }

    @Test("a raster converts to an image of the same size")
    func cgImage() throws {
        let image = try #require(Raster.make(3, 2) { _, _ in 0x336699 }.cgImage)
        #expect(image.width == 3 && image.height == 2)
    }
}
