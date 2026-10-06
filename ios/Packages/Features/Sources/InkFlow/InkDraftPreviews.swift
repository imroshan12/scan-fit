#if DEBUG
import DesignSystem
import Imaging
import Inspect
import Match
import ScanModel
import SwiftUI

private struct InkDraftPreview: View {
    let strings: Strings
    @State private var review: InkReviewState?

    var body: some View {
        Group {
            if let review {
                InkReviewView(review: review, model: .preview(review), strings: strings)
            } else {
                ProgressView()
            }
        }
        .task {
            review = await Task.detached {
                let json = """
                {"type":"signature","required":true,"formats":["jpg"],"sizeKb":{"min":10,"max":20},
                 "dimensions":{"mode":"preferred","width":140,"height":60},"dpi":200}
                """
                guard let spec = try? JSONDecoder().decode(DocSpec.self, from: Data(json.utf8)) else { return nil }
                let image = Raster.make(140, 60) { column, row in
                    let stroke = Int(30 + 10 * sin(Double(column) / 8))
                    return (15...125).contains(column) && abs(row - stroke) < 2 ? 0x202020 : 0xFFFFFF
                }
                let encoded = ImageIOJpegEncoder().encode(image, quality: 90)
                guard case let .success(patched) = JpegPatcher.patch(encoded, dpi: 200) else { return nil }
                let bytes = JpegPatcher.pad(patched, to: 16 * 1024)
                let facts = FileFacts(Inspector.inspect(bytes), docKind: .signature)
                let ready = InkReady(
                    bytes: bytes, kb: 16, width: 140, height: 60, meetsRules: true, quality: .ok,
                    note: MatchNote.of(facts, exams: [], popularity: []), checks: .of(MatchEngine.evaluate(spec, facts))
                )
                let slot = InkSlot(examId: "preview", examName: "IBPS PO / MT", unverified: false, spec: spec)
                return InkReviewState(
                    slot: slot, options: InkReviewOptions(crispBlack: false), result: .ready(ready),
                    handwritingConfirmed: false, restored: true
                )
            }.value
        }
    }
}

#Preview("Restored signature - English light") {
    InkDraftPreview(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Restored signature - English dark largest") {
    InkDraftPreview(strings: Strings(languageCode: "en"))
        .preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}

#Preview("Restored signature - Hindi largest") {
    InkDraftPreview(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi")).dynamicTypeSize(.accessibility5)
}

#Preview("Restored signature - Hindi dark largest") {
    InkDraftPreview(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}
#endif
