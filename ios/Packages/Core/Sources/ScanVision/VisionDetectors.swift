import CoreML
import CoreVideo
import Foundation
import Imaging
import Vision

/// Vision face detection (ALGORITHMS 2.1): on the device, nothing is uploaded. Only the face box is used, so the
/// rectangles request is enough. Boxes come back in raster pixels with a top-left origin.
public struct VisionFaceDetector: FaceDetector {
    public init() {}

    public func detect(in raster: Raster) async throws -> [Face] {
        guard let image = raster.cgImage else { return [] }
        let request = VNDetectFaceRectanglesRequest()
        request.preferCPUOnSimulator()
        try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        let w = Double(raster.width), h = Double(raster.height)
        return (request.results ?? []).map { face in
            let box = face.boundingBox // normalised, origin bottom-left
            return Face(x: box.minX * w, y: (1 - box.maxY) * h, w: box.width * w, h: box.height * h)
        }
    }
}

/// Vision person segmentation (ALGORITHMS 2.3) on the cropped photo. Returns nil when it cannot run here, so the
/// "White background" toggle can say it is unavailable instead of failing the flow.
public struct VisionPersonSegmenter: PersonSegmenter {
    public init() {}

    public func mask(for raster: Raster) async throws -> [UInt8]? {
        guard let image = raster.cgImage else { return nil }
        let request = VNGeneratePersonSegmentationRequest()
        request.qualityLevel = .accurate
        request.outputPixelFormat = kCVPixelFormatType_OneComponent32Float
        request.preferCPUOnSimulator()
        do {
            try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        } catch {
            return nil
        }
        guard let buffer = request.results?.first?.pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let mw = CVPixelBufferGetWidth(buffer), mh = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        var confidence = [Float](repeating: 0, count: mw * mh)
        for y in 0..<mh {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: Float.self)
            for x in 0..<mw { confidence[y * mw + x] = row[x] }
        }
        return PersonMask.fromConfidence(confidence, maskW: mw, maskH: mh, width: raster.width, height: raster.height)
    }
}

private extension VNRequest {
    /// The simulator has no GPU or Neural Engine inference context for Vision ("Could not create inference
    /// context"), so every stage runs on the CPU there. Devices keep Vision's own choice.
    func preferCPUOnSimulator() {
        #if targetEnvironment(simulator)
            guard let stages = try? supportedComputeStageDevices else { return }
            for (stage, devices) in stages {
                let cpu = devices.first { device in
                    if case .cpu = device { return true }
                    return false
                }
                if let cpu { setComputeDevice(cpu, for: stage) }
            }
        #endif
    }
}
