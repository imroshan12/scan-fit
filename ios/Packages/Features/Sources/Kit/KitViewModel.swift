import Match
import Observation
import ScanData
import ScanModel

public struct KitRow: Identifiable, Equatable, Sendable {
    public let route: FlowRoute
    public let roundedKB: Int
    public var id: DocType { route.docType }

    public init(route: FlowRoute, roundedKB: Int) {
        self.route = route
        self.roundedKB = roundedKB
    }
}

public struct KitGroup: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let confidence: Confidence
    public let rows: [KitRow]

    public init(id: String, name: String, confidence: Confidence, rows: [KitRow]) {
        self.id = id
        self.name = name
        self.confidence = confidence
        self.rows = rows
    }
}

@MainActor
@Observable
public final class KitViewModel {
    public enum State: Equatable, Sendable {
        case loading, empty, failed
        case ready([KitGroup])
    }

    public private(set) var state: State = .loading
    @ObservationIgnored private let drafts: any DraftStore
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    @ObservationIgnored private var generation = 0

    public init(
        drafts: any DraftStore = NoDraftStore(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.drafts = drafts
        self.load = load
    }

    #if DEBUG
    convenience init(previewState: State) {
        self.init { nil }
        state = previewState
    }
    #endif

    public func refresh() async {
        guard !Task.isCancelled else { return }
        generation += 1
        let current = generation
        state = .loading
        let bundle = await load()
        guard !Task.isCancelled, current == generation else { return }
        guard let bundle else {
            state = .failed
            return
        }
        var groups: [KitGroup] = []
        for exam in bundle.exams where exam.status == .active {
            var rows: [KitRow] = []
            for spec in exam.documents {
                let kind = DocKind.of(spec.type)
                guard kind != .pdfDocument else { continue }
                let draft = await drafts.load(examID: exam.id, spec: spec, kind: kind)
                guard !Task.isCancelled, current == generation else { return }
                if let draft {
                    rows.append(KitRow(
                        route: FlowRoute(examId: exam.id, docType: spec.type),
                        roundedKB: (draft.bytes.count + 512) / 1024
                    ))
                }
            }
            if !rows.isEmpty {
                groups.append(KitGroup(id: exam.id, name: exam.name, confidence: exam.confidence, rows: rows))
            }
        }
        guard !Task.isCancelled, current == generation else { return }
        state = groups.isEmpty ? .empty : .ready(groups)
    }

    public func observeDrafts() async {
        let stream = await drafts.revisions()
        for await _ in stream {
            guard !Task.isCancelled else { return }
            await refresh()
        }
    }
}
