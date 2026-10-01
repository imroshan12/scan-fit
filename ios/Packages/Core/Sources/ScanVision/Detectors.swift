import Foundation
import Imaging

/// Face detection behind a protocol so the pipeline is testable with fakes (Vision wrapper lands in Phase 2).
public protocol FaceDetector: Sendable {
    func detect(in raster: Raster) async throws -> [Face]
}

/// Person segmentation: one alpha value per pixel (0 background, 255 person), row-major, `raster.width * raster.height` long,
/// or nil when the model is unavailable (Vision wrapper lands in Phase 2).
public protocol PersonSegmenter: Sendable {
    func mask(for raster: Raster) async throws -> [UInt8]?
}

/// The outcome of the face-count rule (ALGORITHMS 9.6): never silently picks one of several faces.
public enum FaceCheck: Sendable, Equatable {
    case single(Face)
    case noFace
    case several(count: Int)

    public static func of(_ faces: [Face]) -> FaceCheck {
        switch faces.count {
        case 0: .noFace
        case 1: .single(faces[0])
        default: .several(count: faces.count)
        }
    }
}
