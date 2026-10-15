import Imaging
import Inspect
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

    /// Save all on the checklist (ALGORITHMS 1.6.2). iOS picks one folder for every file, so progress is not per file.
    public enum SaveAllStatus: Equatable, Sendable {
        case idle, saving
        case done(saved: Int, total: Int, folder: String?)
    }

    public private(set) var state: State = .loading
    public private(set) var readyDocuments: [DocType: Int] = [:]
    public private(set) var saveAllStatus: SaveAllStatus = .idle
    public let examId: String
    public let preferences: UserPreferences
    public let exporter: any DocumentExporting
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    @ObservationIgnored private let drafts: any DraftStore
    @ObservationIgnored private var generation = 0

    public init(
        examId: String, preferences: UserPreferences, drafts: any DraftStore = NoDraftStore(),
        exporter: any DocumentExporting = FilesExporter(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.examId = examId
        self.preferences = preferences
        self.load = load
        self.drafts = drafts
        self.exporter = exporter
    }

    /// The Ready rows Save all writes, and the signatures it holds back for the handwriting tick.
    public var saveAllPlan: SaveAllPlan {
        guard case let .ready(exam) = state else { return .empty }
        let types = exam.documents.map(\.type)
        return .of(
            documents: types, ready: Set(readyDocuments.keys),
            saved: Set(types.filter { preferences.isSaved(examId, $0) }),
            handwritingConfirmed: preferences.handwritingConfirmed
        )
    }

    /// Saves every Ready row through one Files picker (ALGORITHMS 1.6.2): each draft is re-read, written, matched back
    /// by its bytes and verified on its own; Saved is recorded per verified file. Cancelling saves nothing.
    public func saveAll() async {
        guard saveAllStatus != .saving, case let .ready(exam) = state else { return }
        let plan = saveAllPlan.included
        guard !plan.isEmpty else { return }
        saveAllStatus = .saving
        var requests: [(DocType, ExportRequest)] = []
        var missing = 0
        for type in plan {
            guard let spec = exam.documents.first(where: { $0.type == type }) else { continue }
            let kind = DocKind.of(type)
            // The draft may have expired since the checklist loaded: then this row fails, the others go on.
            guard let draft = await drafts.load(examID: exam.id, spec: spec, kind: kind) else {
                missing += 1
                continue
            }
            let inspected = Inspector.inspect(draft.bytes)
            let filename = ExportNaming.fileName(
                examName: exam.name, slot: spec, width: inspected.width ?? 0, height: inspected.height ?? 0,
                bytes: draft.bytes.count
            )
            requests.append((type, ExportRequest(bytes: draft.bytes, filename: filename, spec: spec, kind: kind)))
        }
        let outcome = requests.isEmpty
            ? SaveAllOutcome(states: [], folder: nil)
            : await exporter.saveAll(requests.map(\.1))
        if !outcome.states.isEmpty, outcome.states.allSatisfy({ $0 == .idle }) {
            saveAllStatus = .idle
            return
        }
        var saved = 0
        for ((type, _), result) in zip(requests, outcome.states) where result == .saved {
            preferences.recordSaved(exam.id, type)
            saved += 1
        }
        saveAllStatus = .done(saved: saved, total: requests.count + missing, folder: outcome.folder)
    }

    /// The finished message was read.
    public func saveAllDismissed() {
        if case .done = saveAllStatus { saveAllStatus = .idle }
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
