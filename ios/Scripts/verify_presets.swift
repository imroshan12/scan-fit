// Verifies the Ed25519 signature of a presets bundle (spec/signing/README.md). embed_presets.sh runs it as an Xcode build
// phase, so iOS builds need no Python. Same rules as the app's own loader: the signature and key are base64 text (surrounding
// whitespace ignored), the key is 32 raw bytes, the signature covers the exact bytes of the bundle.
//
//   xcrun --sdk macosx swift verify_presets.swift <presets.json> <presets.json.sig> <public_key.b64>
//
// Exit status: 0 valid, 1 not valid (a bad or malformed signature or key), 2 usage error or unreadable file.
import CryptoKit
import Foundation

func fail(_ message: String, code: Int32) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(code)
}

func trimmedText(_ path: String) -> String? {
    (try? String(contentsOfFile: path, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines)
}

let arguments = CommandLine.arguments
guard arguments.count == 4 else {
    fail("usage: verify_presets.swift <presets.json> <presets.json.sig> <public_key.b64>", code: 2)
}
guard let bundle = FileManager.default.contents(atPath: arguments[1]),
      let signatureText = trimmedText(arguments[2]),
      let keyText = trimmedText(arguments[3])
else {
    fail("cannot read one of the three input files", code: 2)
}
guard let signature = Data(base64Encoded: signatureText),
      let keyBytes = Data(base64Encoded: keyText), keyBytes.count == 32,
      let key = try? Curve25519.Signing.PublicKey(rawRepresentation: keyBytes)
else {
    fail("the signature or the public key is not valid base64 of the right size", code: 1)
}
guard key.isValidSignature(signature, for: bundle) else {
    fail("the signature does not match the bundle", code: 1)
}
