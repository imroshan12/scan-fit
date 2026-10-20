import Foundation
import ScanModel

/// What the UI needs to know about the presets that are currently trusted.
public struct PresetsSummary: Sendable, Equatable {
    public let examCount: Int
    public let version: Int

    public init(examCount: Int, version: Int) {
        self.examCount = examCount
        self.version = version
    }
}

public enum PresetsLoadOutcome: Sendable, Equatable {
    case ready(PresetsSummary)
    case failed(PresetLoadError)
    /// The build is missing its embedded snapshot (a packaging bug, surfaced instead of crashing).
    case missingEmbedded
}

/// Where the embedded snapshot comes from. The app target reads the file that its build phase copied
/// into the main bundle (`Scripts/embed_presets.sh`); tests inject fixtures.
public struct PresetFiles: Sendable {
    public let bundle: Data

    public init(bundle: Data) {
        self.bundle = bundle
    }

    /// Reads `presets.json` from a bundle's resources.
    public static func embedded(in bundle: Bundle = .main) -> PresetFiles? {
        guard let url = bundle.url(forResource: "presets", withExtension: "json"),
              let json = try? Data(contentsOf: url) else { return nil }
        return PresetFiles(bundle: json)
    }
}

/// Holds the trusted presets. Phase 0 loads only the embedded snapshot; OTA sync (ETag, WorkManager-style
/// background fetch, "Spec updated" badges) lands in Phase 4 on top of the same loader.
public actor PresetsRepository {
    private var current: PresetBundle?

    public init() {}

    public var bundle: PresetBundle? { current }

    /// Checks the embedded snapshot's format and installs it. A corrupt or unsupported snapshot is rejected.
    @discardableResult
    public func loadEmbedded(_ files: PresetFiles?) -> PresetsLoadOutcome {
        guard let files else { return .missingEmbedded }
        let result = PresetBundleLoader.load(bundle: files.bundle, currentVersion: current?.presetsVersion ?? 0)
        switch result {
        case let .success(bundle):
            current = bundle
            return .ready(PresetsSummary(examCount: bundle.exams.count, version: bundle.presetsVersion))
        case let .failure(error):
            return .failed(error)
        }
    }
}
