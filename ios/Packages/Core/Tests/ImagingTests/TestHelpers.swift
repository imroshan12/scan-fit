import Foundation
import Imaging
import ScanModel
import Testing
import TestSupport

/// Deterministic noisy picture, identical to the Kotlin tests' `noisyRaster` (32-bit wrapping LCG).
func noisyRaster(_ w: Int, _ h: Int, noise: Int = 60, seed: Int32 = 7) -> Raster {
    var s = seed
    return Raster.make(w, h) { x, y in
        s = s &* 1_103_515_245 &+ 12_345
        let n = Int((UInt32(bitPattern: s) >> 16) & 0xFF) * noise / 255
        let r = min(max((x * 255) / w + n, 0), 255)
        let g = min(max((y * 255) / h + n, 0), 255)
        let b = min(max(((x + y) * 127) / (w + h) + n, 0), 255)
        return (r << 16) | (g << 8) | b
    }
}

enum TestSlots {
    /// A slot of a real preset, e.g. `("ibps_po", .photo)`.
    static func slot(_ preset: String, _ type: DocType) throws -> DocSpec {
        let file = try #require(try SpecFiles.presetFiles().first { $0.lastPathComponent == "\(preset).json" })
        let exam = try PresetBundle.decodeExam(Data(contentsOf: file))
        return try #require(exam.documents.first { $0.type == type })
    }

    static func exam(_ preset: String) throws -> Exam {
        let file = try #require(try SpecFiles.presetFiles().first { $0.lastPathComponent == "\(preset).json" })
        return try PresetBundle.decodeExam(Data(contentsOf: file))
    }

    /// A slot built from inline JSON fields (modes no real preset uses, e.g. `exact`).
    static func inline(type: String = "signature", min: String = "10", max: String = "20", target: String = "16",
                       dims: String, extra: String = "") throws -> DocSpec {
        let json = #"{"type":"\#(type)","required":true,"formats":["jpg"],"size_kb":{"min":\#(min),"max":\#(max),"target":\#(target)},"dimensions":\#(dims)\#(extra)}"#
        return try PresetBundle.decoder().decode(DocSpec.self, from: Data(json.utf8))
    }

    static func spec(_ json: String) throws -> DocSpec {
        try PresetBundle.decoder().decode(DocSpec.self, from: Data(json.utf8))
    }
}
