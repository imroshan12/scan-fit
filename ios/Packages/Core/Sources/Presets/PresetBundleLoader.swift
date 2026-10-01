import Foundation
import ScanModel

/// The load order shared by both platforms (spec/tools/make_signing_vector.py documents it):
/// 1. signature  2. header parse  3. schema gate  4. version monotonic  5. full decode.
/// Every failure keeps the currently installed bundle; the caller never sees a half-trusted bundle.
public enum PresetBundleLoader {
    public static func load(
        bundle: Data,
        signatureText: String,
        publicKey: PresetPublicKey,
        currentVersion: Int
    ) -> Result<PresetBundle, PresetLoadError> {
        guard PresetVerifier.isValid(bundle: bundle, signatureText: signatureText, publicKey: publicKey) else {
            return .failure(.badSignature)
        }
        guard let header = try? PresetBundle.decodeHeader(bundle) else { return .failure(.parse) }
        guard header.schemaVersion <= PresetBundle.supportedSchemaVersion else { return .failure(.badSchema) }
        guard header.presetsVersion > currentVersion else { return .failure(.staleVersion) }
        guard let decoded = try? PresetBundle.decode(bundle) else { return .failure(.parse) }
        return .success(decoded)
    }
}
