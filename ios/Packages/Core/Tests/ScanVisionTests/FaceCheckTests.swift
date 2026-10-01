import Imaging
import ScanVision
import TestSupport
import Testing

@Suite("FaceCheck and fakes")
struct FaceCheckTests {
    private let face = Face(x: 10, y: 20, w: 100, h: 120)

    @Test("zero faces blocks, two or more ask for a re-crop, one continues")
    func counts() {
        #expect(FaceCheck.of([]) == .noFace)
        #expect(FaceCheck.of([face]) == .single(face))
        #expect(FaceCheck.of([face, face]) == .several(count: 2))
        #expect(FaceCheck.of(Array(repeating: face, count: 5)) == .several(count: 5))
        #expect(face.centerX == 60)
    }

    @Test("the fakes return what they are told and count calls")
    func fakes() async throws {
        let detector = FakeFaceDetector(faces: [face])
        let raster = Raster.white(4, 4)
        #expect(try await detector.detect(in: raster) == [face])
        detector.setFaces([])
        #expect(try await detector.detect(in: raster).isEmpty)
        #expect(detector.calls == 2)
        #expect(try await FakePersonSegmenter(mask: nil).mask(for: raster) == nil)
        #expect(try await FakePersonSegmenter(mask: [1, 2]).mask(for: raster) == [1, 2])
    }
}
