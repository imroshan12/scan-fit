import Match
import Observation
import ScanData
import ScanModel

/// The exam checklist (UI_UX §3). Same states as Android's `ExamViewModel`.
@MainActor
@Observable
public final class ExamViewModel {
    public enum State: Equatable, Sendable {
        case loading
        /// The id is not in the trusted presets (a stale link, or a retired exam).
        case notFound
        case ready(Exam)
    }

    public private(set) var state: State = .loading
    public private(set) var readyDocuments: [DocType: Int] = [:]
    public let examId: String
    public let preferences: UserPreferences
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    @ObservationIgnored private let drafts: any DraftStore
    @ObservationIgnored private var generation = 0

    public init(
        examId: String, preferences: UserPreferences, drafts: any DraftStore = NoDraftStore(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.examId = examId
        self.preferences = preferences
        self.load = load
        self.drafts = drafts
    }

    public var isPinned: Bool { preferences.isPinned(examId) }

    public func onAppear() async {
        generation += 1
        let gen = generation
        readyDocuments = [:]
        let id = examId
        let bundle = await load()
        guard !Task.isCancelled, gen == generation else { return }
        guard let exam = bundle?.exams.first(where: { $0.id == id && $0.status == .active }) else {
            state = .notFound
            return
        }
        var available: [DocType: Int] = [:]
        for spec in exam.documents {
            let draft = await drafts.load(examID: id, spec: spec, kind: DocKind.of(spec.type))
            guard !Task.isCancelled, gen == generation else { return }
            if let draft { available[spec.type] = (draft.bytes.count + 512) / 1024 }
        }
        readyDocuments = available
        state = .ready(exam)
    }

    public func observeDrafts() async {
        let stream = await drafts.revisions()
        for await _ in stream {
            guard !Task.isCancelled else { return }
            await onAppear()
        }
    }

    public enum DocumentStatus: Equatable {
        case saved, ready(Int), notStarted
    }

    public func status(_ type: DocType) -> DocumentStatus {
        if preferences.isSaved(examId, type) { return .saved }
        if let kb = readyDocuments[type] { return .ready(kb) }
        return .notStarted
    }

    public func togglePin() {
        preferences.setPinned(examId, !isPinned)
    }

}
