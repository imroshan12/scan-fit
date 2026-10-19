import DesignSystem
import Foundation
import Imaging
import PDF
import ScanModel
import SwiftUI

#if DEBUG
@MainActor
private enum PdfPreviewData {
    static let page = Raster.make(120, 170) { x, y in y % 12 < 3 && (12...108).contains(x) ? 0x303030 : 0xF7F7F7 }
    static let exam = PdfTarget(
        examId: "preview_entrance", examName: "Entrance exam (preview)",
        spec: .pdf(.class10Certificate, sizeKb: SizeKB(min: 50, max: 300, target: 200)), unverified: false, exam: true
    )
    static let examRoute = PdfRoute(examId: "preview_entrance", docType: .class10Certificate)
    static let items = [
        PdfItem(id: 1, document: false, pages: 1, kb: 2400, thumbnail: page),
        PdfItem(
            id: 2, document: false, pages: 1, kb: 1900, thumbnail: page, crop: CropRect(x: 10, y: 10, w: 80, h: 120)
        ),
        PdfItem(id: 3, document: true, pages: 3, kb: 812, thumbnail: page),
    ]

    /// Empty tool, an exam slot with files, making, the result after a save, a failure, an ID card, and the crop.
    static func models() -> [PdfMakerViewModel] {
        [
            .preview(route: PdfRoute(tool: "images"), target: .tool(.images, maxKb: 200)),
            .preview(route: examRoute, target: exam, items: items, addFailure: .encrypted),
            .preview(route: examRoute, target: exam, items: items, making: .fitting(page: 1, total: 5)),
            .preview(route: examRoute, target: exam, items: items,
                     result: .ready(bytes: [], kb: 182, pages: 5, dpi: 150, strategy: .lowerDpi, preview: page,
                                    meetsRules: true),
                     exportState: .saved),
            .preview(route: PdfRoute(tool: "compress"), target: .tool(.compress, maxKb: 100), items: [items[2]],
                     result: .failed(.cannotReach)),
            .preview(route: PdfRoute(tool: "id_card"), target: .tool(.idCard, maxKb: 200), items: [items[0]]),
            .preview(route: examRoute, target: exam, items: items,
                     crop: PdfCrop(itemId: 1, image: page, rect: CropRect(x: 10, y: 10, w: 90, h: 140))),
        ]
    }
}

private struct PdfMakerPreviews: View {
    let model: PdfMakerViewModel
    let strings: Strings

    var body: some View {
        NavigationStack { PdfMakerView(model: model, strings: strings) }
    }
}

#Preview("PDF - empty tool, light") {
    PdfMakerPreviews(model: PdfPreviewData.models()[0], strings: Strings(languageCode: "en"))
}

#Preview("PDF - exam files, dark") {
    PdfMakerPreviews(model: PdfPreviewData.models()[1], strings: Strings(languageCode: "en"))
        .preferredColorScheme(.dark)
}

#Preview("PDF - making, light") {
    PdfMakerPreviews(model: PdfPreviewData.models()[2], strings: Strings(languageCode: "en"))
}

#Preview("PDF - saved, Hindi largest") {
    PdfMakerPreviews(model: PdfPreviewData.models()[3], strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .dynamicTypeSize(.accessibility5)
}

#Preview("PDF - cannot reach, dark largest") {
    PdfMakerPreviews(model: PdfPreviewData.models()[4], strings: Strings(languageCode: "en"))
        .preferredColorScheme(.dark)
        .dynamicTypeSize(.accessibility5)
}

#Preview("PDF - ID card, Hindi") {
    PdfMakerPreviews(model: PdfPreviewData.models()[5], strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
}

#Preview("PDF - crop, light") {
    PdfMakerPreviews(model: PdfPreviewData.models()[6], strings: Strings(languageCode: "en"))
}
#endif
