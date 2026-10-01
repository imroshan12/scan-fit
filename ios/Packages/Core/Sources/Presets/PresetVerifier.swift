import CryptoKit
import Foundation

/// Why a preset bundle was refused. Raw values match the `sync_failure_reason` analytics enum and the
/// `expect` field of `spec/fixtures/signing/vector.json`.
public enum PresetLoadError: String, Error, Sendable, Equatable {
    case badSignature = "bad_signature"
    case badSchema = "bad_schema"
    case staleVersion = "stale_version"
    case parse
}

/// An Ed25519 public key for verifying preset bundles.
public struct PresetPublicKey: Sendable {
    fileprivate let key: Curve25519.Signing.PublicKey

    public init?(base64: String) {
        guard let raw = Data(base64Encoded: base64.trimmingCharacters(in: .whitespacesAndNewlines)),
              raw.count == 32,
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw) else { return nil }
        self.key = key
    }
}

public enum PresetVerifier {
    /// `true` only if `signatureText` is base64 (surrounding whitespace ignored) of a 64-byte Ed25519
    /// signature over exactly `bundle`. Anything malformed is simply `false`.
    public static func isValid(bundle: Data, signatureText: String, publicKey: PresetPublicKey) -> Bool {
        let trimmed = signatureText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let signature = Data(base64Encoded: trimmed), signature.count == 64 else { return false }
        return publicKey.key.isValidSignature(signature, for: bundle)
    }
}
