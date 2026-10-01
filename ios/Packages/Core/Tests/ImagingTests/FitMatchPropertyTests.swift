import Foundation
import Imaging
import Inspect
import Match
import ScanModel
import TestSupport
import Testing

/// Property (ALGORITHMS 4 / TESTING 1): for any source and any real preset slot, a file produced by the fit pipeline is accepted
/// by that slot as EXACT or ACCEPTED, or the pipeline fails with a typed error. Never a silent miss.
@Suite("Fit/match property")
struct FitMatchPropertyTests {
    private let pipeline = FitPipeline(encoder: ImageIOJpegEncoder())

    private func allSlots() throws -> [DocSpec] {
        var seen = Set<String>()
        var out: [DocSpec] = []
        for file in try SpecFiles.presetFiles() {
            let exam = try PresetBundle.decodeExam(Data(contentsOf: file))
            for slot in exam.documents where (slot.formats.contains(.jpg) || slot.formats.contains(.jpeg)) && slot.sizeKb.max != nil {
                // one representative per distinct shape: the property is about the numbers, not the exam name
                let key = "\(slot.type)|\(String(describing: slot.sizeKb))|\(String(describing: slot.dimensions))|\(String(describing: slot.dpi))"
                if seen.insert(key).inserted { out.append(slot) }
            }
        }
        return out
    }

    private func kind(_ type: DocType) -> DocKind {
        switch type {
        case .signature, .tripleSignature: .signature
        case .leftThumb, .thumbImpression: .thumb
        case .handwrittenDeclaration: .declaration
        default: .photo
        }
    }

    /// Dark pen strokes on pale paper with a shadow gradient.
    private func pen(_ w: Int, _ h: Int) -> Raster {
        Raster.make(w, h) { x, y in
            let shade = 238 - x * 60 / w
            let ink = (x / 4 + y / 6) % 11 == 0 && x >= w / 8 && x <= w * 7 / 8 && y >= h / 4 && y <= h * 3 / 4
            return ink ? 0x1A1F55 : (shade << 16) | (shade << 8) | shade
        }
    }

    private func sources() -> [(String, Raster)] {
        [("noisy portrait", noisyRaster(600, 800, noise: 80, seed: 3)), ("smooth landscape", noisyRaster(640, 360, noise: 5, seed: 9)),
         ("tiny", noisyRaster(90, 120, noise: 40, seed: 5)), ("pen drawing", pen(500, 220))]
    }

    @Test("every fit result is accepted by the slot it was made for")
    func property() throws {
        let slots = try allSlots()
        #expect(slots.count >= 20, "expected a broad set of distinct slots, got \(slots.count)")
        var successes = 0, failures = 0
        for slot in slots {
            for (name, source) in sources() {
                let label = "\(slot.type) \(String(describing: slot.sizeKb)) \(slot.dimensions.mode) <- \(name)"
                switch pipeline.run(source, spec: slot) {
                case let .failure(error):
                    #expect(error == .tooDetailed, "\(label): only tooDetailed is an acceptable failure")
                    failures += 1
                case let .success(result):
                    successes += 1
                    let facts = FileFacts(Inspector.inspect(result.fit.bytes), docKind: kind(slot.type))
                    let verdict = MatchEngine.evaluate(slot, facts)
                    #expect(verdict.verdict == .exact || verdict.verdict == .accepted,
                            "\(label): got \(verdict.verdict) \(verdict.failed) (\(facts.kb) KB \(facts.width ?? 0)x\(facts.height ?? 0))")
                }
            }
        }
        #expect(successes > failures * 3 && successes >= 60, "the property must not pass vacuously: \(successes) ok / \(failures) failed")
    }

    @Test("ink pipelines also produce files their slots accept")
    func inkPipelines() throws {
        let slots = try allSlots().filter { [.signature, .leftThumb, .handwrittenDeclaration].contains($0.type) }
        #expect(!slots.isEmpty)
        for slot in slots {
            let which: Pipeline = slot.type == .signature ? .signatureCleanup : (slot.type == .leftThumb ? .thumbCleanup : .documentCleanup)
            guard case let .success(result) = pipeline.run(pen(500, 220), spec: slot, pipeline: which) else { continue }
            let verdict = MatchEngine.evaluate(slot, FileFacts(Inspector.inspect(result.fit.bytes), docKind: kind(slot.type)))
            #expect(verdict.verdict == .exact || verdict.verdict == .accepted, "\(slot.type): \(verdict.verdict) \(verdict.failed)")
        }
    }
}
