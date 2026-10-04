/// Navigation value for the photo flow of one exam slot. Shared by the exam screen (which links to it) and the app
/// target (which shows `PhotoFlowView` for it), so the two features never import each other (ARCHITECTURE §3).
public struct PhotoRoute: Hashable, Sendable {
    public let examId: String
    public let docType: DocType

    public init(examId: String, docType: DocType) {
        self.examId = examId
        self.docType = docType
    }
}
