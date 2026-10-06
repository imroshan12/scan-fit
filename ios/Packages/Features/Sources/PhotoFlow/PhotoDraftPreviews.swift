#if DEBUG
import DesignSystem
import Imaging
import Inspect
import Match
import ScanModel
import SwiftUI

private struct PhotoDraftPreview: View {
    let strings: Strings
    @State private var review: ReviewState?

    var body: some View {
        Group {
            if let review {
                PhotoReviewView(review: review, model: .preview(review), strings: strings)
            } else {
                ProgressView()
            }
        }
        .task {
            review = await Task.detached {
                let json = """
                {"type":"photo","required":true,"formats":["jpg"],"sizeKb":{"min":20,"max":50},
                 "dimensions":{"mode":"preferred","width":200,"height":230},"dpi":200}
                """
                guard let spec = try? JSONDecoder().decode(DocSpec.self, from: Data(json.utf8)) else { return nil }
                let image = Raster.make(200, 230) { column, row in
                    (50...150).contains(column) && (40...190).contains(row) ? 0x567C72 : 0xFFFFFF
                }
                let encoded = ImageIOJpegEncoder().encode(image, quality: 90)
                guard case let .success(patched) = JpegPatcher.patch(encoded, dpi: 200) else { return nil }
                let bytes = JpegPatcher.pad(patched, to: 34 * 1024)
                let facts = FileFacts(Inspector.inspect(bytes), docKind: .photo)
                let ready = ReviewReady(
                    bytes: bytes, kb: 34, width: 200, height: 230, meetsRules: true,
                    note: MatchNote.of(facts, exams: [], popularity: []), checks: .of(MatchEngine.evaluate(spec, facts))
                )
                let slot = PhotoSlot(examId: "preview", examName: "IBPS PO / MT", unverified: false, spec: spec)
                return ReviewState(slot: slot, options: PhotoOptions(), result: .ready(ready), restored: true)
            }.value
        }
    }
}

#Preview("Restored photo - English light") {
    PhotoDraftPreview(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Restored photo - English dark largest") {
    PhotoDraftPreview(strings: Strings(languageCode: "en"))
        .preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}

#Preview("Restored photo - Hindi largest") {
    PhotoDraftPreview(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi")).dynamicTypeSize(.accessibility5)
}

#Preview("Restored photo - Hindi dark largest") {
    PhotoDraftPreview(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}
#endif
