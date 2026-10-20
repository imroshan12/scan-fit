import Foundation
import Imaging
import ScanData

public extension InkFlowViewModel {
    /// Saves the review (ALGORITHMS 1.6) once the running render has finished; Saved is recorded only after the
    /// exporter verified and published the file.
    func save() async {
        guard canSave else { return }
        exportState = .saving
        await renderTask?.value
        guard case let .review(review) = state, case let .ready(ready) = review.result, ready.meetsRules else {
            exportState = .idle
            return
        }
        let filename = ExportNaming.fileName(
            examName: review.slot.examName, slot: review.slot.spec,
            width: ready.width, height: ready.height, bytes: ready.bytes.count
        )
        let request = ExportRequest(
            bytes: ready.bytes, filename: filename, spec: review.slot.spec, kind: review.slot.kind
        )
        // ALGORITHMS 14.3: spent free saves open the paywall; the review stays as it was.
        guard gate?.allows([ready.bytes], trigger: .save) ?? true else {
            exportState = .idle
            return
        }
        exportState = await exporter.save(request)
        guard exportState == .saved else { return }
        await gate?.record(request.bytes)
        if !review.slot.custom { preferences?.recordSaved(review.slot.examId, review.slot.spec.type) }
        await kit.recordExport(request, examId: review.slot.examId, examName: review.slot.examName)
    }

    /// Shares exactly the review bytes under the §9.8 name (ALGORITHMS 1.6.2). Allowed whenever Save is, once the
    /// render has finished; it never records Saved, because the destination cannot be re-read.
    func share() async {
        guard canSave, !renderPending, case let .review(review) = state, case let .ready(ready) = review.result,
              ready.meetsRules else { return }
        let filename = ExportNaming.fileName(
            examName: review.slot.examName, slot: review.slot.spec,
            width: ready.width, height: ready.height, bytes: ready.bytes.count
        )
        guard gate?.allows([ready.bytes], trigger: .share) ?? true else { return }
        let url = await ShareStager.stage(ready.bytes, filename: filename)
        if url != nil { await gate?.record(ready.bytes) }
        guard case let .review(current) = state, current.result == review.result else { return }
        share = url.map { .ready(SharedFile(url: $0)) } ?? .failed
    }

    /// The share sheet closed.
    func shareHandled() {
        if case .ready = share { share = .none }
    }
}
