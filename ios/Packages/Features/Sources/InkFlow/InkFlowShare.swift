import Foundation
import Imaging
import ScanData

public extension InkFlowViewModel {
    /// Shares exactly the review bytes under the §9.8 name (ALGORITHMS 1.6.2). Allowed whenever Save is, once the
    /// render has finished; it never records Saved, because the destination cannot be re-read.
    func share() async {
        guard canSave, !renderPending, case let .review(review) = state, case let .ready(ready) = review.result,
              ready.meetsRules else { return }
        let filename = ExportNaming.fileName(
            examName: review.slot.examName, slot: review.slot.spec,
            width: ready.width, height: ready.height, bytes: ready.bytes.count
        )
        let url = await ShareStager.stage(ready.bytes, filename: filename)
        guard case let .review(current) = state, current.result == review.result else { return }
        share = url.map { .ready(SharedFile(url: $0)) } ?? .failed
    }

    /// The share sheet closed.
    func shareHandled() {
        if case .ready = share { share = .none }
    }
}
