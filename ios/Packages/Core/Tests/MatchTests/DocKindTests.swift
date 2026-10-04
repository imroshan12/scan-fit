import Match
import ScanModel
import Testing

/// ALGORITHMS 9.5: the kind each slot type is matched and export-checked as.
@Suite("DocKind")
struct DocKindTests {
    @Test("every slot type maps to its kind")
    func mapping() {
        let expected: [DocType: DocKind] = [
            .photo: .photo, .postcardPhoto: .photo, .signature: .signature, .tripleSignature: .signature,
            .leftThumb: .thumb, .thumbImpression: .thumb, .leftHandFingersThumb: .fingers,
            .rightHandFingersThumb: .fingers, .handwrittenDeclaration: .declaration,
            .class10Certificate: .pdfDocument, .idProof: .pdfDocument,
        ]
        for (type, kind) in expected { #expect(DocKind.of(type) == kind, "\(type)") }
    }
}
