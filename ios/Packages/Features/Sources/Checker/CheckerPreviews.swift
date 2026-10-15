import DesignSystem
import Foundation
import Inspect
import Match
import ScanModel
import SwiftUI

#if DEBUG
@MainActor
private enum CheckerPreviewData {
    static let slot = CustomSize.parse("signature;140x60;10-20;200")?.spec

    static let exam: Exam? = {
        let json = """
        {"id":"preview_bank","name":"Bank PO (preview)","body":"Preview","category":"banking","status":"active",
         "documents":[],"special_rules":[],"sources":[],"confidence":"high","confidence_note":"",
         "last_verified":"2026-10-01"}
        """
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try? decoder.decode(Exam.self, from: Data(json.utf8))
    }()

    static let file = CheckedFile(
        name: "IMG_2041.jpeg",
        inspected: {
            var inspected = InspectedFile(format: .jpeg, bytes: 48 * 1024)
            inspected.width = 900
            inspected.height = 400
            inspected.issues = [.progressiveJpeg]
            return inspected
        }()
    )

    static let tooBig = SlotEvaluation(verdict: .no, failed: [.sizeKb, .dims], issues: [.tooLargeKb])
    static var targets: [CheckerTarget] {
        guard let exam, let slot else { return [] }
        return [CheckerTarget(exam: exam, slot: slot)]
    }
    static var result: TargetResult? {
        targets.first.map { TargetResult(target: $0, evaluation: tooBig, checks: .of(tooBig), status: .fix) }
    }

    static func models() -> [CheckerViewModel] {
        [
            .preview(file: nil, openFailed: true),
            .preview(file: file, kind: .signature, note: MatchNoteSamples.likelyOk, targets: targets),
            .preview(file: file, kind: .signature, result: result),
        ]
    }
}

private struct CheckerPreviews: View {
    let model: CheckerViewModel
    let strings: Strings

    var body: some View {
        NavigationStack { CheckerView(model: model, strings: strings) }
    }
}

#Preview("Checker - pick, light") {
    CheckerPreviews(model: CheckerPreviewData.models()[0], strings: Strings(languageCode: "en"))
}

#Preview("Checker - exams, dark") {
    CheckerPreviews(model: CheckerPreviewData.models()[1], strings: Strings(languageCode: "en"))
        .preferredColorScheme(.dark)
}

#Preview("Checker - result, Hindi largest") {
    CheckerPreviews(model: CheckerPreviewData.models()[2], strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}
#endif
