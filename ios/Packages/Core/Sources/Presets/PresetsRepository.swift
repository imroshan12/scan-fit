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

/// Where the embedded snapshot comes from. The app target reads files that its build phase copied
/// into the main bundle (`Scripts/embed_presets.sh`); tests inject fixtures.
public struct PresetFiles: Sendable {
    public let bundle: Data
    public let signatureText: String
    public let publicKeyBase64: String

    public init(bundle: Data, signatureText: String, publicKeyBase64: String) {
        self.bundle = bundle
        self.signatureText = signatureText
        self.publicKeyBase64 = publicKeyBase64
    }

    /// Reads `presets.json`, `presets.json.sig` and `presets_public_key.b64` from a bundle's resources.
    public static func embedded(in bundle: Bundle = .main) -> PresetFiles? {
        func read(_ name: String, _ ext: String) -> Data? {
            bundle.url(forResource: name, withExtension: ext).flatMap { try? Data(contentsOf: $0) }
        }
        guard let json = read("presets", "json"),
              let sig = read("presets.json", "sig").flatMap({ String(data: $0, encoding: .utf8) }),
              let key = read("presets_public_key", "b64").flatMap({ String(data: $0, encoding: .utf8) })
        else { return nil }
        return PresetFiles(bundle: json, signatureText: sig, publicKeyBase64: key)
    }
}

/// Holds the trusted presets. Phase 0 loads only the embedded snapshot; OTA sync (ETag, WorkManager-style
/// background fetch, "Spec updated" badges) lands in Phase 4 on top of the same loader.
public actor PresetsRepository {
    private var current: PresetBundle?

    public init() {}

    public var bundle: PresetBundle? { current }

    /// Verifies and installs the embedded snapshot. A forged or corrupt snapshot is rejected, never trusted.
    @discardableResult
    public func loadEmbedded(_ files: PresetFiles?) -> PresetsLoadOutcome {
        guard let files else { return .missingEmbedded }
        guard let key = PresetPublicKey(base64: files.publicKeyBase64) else { return .failed(.badSignature) }
        let result = PresetBundleLoader.load(
            bundle: files.bundle,
            signatureText: files.signatureText,
            publicKey: key,
            currentVersion: current?.presetsVersion ?? 0
        )
        switch result {
        case let .success(bundle):
            current = bundle
            return .ready(PresetsSummary(examCount: bundle.exams.count, version: bundle.presetsVersion))
        case let .failure(error):
            return .failed(error)
        }
    }
}
