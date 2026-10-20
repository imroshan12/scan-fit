import Foundation

/// A share folder of the test's own for `ShareStager.stage(_:filename:in:)`. Every stage empties its folder, so tests
/// that share in parallel must not use the app's folder: one test's stage would delete another's staged file.
public func ownShareFolder() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("share-test-\(UUID().uuidString)", isDirectory: true)
}
