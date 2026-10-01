import Foundation

/// Loads spec/fixtures/cases.json and fixture images: the conformance contract both platforms must pass.
public enum CasesFile {
    public typealias Case = [String: Any]

    public static func section(_ name: String, file: String = #filePath) throws -> [Case] {
        let data = try SpecFiles.data("fixtures/cases.json", file: file)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let cases = root[name] as? [Case] else {
            throw CasesError.missingSection(name)
        }
        return cases
    }

    public static func image(_ name: String, file: String = #filePath) throws -> [UInt8] {
        [UInt8](try SpecFiles.data("fixtures/images/\(name)", file: file))
    }

    public enum CasesError: Error { case missingSection(String) }
}

public extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? { self[key] as? String }
    func int(_ key: String) -> Int? { (self[key] as? NSNumber)?.intValue }
    func bool(_ key: String) -> Bool? { (self[key] as? NSNumber)?.boolValue }
    func double(_ key: String) -> Double? { (self[key] as? NSNumber)?.doubleValue }
    func dict(_ key: String) -> [String: Any]? { self[key] as? [String: Any] }
    func strings(_ key: String) -> [String]? { self[key] as? [String] }
}
