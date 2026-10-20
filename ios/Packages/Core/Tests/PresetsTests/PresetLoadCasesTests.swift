import Foundation
import Presets
import ScanModel
import TestSupport
import Testing

/// Runs spec/fixtures/presets_load/cases.json: the format checks every presets file goes through on both apps.
/// Each case must load or be refused for the stated reason.
private struct LoadCases: Decodable {
    struct Case: Decodable, Sendable, CustomTestStringConvertible {
        let id: String
        let bundle: String
        let currentVersion: Int
        let expect: String
        var testDescription: String { id }
    }

    let supportedSchemaVersion: Int
    let supportedFailureReasons: [String]
    let loadCases: [Case]

    static func load() -> LoadCases? {
        guard let data = try? SpecFiles.data("fixtures/presets_load/cases.json") else { return nil }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try? decoder.decode(LoadCases.self, from: data)
    }

    static let shared = load()
}

private func fixture(_ name: String) throws -> Data {
    try SpecFiles.data("fixtures/presets_load/\(name)")
}

@Suite("Presets load cases")
struct PresetLoadCasesTests {
    @Test("cases.json is present, versioned for this build, and covers every failure reason")
    func casesAreUsable() throws {
        let cases = try #require(LoadCases.shared, "spec/fixtures/presets_load/cases.json missing or unreadable")
        #expect(cases.supportedSchemaVersion == PresetBundle.supportedSchemaVersion)
        #expect(cases.loadCases.count >= 8)
        let reasons = Set(cases.loadCases.map(\.expect)).subtracting(["ok"])
        #expect(reasons == Set(cases.supportedFailureReasons))
        #expect(Set(PresetLoadError.allRawValues) == Set(cases.supportedFailureReasons))
    }

    @Test("the format checks match the cases", arguments: LoadCases.shared?.loadCases ?? [])
    fileprivate func load(_ testCase: LoadCases.Case) throws {
        let result = PresetBundleLoader.load(bundle: try fixture(testCase.bundle), currentVersion: testCase.currentVersion)
        switch result {
        case .success:
            #expect(testCase.expect == "ok", "\(testCase.id): loaded but expected \(testCase.expect)")
        case let .failure(error):
            #expect(error.rawValue == testCase.expect, "\(testCase.id): got \(error.rawValue), expected \(testCase.expect)")
        }
    }

    @Test("a bundle cut short is refused")
    func truncatedBundleIsRejected() throws {
        let good = try fixture("sample_presets.json")
        let result = PresetBundleLoader.load(bundle: good.prefix(good.count / 2), currentVersion: 0)
        #expect(result == .failure(.parse))
    }
}

extension PresetLoadError {
    static let allRawValues = [badSchema, staleVersion, parse].map(\.rawValue)
}
