#if DEBUG
import DesignSystem
import Imaging
import Match
import ScanData
import ScanModel
import SwiftUI

private actor ChecklistPreviewDrafts: DraftStore {
    func load(examID: String, spec: DocSpec, kind: DocKind) -> RetainedDraft? {
        guard spec.type == .photo || spec.type == .signature else { return nil }
        let width = spec.dimensions.width ?? 200, height = spec.dimensions.height ?? 230
        let image = Raster.make(width, height) { _, _ in 0xFFFFFF }
        let encoded = ImageIOJpegEncoder().encode(image, quality: 90)
        guard case let .success(patched) = JpegPatcher.patch(encoded, dpi: 200) else { return nil }
        let bytes = JpegPatcher.pad(patched, to: (spec.type == .photo ? 34 : 16) * 1024)
        guard ExportOperation.verifies(bytes, expected: bytes, spec: spec, kind: kind) else { return nil }
        return RetainedDraft(bytes: bytes, createdAt: Date())
    }

    func retain(_ bytes: [UInt8], examID: String, spec: DocSpec, kind: DocKind) throws { throw DraftError.storage }

    func revisions() -> AsyncStream<UInt64> { AsyncStream { $0.finish() } }
}

private struct ExamDraftPreview: View {
    let strings: Strings

    var body: some View {
        let json = """
        {"id":"preview","name":"IBPS PO / MT","body":"IBPS","category":"banking","status":"active",
         "documents":[
          {"type":"photo","required":true,"formats":["jpg"],"size_kb":{"min":20,"max":50},
           "dimensions":{"mode":"preferred","width":200,"height":230}},
          {"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":10,"max":20},
           "dimensions":{"mode":"preferred","width":140,"height":60}},
          {"type":"left_thumb","required":true,"formats":["jpg"],"size_kb":{"min":20,"max":50},
           "dimensions":{"mode":"preferred","width":240,"height":240}}],
         "special_rules":[],"sources":[{"url":"https://www.ibps.in","kind":"official"}],
         "confidence":"low","confidence_note":"","last_verified":"2026-09-30"}
        """
        if let exam = try? PresetBundle.decodeExam(Data(json.utf8)),
           let defaults = UserDefaults(suiteName: "scanfit.preview.drafts") {
            NavigationStack {
                ExamView(model: ExamViewModel(
                    examId: exam.id, preferences: UserPreferences(defaults: defaults), drafts: ChecklistPreviewDrafts()
                ) { PresetBundle(presetsVersion: 2, exams: [exam], categories: [:], popular: []) }, strings: strings)
            }
        }
    }
}

#Preview("Ready checklist - English light") {
    ExamDraftPreview(strings: Strings(languageCode: "en")).preferredColorScheme(.light)
}

#Preview("Ready checklist - English dark largest") {
    ExamDraftPreview(strings: Strings(languageCode: "en"))
        .preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}

#Preview("Ready checklist - Hindi largest") {
    ExamDraftPreview(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi")).dynamicTypeSize(.accessibility5)
}

#Preview("Ready checklist - Hindi dark largest") {
    ExamDraftPreview(strings: Strings(languageCode: "hi"))
        .environment(\.locale, Locale(identifier: "hi"))
        .preferredColorScheme(.dark).dynamicTypeSize(.accessibility5)
}
#endif
