import Foundation
import ScanModel

/// The format checks shared by both platforms (spec/README.md "Loading presets", cases in
/// spec/fixtures/presets_load/cases.json): 1. header parse  2. schema gate  3. version only goes up  4. full decode.
/// Every failure keeps the currently installed bundle; the caller never sees a half-read bundle.
public enum PresetBundleLoader {
    public static func load(bundle: Data, currentVersion: Int) -> Result<PresetBundle, PresetLoadError> {
        guard let header = try? PresetBundle.decodeHeader(bundle) else { return .failure(.parse) }
        guard header.schemaVersion <= PresetBundle.supportedSchemaVersion else { return .failure(.badSchema) }
        guard header.presetsVersion > currentVersion else { return .failure(.staleVersion) }
        guard let decoded = try? PresetBundle.decode(bundle) else { return .failure(.parse) }
        return .success(decoded)
    }
}
