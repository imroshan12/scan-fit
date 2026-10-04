/// Navigation value for an exam checklist. Shared by Home (which links to it) and the app target (which shows
/// `ExamView` for it), so the two features never import each other (ARCHITECTURE §3).
public struct ExamRoute: Hashable, Sendable {
    public let examId: String

    public init(examId: String) {
        self.examId = examId
    }
}
