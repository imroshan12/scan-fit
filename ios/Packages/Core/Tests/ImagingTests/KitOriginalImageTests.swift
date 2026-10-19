@testable import Imaging
import Inspect
import Testing
import TestSupport

/// The My Kit original (ALGORITHMS 13.1): bounded, baseline RGB, metadata stripped.
@Suite("Kit original image")
struct KitOriginalImageTests {
    private struct NotJpeg: JpegEncoder {
        func encode(_: Raster, quality _: Int) -> [UInt8] { [1, 2, 3] }
    }

    @Test("a large picture is kept at 2400 on the long side as a clean baseline JPEG")
    func large() throws {
        let bytes = try #require(KitOriginalImage.encode(
            Raster.make(3000, 2000) { x, _ in x & 0xFF }, encoder: ImageIOJpegEncoder()
        ))
        let file = Inspector.inspect(bytes)
        #expect(file.width == 2400 && file.height == 1600)
        #expect(file.sof == "SOF0" && file.color == .rgb && file.components == 3)
        #expect(!file.hasExif && !file.hasGps && !file.hasXmp)
    }

    @Test("a small picture keeps its size")
    func small() throws {
        let bytes = try #require(KitOriginalImage.encode(
            Raster.make(800, 600) { _, y in y & 0xFF }, encoder: ImageIOJpegEncoder()
        ))
        let file = Inspector.inspect(bytes)
        #expect(file.width == 800 && file.height == 600)
    }

    @Test("an encoder that does not make a JPEG keeps nothing")
    func notJpeg() {
        #expect(KitOriginalImage.encode(Raster.make(10, 10) { _, _ in 0 }, encoder: NotJpeg()) == nil)
    }
}
