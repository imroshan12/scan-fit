/// What a photo or ink flow makes a file for (ALGORITHMS 11): one slot of a trusted exam preset, or a Custom size.
/// The flows resolve it to a `FlowSlot` and branch on `FlowSlot.custom` only where the two differ (drafts, Saved
/// status, the handwriting tick, the verdict text). Same contract as Android's `FlowTarget`.
public enum FlowTarget: Hashable, Sendable {
    case exam(examId: String, docType: DocType)
    case custom(CustomSize)

    public var docType: DocType {
        switch self {
        case let .exam(_, docType): docType
        case let .custom(size): size.doc
        }
    }

    /// The slot to work on, or nil when an exam slot is not (or no longer) in the trusted `bundle`.
    public func resolve(_ bundle: PresetBundle?) -> FlowSlot? {
        switch self {
        case let .custom(size):
            return FlowSlot(
                examId: CustomSize.examId, examName: CustomSize.examName, unverified: false, spec: size.spec,
                custom: true
            )
        case let .exam(examId, docType):
            guard let exam = bundle?.exams.first(where: { $0.id == examId && $0.status == .active }),
                  let spec = exam.documents.first(where: { $0.type == docType }) else { return nil }
            return FlowSlot(
                examId: exam.id, examName: exam.name, unverified: exam.isUnverified, spec: spec, custom: false
            )
        }
    }
}

/// A resolved slot: the exam (or the synthetic Custom exam) and the document spec the flow fits to.
public struct FlowSlot: Sendable, Equatable {
    public let examId: String
    public let examName: String
    /// Low-confidence preset: never "meets the rules" (CLAUDE.md rule 6). Always false for Custom.
    public let unverified: Bool
    public let spec: DocSpec
    public let custom: Bool
}

/// Navigation value for the flow of one exam slot or one Custom size (photo or ink). Shared by the screens that link to
/// it and the app target (which shows the photo or ink flow for its type), so features never import each other
/// (ARCHITECTURE §3).
public struct FlowRoute: Hashable, Sendable {
    public let target: FlowTarget

    public init(examId: String, docType: DocType) {
        target = .exam(examId: examId, docType: docType)
    }

    public init(custom: CustomSize) {
        target = .custom(custom)
    }

    public var docType: DocType { target.docType }

    /// The exam the flow is for; `custom` for a Custom size.
    public var examId: String {
        switch target {
        case let .exam(examId, _): examId
        case .custom: CustomSize.examId
        }
    }
}

/// Navigation value for the Custom size form (ALGORITHMS 11), pushed from Home or Tools.
public struct CustomSizeRoute: Hashable, Sendable {
    public init() {}
}

/// Navigation value for the pre-submit Checker (ALGORITHMS 12), pushed from Home or Tools.
public struct CheckerRoute: Hashable, Sendable {
    public init() {}
}
