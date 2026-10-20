import Foundation
import Match
import ScanModel

/// Which checklist rows "Save all" writes (ALGORITHMS 1.6.2), in preset document order: every Ready row (a verified
/// draft, no Saved record), except an exam signature still waiting for the one-time handwriting tick, which is held.
public struct SaveAllPlan: Sendable, Equatable {
    public let included: [DocType]
    public let held: [DocType]

    public static let empty = SaveAllPlan(included: [], held: [])

    public init(included: [DocType], held: [DocType]) {
        self.included = included
        self.held = held
    }

    public static func of(
        documents: [DocType], ready: Set<DocType>, saved: Set<DocType>, handwritingConfirmed: Bool
    ) -> SaveAllPlan {
        let candidates = documents.filter { ready.contains($0) && !saved.contains($0) }
        let held = candidates.filter { DocKind.of($0) == .signature && !handwritingConfirmed }
        return SaveAllPlan(included: candidates.filter { !held.contains($0) }, held: held)
    }
}

/// The result of saving several files at once: one state per request, in request order, and where they went.
public struct SaveAllOutcome: Sendable, Equatable {
    public let states: [ExportState]
    /// The folder's name for "4 files saved to …", when the destination tells it.
    public let folder: String?

    public init(states: [ExportState], folder: String?) {
        self.states = states
        self.folder = folder
    }
}

public extension DocumentExporting {
    /// One save after another; `FilesExporter` overrides this with a single Files picker for all files.
    func saveAll(_ requests: [ExportRequest]) async -> SaveAllOutcome {
        var states: [ExportState] = []
        for request in requests { states.append(await save(request)) }
        return SaveAllOutcome(states: states, folder: nil)
    }
}

/// Stages the review bytes for the share sheet (ALGORITHMS 1.6.2): one file at a time in an app-private temporary
/// folder, re-read and compared before it is handed out. Sharing never records Saved.
public enum ShareStager {
    static var folder: URL { FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true) }

    /// A file URL for `bytes` named `filename`, or nil when it could not be written exactly. `folder` is for tests
    /// only: every stage empties its folder, so tests that share in parallel each need their own (`ownShareFolder`).
    public static func stage(_ bytes: [UInt8], filename: String, in folder: URL? = nil) async -> URL? {
        let folder = folder ?? Self.folder
        return await Task.detached(priority: .userInitiated) { () -> URL? in
            let url = folder.appendingPathComponent(filename)
            // A §9.8 name is a single path segment; anything else is not shared.
            guard url.deletingLastPathComponent().standardizedFileURL == folder.standardizedFileURL else { return nil }
            do {
                try? FileManager.default.removeItem(at: folder)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                try Data(bytes).write(to: url, options: .atomic)
                guard [UInt8](try Data(contentsOf: url)) == bytes else {
                    try? FileManager.default.removeItem(at: folder)
                    return nil
                }
                return url
            } catch {
                return nil
            }
        }.value
    }

    /// Empties the share folder: called on app start, so a shared file never outlives the session.
    public static func clear(in folder: URL? = nil) async {
        let folder = folder ?? Self.folder
        await Task.detached { try? FileManager.default.removeItem(at: folder) }.value
    }
}

/// A staged file waiting for the share sheet; a fresh id each time, so sharing twice opens the sheet twice.
public struct SharedFile: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public let url: URL

    public init(url: URL) {
        self.url = url
    }
}

/// Share in a review (ALGORITHMS 1.6.2): nothing pending, a staged file for the sheet, or staging failed.
public enum ShareStatus: Equatable, Sendable {
    case none
    case ready(SharedFile)
    case failed

    public var file: SharedFile? {
        if case let .ready(file) = self { file } else { nil }
    }
}
