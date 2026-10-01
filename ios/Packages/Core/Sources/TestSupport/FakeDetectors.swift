import Foundation
import Imaging
import ScanVision

/// Test double for `FaceDetector`: returns the configured faces and records how often it was called.
public final class FakeFaceDetector: FaceDetector, @unchecked Sendable {
    private let lock = NSLock()
    private var _faces: [Face]
    private var _calls = 0

    public init(faces: [Face] = []) { _faces = faces }

    public var calls: Int { lock.withLock { _calls } }

    public func setFaces(_ faces: [Face]) { lock.withLock { _faces = faces } }

    public func detect(in raster: Raster) async throws -> [Face] {
        lock.withLock {
            _calls += 1
            return _faces
        }
    }
}

/// Test double for `PersonSegmenter`: a fixed mask, or nil to simulate the model being unavailable.
public struct FakePersonSegmenter: PersonSegmenter {
    public var mask: [UInt8]?

    public init(mask: [UInt8]? = nil) { self.mask = mask }

    public func mask(for raster: Raster) async throws -> [UInt8]? { mask }
}
