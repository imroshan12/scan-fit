import Foundation
import Imaging
import Inspect
import Match
import ScanData
import ScanModel

public extension InkFlowViewModel {
    /// Keeps the picture this review was made from in My Kit (ALGORITHMS 13.1). The review itself never changes.
    func saveOriginal() async {
        guard case let .review(review) = state, !review.restored, let source = lastCrop?.image,
              kitSave != .saving, kitSave != .saved else { return }
        let gen = sourceGeneration
        kitSave = .saving
        let tools = self.tools
        let jpeg = await Task.detached(priority: .userInitiated) { tools.encodeOriginal(source) }.value
        let result: KitSaveStatus = if let jpeg {
            KitSaveStatus(await kit.saveOriginal(kind: review.slot.kind, jpeg: jpeg))
        } else {
            .failed
        }
        if gen == sourceGeneration { kitSave = result }
    }
}

extension InkFlowViewModel {
    /// Re-inspects the fitted bytes and evaluates them against the slot, as the export will be (rule 3).
    nonisolated static func ready(
        _ bytes: [UInt8], _ spec: DocSpec, _ kind: DocKind, _ quality: InkQuality, _ exams: [Exam], _ popular: [String]
    ) -> InkReady {
        let inspected = Inspector.inspect(bytes)
        let facts = FileFacts(inspected, docKind: kind)
        let evaluation = MatchEngine.evaluate(spec, facts)
        return InkReady(
            bytes: bytes,
            kb: (bytes.count + 512) / 1024,
            width: inspected.width ?? 0,
            height: inspected.height ?? 0,
            meetsRules: evaluation.verdict == .exact || evaluation.verdict == .accepted,
            quality: quality,
            note: MatchNote.of(facts, exams: exams, popularity: popular),
            checks: .of(evaluation)
        )
    }
}
