import CoreGraphics
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
    @ObservationIgnored private let images: any KitImages
    @ObservationIgnored private let load: @Sendable () async -> PresetBundle?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var bundle: PresetBundle?

    public init(
        drafts: any DraftStore = NoDraftStore(), images: any KitImages = LiveKitImages(),
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.drafts = drafts
        self.images = images
        self.load = load
    }

    /// A Prepared row's thumbnail: the retained draft, re-read through its checks (ALGORITHMS 13.3 previews).
    public func thumbnail(_ route: FlowRoute) async -> CGImage? {
        guard case let .exam(examId, docType) = route.target,
              let exam = bundle?.exams.first(where: { $0.id == examId }),
              let spec = exam.documents.first(where: { $0.type == docType }),
              let draft = await drafts.load(examID: examId, spec: spec, kind: DocKind.of(docType)) else { return nil }
        let images = self.images
        return await Task.detached(priority: .userInitiated) {
            images.image(draft.bytes, maxPixelSize: KitOriginalsViewModel.thumbnailPixels)
        }.value
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
        self.bundle = bundle
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
