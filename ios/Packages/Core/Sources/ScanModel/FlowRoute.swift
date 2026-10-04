/// Navigation value for the flow of one exam slot (photo or ink). Shared by the exam screen (which links to it) and the
/// app target (which shows the photo or ink flow for its type), so features never import each other (ARCHITECTURE §3).
public struct FlowRoute: Hashable, Sendable {
    public let examId: String
    public let docType: DocType

    public init(examId: String, docType: DocType) {
        self.examId = examId
        self.docType = docType
    }
}
