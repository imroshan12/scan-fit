import Foundation
import Imaging
import ScanModel
import ScanVision

extension PhotoFlowViewModel {
    nonisolated static func background<Value: Sendable>(
        _ work: @escaping @Sendable () async throws -> Value
    ) async -> Value? {
        try? await Task.detached(priority: .userInitiated, operation: work).value
    }

    nonisolated static func decodeCap(_ spec: DocSpec) -> Int {
        let dimensions = spec.dimensions
        let largest = [dimensions.width, dimensions.height, dimensions.maxW, dimensions.maxH]
            .compactMap { $0 }.max() ?? 0
        return ImageIODecoder.longSideCap(largestTargetDimension: largest)
    }

    nonisolated static func framed(_ slot: PhotoSlot, _ image: Raster, _ found: [Face]) -> CropState {
        let aspect = slot.aspect ?? Double(image.width) / Double(image.height)
        switch FaceCheck.of(found) {
        case let .single(face):
            let framing = AutoFraming.frame(face, imgW: image.width, imgH: image.height, aspect: aspect)
            return CropState(slot: slot, image: image, rect: framing.crop, tight: framing.coverageAdjusted)
        case .noFace, .several:
            let rect = Geometry.defaultCrop(srcW: image.width, srcH: image.height, aspect: aspect)
            let problem: CropProblem? = found.count > 1 ? .severalFaces : nil
            return CropState(slot: slot, image: image, rect: rect, tight: false, problem: problem)
        }
    }

    nonisolated static func ddmmyyyy(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let components = calendar.dateComponents([.day, .month, .year], from: date)
        func pad(_ number: Int, _ width: Int) -> String {
            let digits = String(number)
            return String(repeating: "0", count: max(0, width - digits.count)) + digits
        }
        return "\(pad(components.day ?? 1, 2))/\(pad(components.month ?? 1, 2))/\(pad(components.year ?? 2000, 4))"
    }
}
