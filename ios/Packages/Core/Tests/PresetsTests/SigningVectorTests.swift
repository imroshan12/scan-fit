import Foundation
import Presets
import ScanModel
import TestSupport
import Testing

/// Runs spec/fixtures/signing/vector.json, the cross-platform Ed25519 test vector (ROADMAP Phase 0).
/// The valid case must verify; every other case must be rejected for the stated reason.
private struct Vector: Decodable {
    struct VerifyCase: Decodable, Sendable, CustomTestStringConvertible {
        let id: String
        let bundle: String
        let signature: String
        let publicKey: String
        let valid: Bool
        var testDescription: String { id }
    }

    struct LoadCase: Decodable, Sendable, CustomTestStringConvertible {
        let id: String
        let bundle: String
        let signature: String
        let publicKey: String
        let currentVersion: Int
        let expect: String
        var testDescription: String { id }
    }

    let supportedSchemaVersion: Int
    let supportedFailureReasons: [String]
    let verifyCases: [VerifyCase]
    let loadCases: [LoadCase]

    static func load() -> Vector? {
        guard let data = try? SpecFiles.data("fixtures/signing/vector.json") else { return nil }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try? decoder.decode(Vector.self, from: data)
    }

    static let shared = load()
}

private func fixture(_ name: String) throws -> Data {
    try SpecFiles.data("fixtures/signing/\(name)")
}

private func key(_ name: String) throws -> PresetPublicKey {
    let text = try String(decoding: fixture(name), as: UTF8.self)
    return try #require(PresetPublicKey(base64: text), "\(name) is not a valid Ed25519 public key")
}

@Suite("Signing vector")
struct SigningVectorTests {
    @Test("vector.json is present, versioned for this build, and non-trivial")
    func vectorIsUsable() throws {
        let vector = try #require(Vector.shared, "spec/fixtures/signing/vector.json missing or unreadable")
        #expect(vector.supportedSchemaVersion == PresetBundle.supportedSchemaVersion)
        #expect(vector.verifyCases.count >= 8)
        #expect(vector.loadCases.count >= 8)
        #expect(Set(vector.verifyCases.map(\.valid)) == [true, false])
        let reasons = Set(vector.loadCases.map(\.expect)).subtracting(["ok"])
        #expect(reasons == Set(vector.supportedFailureReasons))
        #expect(Set(PresetLoadError.allRawValues) == Set(vector.supportedFailureReasons))
    }

    @Test("raw verification matches the vector", arguments: Vector.shared?.verifyCases ?? [])
    fileprivate func verify(_ testCase: Vector.VerifyCase) throws {
        let bundle = try fixture(testCase.bundle)
        let signature = String(decoding: try fixture(testCase.signature), as: UTF8.self)
        let ok = PresetVerifier.isValid(bundle: bundle, signatureText: signature, publicKey: try key(testCase.publicKey))
        #expect(ok == testCase.valid, "\(testCase.id)")
    }

    @Test("full loader order matches the vector", arguments: Vector.shared?.loadCases ?? [])
    fileprivate func load(_ testCase: Vector.LoadCase) throws {
        let result = PresetBundleLoader.load(
            bundle: try fixture(testCase.bundle),
            signatureText: String(decoding: try fixture(testCase.signature), as: UTF8.self),
            publicKey: try key(testCase.publicKey),
            currentVersion: testCase.currentVersion
        )
        switch result {
        case .success:
            #expect(testCase.expect == "ok", "\(testCase.id): loaded but expected \(testCase.expect)")
        case let .failure(error):
            #expect(error.rawValue == testCase.expect, "\(testCase.id): got \(error.rawValue), expected \(testCase.expect)")
        }
    }

    @Test("a tampered bundle never decodes, even when only one byte changes")
    func tamperedBundleIsRejected() throws {
        let good = try fixture("sample_presets.json")
        var flipped = good
        flipped[flipped.count / 2] ^= 0x01
        let signature = String(decoding: try fixture("sample_presets.json.sig"), as: UTF8.self)
        let result = PresetBundleLoader.load(bundle: flipped, signatureText: signature,
                                             publicKey: try key("dev_public_key.b64"), currentVersion: 0)
        #expect(result == .failure(.badSignature))
    }

    @Test("a key that is not 32 base64 bytes is refused")
    func badKeysAreRefused() {
        #expect(PresetPublicKey(base64: "") == nil)
        #expect(PresetPublicKey(base64: "not base64!") == nil)
        #expect(PresetPublicKey(base64: Data(repeating: 1, count: 31).base64EncodedString()) == nil)
    }
}

extension PresetLoadError {
    static let allRawValues = [badSignature, badSchema, staleVersion, parse].map(\.rawValue)
}
